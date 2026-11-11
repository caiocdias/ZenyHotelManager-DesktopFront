package com.zeny.desktop.core

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.PreferencesSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.config.ServerUrlResolver
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.CallOptions
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.sdk.model.MeResponse
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.ogen.client.ktor.HttpResponseTyped
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val ME = "/api/v1/auth/me"
private const val REFRESH = "/api/v1/auth/refresh"
private const val PASSWORD = "/api/v1/auth/password"
private const val RESERVATION_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
private const val RESERVATION = "/api/v1/reservations/$RESERVATION_ID"

/** Real (não virtual): o `ZenyCore` roda o refresh em `Dispatchers.IO`, fora do relógio do `runTest`. */
private const val REAL_TIMEOUT_MILLIS = 3_000L

/**
 * Ataque ao `ZenyCore` montado, de ponta a ponta contra o `FakeZenyServer`: refresh token repetido, `close()`,
 * dois `ZenyCore` lado a lado, a URL base, enum extensível e o refresh com corpo incompatível.
 */
@OptIn(RawSdkAccess::class)
@Suppress("InjectDispatcher")
class ZenyCoreAttackTest {
    private val nodes = mutableListOf<Preferences>()
    private val cores = mutableListOf<ZenyCore>()

    @AfterTest
    fun tearDown() {
        cores.forEach(ZenyCore::close)
        nodes.forEach(Preferences::removeNode)
        Preferences.userRoot().flush()
    }

    private fun settings(): PreferencesSettings =
        PreferencesSettings(Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}").also { nodes += it })

    private fun core(
        engine: MockEngine,
        url: String = "http://servidor.teste",
        settings: LocalSettings = settings(),
    ): ZenyCore = ZenyCore(checkNotNull(ServerUrl.parse(url)), settings, engine).also { cores += it }

    private suspend fun ZenyCore.me(): ApiResponse<MeResponse> = executor.execute { apis.iam.getMe(it.headers) }

    private suspend fun ZenyCore.login() = session.login("maria.silva", "senha-longa-123")

    private fun HttpRequestData.path() = url.encodedPath

    private suspend fun HttpRequestData.bodyText() = body.toByteArray().decodeToString()

    /** Um engine que passa pelo servidor falso e deixa o teste agir em volta de cada requisição. */
    private fun around(
        server: FakeZenyServer,
        block: suspend MockRequestHandleScope.(
            request: HttpRequestData,
            proceed: suspend () -> HttpResponseData,
        ) -> HttpResponseData,
    ): MockEngine {
        val handler =
            server.engine.config.requestHandlers
                .single()
        return MockEngine { request ->
            val scope = this
            scope.block(request) { handler(scope, request) }
        }
    }

    /** O servidor gira o par no `/auth/refresh`, mas a resposta se perde na rede uma vez. */
    private fun losingFirstRefreshResponse(server: FakeZenyServer): MockEngine {
        val lose = AtomicBoolean(true)
        return around(server) { request, proceed ->
            val response = proceed()
            if (request.path() == REFRESH && lose.getAndSet(false)) throw IOException("resposta do refresh perdida")
            response
        }
    }

    @Test
    fun refreshTokenReusedAfterLostRefreshResponseEndsSessionAndLeavesNoToken() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(losingFirstRefreshResponse(server))
            core.login()
            server.expireAccess()

            // O servidor girou para A2/R2, mas o cliente não soube: segue com A1/R1.
            assertFailsWith<ApiError.Network> { core.me() }
            assertIs<SessionState.Active>(core.session.state.value)
            // O próximo 401 repete o R1: o servidor revoga a sessão (REFRESH_TOKEN_REUSED).
            assertFailsWith<ApiError.SessionEnded> { core.me() }

