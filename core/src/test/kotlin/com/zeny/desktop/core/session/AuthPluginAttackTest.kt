package com.zeny.desktop.core.session

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ProblemMapper
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CredentialCheck
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.client.MetaApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.ChangePasswordRequest
import com.zeny.desktop.sdk.model.DeviceRequest
import com.zeny.desktop.sdk.model.LoginRequest
import com.zeny.desktop.sdk.model.RefreshRequest
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// O refresh e as chamadas concorrentes rodam em threads reais (Dispatchers.Default), como no escopo do core.
@Suppress("InjectDispatcher", "LargeClass")
class AuthPluginAttackTest {
    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val hotelJson =
        """{"configured":true,"id":"$id","timezone":"America/Sao_Paulo","updatedAt":"2026-11-10T13:00:00Z"}"""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val ended = CopyOnWriteArrayList<String?>()
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private var refresher: TokenRefresher = RotatingRefresher()
    private val auth by lazy { Authenticator(store, refresher, scope) { code, _ -> ended += code } }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        createCoreHttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        ) { install(AuthPlugin) { authenticator = { auth } } }

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClientOgen =
        ogenBase(client(handler), "http://servidor.teste")

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(code: String) =
        json("""{"code":"$code","detail":"detalhe"}""", HttpStatusCode.Unauthorized)

    private val HttpRequestData.authorization: List<String>? get() = headers.getAll(HttpHeaders.Authorization)

    private fun rotating() = refresher as RotatingRefresher

    /** Segura as primeiras `count` requisições até todas chegarem, para que os `401` se sobreponham. */
    private class Barrier(
        private val count: Int,
    ) {
        private val arrived = AtomicInteger()
        private val all = CompletableDeferred<Unit>()

        suspend fun arrive() {
            if (arrived.incrementAndGet() >= count) all.complete(Unit)
            all.await()
        }
    }

    @Test
    fun twentyConcurrentUnauthenticatedCallsShareOneRefreshAndRetryOnceEach() =
        runTest(timeout = 5.seconds) {
            val barrier = Barrier(20)
            val hotel =
                HotelApi(
                    api { request ->
                        if (request.authorization == listOf("Bearer A2")) {
                            json(hotelJson)
                        } else {
                            barrier.arrive()
                            problem("UNAUTHENTICATED")
                        }
                    },
                )

            val statuses =
                List(20) {
                    async(Dispatchers.Default) {
                        hotel
                            .getHotel()
                            .raw.status.value
                    }
                }.awaitAll()

            assertEquals(List(20) { 200 }, statuses)
            assertEquals(listOf("R1"), rotating().received.toList())
            assertEquals(20, requests.count { it.authorization == listOf("Bearer A1") })
            assertEquals(20, requests.count { it.authorization == listOf("Bearer A2") })
            assertEquals(40, requests.size)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun twentyConcurrentOtherUnauthorizedEndSessionExactlyOnceWithoutRetry() =
        runTest(timeout = 5.seconds) {
            val barrier = Barrier(20)
            val hotel =
                HotelApi(
                    api {
                        barrier.arrive()
                        problem("ACCOUNT_DISABLED")
                    },
                )

            val statuses =
                List(20) {
                    async(Dispatchers.Default) {
                        hotel
                            .getHotel()
                            .raw.status.value
                    }
                }.awaitAll()

            assertEquals(List(20) { 401 }, statuses)
            assertEquals(20, requests.size)
            assertEquals(0, rotating().calls)
            assertEquals(listOf<String?>("ACCOUNT_DISABLED"), ended.toList())
            assertNull(store.current())
        }

    @Test
    fun twentyConcurrentRetriesStillUnauthorizedEndSessionExactlyOnce() =
        runTest(timeout = 5.seconds) {
            val barrier = Barrier(20)
            val hotel =
                HotelApi(
                    api { request ->
                        if (request.authorization == listOf("Bearer A1")) barrier.arrive()
                        problem("UNAUTHENTICATED")
                    },
                )

            val statuses =
                List(20) {
                    async(Dispatchers.Default) {
                        hotel
                            .getHotel()
                            .raw.status.value
                    }
                }.awaitAll()

            assertEquals(List(20) { 401 }, statuses)
            assertEquals(1, rotating().calls)
            // Quem recebe o 401 depois que outra repetição já encerrou a sessão não repete: no máximo uma por chamada.
            val retries = requests.count { it.authorization == listOf("Bearer A2") }
            assertTrue(retries in 1..20, "repetições: $retries")
            assertEquals(20, requests.count { it.authorization == listOf("Bearer A1") })
            assertEquals(20 + retries, requests.size)
            assertEquals(listOf<String?>("UNAUTHENTICATED"), ended.toList())
            assertNull(store.current())
        }

    @Test
    fun rejectedRefreshWithTwentyWaitingCallsEndsSessionOnceAndNoneRetries() =
        runTest(timeout = 5.seconds) {
            val refreshCalls = AtomicInteger()
            refresher =
                TokenRefresher {
                    refreshCalls.incrementAndGet()
                    throw ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList())
                }
            val barrier = Barrier(20)
            val hotel =
                HotelApi(
                    api {
                        barrier.arrive()
                        problem("UNAUTHENTICATED")
                    },
                )

            val responses = List(20) { async(Dispatchers.Default) { hotel.getHotel().raw } }.awaitAll()

            assertTrue(responses.all { it.status.value == 401 })
            assertEquals(20, requests.size, "nenhuma repetição sem token novo")
            assertEquals(1, refreshCalls.get())
            assertEquals(listOf<String?>("REFRESH_TOKEN_REUSED"), ended.toList())
            assertNull(store.current())
            for (raw in responses) {
                val error = assertIs<ApiError.Problem>(ProblemMapper.fromResponse(raw))
                assertEquals("UNAUTHENTICATED", error.code)
            }
        }

    @Test
    fun otherUnauthorizedDuringInFlightRefreshEndsSessionOnceAndTheRefreshIsDiscarded() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            rotating().gates += gate
            val base =
                api { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/me") -> problem("ACCOUNT_DISABLED")
                        request.authorization == listOf("Bearer A2") -> json(hotelJson)
                        else -> problem("UNAUTHENTICATED")
                    }
                }
            val pending =
                async(Dispatchers.Default) {
                    HotelApi(base)
                        .getHotel()
                        .raw.status.value
                }
            rotating().entered.receive()

            assertEquals(
                401,
                IamApi(base)
                    .getMe()
                    .raw.status.value,
            )
            gate.complete(Unit)

            assertEquals(401, pending.await())
            assertEquals(listOf<String?>("ACCOUNT_DISABLED"), ended.toList())
            assertNull(store.current(), "o par do refresh não ressuscita a sessão encerrada")
            assertEquals(2, requests.size, "a chamada pendente não repete")
        }

    @Test
    fun unauthorizedWithoutRecognizableCodeEndsSessionWithoutRetryAndWithoutThrowing() =
        runTest(timeout = 5.seconds) {
            val bodies =
                listOf(
                    "" to "application/json",
                    "<html><body>401 Unauthorized</body></html>" to "text/html",
                    """{"code":123}""" to "application/json",
                    "null" to "application/json",
                    """["UNAUTHENTICATED"]""" to "application/json",
                    """{"code":"UNAUTHENTICATED"""" to "application/json",
                )
            for ((body, type) in bodies) {
                store.set(Tokens("A1", "R1", "s1", "u1", false))
                ended.clear()
                requests.clear()
                val hotel =
                    HotelApi(
                        api { respond(body, HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, type)) },
                    )

                val response = hotel.getHotel()

                assertEquals(401, response.raw.status.value, body)
                assertEquals(1, requests.size, "sem repetição para '$body'")
                assertEquals(0, rotating().calls, "sem refresh para '$body'")
                assertEquals(listOf<String?>(null), ended.toList(), "encerra a sessão para '$body'")
                assertIs<ApiError.Unexpected>(ProblemMapper.fromResponse(response.raw), body)
            }
        }

    @Test
    fun problemJsonContentTypeIsRecognizedAsUnauthenticated() =
        runTest(timeout = 5.seconds) {
            val hotel =
                HotelApi(
                    api { request ->
                        if (request.authorization == listOf("Bearer A2")) {
                            json(hotelJson)
                        } else {
                            respond(
                                """{"type":"about:blank","code":"UNAUTHENTICATED","detail":"expirado"}""",
                                HttpStatusCode.Unauthorized,
                                headersOf(HttpHeaders.ContentType, "application/problem+json"),
                            )
                        }
                    },
                )

            assertEquals(
                200,
                hotel
                    .getHotel()
                    .raw.status.value,
            )
            assertEquals(1, rotating().calls)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun bodyOfTheRetriedUnauthorizedStaysReadableForProblemMapper() =
        runTest(timeout = 5.seconds) {
            val hotel =
                HotelApi(
                    api { request ->
                        val code =
                            if (request.authorization ==
                                listOf("Bearer A2")
                            ) {
                                "SESSION_REVOKED"
                            } else {
                                "UNAUTHENTICATED"
                            }
                        json("""{"code":"$code","detail":"detalhe $code"}""", HttpStatusCode.Unauthorized)
                    },
                )

            val response = hotel.getHotel()

            val error = assertIs<ApiError.Problem>(ProblemMapper.fromResponse(response.raw))
            assertEquals("SESSION_REVOKED", error.code)
            assertEquals("detalhe SESSION_REVOKED", error.detail)
            assertEquals(listOf<String?>("SESSION_REVOKED"), ended.toList())
            assertEquals(2, requests.size)
        }

    @Test
    fun publicOperationsNeverCarryAuthorizationNorReactToTheir401() =
        runTest(timeout = 5.seconds) {
            val base =
                api { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/login") -> problem("INVALID_CREDENTIALS")
                        request.url.encodedPath.endsWith("/auth/refresh") -> problem("REFRESH_TOKEN_REUSED")
                        else -> problem("UNAUTHENTICATED")
                    }
                }

            IamApi(base).login(LoginRequest(DeviceRequest("dev-1", "Recepção"), "ana", "senha-longa-1"))
            IamApi(base).refreshTokens(RefreshRequest("R1"))
            MetaApi(base).getServerVersion()

            assertEquals(3, requests.size, "nenhuma repetição")
            assertTrue(requests.all { it.authorization == null }, "Authorization numa operação pública")
            assertEquals(0, rotating().calls)
            assertTrue(ended.isEmpty())
            assertEquals("A1", store.current()?.accessToken)
        }

    @Test
    fun requestWithoutOgenSecurityAttributeCarriesNoAuthorization() =
        runTest(timeout = 5.seconds) {
            val http = client { problem("UNAUTHENTICATED") }

            http.get("http://servidor.teste/api/v1/hotel")

            assertNull(requests.single().authorization)
            assertEquals(0, rotating().calls)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun providerIsResolvedPerRequestSoTheClientCanBeBuiltBeforeTheAuthenticator() =
        runTest(timeout = 5.seconds) {
            lateinit var late: Authenticator
            val provided = AtomicInteger()
            val http =
                createCoreHttpClient(
                    MockEngine { request ->
                        requests += request
                        respond(hotelJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(AuthPlugin) {
                        authenticator = {
                            provided.incrementAndGet()
                            late
                        }
                    }
                }
            val base = ogenBase(http, "http://servidor.teste")

            MetaApi(base).getServerVersion()
            assertEquals(0, provided.get(), "operação pública não pede o authenticator")
            late = auth
            HotelApi(base).getHotel()

            assertEquals(listOf("Bearer A1"), requests.last().authorization)
            assertTrue(provided.get() >= 1)
        }

    @Test
    fun retryResendsTheSameBodyAndIdempotencyKeyWithASingleAuthorization() =
        runTest(timeout = 5.seconds) {
            val reservations =
                ReservationsApi(
                    api { request ->
                        if (request.authorization == listOf("Bearer A2")) json("{}") else problem("UNAUTHENTICATED")
                    },
                )

            reservations.cancelReservation(
                id = Uuid.parse(id),
                idempotencyKey = "chave-1",
                reservationCancelRequest =
                    ReservationCancelRequest(reason = ReservationCancelRequest.Reason.OTHER, waivePenalty = true),
            )

            assertEquals(2, requests.size)
            val (first, retry) = requests
            assertContentEquals(first.body.toByteArray(), retry.body.toByteArray())
            assertTrue(retry.body.toByteArray().isNotEmpty())
            assertEquals(listOf("chave-1"), retry.headers.getAll("Idempotency-Key"))
            assertEquals(listOf("Bearer A2"), retry.authorization)
            assertEquals(first.method, retry.method)
            assertEquals(first.url, retry.url)
        }

    @Test
    fun credentialCheckRetryAnsweredWithInvalidCredentialsKeepsTheRefreshedSession() =
        runTest(timeout = 5.seconds) {
            val iam =
                IamApi(
                    api { request ->
                        val code =
                            if (request.authorization ==
                                listOf("Bearer A2")
                            ) {
                                "INVALID_CREDENTIALS"
                            } else {
                                "UNAUTHENTICATED"
                            }
                        problem(code)
                    },
                )

            val response =
                iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1")) {
                    attributes.put(CredentialCheck, true)
                }

            assertEquals(401, response.raw.status.value)
            assertEquals(2, requests.size)
            assertEquals(1, rotating().calls)
            assertTrue(ended.isEmpty())
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun credentialCheckDoesNotProtectOtherUnauthorizedCodes() =
        runTest(timeout = 5.seconds) {
            val iam = IamApi(api { problem("ACCOUNT_DISABLED") })

            iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1")) {
                attributes.put(CredentialCheck, true)
            }

            assertEquals(listOf<String?>("ACCOUNT_DISABLED"), ended.toList())
            assertNull(store.current())
            assertEquals(1, requests.size)
        }

    @Test
    fun invalidCredentialsWithoutCredentialCheckEndsSession() =
        runTest(timeout = 5.seconds) {
            val iam = IamApi(api { problem("INVALID_CREDENTIALS") })

            iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1"))

            assertEquals(listOf<String?>("INVALID_CREDENTIALS"), ended.toList())
            assertNull(store.current())
        }

    @Test
    fun networkFailureDuringRefreshReachesTheExecutorAsNetworkAndKeepsSession() =
        runTest(timeout = 5.seconds) {
            val rotatingAfter = RotatingRefresher()
            val refreshCalls = AtomicInteger()
            refresher =
                TokenRefresher { current ->
                    if (refreshCalls.incrementAndGet() == 1) throw ApiError.Network(IOException("sem rede"))
                    rotatingAfter.refresh(current)
                }
            val hotel =
                HotelApi(
                    api { request ->
                        if (request.authorization ==
                            listOf("Bearer A2")
                        ) {
                            json(hotelJson)
                        } else {
                            problem("UNAUTHENTICATED")
                        }
                    },
                )
            val executor = ApiExecutor()

            assertFailsWith<ApiError.Network> { executor.execute { hotel.getHotel(it.headers) } }
            assertEquals("A1", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
            assertEquals(1, requests.size)

            val ok = executor.execute { hotel.getHotel(it.headers) }

            assertEquals(id, ok.value.id.toString())
            assertEquals(listOf("R1"), rotatingAfter.received.toList())
            assertTrue(ended.isEmpty())
        }

    @Test
    fun networkFailureOnTheRetryKeepsTheRefreshedSessionAndTheResendUsesTheNewToken() =
        runTest(timeout = 5.seconds) {
            var retryFailed = false
            val hotel =
                HotelApi(
                    api { request ->
                        when {
                            request.authorization != listOf("Bearer A2") -> {
                                problem("UNAUTHENTICATED")
                            }

                            !retryFailed -> {
                                retryFailed = true
                                throw IOException("conexão caiu na repetição")
                            }

                            else -> {
                                json(hotelJson)
                            }
                        }
                    },
                )
            val executor = ApiExecutor()

            assertFailsWith<ApiError.Network> { executor.execute { hotel.getHotel(it.headers) } }
            assertEquals("A2", store.current()?.accessToken)
            assertTrue(ended.isEmpty())

            executor.execute { hotel.getHotel(it.headers) }

            assertEquals(listOf("Bearer A2"), requests.last().authorization)
            assertEquals(3, requests.size)
            assertEquals(1, rotating().calls)
        }

    @Test
    fun cancelledCallDuringRefreshDoesNotCancelTheRefresh() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            rotating().gates += gate
            val hotel =
                HotelApi(
                    api { request ->
                        if (request.authorization ==
                            listOf("Bearer A2")
                        ) {
                            json(hotelJson)
                        } else {
                            problem("UNAUTHENTICATED")
                        }
                    },
                )
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { hotel.getHotel() }
            rotating().entered.receive()

            cancelled.cancelAndJoin()
            gate.complete(Unit)

            assertEquals(
                200,
                hotel
                    .getHotel()
                    .raw.status.value,
            )
            assertEquals(listOf("R1"), rotating().received.toList())
            assertTrue(ended.isEmpty())
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun refresherUsingTheSameClientNeitherRecursesNorReusesARotatedRefreshToken() =
        runTest(timeout = 5.seconds) {
            val validRefresh = AtomicReference("R1")
            val generation = AtomicInteger(1)
            val refreshHits = CopyOnWriteArrayList<String>()
            val barrier = Barrier(20)
            lateinit var iam: IamApi
            refresher =
                TokenRefresher { current ->
                    val body = iam.refreshTokens(RefreshRequest(current.refreshToken)).body()
                    Tokens(
                        body.accessToken,
                        body.refreshToken,
                        body.sessionId.toString(),
                        body.userId.toString(),
                        false,
                    )
                }
            val base =
                api { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) {
                        val sent = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                        val token = sent.getValue("refreshToken").jsonPrimitive.content
                        refreshHits += token
                        val n = generation.incrementAndGet()
                        if (validRefresh.getAndUpdate { if (it == token) "R$n" else it } != token) {
                            problem("REFRESH_TOKEN_REUSED")
                        } else {
                            json(
                                """{"accessToken":"A$n","accessTokenExpiresAt":"2026-11-11T13:00:00Z",""" +
                                    """"passwordChangeRequired":false,"refreshToken":"R$n",""" +
                                    """"refreshTokenExpiresAt":"2026-11-12T13:00:00Z","sessionId":"$id",""" +
                                    """"tokenType":"Bearer","userId":"$id"}""",
                            )
                        }
                    } else if (request.authorization == listOf("Bearer A2")) {
                        json(hotelJson)
                    } else {
                        barrier.arrive()
                        problem("UNAUTHENTICATED")
                    }
                }
            iam = IamApi(base)
            val hotel = HotelApi(base)

            val statuses =
                List(20) {
                    async(Dispatchers.Default) {
                        hotel
                            .getHotel()
                            .raw.status.value
                    }
                }.awaitAll()

            assertEquals(List(20) { 200 }, statuses)
            assertEquals(listOf("R1"), refreshHits.toList())
            assertNull(requests.single { it.url.encodedPath.endsWith("/auth/refresh") }.authorization)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun noTokenReachesStdoutStderrOrTheErrorsSeenByTheCaller() =
        runTest(timeout = 5.seconds) {
            store.set(Tokens("access-secreto-1", "refresh-secreto-1", "s1", "u1", false))
            val refreshCalls = AtomicInteger()
            refresher =
                TokenRefresher { current ->
                    when (refreshCalls.incrementAndGet()) {
                        1 -> throw ApiError.Network(IOException("sem rede"))
                        2 -> Tokens("access-secreto-2", "refresh-secreto-2", current.sessionId, current.userId, false)
                        else -> throw ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList())
                    }
                }
            val hotel =
                HotelApi(
                    api { request ->
                        val auth = request.authorization?.single().orEmpty()
                        if (auth.endsWith("access-secreto-2") &&
                            requests.size < 4
                        ) {
                            json(hotelJson)
                        } else {
                            problem("UNAUTHENTICATED")
                        }
                    },
                )
            val executor = ApiExecutor()
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val originalOut = System.out
            val originalErr = System.err
            val seen = mutableListOf<Throwable>()
            System.setOut(PrintStream(out, true))
            System.setErr(PrintStream(err, true))
            try {
                runCatching { executor.execute { hotel.getHotel(it.headers) } }.exceptionOrNull()?.let(seen::add)
                runCatching { executor.execute { hotel.getHotel(it.headers) } }.exceptionOrNull()?.let(seen::add)
                runCatching { executor.execute { hotel.getHotel(it.headers) } }.exceptionOrNull()?.let(seen::add)
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }

            assertTrue(seen.any { it is ApiError.Network }, "a falha de rede chega ao chamador: $seen")
            assertTrue(seen.any { it is ApiError.SessionEnded }, "o fim da sessão chega ao chamador: $seen")
            val texts = seen.map { it.stackTraceToString() } + out.toString() + err.toString() + ended.toString()
            for (text in texts) assertTrue("secreto" !in text, "token vazou em: $text")
        }
}
