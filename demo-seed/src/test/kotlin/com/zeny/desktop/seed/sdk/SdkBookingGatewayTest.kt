package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.seed.data.CompanySpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.ReservationSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BOOKING_SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val G1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val G2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val RESERVATION = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c05"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c06"
private const val COMPANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c07"
private const val STAMP = "2026-11-12T10:00:00Z"
private const val CPF = "52998224725"
private val TODAY = LocalDate.of(2026, 11, 12)

private fun MockRequestHandleScope.ok(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun guestJson(
    id: String,
    status: String = "ACTIVE",
) = """{"createdAt":"$STAMP","documents":[],"fullName":"Fulano","hasSensitiveData":false,"id":"$id",
    |"preferences":{},"status":"$status","updatedAt":"$STAMP","vip":false}
    """.trimMargin()

private fun listItem(
    id: String,
    externalId: String?,
    status: String = "CONFIRMED",
    roomId: String? = null,
    adults: Int = 2,
): String {
    val ext = if (externalId == null) "" else ""","externalId":"$externalId""""
    val room =
        if (roomId ==
            null
        ) {
            ""
        } else {
            ""","roomAssignment":{"doNotMove":false,"id":"$G2","reallocationRequired":false,"roomId":"$roomId"}"""
        }
    return """{"adults":$adults,"arrival":"2026-11-12","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$G1",
        |"channel":"FRONT_DESK","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-14",
        |"departureOverdue":false,"folioId":"$G1","holderGuestId":"$G1","id":"$id","origin":"FRONT_DESK",
        |"ratePlanCategory":"NORMAL","ratePlanId":"$PLAN","roomTypeId":"$TYPE","segment":"LEISURE","status":"$status",
        |"total":"640.00","version":1$ext$room}
        """.trimMargin()
}

private fun reservationJson(status: String) =
    """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-12","bookingId":"$G1",
    |"cancellationTerms":{"deadlineHours":24,"name":"x","penaltyType":"NIGHTS","policyId":"$G1","version":1},
    |"childAges":[],"code":"R-1","createdAt":"$STAMP","dayUse":false,"departure":"2026-11-14","folioId":"$G1",
    |"guaranteeTerms":{"name":"x","policyId":"$G1","type":"NONE","version":1},"guests":[],"id":"$RESERVATION",
    |"nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL","ratePlanId":"$PLAN","roomTotal":"0.00",
    |"roomTypeId":"$TYPE","segment":"LEISURE","status":"$status","total":"0.00","version":1}
    """.trimMargin()

private fun detailJson(status: String) =
    """{"bookingCode":"B-1","holderGuestId":"$G1","reservation":${reservationJson(status)},"restrictionAlerts":[]}"""

private fun bookingJson(status: String) =
    """{"channel":"FRONT_DESK","code":"B-1","createdAt":"$STAMP","holderGuestId":"$G1","id":"$G1","origin":"FRONT_DESK",
    |"reservations":[${reservationJson(status)}],"restrictionAlerts":[],"version":1}
    """.trimMargin()

private const val CHECK_IN_JSON =
    """{"reservation":{"adults":2,"arrival":"2026-11-12","bookingId":"$G1","children":0,"code":"R-1","dayUse":false,
    "departure":"2026-11-14","folioId":"$G1","guests":[],"id":"$RESERVATION","roomTypeId":"$TYPE",
    "status":"IN_HOUSE","version":2},"restrictionAlerts":[],
    "room":{"housekeepingStatus":"INSPECTED","inspectionPending":false,"number":"101","occupancy":"OCCUPIED",
    "roomId":"$ROOM","version":2}}"""

private val SPEC =
    ReservationSpec("A1", "G1", "STD", TODAY, TODAY.plusDays(2), 2, "101", true)

class SdkBookingGatewayTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val bodies = CopyOnWriteArrayList<String>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkBookingGateway(
            SeedCore(
                BOOKING_SERVER,
                MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                },
            ),
        )

    private fun body(index: Int) = Json.parseToJsonElement(bodies[index]).jsonObject

    @Test
    fun guestSearchSendsCpfInBodyOnly() =
        runTest {
            val gateway = gateway { ok("""{"items":[${guestJson(G1)}]}""") }
            assertEquals(G1, gateway.guestId(CPF))
            val request = requests.single()
            assertFalse(CPF in request.url.toString(), "CPF na URL")
            assertEquals("CPF", body(0).getValue("type").jsonPrimitive.content)
            assertEquals(CPF, body(0).getValue("number").jsonPrimitive.content)
        }

    @Test
    fun guestSearchWithoutMatchIsNull() =
        runTest {
            assertNull(gateway { ok("""{"items":[]}""") }.guestId(CPF))
        }

    @Test
    fun createGuestSendsProfileWithBrazilianDefaults() =
        runTest {
            val gateway = gateway { ok(guestJson(G1), HttpStatusCode.Created) }
            val spec = GuestSpec("G1", "Fulano de Tal", LocalDate.of(1985, 3, 9), CPF, "11222333000181")
            assertEquals(G1, gateway.createGuest(spec, COMPANY))
            val sent = body(0)
            val profile = sent.getValue("profile").jsonObject
            assertEquals("Fulano de Tal", profile.getValue("fullName").jsonPrimitive.content)
            assertFalse(profile.getValue("vip").jsonPrimitive.boolean)
            assertEquals("1985-03-09", profile.getValue("birthDate").jsonPrimitive.content)
            assertEquals("BR", profile.getValue("nationality").jsonPrimitive.content)
            assertEquals("BR", profile.getValue("residenceCountry").jsonPrimitive.content)
            assertEquals(COMPANY, profile.getValue("companyId").jsonPrimitive.content)
            val document =
                sent
                    .getValue("documents")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("CPF", document.getValue("type").jsonPrimitive.content)
            assertEquals(CPF, document.getValue("number").jsonPrimitive.content)
            assertFalse(CPF in requests.single().url.toString())
        }

    @Test
    fun companyLookupSendsCnpjAndReadsAllPages() =
        runTest {
            fun company(
                id: String,
                cnpj: String,
            ) =
                """{"active":true,"cnpj":"$cnpj","createdAt":"$STAMP","id":"$id","legalName":"X","updatedAt":"$STAMP"}"""
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        ok("""{"items":[${company(G1, "99999999000199")}],"nextCursor":"p2"}""")
                    } else {
                        ok("""{"items":[${company(G2, "11222333000181")}]}""")
                    }
                }
            assertEquals(G2, gateway.companyId("11222333000181"))
            assertEquals("11222333000181", requests.first().url.parameters["cnpj"])
            assertEquals(2, requests.size)
        }

    @Test
    fun createCompanySendsLegalAndTradeName() =
        runTest {
            val gateway =
                gateway {
                    ok(
                        """{"active":true,"cnpj":"11222333000181","createdAt":"$STAMP","id":"$COMPANY",
                        |"legalName":"Razao","updatedAt":"$STAMP"}
                        """.trimMargin(),
                        HttpStatusCode.Created,
                    )
                }
            assertEquals(COMPANY, gateway.createCompany(CompanySpec("Razao", "Fantasia", "11222333000181")))
            val sent = body(0)
            assertEquals("Razao", sent.getValue("legalName").jsonPrimitive.content)
            assertEquals("Fantasia", sent.getValue("tradeName").jsonPrimitive.content)
            assertEquals("11222333000181", sent.getValue("cnpj").jsonPrimitive.content)
        }

    @Test
    fun inventoryMapsTotalsAndOutOfOrder() =
        runTest {
            fun day(
                date: String,
                total: Int,
                ooo: Int,
            ) = """{"available":0,"blocked":0,"date":"$date","outOfOrder":$ooo,"overbookingLimit":0,
                |"roomTypeId":"$TYPE","sold":0,"total":$total}
                """.trimMargin()
            val gateway = gateway { ok("""{"items":[${day("2026-11-12", 8, 1)},${day("2026-11-13", 8, 0)}]}""") }
            val days = gateway.inventory(TYPE, TODAY, TODAY.plusDays(1))
            assertEquals(listOf(TODAY, TODAY.plusDays(1)), days.map { it.date })
            assertEquals(listOf(8, 8), days.map { it.total })
            assertEquals(listOf(1, 0), days.map { it.outOfOrder })
            val url = requests.single().url
            assertEquals("2026-11-12", url.parameters["from"])
            assertEquals("2026-11-13", url.parameters["to"])
            assertEquals(TYPE, url.parameters["roomTypeId"])
        }

    @Test
    fun reservationLookupReadsAllPages() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        ok("""{"items":[${listItem(G1, "OUTRO")}],"nextCursor":"p2"}""")
                    } else {
                        ok("""{"items":[${listItem(RESERVATION, "DEMO-SEED-A1", roomId = ROOM)}]}""")
                    }
                }
            val found = gateway.findReservation("DEMO-SEED-A1")
            assertEquals(RESERVATION, found?.id)
            assertEquals("CONFIRMED", found?.status)
            assertEquals(ROOM, found?.roomId)
            assertEquals(2, requests.size)
            assertEquals("DEMO-SEED-A1", requests.first().url.parameters["externalId"])
        }

    @Test
    fun reservationLookupIgnoresItemsWithOtherExternalId() =
        runTest {
            val gateway = gateway { ok("""{"items":[${listItem(G1, "OUTRO")},${listItem(G2, null)}]}""") }
            assertNull(gateway.findReservation("DEMO-SEED-A1"))
        }

    @Test
    fun unknownStatusPassesThroughRaw() =
        runTest {
            val gateway =
                gateway { ok("""{"items":[${listItem(RESERVATION, "DEMO-SEED-A1", status = "ESTADO_NOVO")}]}""") }
            assertEquals("ESTADO_NOVO", gateway.findReservation("DEMO-SEED-A1")?.status)
        }

    @Test
    fun bookingCarriesExternalIdAndIdempotencyKey() =
        runTest {
            val gateway = gateway { ok(bookingJson("CONFIRMED"), HttpStatusCode.Created) }
            val ref = gateway.createReservation(SPEC, G1, TYPE, PLAN)
            assertEquals(RESERVATION, ref.id)
            assertEquals("CONFIRMED", ref.status)
            assertNull(ref.roomId)
            assertTrue(
                requests
                    .single()
                    .headers["Idempotency-Key"]
                    .orEmpty()
                    .isNotBlank(),
            )
            val sent = body(0)
            assertEquals("DEMO-SEED-A1", sent.getValue("externalId").jsonPrimitive.content)
            assertEquals("FRONT_DESK", sent.getValue("origin").jsonPrimitive.content)
            assertEquals(G1, sent.getValue("holderGuestId").jsonPrimitive.content)
            val reservation =
                sent
                    .getValue("reservations")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(2, reservation.getValue("adults").jsonPrimitive.int)
            assertEquals("2026-11-12", reservation.getValue("arrival").jsonPrimitive.content)
            assertEquals("2026-11-14", reservation.getValue("departure").jsonPrimitive.content)
            assertEquals(JsonArray(emptyList()), reservation.getValue("childAges"))
            assertEquals(JsonArray(emptyList()), reservation.getValue("nightlyRates"))
            assertEquals("LEISURE", reservation.getValue("segment").jsonPrimitive.content)
            assertEquals(TYPE, reservation.getValue("roomTypeId").jsonPrimitive.content)
            assertEquals(PLAN, reservation.getValue("ratePlanId").jsonPrimitive.content)
            val guest =
                reservation
                    .getValue("guests")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(G1, guest.getValue("guestId").jsonPrimitive.content)
            assertTrue(guest.getValue("primary").jsonPrimitive.boolean)
        }

    @Test
    fun eachCreateUsesNewIdempotencyKey() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/check-in")) {
                        ok(CHECK_IN_JSON)
                    } else {
                        ok(bookingJson("CONFIRMED"), HttpStatusCode.Created)
                    }
                }
            gateway.createReservation(SPEC, G1, TYPE, PLAN)
            gateway.createReservation(SPEC, G1, TYPE, PLAN)
            gateway.checkIn(RESERVATION, ROOM, listOf(G1))
            gateway.checkIn(RESERVATION, ROOM, listOf(G1))
            val keys = requests.map { it.headers["Idempotency-Key"] }
            assertEquals(4, keys.size)
            assertEquals(4, keys.toSet().size, "chave repetida entre chamadas")
            assertTrue(keys.all { !it.isNullOrBlank() })
        }

    @Test
    fun assignRoomSendsInitialReason() =
        runTest {
            val gateway = gateway { ok(detailJson("CONFIRMED")) }
            gateway.assignRoom(RESERVATION, ROOM)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains(RESERVATION),
            )
            assertEquals(ROOM, body(0).getValue("roomId").jsonPrimitive.content)
            assertEquals("INITIAL", body(0).getValue("reason").jsonPrimitive.content)
        }

    @Test
    fun checkInSendsIdempotencyKeyAndPrimaryFirst() =
        runTest {
            val gateway = gateway { ok(CHECK_IN_JSON) }
            gateway.checkIn(RESERVATION, ROOM, listOf(G1, G2))
            val request = requests.single()
            assertTrue(request.headers["Idempotency-Key"].orEmpty().isNotBlank())
            val sent = body(0)
            assertEquals(ROOM, sent.getValue("roomId").jsonPrimitive.content)
            val guests = sent.getValue("guests").jsonArray.map { it.jsonObject }
            assertEquals(listOf(G1, G2), guests.map { it.getValue("guestId").jsonPrimitive.content })
            assertEquals(listOf(true, false), guests.map { it.getValue("primary").jsonPrimitive.boolean })
        }

    @Test
    fun checkInGuestCountIsReservedAdultsWhenKnown() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/check-in")) {
                        ok(CHECK_IN_JSON)
                    } else {
                        ok("""{"items":[${listItem(RESERVATION, "DEMO-SEED-A1", adults = 3)}]}""")
                    }
                }
            gateway.findReservation("DEMO-SEED-A1")
            gateway.checkIn(RESERVATION, ROOM, listOf(G1))
            assertEquals(3, body(1).getValue("guestCount").jsonPrimitive.int)
        }

    @Test
    fun checkInGuestCountComesFromCreatedReservation() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/check-in")) {
                        ok(CHECK_IN_JSON)
                    } else {
                        ok(bookingJson("CONFIRMED"), HttpStatusCode.Created)
                    }
                }
            gateway.createReservation(SPEC, G1, TYPE, PLAN)
            gateway.checkIn(RESERVATION, ROOM, listOf(G1))
            assertEquals(SPEC.adults, body(1).getValue("guestCount").jsonPrimitive.int)
        }
}
