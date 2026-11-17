package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val A_RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e11"
private const val A_BOOKING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f11"
private const val A_PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d11"
private const val A_TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b11"
private const val A_GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c11"

/**
 * Ataques à `BookingsFacade` (M3 E03): a criação com e sem token sob perda de rede, como a `NewReservationState` a usa
 * (uma ação nova a cada tentativa, sobre a mesma `Intent`), e a alteração aprovada, que nunca leva `Idempotency-Key`.
 */
class BookingsFacadeAtaqueTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-ataque"
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

    private fun reservationJson() =
        """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-12-10","bookingId":"$A_BOOKING",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$A_BOOKING",
        "version":1},"childAges":[],"code":"R-1","createdAt":"2026-11-16T10:00:00Z","dayUse":false,
        "departure":"2026-12-12","folioId":"$A_BOOKING",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$A_BOOKING","type":"NONE","version":1},
        "guests":[],"id":"$A_RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$A_PLAN","roomTotal":"361.00","roomTypeId":"$A_TYPE","segment":"LEISURE","status":"CONFIRMED",
        "total":"361.00","version":1}"""

    private fun booking() =
        """{"channel":"DIRECT","code":"B-1","createdAt":"2026-11-16T10:00:00Z","holderGuestId":"$A_GUEST",
        "id":"$A_BOOKING","origin":"FRONT_DESK","reservations":[${reservationJson()}],"restrictionAlerts":[],
        "version":1}"""

    private fun detail() =
        """{"bookingCode":"B-1","holderGuestId":"$A_GUEST","reservation":${reservationJson()},
        "restrictionAlerts":[]}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"rates.discount","permission":"p","resourceRef":"r"}"""

    private fun input() =
        BookingInput(
            holderGuestId = A_GUEST,
            origin = "FRONT_DESK",
            arrival = BusinessDate.parse("2026-12-10"),
            departure = BusinessDate.parse("2026-12-12"),
            adults = 2,
            childAges = emptyList(),
            roomTypeId = A_TYPE,
            ratePlanId = A_PLAN,
            segment = "LEISURE",
            specialRequests = null,
            nightlyRates =
                listOf(
                    NightlyRateInput(BusinessDate.parse("2026-12-10"), Money.parse("1.00")),
                    NightlyRateInput(BusinessDate.parse("2026-12-11"), Money.parse("1.00")),
                ),
        )

    private fun changeInput() =
        ReservationChangeInput(
            arrival = BusinessDate.parse("2026-12-10"),
            departure = BusinessDate.parse("2026-12-13"),
            adults = 2,
            childAges = emptyList(),
            roomTypeId = A_TYPE,
            ratePlanId = A_PLAN,
            segment = "LEISURE",
            specialRequests = null,
            nightlyRates = emptyList(),
        )

    @Test
    fun lostApprovedRunIsResentWithTheRotatedKeyAndTheToken() =
        runTest {
            val approvedRuns = AtomicInteger()
            core { request ->
                when {
                    request.headers["X-Approval-Token"] == null -> json(approvalProblem, HttpStatusCode.Forbidden)
                    approvedRuns.getAndIncrement() == 0 -> throw IOException("conexão caiu depois do envio")
                    else -> json(booking(), HttpStatusCode.Created)
                }
            }.use { core ->
                val intent = Intent()
                val first = core.bookings.createBooking(input(), intent)
                assertFailsWith<ApiError.ApprovalRequired> { first.run(null) }
                assertIs<ApiError.Network>(assertFailsWith<ApiError> { first.run("tok-gerente") })

                // A tela repete com uma ação NOVA sobre a mesma `Intent`, e o coordenador tenta primeiro sem token.
                val retried = core.bookings.createBooking(input(), intent).run(null)

                assertEquals("B-1", retried.value.code)
                assertEquals(3, requests.size)
                val original = requests[0].headers["Idempotency-Key"]
                val rotated = requests[1].headers["Idempotency-Key"]
                assertNotEquals(original, rotated)
                assertEquals(rotated, requests[2].headers["Idempotency-Key"], "a repetição girou a chave de novo")
                assertEquals("tok-gerente", requests[2].headers["X-Approval-Token"], "a repetição perdeu o token")
                assertEquals(1, requests[2].headers.getAll("Idempotency-Key")?.size)
            }
        }

    @Test
    fun unknownResultWithoutApprovalIsResentWithTheSameKeyAndNoToken() =
        runTest {
            val calls = AtomicInteger()
            core {
                if (calls.getAndIncrement() == 0) throw IOException("sem resposta")
                json(booking(), HttpStatusCode.Created)
            }.use { core ->
                val intent = Intent()
                assertFailsWith<ApiError.Network> { core.bookings.createBooking(input(), intent).run(null) }
                core.bookings.createBooking(input(), intent).run(null)

                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                assertNull(requests[1].headers["X-Approval-Token"])
            }
        }

    @Test
    fun serverErrorIsAProblemWithItsStatus() =
        runTest {
            core {
                json("""{"code":"INTERNAL_ERROR","detail":"falhou"}""", HttpStatusCode.InternalServerError)
            }.use { core ->
                val failure =
                    assertFailsWith<ApiError.Problem> { core.bookings.createBooking(input(), Intent()).run(null) }
                // A tela trata `status >= 500` como resultado desconhecido (mesma chave); outro tipo perderia isso.
                assertEquals(500, failure.status)
            }
        }

    @Test
    fun approvedChangeKeepsTheSameIfMatchAndNeverSendsAnIdempotencyKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(detail(), HttpStatusCode.OK)
                }
            }.use { core ->
                val action = core.bookings.changeReservation(A_RES, changeInput(), "\"v9\"")
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok")

                assertEquals(listOf("\"v9\"", "\"v9\""), requests.map { it.headers["If-Match"] })
                assertEquals(listOf(null, null), requests.map { it.headers["Idempotency-Key"] })
                assertEquals(listOf(null, "tok"), requests.map { it.headers["X-Approval-Token"] })
            }
        }
}
