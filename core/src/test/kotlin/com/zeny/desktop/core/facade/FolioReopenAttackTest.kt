package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val LIMIT = 500

/** Ataque à reabertura: o limite de 500 rodando a ação, e a repetição sem `Idempotency-Key` em todo caminho. */
class FolioReopenAttackTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    private val openFolio =
        """{"balance":"0.00","creditLimitExceeded":false,"id":"$FOLIO","number":"F-1",
        "openedAt":"2026-11-18T10:00:00Z","openedOn":"2026-11-18",
        "owner":{"id":"$FOLIO","type":"RESERVATION"},"status":"OPEN",
        "updatedAt":"2026-11-18T10:00:00Z"}"""

    private suspend fun reasonOf(request: HttpRequestData): String =
        Json
            .parseToJsonElement(request.body.toByteArray().decodeToString())
            .jsonObject
            .getValue("reason")
            .jsonPrimitive.content

    @Test
    fun reasonOfExactly500RunsAndReachesTheWireAnd501DoesNotBuildTheAction() =
        runTest {
            core { json(openFolio) }.use { core ->
                core.folios.reopen(FOLIO, "x".repeat(LIMIT)).run(null)
                assertEquals(1, requests.size, "500 caracteres rodam e chegam ao fio")
                assertEquals(LIMIT, reasonOf(requests.single()).length)

                assertFailsWith<IllegalArgumentException> { core.folios.reopen(FOLIO, "x".repeat(LIMIT + 1)) }
                assertEquals(1, requests.size, "501 caracteres nem montam a ação")
            }
        }

    @Test
    fun whitespaceAroundA500CharReasonIsTrimmedBeforeTheLimit() =
        runTest {
            core { json(openFolio) }.use { core ->
                core.folios.reopen(FOLIO, "  " + "x".repeat(LIMIT) + "\n ").run(null)
                assertEquals("x".repeat(LIMIT), reasonOf(requests.single()))
            }
        }

    @Test
    fun repeatAfterServerErrorCarriesNeitherKeyNorToken() =
        runTest {
            var calls = 0
            core {
                if (calls++ == 0) {
                    json("""{"code":"X","detail":"d"}""", HttpStatusCode.ServiceUnavailable)
                } else {
                    json(openFolio)
                }
            }.use { core ->
                val action = core.folios.reopen(FOLIO, "engano")
                assertFailsWith<ApiError.Problem> { action.run(null) }
                action.run(null)
                assertEquals(2, requests.size)
                requests.forEach {
                    assertNull(it.headers["Idempotency-Key"])
                    assertNull(it.headers["X-Approval-Token"])
                }
            }
        }

    @Test
    fun expiredTokenRisesAsProblemAndStillSendsNoKey() =
        runTest {
            core {
                json("""{"code":"APPROVAL_TOKEN_EXPIRED","detail":"d"}""", HttpStatusCode.Forbidden)
            }.use { core ->
                val problem =
                    assertFailsWith<ApiError.Problem> { core.folios.reopen(FOLIO, "engano").run("tok-velho") }
                assertEquals("APPROVAL_TOKEN_EXPIRED", problem.code)
                assertEquals("tok-velho", requests.single().headers["X-Approval-Token"])
                assertNull(requests.single().headers["Idempotency-Key"])
            }
        }

    @Test
    fun approvalTokenGoesOutExactlyOnceAndKeyHeaderIsAbsentFromTheWire() =
        runTest {
            core { json(openFolio) }.use { core ->
                core.folios.reopen(FOLIO, "engano").run("tok-gerente")
                val request = requests.single()
                assertEquals(listOf("tok-gerente"), request.headers.getAll("X-Approval-Token"))
                assertEquals(null, request.headers.getAll("Idempotency-Key"))
            }
        }

    @Test
    fun sentinelTokenNeverLeaksIntoFailedApprovedRepeats() =
        runTest {
            val sentinel = "TOKEN-SENTINELA-7f3a91"
            val failures =
                listOf<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>(
                    { throw IOException("caiu") },
                    { json("""{"code":"X","detail":"d"}""", HttpStatusCode.ServiceUnavailable) },
                    { json("""{"code":"APPROVAL_TOKEN_EXPIRED","detail":"d"}""", HttpStatusCode.Forbidden) },
                )
            failures.forEach { handler ->
                core(handler).use { core ->
                    val error = assertFailsWith<ApiError> { core.folios.reopen(FOLIO, "engano").run(sentinel) }
                    var link: Throwable? = error
                    while (link != null) {
                        assertTrue(sentinel !in link.message.orEmpty(), "message: $link")
                        assertTrue(sentinel !in link.toString(), "toString: $link")
                        link = link.cause
                    }
                }
            }
            requests.forEach { assertEquals(listOf(sentinel), it.headers.getAll("X-Approval-Token")) }
        }

    @Test
    fun networkFailureIsNotRetriedByTheCore() =
        runTest {
            core { throw IOException("caiu") }.use { core ->
                assertFailsWith<ApiError.Network> { core.folios.reopen(FOLIO, "engano").run(null) }
                assertEquals(1, requests.size, "o core não repete sozinho uma escrita sem chave")
            }
        }
}
