package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkBookingGatewayEstadoAtaqueTest : BookingAtaqueBase() {
    // ---------- findReservation ----------

    @Test
    fun lookupPassesExternalIdInQueryAndCursorFromPreviousPageOnly() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> reply("""{"items":[${itemOf(U1, "A")}],"nextCursor":"c2"}""")
                        "c2" -> reply("""{"items":[${itemOf(U2, "B")}],"nextCursor":"c3"}""")
                        else -> reply("""{"items":[${itemOf(U3, "DEMO-SEED-Z9")}]}""")
                    }
                }
            assertEquals(U3, gateway.findReservation("DEMO-SEED-Z9")?.id)
            assertEquals(3, requests.size)
            requests.forEach { assertEquals("DEMO-SEED-Z9", it.url.parameters["externalId"]) }
        }

    @Test
    fun lookupWithEmptyNextCursorStopsAfterOnePage() =
        runTest {
            val gateway = gateway { reply("""{"items":[${itemOf(U1, "A")}],"nextCursor":""}""") }
            assertNull(gateway.findReservation("DEMO-SEED-Z9"))
            assertEquals(1, requests.size, "cursor vazio deveria encerrar a paginação")
        }

    @Test
    fun lookupWithNullNextCursorStopsAfterOnePage() =
        runTest {
            val gateway = gateway { reply("""{"items":[],"nextCursor":null}""") }
            assertNull(gateway.findReservation("DEMO-SEED-Z9"))
            assertEquals(1, requests.size)
        }

    @Test
    fun lookupStopsAtFirstMatchWithoutFetchingLaterPages() =
        runTest {
            val gateway =
                gateway { reply("""{"items":[${itemOf(U1, "DEMO-SEED-Z9")}],"nextCursor":"c2"}""") }
            assertEquals(U1, gateway.findReservation("DEMO-SEED-Z9")?.id)
            assertEquals(1, requests.size)
        }

    @Test
    fun lookupExternalIdComparisonIsExactNotPrefixNorCaseInsensitive() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        """{"items":[${itemOf(U1, "DEMO-SEED-Z90")},${itemOf(U2, "demo-seed-z9")},
                        |${itemOf(U3, "DEMO-SEED-Z9 ")}]}
                        """.trimMargin(),
                    )
                }
            assertNull(gateway.findReservation("DEMO-SEED-Z9"))
        }

    @Test
    fun lookupWithRoomAssignmentOfReleasedKindIsStillReportedByItem() =
        runTest {
            val gateway = gateway { reply("""{"items":[${itemOf(U3, "DEMO-SEED-Z9", status = "CHECKED_OUT")}]}""") }
            val ref = assertNotNull(gateway.findReservation("DEMO-SEED-Z9"))
            assertEquals("CHECKED_OUT", ref.status)
            assertNull(ref.roomId)
        }

    // ---------- guestCount em memória ----------

    @Test
    fun checkInOfNeverSeenReservationOmitsGuestCount() =
        runTest {
            val gateway = gateway { reply(CHECK_IN_OK) }
            gateway.checkIn(U3, U6, listOf(U1))
            assertFalse("guestCount" in sent(0), "guestCount inventado para reserva nunca vista")
        }

    @Test
    fun guestCountIsPerReservationNotLastSeen() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/check-in")) {
                        reply(CHECK_IN_OK)
                    } else if (request.url.parameters["externalId"] == "E1") {
                        reply("""{"items":[${itemOf(U1, "E1", adults = 3)}]}""")
                    } else {
                        reply("""{"items":[${itemOf(U2, "E2", adults = 1)}]}""")
                    }
                }
            gateway.findReservation("E1")
            gateway.findReservation("E2")
            gateway.checkIn(U1, U6, listOf(U1))
            gateway.checkIn(U2, U6, listOf(U1))
            gateway.checkIn(U3, U6, listOf(U1))
            val checkIns = requests.indices.filter { requests[it].url.encodedPath.endsWith("/check-in") }
            assertEquals(3, sent(checkIns[0]).getValue("guestCount").jsonPrimitive.int)
            assertEquals(1, sent(checkIns[1]).getValue("guestCount").jsonPrimitive.int)
            assertFalse("guestCount" in sent(checkIns[2]))
        }

    @Test
    fun guestCountRefreshesWhenReservationIsSeenAgainWithNewAdults() =
        runTest {
            var adults = 2
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/check-in")) {
                        reply(CHECK_IN_OK)
                    } else {
                        reply("""{"items":[${itemOf(U1, "E1", adults = adults)}]}""")
                    }
                }
            gateway.findReservation("E1")
            adults = 4
            gateway.findReservation("E1")
            gateway.checkIn(U1, U6, listOf(U1))
            assertEquals(4, sent(2).getValue("guestCount").jsonPrimitive.int)
        }

    @Test
    fun twoGatewaysDoNotShareGuestCountState() =
        runTest {
            val first =
                gateway { request ->
                    if (request.url.encodedPath.endsWith(
                            "/check-in",
                        )
                    ) {
                        reply(CHECK_IN_OK)
                    } else {
                        reply(bookingOf(), HttpStatusCode.Created)
                    }
                }
            first.createReservation(RES_SPEC, U1, U4, U5)
            val second = gateway { reply(CHECK_IN_OK) }
            second.checkIn(U3, U6, listOf(U1))
            assertFalse("guestCount" in sent(requests.lastIndex), "estado vazou entre gateways")
        }

    @Test
    fun createdReservationGuestCountFollowsSpecAdults() =
        runTest {
            val spec = RES_SPEC.copy(adults = 1)
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith(
                            "/check-in",
                        )
                    ) {
                        reply(CHECK_IN_OK)
                    } else {
                        reply(bookingOf(), HttpStatusCode.Created)
                    }
                }
            gateway.createReservation(spec, U1, U4, U5)
            gateway.checkIn(U3, U6, listOf(U1))
            assertEquals(
                1,
                sent(0)
                    .getValue("reservations")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("adults")
                    .jsonPrimitive.int,
            )
            assertEquals(1, sent(1).getValue("guestCount").jsonPrimitive.int)
        }

    // ---------- guests / duplicados ----------

    @Test
    fun duplicateGuestsOnServerResolveToOneOfThemWithoutExtraRequests() =
        runTest {
            val gateway = gateway { reply("""{"items":[${guestOf(U1)},${guestOf(U2)},${guestOf(U4)}]}""") }
            val id = gateway.guestId(THE_CPF)
            assertTrue(id in setOf(U1, U2, U4))
            assertEquals(1, requests.size)
            assertEquals(id, gateway.guestId(THE_CPF), "resultado instável para a mesma resposta")
        }

    @Test
    fun guestSearchBodyHasOnlyNumberAndCpfType() =
        runTest {
            val gateway = gateway { reply("""{"items":[]}""") }
            gateway.guestId(THE_CPF)
            assertEquals(setOf("number", "type"), sent(0).keys)
            assertEquals(JsonPrimitive("CPF"), sent(0).getValue("type"))
            assertTrue(
                requests
                    .single()
                    .url.parameters
                    .isEmpty(),
                "parâmetros de query na busca por documento",
            )
        }

    // ---------- inventário ----------

    @Test
    fun inventoryEmptyReturnsEmptyList() =
        runTest {
            val gateway = gateway { reply("""{"items":[]}""") }
            assertTrue(gateway.inventory(U4, DAY, DAY.plusDays(3)).isEmpty())
        }

    @Test
    fun inventoryPreservesServerOrderAndMissingDatesAreNotInvented() =
        runTest {
            fun day(
                date: String,
                total: Int,
            ) = """{"available":0,"blocked":0,"date":"$date","outOfOrder":0,"overbookingLimit":0,"roomTypeId":"$U4",
                |"sold":0,"total":$total}
                """.trimMargin()
            val gateway = gateway { reply("""{"items":[${day("2026-11-15", 5)},${day("2026-11-12", 8)}]}""") }
            val days = gateway.inventory(U4, DAY, DAY.plusDays(4))
            assertEquals(listOf(LocalDate.of(2026, 11, 15), DAY), days.map { it.date })
            assertEquals(2, days.size)
        }

    @Test
    fun inventoryMalformedDateIsIncompatibleOrParseFailureWithoutCrashingRawJson() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        """{"items":[{"available":0,"blocked":0,"date":"12/11/2026","outOfOrder":0,"overbookingLimit":0,
                        |"roomTypeId":"$U4","sold":0,"total":1}]}
                        """.trimMargin(),
                    )
                }
            val failure = runCatching { gateway.inventory(U4, DAY, DAY) }.exceptionOrNull()
            assertNotNull(failure, "data inválida passou em silêncio")
            assertTrue(failure is ApiError || failure is java.time.format.DateTimeParseException)
        }

    @Test
    fun inventoryDatesAreSentAsIsoWithoutTimeZone() =
        runTest {
            val gateway = gateway { reply("""{"items":[]}""") }
            gateway.inventory(U4, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31))
            val url = requests.single().url
            assertEquals("2026-01-05", url.parameters["from"])
            assertEquals("2026-12-31", url.parameters["to"])
        }

    // ---------- corpos exatos ----------

    @Test
    fun bookingBodyHasExactlyTheExpectedShape() =
        runTest {
            val gateway = gateway { reply(bookingOf(), HttpStatusCode.Created) }
            gateway.createReservation(RES_SPEC, U1, U4, U5)
            val body = sent(0)
            assertEquals("FRONT_DESK", body.getValue("origin").jsonPrimitive.content)
            assertEquals("DEMO-SEED-Z9", body.getValue("externalId").jsonPrimitive.content)
            val reservations = body.getValue("reservations").jsonArray
            assertEquals(1, reservations.size)
            val reservation = reservations.single().jsonObject
            assertEquals(0, reservation.getValue("childAges").jsonArray.size)
            assertEquals(0, reservation.getValue("nightlyRates").jsonArray.size)
            assertEquals("LEISURE", reservation.getValue("segment").jsonPrimitive.content)
            val guests = reservation.getValue("guests").jsonArray
            assertEquals(1, guests.size)
            assertEquals(JsonPrimitive(true), guests.single().jsonObject.getValue("primary"))
            assertEquals(
                U1,
                guests
                    .single()
                    .jsonObject
                    .getValue("guestId")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun bookingReturnedWithTwoReservationsFailsInsteadOfPickingOne() =
        runTest {
            val two =
                bookingOf().replace(
                    "\"reservations\":[${reservationOf()}]",
                    "\"reservations\":[${reservationOf()},${reservationOf()}]",
                )
            val gateway = gateway { reply(two, HttpStatusCode.Created) }
            assertFailsWith<IllegalArgumentException> { gateway.createReservation(RES_SPEC, U1, U4, U5) }
        }

    @Test
    fun assignRoomBodyHasOnlyRoomIdAndInitialReason() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        """{"bookingCode":"B","holderGuestId":"$U1","reservation":${reservationOf()},
                        |"restrictionAlerts":[]}
                        """.trimMargin(),
                    )
                }
            gateway.assignRoom(U3, U6)
            assertEquals(setOf("roomId", "reason"), sent(0).keys)
            assertEquals("INITIAL", sent(0).getValue("reason").jsonPrimitive.content)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains(U3),
            )
        }

    @Test
    fun checkInBodyWithSeveralGuestsMarksOnlyFirstPrimaryAndOmitsExtras() =
        runTest {
            val gateway = gateway { reply(CHECK_IN_OK) }
            gateway.checkIn(U3, U6, listOf(U1, U2, U4))
            val guests = sent(0).getValue("guests").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf(true, false, false),
                guests.map {
                    it
                        .getValue("primary")
                        .jsonPrimitive.content
                        .toBoolean()
                },
            )
            assertEquals(setOf("roomId", "guests"), sent(0).keys, "campos extras no check-in: ${sent(0).keys}")
        }

    @Test
    fun malformedIdsFailBeforeAnyRequest() =
        runTest {
            val gateway = gateway { reply("""{"items":[]}""") }
            assertFailsWith<IllegalArgumentException> { gateway.checkIn("x", U6, listOf(U1)) }
            assertFailsWith<IllegalArgumentException> { gateway.assignRoom(U3, "y") }
            assertFailsWith<IllegalArgumentException> { gateway.inventory("z", DAY, DAY) }
            assertTrue(requests.isEmpty())
            assertIs<SdkBookingGateway>(gateway)
        }
}