            assertIs<SessionState.Ended>(core.session.state.value)
            val refreshes = server.requests.filter { it.path() == REFRESH }
            assertEquals(2, refreshes.size)
            for (refresh in refreshes) {
                val body = refresh.bodyText()
                assertTrue("\"R1\"" in body, "refresh sem o R1: $body")
            }
            // Nada de token sobrando: a chamada seguinte sai sem Authorization e sem novo refresh.
            server.requests.clear()
            assertFailsWith<ApiError.SessionEnded> { core.me() }
            assertEquals(listOf(null), server.authorizations(ME))
            assertEquals(0, server.count(REFRESH))
        }

    @Test
    fun fiveConcurrentCallsWithReusedRefreshTokenAllEndWithOneMoreRefresh() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(losingFirstRefreshResponse(server))
            core.login()
            server.expireAccess()
            assertFailsWith<ApiError.Network> { core.me() }
            val arrived = AtomicInteger()
            val allArrived = CompletableDeferred<Unit>()
            server.beforeSecured = { request ->
                if (request.path() == ME && server.authorizationOf(request) == listOf("Bearer A1")) {
                    if (arrived.incrementAndGet() == 5) allArrived.complete(Unit)
                    allArrived.await()
                }
            }

            val outcomes =
                List(5) { async(Dispatchers.Default) { runCatching { core.me() }.exceptionOrNull() } }.awaitAll()

            for (outcome in outcomes) assertIs<ApiError.SessionEnded>(outcome)
            assertEquals(2, server.count(REFRESH), "um refresh perdido e só um com o R1 repetido")
            assertIs<SessionState.Ended>(core.session.state.value)
        }

    @Test
    fun retryStillUnauthenticatedEndsSessionWithoutRefreshLoop() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(server.engine)
            core.login()
            server.rejectAllWith = "UNAUTHENTICATED"

            assertFailsWith<ApiError.SessionEnded> { core.me() }

            assertEquals(1, server.count(REFRESH))
            assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), server.authorizations(ME))
            assertIs<SessionState.Ended>(core.session.state.value)
        }

    @Test
    fun wrongCurrentPasswordKeepsSessionEndToEnd() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(server.engine)
            val active = core.login()
            server.routes = { request ->
                if (request.path() == PASSWORD) {
                    problem(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS")
                } else {
                    problem(HttpStatusCode.NotFound, "NOT_FOUND")
                }
            }

            val error =
                assertFailsWith<ApiError.Problem> { core.session.changePassword("errada-123456", "nova-123456789") }

            assertEquals("INVALID_CREDENTIALS", error.code)
            assertEquals(active, core.session.state.value)
            assertEquals(0, server.count(REFRESH))
            assertEquals(200, core.me().status)
        }

    @Test
    fun approvedRepeatWithExpiredAccessIsRetriedWithTheSameKeyAndToken() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(server.engine)
            core.login()
            server.routes = { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(APPROVAL_JSON, HttpStatusCode.Forbidden)
                } else {
                    json(reservationJson(status = "CANCELLED"))
                }
            }
            val intent = Intent()
            assertFailsWith<ApiError.ApprovalRequired> { core.executor.execute(intent, call = cancel(core)) }
            server.expireAccess()

            assertEquals(200, core.executor.executeApproved(intent, "tok-1", cancel(core)).status)

            val cancels = server.requests.filter { it.path() == "$RESERVATION/cancel" }
            assertEquals(3, cancels.size)
            val (_, rejected, retried) = cancels
            assertEquals(listOf("Bearer A1"), server.authorizationOf(rejected))
            assertEquals(listOf("Bearer A2"), server.authorizationOf(retried))
            for (request in listOf(rejected, retried)) {
                assertEquals(listOf(intent.key), request.headers.getAll("Idempotency-Key"))
                assertEquals(listOf("tok-1"), request.headers.getAll("X-Approval-Token"))
            }
            assertEquals(1, server.count(REFRESH))
        }

    @Test
    fun unknownExtensibleEnumAndUnknownPropertyAreToleratedEndToEnd() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(server.engine)
            core.login()
            server.routes = { json(reservationJson(status = "EM_DISPUTA", extra = """"campoNovo":{"x":1},""")) }

            val reservation = getReservation(core)

            val status = reservation.value.reservation.status
            assertEquals("EM_DISPUTA", status)
            assertEquals("Outro (EM_DISPUTA)", ExtensibleEnumLabels(mapOf("CONFIRMED" to "Confirmada")).label(status))
        }

    @Test
    fun unknownValueOfAClosedEnumIsIncompatibleAndKeepsTheSession() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(server.engine)
            val active = core.login()
            server.routes = { json(reservationJson(status = "CONFIRMED", ratePlanCategory = "PLANO_NOVO")) }

            assertFailsWith<ApiError.IncompatibleResponse> { getReservation(core) }

            assertEquals(active, core.session.state.value)
            assertEquals(0, server.count(REFRESH))
        }

    @Test
    fun closeTwiceIsHarmless() {
        val server = FakeZenyServer()
        val core = core(server.engine)
        core.close()
        core.close()
        val withDefaultEngine = ZenyCore(checkNotNull(ServerUrl.parse("http://127.0.0.1:9")), settings())
        withDefaultEngine.close()
        withDefaultEngine.close()
    }

    @Test
    fun callsAfterCloseFailWithoutReachingTheServer() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val core = core(server.engine)
            core.login()
            val before = server.requests.size
            core.close()

            assertFails("getMe depois do close") { core.me() }
            assertFails("login depois do close") { core.login() }

            assertEquals(before, server.requests.size, "requisição depois do close")
        }

    @Test
    fun closeDuringInFlightRefreshReleasesTheCallerAndCancelsTheRefresh() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val refreshSent = CompletableDeferred<Unit>()
            val refreshCancelled = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val engine =
                around(server) { request, proceed ->
                    if (request.path() == REFRESH) {
                        refreshSent.complete(Unit)
                        try {
                            never.await()
                        } catch (e: CancellationException) {
                            refreshCancelled.complete(Unit)
                            throw e
                        }
                    }
                    proceed()
                }
            val core = core(engine)
            core.login()
            server.expireAccess()
            val call = async(Dispatchers.Default) { runCatching { core.me() } }
            realTime { refreshSent.await() }

            core.close()

            val outcome = realTime { call.await() }
            assertTrue(outcome.isFailure, "getMe com o core fechado no meio do refresh: $outcome")
            realTime { refreshCancelled.await() }
            assertEquals(0, server.count(REFRESH), "o refresh chegou ao servidor depois do close")
        }

    @Test
    fun twoCoresSideBySideShareNoTokenAndClosingOneKeepsTheOther() =
        runTest(timeout = 5.seconds) {
            val serverA = FakeZenyServer(accessPrefix = "A-", refreshPrefix = "RA-")
            val serverB = FakeZenyServer(accessPrefix = "B-", refreshPrefix = "RB-")
            val coreA = core(serverA.engine, url = "http://a.teste")
            val coreB = core(serverB.engine, url = "http://b.teste:8443")
            coreA.login()

            // B sem login não herda o token de A.
            assertFailsWith<ApiError.SessionEnded> { coreB.me() }
            assertEquals(listOf(null), serverB.authorizations(ME))
            assertEquals(SessionState.LoggedOut, coreB.session.state.value, "B sem sessão não passa a Ended")
            coreB.login()
            assertEquals(200, coreA.me().status)
            assertEquals(200, coreB.me().status)

            coreA.close()
            serverB.expireAccess()
            assertEquals(200, coreB.me().status)

            assertEquals(1, serverB.count(REFRESH))
            assertTrue(serverA.requests.all { it.url.host == "a.teste" })
            assertTrue(serverB.requests.all { it.url.host == "b.teste" && it.url.port == 8443 })
            val tokensSeenByB = serverB.requests.mapNotNull { serverB.authorizationOf(it)?.single() }
            assertTrue(tokensSeenByB.none { "A-" in it }, "B recebeu token de A: $tokensSeenByB")
            val tokensSeenByA = serverA.requests.mapNotNull { serverA.authorizationOf(it)?.single() }
            assertTrue(tokensSeenByA.none { "B-" in it }, "A recebeu token de B: $tokensSeenByA")
            assertIs<SessionState.Active>(coreB.session.state.value)
        }

    @Test
    fun portAndPathPrefixOfTheServerUrlAreKept() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val urls = mutableListOf<String>()
            val core =
                core(
                    around(server) { request, _ ->
                        urls += request.url.toString()
                        problem(HttpStatusCode.NotFound, "NOT_FOUND")
                    },
                    url = "https://servidor.teste:8443/zeny/",
                )

            assertFailsWith<ApiError> { core.login() }

            assertEquals(listOf("https://servidor.teste:8443/zeny/api/v1/auth/login"), urls)
        }

    @Test
    fun resolvedServerUrlIsWhereTheRequestsGo() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer()
            val settings = settings()
            settings.serverUrl = "http://salvo.teste:9000"
            val env = mapOf("ZENY_SERVER_URL" to "http://ambiente.teste")
            val url = ServerUrlResolver(settings, env = { env[it] }, envFileDir = null).resolve()
            val core = ZenyCore(url, settings, server.engine).also { cores += it }

            core.login()
            core.me()

            assertTrue(server.requests.isNotEmpty())
            assertTrue(server.requests.all { it.url.host == "salvo.teste" && it.url.port == 9000 })
        }

    @Test
    fun malformedRefreshResponseEndsSessionWithoutLeakingTokens() =
        runTest(timeout = 5.seconds) {
            val server = FakeZenyServer(accessPrefix = "A-SECRET-", refreshPrefix = "R-SECRET-")
            val engine =
                around(server) { request, proceed ->
                    val response = proceed()
                    if (request.path() == REFRESH) {
                        // O servidor girou o par, mas o corpo chega cortado, com os tokens novos dentro.
                        json("""{"tokenType":"Bearer","accessToken":"A-SECRET-2","refreshToken":"R-SECRET-2""")
                    } else {
                        response
                    }
                }
            val core = core(engine)
            val captured = ByteArrayOutputStream()
            val (out, err) = System.out to System.err
            System.setOut(PrintStream(captured, true))
            System.setErr(PrintStream(captured, true))
            val error =
                try {
                    core.login()
                    server.expireAccess()
                    assertFailsWith<ApiError.SessionEnded> { core.me() }
                } finally {
                    System.setOut(out)
                    System.setErr(err)
                }

            assertIs<SessionState.Ended>(core.session.state.value)
            assertFalse("SECRET" in captured.toString(Charsets.UTF_8), "token na saída")
            assertFalse("SECRET" in error.stackTraceToString(), "token na pilha do erro")
            val ended = core.session.state.value
            assertFalse("SECRET" in ended.toString())
            server.requests.clear()
            assertFailsWith<ApiError.SessionEnded> { core.me() }
            assertEquals(listOf(null), server.authorizations(ME), "token sobrando depois do fim da sessão")
        }

    /** Espera em tempo real: o trabalho do `ZenyCore` roda em `Dispatchers.IO`, fora do relógio do `runTest`. */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MILLIS) { block() } }

    /** A chamada termina, em tempo real, com alguma falha (não fica pendurada nem conclui). */
    private suspend fun assertFails(
        what: String,
        block: suspend () -> Unit,
    ) {
        val failure = runCatching { realTime(block) }.exceptionOrNull()
        assertFalse(failure is TimeoutCancellationException, "$what ficou pendurado")
        assertTrue(failure != null, "$what concluiu")
    }

    private suspend fun getReservation(core: ZenyCore): ApiResponse<ReservationDetailResponse> =
        core.executor.execute { core.apis.reservations.getReservation(Uuid.parse(RESERVATION_ID), it.headers) }

    private fun cancel(core: ZenyCore): suspend (CallOptions) -> HttpResponseTyped<ReservationDetailResponse> =
        { o ->
            core.apis.reservations.cancelReservation(
                id = Uuid.parse(RESERVATION_ID),
                idempotencyKey = o.requireIdempotencyKey(),
                xApprovalToken = o.approvalToken,
                reservationCancelRequest =
                    ReservationCancelRequest(reason = ReservationCancelRequest.Reason.OTHER, waivePenalty = true),
                block = o.headers,
            )
        }
}

