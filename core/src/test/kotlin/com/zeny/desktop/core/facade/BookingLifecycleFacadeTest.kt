package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val BOOKING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val SECRET = "OBSERVACAO-NAO-VAZA"

/** Cancelar e reativar reservas na `BookingsFacade`, contra o `MockEngine`. */
class BookingLifecycleFacadeTest {
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

    private fun detail(status: String) =
        """{"bookingCode":"B-1","holderGuestId":"$GUEST","reservation":{"additionalPackagesTotal":"0.00","adults":2,
        "arrival":"2026-12-10","bookingId":"$BOOKING",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$BOOKING","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-16T10:00:00Z","dayUse":false,"departure":"2026-12-12",
        "folioId":"$BOOKING",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$BOOKING","type":"NONE","version":1},
        "guests":[],"id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$PLAN","roomTotal":"361.00","roomTypeId":"$TYPE","segment":"LEISURE","status":"$status",
        "total":"361.00","version":2},"restrictionAlerts":[]}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"reservations.penalties.waive","permission":"p",
        "resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun cancelInput(
        reason: String = "GUEST_REQUEST",
        waivePenalty: Boolean = false,
        notes: String? = null,
    ) = CancelInput(reason, waivePenalty, notes)

    @Test
    fun cancelSendsReasonWaiverAndKey() =
        runTest {
            core { json(detail("CANCELLED")) }.use { core ->
                val intent = Intent()
                val response = core.bookings.cancelReservation(RES, cancelInput(waivePenalty = true), intent).run(null)
                assertEquals("CANCELLED", response.value.reservation.status)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/reservations/$RES/cancel"), request.url.encodedPath)
                assertEquals(intent.key, request.headers["Idempotency-Key"])
                assertNull(request.headers["If-Match"])
                assertNull(request.headers["X-Approval-Token"])
                val body = bodyOf(request)
                assertEquals("GUEST_REQUEST", body["reason"]!!.jsonPrimitive.content)
                assertEquals("true", body["waivePenalty"]!!.jsonPrimitive.content)
                assertTrue("notes" !in body.keys, body.keys.toString())

                core.bookings.cancelReservation(RES, cancelInput("OTHER", false, "   "), Intent()).run(null)
                val blank = bodyOf(requests[1])
                assertEquals("OTHER", blank["reason"]!!.jsonPrimitive.content)
                assertEquals("false", blank["waivePenalty"]!!.jsonPrimitive.content)
                assertTrue("notes" !in blank.keys, blank.keys.toString())

                core.bookings.cancelReservation(RES, cancelInput("OTHER", false, "Voo cancelado"), Intent()).run(null)
                assertEquals("Voo cancelado", bodyOf(requests[2])["notes"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun cancelApprovedRotatesKeyAndSendsToken() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(detail("CANCELLED"))
                }
            }.use { core ->
                val action = core.bookings.cancelReservation(RES, cancelInput(waivePenalty = true), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                assertNull(requests[1].headers["If-Match"])
            }
        }

    @Test
    fun cancelRejectsExpiredAndUnknownReason() =
        runTest {
            core { json(detail("CANCELLED")) }.use { core ->
                val bad = "motivo-segredo"
                listOf("EXPIRED", bad, "", "guest_request").forEach { reason ->
                    val error =
                        assertFailsWith<IllegalArgumentException> {
                            core.bookings.cancelReservation(RES, cancelInput(reason), Intent())
                        }
                    assertTrue(bad !in error.message.orEmpty(), error.message)
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun reactivateSendsKeyAndNoBody() =
        runTest {
            core { json(detail("CONFIRMED")) }.use { core ->
                val intent = Intent()
                val response = core.bookings.reactivateReservation(RES, intent).run(null)
                assertEquals("CONFIRMED", response.value.reservation.status)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/reservations/$RES/reactivate"), request.url.encodedPath)
                assertEquals(intent.key, request.headers["Idempotency-Key"])
                assertNull(request.headers["If-Match"])
                assertNull(request.headers["X-Approval-Token"])
                assertEquals(0, request.body.toByteArray().size)
            }
        }

    @Test
    fun reactivateApprovedRotatesKeyAndSendsToken() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(detail("CONFIRMED"))
                }
            }.use { core ->
                val action = core.bookings.reactivateReservation(RES, Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun invalidIdFailsBeforeNetwork() =
        runTest {
            core { json(detail("CANCELLED")) }.use { core ->
                val bad = "id-segredo"
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> {
                            core.bookings.cancelReservation(bad, cancelInput(), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.bookings.reactivateReservation(bad, Intent())
                        },
                    )
                failures.forEach { assertTrue(bad !in it.message.orEmpty(), it.message) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun cancelInputHidesNotesInToString() {
        val text = cancelInput(notes = SECRET).toString()
        assertTrue(SECRET !in text, text)
    }

    @Test
    fun cancellationInvalidShowsServerDetail() =
        runTest {
            val detail = "O motivo EXPIRED é reservado ao servidor."
            core {
                json(
                    """{"code":"RESERVATION_CANCELLATION_INVALID","detail":"$detail"}""",
                    HttpStatusCode.BadRequest,
                )
            }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.bookings.cancelReservation(RES, cancelInput(), Intent()).run(null)
                    }
                assertEquals("RESERVATION_CANCELLATION_INVALID", error.code)
                assertEquals(detail, error.userMessage)
            }
        }
}
