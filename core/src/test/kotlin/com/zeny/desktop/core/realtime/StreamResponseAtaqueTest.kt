package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.ConnectionItem.Event
import com.zeny.desktop.core.realtime.ConnectionItem.Status
import com.zeny.desktop.core.session.TokenRefresher
import com.zeny.desktop.core.session.Tokens
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ataque à classificação das respostas do stream: `Content-Type`, status, sessão, rede e vazamento. */
class StreamResponseAtaqueTest {
    private val connecting = StreamAtaqueFixture.connecting
    private val live = StreamAtaqueFixture.live
    private val readyItem = Event(RealtimeEvent.Ready, "e-1")

    private fun reconnecting(
        attempt: Int,
        delayMillis: Long,
    ) = StreamAtaqueFixture.reconnecting(attempt, delayMillis)

    private fun ready(id: String) = StreamAtaqueFixture.ready(id)

    private fun message(
        id: String,
        data: String = """{"type":"rooms.room-status","id":"r-$id","version":3}""",
    ) = StreamAtaqueFixture.message(id, data = data)

    /** A conexão falhou de vez: só `Connecting` e `Failed`, uma requisição, e o erro devolvido. */
    private suspend fun TestScope.failsOnce(
        label: String,
        setup: (StreamAtaqueFixture) -> Unit,
    ): ApiError {
        val f = StreamAtaqueFixture(this)
        setup(f)
        val items = f.connection().run().toList()
        assertEquals(2, items.size, "$label: $items")
        assertEquals(connecting, items[0], label)
        val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items[1]).status, label)
        assertEquals(1, f.requests.size, label)
        return failed.error
    }

    // ---- Content-Type ----

    @Test
    fun eventStreamWithCharsetIsAccepted() =
        runTest {
            for (type in listOf("text/event-stream; charset=utf-8", "text/event-stream;charset=UTF-8")) {
                val f = StreamAtaqueFixture(this)
                f.stream(ready("e-1"), contentType = type)
                assertEquals(
                    listOf(connecting, live, readyItem),
                    f
                        .connection()
                        .run()
                        .take(3)
                        .toList(),
                    type,
                )
            }
        }

    @Test
    fun eventStreamMediaTypeIsCaseInsensitive() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1"), contentType = "Text/Event-Stream")
            assertEquals(
                listOf(connecting, live, readyItem),
                f
                    .connection()
                    .run()
                    .take(3)
                    .toList(),
            )
        }

    @Test
    fun successWithoutContentTypeFailsWithoutRetry() =
        runTest {
            val error = failsOnce("sem Content-Type") { it.stream(ready("e-1"), contentType = null) }
            assertIs<ApiError.IncompatibleResponse>(error)
        }

    @Test
    fun otherMediaTypesFailWithoutRetry() =
        runTest {
            for (type in listOf(
                "text/plain",
                "text/event-streamx",
                "application/octet-stream",
                "text/html; charset=utf-8",
            )) {
                val error = failsOnce(type) { it.stream(ready("e-1"), contentType = type) }
                assertIs<ApiError.IncompatibleResponse>(error, type)
            }
        }

    @Test
    fun malformedContentTypeFailsAsIncompatibleResponse() =
        runTest {
            // Um Content-Type que nem se interpreta é "outro Content-Type": a resposta é incompatível, não a rede.
            for (type in listOf("text", "/")) {
                val error = failsOnce(type) { it.stream(ready("e-1"), contentType = type) }
                assertIs<ApiError.IncompatibleResponse>(error, "Content-Type '$type' virou ${error::class.simpleName}")
            }
        }

    @Test
    fun otherSuccessStatusesAreReadAsStream() =
        runTest {
            // Um 2xx com o tipo certo é lido; um 204 sem corpo nem tipo falha sem repetir.
            val f = StreamAtaqueFixture(this)
            f.respondWith {
                respond(
                    ByteReadChannel(ready("e-1")),
                    HttpStatusCode.fromValue(203),
                    headersOf("Content-Type", "text/event-stream"),
                )
            }
            assertEquals(
                listOf(connecting, live, readyItem),
                f
                    .connection()
                    .run()
                    .take(3)
                    .toList(),
            )
            val error =
                failsOnce(
                    "204",
                ) { it.respondWith { respond(ByteReadChannel(""), HttpStatusCode.NoContent, headersOf()) } }
            assertIs<ApiError.IncompatibleResponse>(error)
        }

    // ---- status definitivos ----

    private val fatalStatuses =
        listOf(300, 301, 302, 303, 307, 308, 400, 402, 403, 404, 405, 406, 409, 410, 413, 415, 422, 451)

    @Test
    fun fatalStatusesWithProblemBodyFailOnce() =
        runTest {
            for (code in fatalStatuses) {
                val error = failsOnce("HTTP $code") { it.problem(code, "SOME_CODE") }
                val problem = assertIs<ApiError.Problem>(error, "HTTP $code")
                assertEquals(code, problem.status)
                assertEquals("SOME_CODE", problem.code)
            }
        }

    @Test
    fun fatalStatusesWithoutBodyFailOnceAsUnexpected() =
        runTest {
            for (code in fatalStatuses) {
                val error = failsOnce("HTTP $code") { it.status(code) }
                assertEquals(code, assertIs<ApiError.Unexpected>(error, "HTTP $code").status)
            }
        }

    @Test
    fun passwordChangeRequiredFailsOnce() =
        runTest {
            val error = failsOnce("403") { it.problem(403, "PASSWORD_CHANGE_REQUIRED") }
            assertIs<ApiError.PasswordChangeRequired>(error)
        }

    // ---- status que repetem ----

    @Test
    fun retryableStatusesRetryWithAnyBody() =
        runTest {
            val bodies =
                listOf(
                    """{"code":"UNAVAILABLE","detail":"detalhe"}""" to "application/problem+json",
                    "" to "application/problem+json",
                    "<html>proxy</html>" to "text/html",
                )
            for (code in listOf(408, 429, 500, 501, 502, 503, 504, 599)) {
                for ((body, type) in bodies) {
                    val f = StreamAtaqueFixture(this)
                    f.status(code, body, type)
                    f.stream(ready("e-1"))
                    val items =
                        f
                            .connection()
                            .run()
                            .take(5)
                            .toList()
                    val label = "HTTP $code com '$body'"
                    assertEquals(listOf(connecting, reconnecting(1, 1_000), connecting, live, readyItem), items, label)
                    assertEquals(1_000L, f.requestTimes[1] - f.requestTimes[0], label)
                }
            }
        }

    @Test
    fun networkFailuresBeforeResponseRetry() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.respondWith { throw ConnectException("recusada") }
            f.respondWith { throw UnresolvedAddressException() }
            f.respondWith { throw SocketTimeoutException("lento") }
            f.stream(ready("e-1"))
            val items =
                f
                    .connection()
                    .run()
                    .take(9)
                    .toList()
            assertEquals(
                listOf(
                    connecting,
                    reconnecting(1, 1_000),
                    connecting,
                    reconnecting(2, 2_000),
                    connecting,
                    reconnecting(3, 4_000),
                    connecting,
                    live,
                    readyItem,
                ),
                items,
            )
        }

    // ---- sessão ----

    @Test
    fun unauthorizedWithoutBodyStops() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.status(401)
            assertEquals(listOf<ConnectionItem>(connecting), f.connection().run().toList())
            assertEquals(1, f.requests.size)
            assertEquals(listOf<String?>(null), f.ended)
        }

    @Test
    fun logoutBeforeReconnectionStopsWithoutSendingToken() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1"))
            f.status(401)
            val (items, job) = f.collectInBackground(f.connection())
            runCurrent()
            assertEquals(reconnecting(1, 1_000), items.last())
            f.store.clear()
            advanceTimeBy(60_000)
            assertTrue(job.isCompleted, "o 401 sem sessão encerra a assinatura")
            assertEquals(listOf(connecting, live, readyItem, reconnecting(1, 1_000), connecting), items)
            assertEquals(listOf("Bearer A1", null), f.bearers())
            assertEquals(2, f.requests.size)
        }

    @Test
    fun unauthorizedAfterLiveStopsWithoutFailed() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + message("e-2"))
            f.problem(401, "SESSION_REVOKED")
            val items = f.connection().run().toList()
            assertEquals(
                listOf(
                    connecting,
                    live,
                    readyItem,
                    Event(StreamAtaqueFixture.roomStatus("e-2"), "e-2"),
                    reconnecting(1, 1_000),
                    connecting,
                ),
                items,
            )
            assertEquals(listOf(null, "e-2"), f.lastEventIds())
            assertEquals(listOf<String?>("SESSION_REVOKED"), f.ended)
        }

    @Test
    fun expiredTokenIsRefreshedAndStreamResumesFromPosition() =
        runTest {
            val f = StreamAtaqueFixture(this, TokenRefresher { Tokens("A2", "R2", "s1", "u1", false) })
            f.stream(ready("e-1") + message("e-2"))
            f.problem(401, "UNAUTHENTICATED")
            f.stream(ready("e-3"))
            val items =
                f
                    .connection()
                    .run()
                    .take(8)
                    .toList()
            assertEquals(
                listOf(
                    connecting,
                    live,
                    readyItem,
                    Event(StreamAtaqueFixture.roomStatus("e-2"), "e-2"),
                    reconnecting(1, 1_000),
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-3"),
                ),
                items,
            )
            assertEquals(listOf("Bearer A1", "Bearer A1", "Bearer A2"), f.bearers())
            assertEquals(listOf(null, "e-2", "e-2"), f.lastEventIds())
            assertEquals(emptyList(), f.ended)
        }

    @Test
    fun rejectedRefreshStops() =
        runTest {
            val f =
                StreamAtaqueFixture(
                    this,
                    TokenRefresher { throw ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList()) },
                )
            f.problem(401, "UNAUTHENTICATED")
            assertEquals(listOf<ConnectionItem>(connecting), f.connection().run().toList())
            assertEquals(1, f.requests.size)
            assertEquals(listOf<String?>("REFRESH_TOKEN_REUSED"), f.ended)
        }

    @Test
    fun transientRefreshFailureRetriesWithBackoff() =
        runTest {
            var calls = 0
            val refresher =
                TokenRefresher {
                    calls++
                    if (calls == 1) throw ApiError.Network(IOException("sem rede"))
                    Tokens("A2", "R2", "s1", "u1", false)
                }
            val f = StreamAtaqueFixture(this, refresher)
            f.problem(401, "UNAUTHENTICATED")
            f.problem(401, "UNAUTHENTICATED")
            f.stream(ready("e-1"))
            val items =
                f
                    .connection()
                    .run()
                    .take(5)
                    .toList()
            assertEquals(listOf(connecting, reconnecting(1, 1_000), connecting, live, readyItem), items)
            assertEquals(listOf("Bearer A1", "Bearer A1", "Bearer A2"), f.bearers())
            assertEquals(emptyList(), f.ended)
        }

    // ---- vazamento ----

    private fun assertNoSecret(
        error: ApiError,
        vararg secrets: String,
    ) {
        val text = error.stackTraceToString()
        for (secret in secrets) assertFalse(secret in text, "'$secret' vazou no erro")
    }

    @Test
    fun failureAfterPayloadCarriesNeitherPayloadNorToken() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(
                ready("e-1") +
                    message("e-2", data = """{"type":"segredo-tipo","id":"segredo-id","version":1}""") +
                    message("e-3", data = "segredo-lixo"),
            )
            f.problem(403, "PERMISSION_DENIED")
            val items = f.connection().run().toList()
            val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            assertEquals(403, assertIs<ApiError.Problem>(failed.error).status)
            assertNoSecret(failed.error, "segredo", "A1", "R1", "Bearer", "e-3")
        }

    @Test
    fun incompatibleSuccessHidesBody() =
        runTest {
            val error =
                failsOnce("json") {
                    it.stream("""{"accessToken":"segredo-A1"}""", contentType = "application/json")
                }
            assertIs<ApiError.IncompatibleResponse>(error)
            assertNoSecret(error, "segredo", "A1", "accessToken")
        }

    @Test
    fun nonProblemErrorBodyDoesNotLeak() =
        runTest {
            val error = failsOnce("400") { it.status(400, """{"token":"segredo-A1"}""", "application/json") }
            assertEquals(400, assertIs<ApiError.Unexpected>(error).status)
            assertNoSecret(error, "segredo", "A1")
        }

    @Test
    fun noTokenInAnyRequestUrl() =
        runTest {
            val f = StreamAtaqueFixture(this, TokenRefresher { Tokens("A2", "R2", "s1", "u1", false) })
            f.stream(ready("e-1"))
            f.problem(401, "UNAUTHENTICATED")
            f.stream(ready("e-3"))
            f
                .connection()
                .run()
                .take(7)
                .toList()
            assertEquals(3, f.requests.size)
            for (request in f.requests) {
                val url = request.url.toString()
                for (secret in listOf("A1", "A2", "R1", "R2")) assertFalse(secret in url, "token na URL: $url")
            }
        }
}
