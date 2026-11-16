package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val BOOKING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ETAG = "\"v7\""
private const val SECRET = "SEGREDO-NAO-VAZA"

/** Criar, ler, alterar e confirmar reservas da `BookingsFacade`, contra o `MockEngine`. */
class BookingsFacadeTest {
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

    private fun reservationJson(status: String = "CONFIRMED") =
        """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-12-10","bookingId":"$BOOKING",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$BOOKING","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-16T10:00:00Z","dayUse":false,"departure":"2026-12-12",
        "folioId":"$BOOKING",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$BOOKING","type":"NONE","version":1},
        "guests":[],"id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$PLAN","roomTotal":"361.00","roomTypeId":"$TYPE","segment":"LEISURE","status":"$status",
        "total":"361.00","version":1}"""

    private fun booking() =
        """{"channel":"DIRECT","code":"B-1","createdAt":"2026-11-16T10:00:00Z","holderGuestId":"$GUEST",
        "id":"$BOOKING","origin":"FRONT_DESK","reservations":[${reservationJson()}],"restrictionAlerts":[],
        "version":1}"""

    private fun detail(status: String = "CONFIRMED") =
        """{"bookingCode":"B-1","holderGuestId":"$GUEST","reservation":${reservationJson(status)},
        "restrictionAlerts":[]}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"rates.discount","permission":"p","resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun rates() =
        listOf(
            NightlyRateInput(BusinessDate.parse("2026-12-10"), Money.parse("180.50")),
            NightlyRateInput(BusinessDate.parse("2026-12-11"), Money.parse("180.50")),
        )

    private fun bookingInput(
        origin: String = "FRONT_DESK",
        segment: String = "LEISURE",
        holder: String = GUEST,
        specialRequests: String? = "Berço",
        nightlyRates: List<NightlyRateInput> = rates(),
    ) = BookingInput(
        holderGuestId = holder,
        origin = origin,
        arrival = BusinessDate.parse("2026-12-10"),
        departure = BusinessDate.parse("2026-12-12"),
        adults = 2,
        childAges = listOf(5, 8),
        roomTypeId = TYPE,
        ratePlanId = PLAN,
        segment = segment,
        specialRequests = specialRequests,
        nightlyRates = nightlyRates,
    )

    private fun changeInput(
        specialRequests: String? = "Andar alto",
        segment: String = "BUSINESS",
    ) = ReservationChangeInput(
        arrival = BusinessDate.parse("2026-12-10"),
        departure = BusinessDate.parse("2026-12-13"),
        adults = 1,
        childAges = emptyList(),
        roomTypeId = TYPE,
        ratePlanId = PLAN,
        segment = segment,
        specialRequests = specialRequests,
        nightlyRates = emptyList(),
    )

    @Test
    fun createBookingSendsOneReservation() =
        runTest {
            core { json(booking(), HttpStatusCode.Created) }.use { core ->
                val intent = Intent()
                val response = core.bookings.createBooking(bookingInput(), intent).run(null)
                assertEquals("B-1", response.value.code)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/bookings"), request.url.encodedPath)
                assertEquals(intent.key, request.headers["Idempotency-Key"])
                val body = bodyOf(request)
                assertEquals(GUEST, body["holderGuestId"]!!.jsonPrimitive.content)
                assertEquals("FRONT_DESK", body["origin"]!!.jsonPrimitive.content)
                assertTrue("externalId" !in body.keys, body.keys.toString())
                val reservation = body["reservations"]!!.jsonArray.single().jsonObject
                assertEquals(emptyList(), reservation["guests"]!!.jsonArray.toList())
                assertEquals("2026-12-10", reservation["arrival"]!!.jsonPrimitive.content)
                assertEquals("2026-12-12", reservation["departure"]!!.jsonPrimitive.content)
                assertEquals(2, reservation["adults"]!!.jsonPrimitive.content.toInt())
                assertEquals(
                    listOf(5, 8),
                    reservation["childAges"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() },
                )
                assertEquals(TYPE, reservation["roomTypeId"]!!.jsonPrimitive.content)
                assertEquals(PLAN, reservation["ratePlanId"]!!.jsonPrimitive.content)
                assertEquals("LEISURE", reservation["segment"]!!.jsonPrimitive.content)
                assertEquals("Berço", reservation["specialRequests"]!!.jsonPrimitive.content)
                val nightly = reservation["nightlyRates"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf("2026-12-10", "2026-12-11"), nightly.map { it["date"]!!.jsonPrimitive.content })
                assertEquals(listOf("180.50", "180.50"), nightly.map { it["roomAmount"]!!.jsonPrimitive.content })
            }
        }

    @Test
    fun createBookingApprovedRotatesKeyAndSendsToken() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(booking(), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.bookings.createBooking(bookingInput(), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun createBookingBlankRequestsAreOmitted() =
        runTest {
            core { json(booking(), HttpStatusCode.Created) }.use { core ->
                core.bookings.createBooking(bookingInput(specialRequests = null), Intent()).run(null)
                core.bookings.createBooking(bookingInput(specialRequests = "   "), Intent()).run(null)
                requests.forEach {
                    val reservation = bodyOf(it)["reservations"]!!.jsonArray.single().jsonObject
                    assertTrue("specialRequests" !in reservation.keys, reservation.keys.toString())
                }
            }
        }

    @Test
    fun invalidInputFailsBeforeNetwork() =
        runTest {
            core { json(booking(), HttpStatusCode.Created) }.use { core ->
                val facade = core.bookings
                val bad = "segredo-invalido"
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> {
                            facade.createBooking(bookingInput(holder = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.createBooking(bookingInput(origin = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.createBooking(bookingInput(segment = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.createBooking(bookingInput().copy(roomTypeId = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.createBooking(bookingInput().copy(ratePlanId = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.changeReservation(bad, changeInput(), ETAG)
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.changeReservation(RES, changeInput(segment = bad), ETAG)
                        },
                    )
                failures.forEach { assertTrue(bad !in it.message.orEmpty(), it.message) }
                assertFailsWith<IllegalArgumentException> { facade.booking(bad) }
                assertFailsWith<IllegalArgumentException> { facade.confirmReservation(bad, "CARD", Intent()) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun changeReservationSendsIfMatchAndNoIdempotencyKey() =
        runTest {
            core { json(detail(), HttpStatusCode.OK, "ETag" to "\"v8\"") }.use { core ->
                val action = core.bookings.changeReservation(RES, changeInput(), ETAG)
                val response = action.run(null)
                assertEquals("\"v8\"", response.etag)
                action.run("tok-1")
                val first = requests[0]
                assertEquals(HttpMethod.Put, first.method)
                assertTrue(first.url.encodedPath.endsWith("/reservations/$RES"), first.url.encodedPath)
                assertEquals(ETAG, first.headers["If-Match"])
                assertNull(first.headers["Idempotency-Key"])
                assertNull(first.headers["X-Approval-Token"])
                val body = bodyOf(first)
                assertEquals("2026-12-13", body["departure"]!!.jsonPrimitive.content)
                assertEquals("BUSINESS", body["segment"]!!.jsonPrimitive.content)
                assertEquals("Andar alto", body["specialRequests"]!!.jsonPrimitive.content)
                assertEquals(emptyList(), body["nightlyRates"]!!.jsonArray.toList())
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertNull(requests[1].headers["Idempotency-Key"])
                assertEquals(ETAG, requests[1].headers["If-Match"])
                assertFailsWith<IllegalArgumentException> { core.bookings.changeReservation(RES, changeInput(), "  ") }
            }
        }

    @Test
    fun changeReservationNullRequestsClear() =
        runTest {
            core { json(detail()) }.use { core ->
                core.bookings.changeReservation(RES, changeInput(specialRequests = null), ETAG).run(null)
                val body = bodyOf(requests.single())
                assertTrue("specialRequests" in body.keys, body.keys.toString())
                assertEquals(JsonNull, body["specialRequests"])
            }
        }

    @Test
    fun changeReservation412IsPreconditionFailed() =
        runTest {
            core { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED") }.use { core ->
                assertFailsWith<ApiError.PreconditionFailed> {
                    core.bookings.changeReservation(RES, changeInput(), ETAG).run(null)
                }
            }
        }

    @Test
    fun confirmReservationSendsGuaranteeAndKey() =
        runTest {
            core { json(detail(), HttpStatusCode.OK) }.use { core ->
                val intent = Intent()
                val response = core.bookings.confirmReservation(RES, "CARD", intent)
                assertEquals("CONFIRMED", response.reservation.status)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/reservations/$RES/confirm"), request.url.encodedPath)
                assertEquals(intent.key, request.headers["Idempotency-Key"])
                assertEquals("""{"guarantee":"CARD"}""", request.body.toByteArray().decodeToString())
                core.bookings.confirmReservation(RES, "DEPOSIT", Intent())
                assertEquals("DEPOSIT", bodyOf(requests[1])["guarantee"]!!.jsonPrimitive.content)
                val bad = "segredo-garantia"
                val error =
                    assertFailsWith<IllegalArgumentException> { core.bookings.confirmReservation(RES, bad, Intent()) }
                assertTrue(bad !in error.message.orEmpty())
                assertFailsWith<IllegalArgumentException> { core.bookings.confirmReservation(RES, "NONE", Intent()) }
                assertEquals(2, requests.size)
            }
        }

    @Test
    fun confirmRefusalIsProblem() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "PROVISIONAL_RESERVATION_EXPIRED") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> { core.bookings.confirmReservation(RES, "CARD", Intent()) }
                assertEquals("PROVISIONAL_RESERVATION_EXPIRED", error.code)
            }
        }

    @Test
    fun bookingReadsById() =
        runTest {
            core { json(booking()) }.use { core ->
                val response = core.bookings.booking(BOOKING)
                assertEquals("B-1", response.code)
                assertEquals(
                    RES,
                    response.reservations
                        .single()
                        .id
                        .toString(),
                )
                val request = requests.single()
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.encodedPath.endsWith("/bookings/$BOOKING"), request.url.encodedPath)
                assertNull(request.headers["Idempotency-Key"])
            }
            core { problem(HttpStatusCode.NotFound, "BOOKING_NOT_FOUND") }.use { core ->
                val error = assertIs<ApiError.Problem>(assertFailsWith<ApiError> { core.bookings.booking(BOOKING) })
                assertEquals("BOOKING_NOT_FOUND", error.code)
            }
        }

    @Test
    fun inputsHideSpecialRequestsInToString() {
        val texts =
            listOf(
                bookingInput(specialRequests = SECRET).toString(),
                changeInput(specialRequests = SECRET).toString(),
            )
        texts.forEach { assertTrue(SECRET !in it, it) }
    }
}