private const val APPROVAL_JSON =
    """{"code":"APPROVAL_REQUIRED","detail":"Precisa de aprovação","action":"reservations.cancel",""" +
        """"permission":"reservations.penalties.waive","resourceRef":"reservation:$RESERVATION_ID",""" +
        """"limit":{"key":"reservations.penalties.waive.amount","amount":"150.00"}}"""

private const val POLICY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c10"

/** Um `ReservationDetailResponse` mínimo e válido, com `status`, `ratePlanCategory` e um trecho extra à escolha. */
private fun reservationJson(
    status: String,
    ratePlanCategory: String = "NORMAL",
    extra: String = "",
): String =
    """
    {"bookingCode":"B-1","holderGuestId":"$POLICY_ID","restrictionAlerts":[],$extra
     "reservation":{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-20","bookingId":"$POLICY_ID",
      "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$POLICY_ID","version":1},
      "childAges":[],"code":"R-1","createdAt":"2026-11-11T13:00:00Z","dayUse":false,"departure":"2026-11-22",
      "folioId":"$POLICY_ID",
      "guaranteeTerms":{"name":"Sem garantia","policyId":"$POLICY_ID","type":"NONE","version":1},
      "guests":[],"id":"$RESERVATION_ID","nightlyRates":[],"penaltiesCharged":"0.00",
      "ratePlanCategory":"$ratePlanCategory","ratePlanId":"$POLICY_ID","roomTotal":"400.00","roomTypeId":"$POLICY_ID",
      "segment":"LEISURE","status":"$status","total":"400.00","version":2,$extra"novaChave":true}}
    """.trimIndent()
