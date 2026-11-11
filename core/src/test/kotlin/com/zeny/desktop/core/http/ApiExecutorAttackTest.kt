package com.zeny.desktop.core.http

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.client.MetaApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.ChangePasswordRequest
import com.zeny.desktop.sdk.model.DeviceRequest
import com.zeny.desktop.sdk.model.LoginRequest
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.ogen.client.ktor.HttpResponseTyped
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ApiExecutorAttackTest {
    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val requests = mutableListOf<HttpRequestData>()
    private val executor = ApiExecutor()
    private val cancelBody =
        ReservationCancelRequest(reason = ReservationCancelRequest.Reason.OTHER, waivePenalty = true)
    private val hotelJson =
        """{"configured":true,"id":"$id","timezone":"America/Sao_Paulo","updatedAt":"2026-11-10T13:00:00Z"}"""

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ogenBase(
            createCoreHttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ),
            "http://servidor.teste",
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        vararg extra: Pair<String, String>,
    ) = respond(
        body,
        status,
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            *extra.map { it.first to listOf(it.second) }.toTypedArray(),
        ),
    )

    private fun MockRequestHandleScope.typed(
        body: String,
        contentType: String,
    ) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType))

    private fun cancelCall(
        reservations: ReservationsApi,
    ): suspend (CallOptions) -> HttpResponseTyped<ReservationDetailResponse> =
        { o ->
            reservations.cancelReservation(
                id = Uuid.parse(id),
                idempotencyKey = o.requireIdempotencyKey(),
                xApprovalToken = o.approvalToken,
                reservationCancelRequest = cancelBody,
                block = o.headers,
            )
        }

    @Test
    fun eachHeaderLeavesExactlyOnceOnTheFirstCallAndOnApprovedRepeats() =
        runTest {
            val reservations = ReservationsApi(api { json("{}") })
            val intent = Intent()
            val cancel = cancelCall(reservations)

            runCatching { executor.execute(intent, call = cancel) }
            runCatching { executor.executeApproved(intent, "tok-1", cancel) }
            runCatching { executor.executeApproved(intent, "tok-2", cancel) }

            assertEquals(3, requests.size)
            for (request in requests) {
                assertEquals(1, request.headers.getAll("Idempotency-Key")?.size, "Idempotency-Key")
            }
            assertNull(requests[0].headers.getAll("X-Approval-Token"))
            assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
            assertEquals(listOf("tok-2"), requests[2].headers.getAll("X-Approval-Token"))
        }

    @Test
    fun executeApprovedTwiceProducesTwoNewDistinctKeys() =
        runTest {
            val reservations = ReservationsApi(api { json("{}") })
            val intent = Intent()
            val cancel = cancelCall(reservations)
            val original = intent.key

            runCatching { executor.executeApproved(intent, "tok-1", cancel) }
            runCatching { executor.executeApproved(intent, "tok-1", cancel) }

            val keys = requests.map { it.headers["Idempotency-Key"] }
            assertEquals(2, keys.toSet().size, "as duas repeticoes devem ter chaves diferentes: $keys")
            assertFalse(original in keys, "nenhuma repeticao aprovada reusa a chave original")
        }

    @Test
    fun newKeyAfterApprovalIsKeptOnNetworkResend() =
        runTest {
            var calls = 0
            val reservations =
                ReservationsApi(
                    api {
                        if (++calls == 1) throw IOException("sem rede")
                        json("{}")
                    },
                )
            val intent = Intent()
            val original = intent.key
            val cancel = cancelCall(reservations)

            assertFailsWith<ApiError.Network> { executor.executeApproved(intent, "tok-1", cancel) }
            runCatching { executor.execute(intent, call = cancel) }

            val first = requests[0].headers["Idempotency-Key"]
            assertNotEquals(original, first)
            assertEquals(first, requests[1].headers["Idempotency-Key"])
            assertEquals(intent.key, first)
            assertNull(requests[1].headers["X-Approval-Token"])
        }

    @Test
    fun intentKeyIsCanonicalUuidAndDiffersBetweenIntents() {
        val keys = List(50) { Intent().key }

        assertEquals(50, keys.toSet().size)
        for (key in keys) assertEquals(key, Uuid.parse(key).toString())
    }

    @Test
    fun intentKeyIsStableAcrossManyReadsAndThreads() =
        runBlocking {
            val intent = Intent()
            val expected = intent.key

            val seen =
                (1..8)
                    .map { async(Dispatchers.Default) { List(5_000) { intent.key }.toSet() } }
                    .awaitAll()
                    .flatten()
                    .toSet()

            assertEquals(setOf(expected), seen)
        }

    @Test
    fun intentKeyDoesNotChangeByExecutingWithoutApproval() =
        runTest {
            val reservations = ReservationsApi(api { json("{}") })
            val intent = Intent()
            val expected = intent.key

            repeat(5) { runCatching { executor.execute(intent, call = cancelCall(reservations)) } }

            assertEquals(setOf(expected), requests.map { it.headers["Idempotency-Key"] }.toSet())
            assertEquals(expected, intent.key)
        }

    @Test
    fun callOptionsHeadersSkipNullValuesAndMarkCredentialCheck() {
        val plain = HttpRequestBuilder().apply(CallOptions(null, null).headers)
        assertTrue(plain.headers.names().isEmpty())
        assertNull(plain.attributes.getOrNull(CredentialCheck))

        val marked = HttpRequestBuilder().apply(CallOptions("k", "t", true).headers)
        assertEquals(listOf("k"), marked.headers.getAll("Idempotency-Key"))
        assertEquals(listOf("t"), marked.headers.getAll("X-Approval-Token"))
        assertEquals(true, marked.attributes.getOrNull(CredentialCheck))
    }

    @Test
    fun unauthorizedOnPublicOperationIsAProblemNotSessionEnded() =
        runTest {
            val iam = IamApi(api { json("""{"code":"INVALID_CREDENTIALS"}""", HttpStatusCode.Unauthorized) })

            val failure =
                assertFailsWith<ApiError.Problem> {
                    executor.execute {
                        iam.login(LoginRequest(DeviceRequest(id, "pc"), "ana", "senha-errada-1"), it.headers)
                    }
                }

            assertEquals("INVALID_CREDENTIALS", failure.code)
        }

    @Test
    fun unauthorizedOnPublicMetaOperationIsAProblem() =
        runTest {
            val meta = MetaApi(api { json("""{"code":"UNAUTHENTICATED"}""", HttpStatusCode.Unauthorized) })

            assertFailsWith<ApiError.Problem> { executor.execute { meta.getServerVersion(it.headers) } }
        }

    @Test
    fun accountLockedWithCredentialCheckStaysAProblem() =
        runTest {
            val iam = IamApi(api { json("""{"code":"ACCOUNT_LOCKED"}""", HttpStatusCode.Unauthorized) })

            val failure =
                assertFailsWith<ApiError.Problem> {
                    executor.execute(credentialCheck = true) {
                        iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1"), it.headers)
                    }
                }

            assertEquals("ACCOUNT_LOCKED", failure.code)
        }

    @Test
    fun accountLockedWithoutCredentialCheckEndsSession() =
        runTest {
            val iam = IamApi(api { json("""{"code":"ACCOUNT_LOCKED"}""", HttpStatusCode.Unauthorized) })

            val failure =
                assertFailsWith<ApiError.SessionEnded> {
                    executor.execute {
                        iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1"), it.headers)
                    }
                }

            assertEquals("ACCOUNT_LOCKED", failure.code)
        }

    @Test
    fun unauthorizedWithEmptyBodyOnSecuredOperationIsSessionEnded() =
        runTest {
            val iam = IamApi(api { respond("", HttpStatusCode.Unauthorized) })

            val failure = assertFailsWith<ApiError.SessionEnded> { executor.execute { iam.logout(it.headers) } }

            assertNull(failure.code)
        }

    @Test
    fun forbiddenIsNotSessionEnded() =
        runTest {
            val hotel = HotelApi(api { json("""{"code":"FORBIDDEN"}""", HttpStatusCode.Forbidden) })

            assertFailsWith<ApiError.Problem> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun missingEtagIsNull() =
        runTest {
            val hotel = HotelApi(api { json(hotelJson) })

            assertNull(executor.execute { hotel.getHotel(block = it.headers) }.etag)
        }

    @Test
    fun weakEtagIsReturnedAsReceived() =
        runTest {
            val hotel = HotelApi(api { json(hotelJson, extra = arrayOf("ETag" to "W/\"7\"")) })

            assertEquals("W/\"7\"", executor.execute { hotel.getHotel(block = it.headers) }.etag)
        }

    @Test
    fun noContentWithUnitSucceeds() =
        runTest {
            val iam = IamApi(api { respond("", HttpStatusCode.NoContent) })

            val response = executor.execute { iam.logout(it.headers) }

            assertEquals(204, response.status)
            assertEquals(Unit, response.value)
            assertNull(response.etag)
        }

    @Test
    fun emptySuccessBodyWhereObjectIsExpectedIsIncompatible() =
        runTest {
            val hotel = HotelApi(api { typed("", "application/json") })

            assertFailsWith<ApiError.IncompatibleResponse> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun malformedJsonOnSuccessIsIncompatible() =
        runTest {
            val hotel = HotelApi(api { json("{\"configured\":") })

            assertFailsWith<ApiError.IncompatibleResponse> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun noContentWhereObjectIsExpectedIsAnApiError() =
        runTest {
            val hotel = HotelApi(api { respond("", HttpStatusCode.NoContent) })

            val failure = runCatching { executor.execute { hotel.getHotel(block = it.headers) } }.exceptionOrNull()

            assertTrue(failure is ApiError, "esperava ApiError, veio $failure")
        }

    @Test
    fun htmlSuccessBodyFromAProxyWhereObjectIsExpectedIsIncompatible() =
        runTest {
            val hotel =
                HotelApi(api { typed("<html>portal cativo</html>", "text/html") })

            val failure = runCatching { executor.execute { hotel.getHotel(block = it.headers) } }.exceptionOrNull()

            assertTrue(failure is ApiError.IncompatibleResponse, "esperava IncompatibleResponse, veio $failure")
        }

    @Test
    fun serverErrorWithHtmlBodyIsAnApiError() =
        runTest {
            val hotel =
                HotelApi(
                    api {
                        respond(
                            "<html>502</html>",
                            HttpStatusCode.BadGateway,
                            headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )

            assertFailsWith<ApiError> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun javaNetFailuresAndKtorTimeoutBecomeNetwork() =
        runTest {
            val failures =
                listOf<() -> Throwable>(
                    { ConnectException("recusada") },
                    { SocketTimeoutException("lento") },
                    { HttpRequestTimeoutException("http://servidor.teste", 30_000L) },
                )
            for (make in failures) {
                val hotel = HotelApi(api { throw make() })

                assertFailsWith<ApiError.Network> { executor.execute { hotel.getHotel(block = it.headers) } }
            }
        }

    @Test
    fun realRequestTimeoutBecomesNetwork() =
        runBlocking {
            val client =
                createCoreHttpClient(
                    MockEngine {
                        delay(5_000)
                        respond(hotelJson)
                    },
                ) { install(HttpTimeout) { requestTimeoutMillis = 50 } }
            val hotel = HotelApi(ogenBase(client, "http://servidor.teste"))

            assertFailsWith<ApiError.Network> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun cancellationPropagatesTheSameInstance() =
        runTest {
            val original = CancellationException("cancelado")

            val failure = assertFailsWith<CancellationException> { executor.execute<Unit> { throw original } }

            assertTrue(failure === original)
        }

    @Test
    fun cancellationInsideEngineIsNotNetwork() =
        runTest {
            val hotel = HotelApi(api { throw CancellationException("cancelado no engine") })

            assertFailsWith<CancellationException> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun unexpectedRuntimeExceptionIsNotSwallowedAsNetwork() =
        runTest {
            val failure =
                runCatching { executor.execute<Unit> { throw IllegalStateException("bug") } }.exceptionOrNull()

            assertTrue(failure is IllegalStateException, "esperava IllegalStateException, veio $failure")
        }

    @Test
    fun userMessageNeverEchoesTheCause() =
        runTest {
            val secret = "tok-segredo-123 http://interno.local/x"
            val failures =
                listOf<() -> Throwable>(
                    { IOException(secret) },
                    { ConnectException(secret) },
                    { HttpRequestTimeoutException(secret, 1L) },
                )
            for (make in failures) {
                val hotel = HotelApi(api { throw make() })

                val failure =
                    assertFailsWith<ApiError.Network> { executor.execute { hotel.getHotel(block = it.headers) } }

                assertFalse("tok-segredo" in failure.userMessage, failure.userMessage)
                assertFalse("interno.local" in failure.userMessage, failure.userMessage)
            }
            val hotel = HotelApi(api { json("""{"configured":"$secret"}""") })
            val incompatible =
                assertFailsWith<ApiError.IncompatibleResponse> {
                    executor.execute { hotel.getHotel(block = it.headers) }
                }
            assertFalse("tok-segredo" in incompatible.userMessage, incompatible.userMessage)
        }

    @Test
    fun clientInstallsNoLoggingPlugin() {
        val client = createCoreHttpClient(MockEngine { respond("") })
        val registry = client.attributes[AttributeKey<Attributes>("ApplicationPluginRegistry")]
        val names = registry.allKeys.map { it.name }

        assertTrue(names.any { it == "HttpTimeout" }, "controle positivo: $names")
        assertTrue(names.none { it.contains("Logging", ignoreCase = true) }, "plugins: $names")
    }

    @Test
    fun serverErrorStatusIsAnApiErrorNotAKtorException() =
        runTest {
            val hotel = HotelApi(api { json("{}", HttpStatusCode.InternalServerError) })

            val failure = runCatching { executor.execute { hotel.getHotel(block = it.headers) } }.exceptionOrNull()

            assertTrue(failure is ApiError, "esperava ApiError, veio $failure")
        }
}
