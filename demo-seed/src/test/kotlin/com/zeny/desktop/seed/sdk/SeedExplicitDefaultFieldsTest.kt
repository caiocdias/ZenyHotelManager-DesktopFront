package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.data.CashPointSpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.HousekeepingTarget
import com.zeny.desktop.seed.data.RatePlanSpec
import com.zeny.desktop.seed.data.ReservationSpec
import com.zeny.desktop.seed.data.RoomSpec
import com.zeny.desktop.seed.data.RoomTypeSpec
import com.zeny.desktop.seed.pending.PendingPlanSpec
import com.zeny.desktop.seed.pending.PendingPriceSpec
import com.zeny.desktop.seed.pending.PendingReservationSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HOLDER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val RESERVATION = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val POLICY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val SEASON = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"
private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val CODE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"

/**
 * ADR 0014 no seed: cada campo que saiu do `required` no contrato de `8c563cda0575` e que um gateway constrói vai no
 * corpo, com o valor de antes da troca, inclusive `false` e lista vazia. O servidor recusa (`422`); só o corpo enviado
 * importa.
 */
class SeedExplicitDefaultFieldsTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private suspend fun sentBody(call: suspend (SeedCore) -> Unit): JsonObject {
        requests.clear()
        SeedCore(
            SERVER_A,
            MockEngine { request ->
                requests += request
                respond(
                    """{"code":"VALIDATION_FAILED","detail":"recusado"}""",
                    HttpStatusCode.UnprocessableEntity,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).use { seed ->
            try {
                call(seed)
            } catch (_: ApiError) {
                // a recusa é o esperado: o teste olha só o corpo enviado
            }
        }
        val request = requests.single()
        return Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
    }

    private val empty = JsonArray(emptyList())

    private fun JsonObject.singleReservation(): JsonObject = getValue("reservations").jsonArray.single().jsonObject

    private fun JsonObject.primaries(): List<JsonElement?> =
        getValue("guests").jsonArray.map { it.jsonObject["primary"] }

    private fun reservationSpec() =
        ReservationSpec(
            slot = "A1",
            holderGuestKey = "h1",
            roomTypeCode = "STD",
            arrival = LocalDate.parse("2026-11-20"),
            departure = LocalDate.parse("2026-11-22"),
            adults = 2,
            roomNumber = null,
            checkIn = false,
        )

    private fun pendingReservationSpec() =
        PendingReservationSpec(
            externalId = "DEMO-SEED-P1",
            holderGuestId = HOLDER,
            roomTypeId = ROOM_TYPE,
            ratePlanId = PLAN,
            origin = "WEBSITE",
            arrival = LocalDate.parse("2026-11-25"),
            departure = LocalDate.parse("2026-11-27"),
            adults = 2,
        )

    @Test
    fun bookingGatewayCreateGuestSendsDocumentsAndVipFalse() =
        runTest {
            val spec = GuestSpec("h1", "Fulana Fictícia", LocalDate.parse("1990-01-02"), "529.982.247-25", null)
            val body = sentBody { SdkBookingGateway(it).createGuest(spec, null) }
            val documents = body.getValue("documents").jsonArray
            assertEquals(1, documents.size)
            assertEquals(JsonPrimitive("CPF"), documents.single().jsonObject["type"])
            assertEquals(JsonPrimitive(false), body.getValue("profile").jsonObject["vip"])
        }

    @Test
    fun bookingGatewayReservationSendsEmptyListsAndPrimaryHolder() =
        runTest {
            val body = sentBody { SdkBookingGateway(it).createReservation(reservationSpec(), HOLDER, ROOM_TYPE, PLAN) }
            val reservation = body.singleReservation()
            assertEquals(empty, reservation["childAges"])
            assertEquals(empty, reservation["nightlyRates"])
            val guests = reservation.getValue("guests").jsonArray
            assertEquals(JsonPrimitive(HOLDER), guests.single().jsonObject["guestId"])
            assertEquals(JsonPrimitive(true), guests.single().jsonObject["primary"])
        }

    @Test
    fun bookingGatewayCheckInSendsPrimaryOnlyForTheFirstGuest() =
        runTest {
            val body = sentBody { SdkBookingGateway(it).checkIn(RESERVATION, ROOM, listOf(HOLDER, GUEST_B)) }
            assertEquals(listOf(JsonPrimitive(true), JsonPrimitive(false)), body.primaries())
        }

    @Test
    fun configGatewayRoomTypeSendsDisplayOrderAndEmptyFeatures() =
        runTest {
            val spec = RoomTypeSpec("STD", "Standard", 3, 2, 0, 3, 1)
            val body = sentBody { SdkConfigGateway(it).createRoomType(spec) }
            assertEquals(JsonPrimitive(3), body["displayOrder"])
            assertEquals(empty, body["featureIds"])
        }

    @Test
    fun configGatewayRoomSendsEmptyFeatures() =
        runTest {
            val spec = RoomSpec("101", 1, "STD", HousekeepingTarget.CLEAN, false)
            val body = sentBody { SdkConfigGateway(it).createRoom(spec, ROOM_TYPE) }
            assertEquals(empty, body["featureIds"])
        }

    @Test
    fun configGatewayCashPointSendsBlindCloseTrueAndFalse() =
        runTest {
            for (blind in listOf(true, false)) {
                val body = sentBody { SdkConfigGateway(it).createCashPoint(CashPointSpec("Recepção", blind)) }
                assertEquals(JsonPrimitive(blind), body["blindClose"], "blindClose=$blind")
            }
        }

    @Test
    fun configGatewayRatePlanSendsEmptyPackages() =
        runTest {
            val spec = RatePlanSpec("BAR", "Balcão", "ROOM_ONLY", listOf("DIRECT"), listOf("STD"))
            val body = sentBody { SdkConfigGateway(it).createRatePlan(spec, listOf(ROOM_TYPE), POLICY, POLICY) }
            assertEquals(empty, body["packageIds"])
        }

    @Test
    fun configGatewayPricesSendEmptyWeekdays() =
        runTest {
            val body =
                sentBody {
                    SdkConfigGateway(it).updatePrices(
                        PLAN,
                        ROOM_TYPE,
                        SEASON,
                        BigDecimal("100.00"),
                        BigDecimal("150.00"),
                        BigDecimal("50.00"),
                    )
                }
            assertEquals(empty, body["weekdays"])
        }

    @Test
    fun pendingGatewayRatePlanSendsEmptyPackages() =
        runTest {
            val spec = PendingPlanSpec("SITE", "Site", POLICY, POLICY, ROOM_TYPE)
            val body = sentBody { SdkPendingGateway(it).createWebsiteRatePlan(spec) }
            assertEquals(empty, body["packageIds"])
        }

    @Test
    fun pendingGatewayPricesSendEmptyWeekdays() =
        runTest {
            val prices =
                PendingPriceSpec(
                    ROOM_TYPE,
                    BigDecimal("100.00"),
                    BigDecimal("150.00"),
                    LocalDate.parse("2026-11-25"),
                    LocalDate.parse("2026-11-30"),
                )
            val body = sentBody { SdkPendingGateway(it).writePrices(PLAN, prices) }
            assertEquals(empty, body["weekdays"])
        }

    @Test
    fun pendingGatewayReservationSendsEmptyListsAndPrimaryHolder() =
        runTest {
            val body = sentBody { SdkPendingGateway(it).createReservation(pendingReservationSpec()) }
            val reservation = body.singleReservation()
            assertEquals(empty, reservation["childAges"])
            assertEquals(empty, reservation["nightlyRates"])
            val guests = reservation.getValue("guests").jsonArray
            assertEquals(JsonPrimitive(HOLDER), guests.single().jsonObject["guestId"])
            assertEquals(JsonPrimitive(true), guests.single().jsonObject["primary"])
        }

    @Test
    fun pendingGatewayCancelSendsWaivePenaltyFalse() =
        runTest {
            val body = sentBody { SdkPendingGateway(it).cancelReservation(RESERVATION, "GUEST_REQUEST") }
            assertEquals(JsonPrimitive(false), body["waivePenalty"])
        }

    @Test
    fun folioGatewayPostingSendsQuantityOne() =
        runTest {
            val body = sentBody { SdkFolioGateway(it).post(FOLIO, CODE, "32.00", "Seed M4: Frigobar") }
            assertEquals(JsonPrimitive(1.0), body["quantity"])
            assertEquals("1.0", body["quantity"].toString())
        }
}
