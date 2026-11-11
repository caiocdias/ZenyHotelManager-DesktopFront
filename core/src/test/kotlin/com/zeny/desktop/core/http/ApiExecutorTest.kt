package com.zeny.desktop.core.http

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.client.RoomsApi
import com.zeny.desktop.sdk.model.ChangePasswordRequest
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.ogen.client.ktor.HttpResponseTyped
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ApiExecutorTest {
    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val requests = mutableListOf<HttpRequestData>()
    private val executor = ApiExecutor()
    private val cancelBody =
        ReservationCancelRequest(reason = ReservationCancelRequest.Reason.OTHER, waivePenalty = true)

    private val hotelJson =
        """{"configured":true,"id":"$id","timezone":"America/Sao_Paulo","updatedAt":"2026-11-10T13:00:00Z"}"""

    private val approvalJson =
        """{"code":"APPROVAL_REQUIRED","detail":"Precisa de aprovação","action":"reservations.cancel",""" +
            """"permission":"reservations.penalties.waive","resourceRef":"reservation:$id"}"""

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
    fun readsEtag() =
        runTest {
            val hotel = HotelApi(api { json(hotelJson, extra = arrayOf("ETag" to "\"7\"")) })

            val response = executor.execute { hotel.getHotel(block = it.headers) }

            assertEquals("\"7\"", response.etag)
            assertEquals(200, response.status)
        }

    @Test
    fun preconditionFailedIsTyped() =
        runTest {
            val hotel = HotelApi(api { json("""{"code":"PRECONDITION_FAILED"}""", HttpStatusCode.PreconditionFailed) })

            assertFailsWith<ApiError.PreconditionFailed> { executor.execute { hotel.getHotel(block = it.headers) } }
        }

    @Test
    fun approvalRetryUsesTokenAndNewKey() =
        runTest {
            var calls = 0
            val reservations =
                ReservationsApi(
                    api {
                        if (++calls == 1) json(approvalJson, HttpStatusCode.Forbidden) else json("{}")
                    },
                )
            val intent = Intent()
            val cancel = cancelCall(reservations)

            val failure = assertFailsWith<ApiError.ApprovalRequired> { executor.execute(intent, call = cancel) }
            assertEquals("reservations.penalties.waive", failure.permission)
            runCatching { executor.executeApproved(intent, "tok-1", cancel) }

            val first = requests[0]
            val second = requests[1]
            assertEquals(1, first.headers.getAll("Idempotency-Key")?.size)
            assertNull(first.headers["X-Approval-Token"])
            assertEquals(listOf(intent.key), second.headers.getAll("Idempotency-Key"))
            assertEquals(listOf("tok-1"), second.headers.getAll("X-Approval-Token"))
            assertTrue(first.headers["Idempotency-Key"] != second.headers["Idempotency-Key"])
        }

    @Test
    fun networkRetryKeepsKey() =
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
            val cancel = cancelCall(reservations)

            assertFailsWith<ApiError.Network> { executor.execute(intent, call = cancel) }
            runCatching { executor.execute(intent, call = cancel) }

            assertEquals(2, requests.size)
            assertEquals(listOf(intent.key), requests[0].headers.getAll("Idempotency-Key"))
            assertEquals(listOf(intent.key), requests[1].headers.getAll("Idempotency-Key"))
        }

    @Test
    fun approvedNetworkResendKeepsKeyAndToken() =
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
            val cancel = cancelCall(reservations)

            assertFailsWith<ApiError.Network> { executor.executeApproved(intent, "tok-1", cancel) }
            runCatching { executor.execute(intent, call = cancel) }

            assertEquals(2, requests.size)
            assertEquals(listOf(intent.key), requests[1].headers.getAll("Idempotency-Key"))
            assertEquals(requests[0].headers.getAll("Idempotency-Key"), requests[1].headers.getAll("Idempotency-Key"))
            assertEquals(listOf("tok-1"), requests[0].headers.getAll("X-Approval-Token"))
            assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
        }

    @Test
    fun getWithoutIntentSendsNoIdempotencyKey() =
        runTest {
            val hotel = HotelApi(api { json(hotelJson) })

            executor.execute { hotel.getHotel(block = it.headers) }

            assertNull(requests.single().headers["Idempotency-Key"])
        }

    @Test
    fun requireIdempotencyKeyWithoutIntentFailsClearly() {
        val failure = assertFailsWith<IllegalStateException> { CallOptions(null, null).requireIdempotencyKey() }

        assertEquals("operação exige Intent", failure.message)
    }

    @Test
    fun securedUnauthorizedIsSessionEnded() =
        runTest {
            val hotel = HotelApi(api { json("""{"code":"ACCOUNT_DISABLED"}""", HttpStatusCode.Unauthorized) })

            val failure =
                assertFailsWith<ApiError.SessionEnded> { executor.execute { hotel.getHotel(block = it.headers) } }

            assertEquals("ACCOUNT_DISABLED", failure.code)
        }

    @Test
    fun credentialCheckUnauthorizedStaysAProblem() =
        runTest {
            val iam = IamApi(api { json("""{"code":"INVALID_CREDENTIALS"}""", HttpStatusCode.Unauthorized) })

            val failure =
                assertFailsWith<ApiError.Problem> {
                    executor.execute(credentialCheck = true) {
                        iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1"), it.headers)
                    }
                }

            assertEquals("Login ou senha incorretos.", failure.userMessage)
            assertEquals(true, requests.single().attributes.getOrNull(CredentialCheck))
        }

    @Test
    fun credentialCheckWithOtherUnauthorizedCodeEndsSession() =
        runTest {
            val iam = IamApi(api { json("""{"code":"UNAUTHENTICATED"}""", HttpStatusCode.Unauthorized) })

            assertFailsWith<ApiError.SessionEnded> {
                executor.execute(credentialCheck = true) {
                    iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1"), it.headers)
                }
            }
        }

    @Test
    fun unknownJsonShapeIsIncompatible() =
        runTest {
            val area =
                """{"active":true,"createdAt":"2026-11-10T13:00:00Z","id":"$id","kind":"ROOFTOP",""" +
                    """"name":"x","updatedAt":"2026-11-10T13:00:00Z"}"""
            val rooms = RoomsApi(api { json(area) })

            assertFailsWith<ApiError.IncompatibleResponse> {
                executor.execute { rooms.getArea(Uuid.parse(id), it.headers) }
            }
        }

    @Test
    fun unreachableServerIsNetworkError() =
        runTest {
            val failures =
                listOf<() -> Throwable>(
                    { ConnectTimeoutException("http://servidor.teste") },
                    { UnresolvedAddressException() },
                )
            for (make in failures) {
                val hotel = HotelApi(api { throw make() })

                assertFailsWith<ApiError.Network> { executor.execute { hotel.getHotel(block = it.headers) } }
            }
        }

    @Test
    fun apiErrorThrownInsideCallPassesThrough() =
        runTest {
            val original = ApiError.Network(IOException("refresh"))

            val failure = assertFailsWith<ApiError.Network> { executor.execute<Unit> { throw original } }

            assertTrue(failure === original)
        }

    @Test
    fun cancellationIsNotNetworkError() =
        runTest {
            assertFailsWith<CancellationException> {
                executor.execute<Unit> { throw CancellationException("cancelado") }
            }
        }

    @Test
    fun clientHasTimeouts() =
        runTest {
            val hotel = HotelApi(api { json(hotelJson) })

            executor.execute { hotel.getHotel(block = it.headers) }

            val config = requests.single().getCapabilityOrNull(HttpTimeoutCapability)
            assertEquals(5_000L, config?.connectTimeoutMillis)
            assertEquals(30_000L, config?.requestTimeoutMillis)
            assertEquals(30_000L, config?.socketTimeoutMillis)
        }
}
