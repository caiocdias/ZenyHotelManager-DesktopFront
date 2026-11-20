package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

private const val GUEST_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val CODE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val CPF = "529.982.247-25"

/**
 * ADR 0014: cada campo que saiu do `required` no contrato de `8c563cda0575` e que a fachada constrói vai no corpo, com
 * o valor de antes da troca, inclusive `false` e lista vazia. O servidor recusa (`422`); só o corpo enviado importa.
 */
class ExplicitDefaultFieldsTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private suspend fun sentBody(call: suspend (ZenyCore) -> Unit): JsonObject {
        requests.clear()
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                problem(HttpStatusCode.UnprocessableEntity, "VALIDATION_FAILED")
            },
        ).use { core ->
            try {
                call(core)
            } catch (_: ApiError.Problem) {
                // a recusa é o esperado: o teste olha só o corpo enviado
            }
        }
        val request = requests.single()
        return Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
    }

    private fun ints(vararg values: Int) = JsonArray(values.map { JsonPrimitive(it) })

    private fun JsonObject.field(name: String): JsonElement? = get(name)

    private fun JsonObject.singleReservation(): JsonObject = getValue("reservations").jsonArray.single().jsonObject

    private fun checkInGuests() = listOf(CheckInGuest(GUEST_A, true), CheckInGuest(GUEST_B, false))

    private fun JsonObject.primaries(): List<JsonElement?> =
        getValue("guests").jsonArray.map { it.jsonObject["primary"] }

    @Test
    fun createGuestSendsDocumentsAndVipFalse() =
        runTest {
            val body =
                sentBody {
                    it.guests.create(
                        NewGuest("Fulana Fictícia", "CPF", CPF, null, "BR", BusinessDate.parse("1990-01-02")),
                    )
                }
            val documents = body.field("documents")!!.jsonArray
            assertEquals(1, documents.size)
            assertEquals(JsonPrimitive("CPF"), documents.single().jsonObject["type"])
            assertEquals(JsonPrimitive(false), body.getValue("profile").jsonObject["vip"])
        }

    @Test
    fun checkInSendsPrimaryOfEveryGuest() =
        runTest {
            val body =
                sentBody {
                    it.reservations.checkIn(RES, CheckInInput(checkInGuests(), null, null, null), Intent()).run(null)
                }
            assertEquals(listOf(JsonPrimitive(true), JsonPrimitive(false)), body.primaries())
        }

    private fun walkIn(childAges: List<Int>) =
        WalkInInput(
            departure = BusinessDate.parse("2026-11-15"),
            adults = 2,
            childAges = childAges,
            ratePlanId = PLAN,
            roomTypeId = null,
            roomId = ROOM,
            segment = "LEISURE",
            guests = checkInGuests(),
            guestCount = null,
        )

    @Test
    fun walkInSendsChildAgesAndPrimary() =
        runTest {
            val body = sentBody { it.reservations.walkIn(walkIn(listOf(5, 8)), Intent()).run(null) }
            assertEquals(ints(5, 8), body.field("childAges"))
            assertEquals(listOf(JsonPrimitive(true), JsonPrimitive(false)), body.primaries())
        }

    @Test
    fun walkInWithoutChildrenSendsEmptyChildAges() =
        runTest {
            val body = sentBody { it.reservations.walkIn(walkIn(emptyList()), Intent()).run(null) }
            assertEquals(ints(), body.field("childAges"))
        }

    @Test
    fun documentSearchSendsStatuses() =
        runTest {
            val query = DocumentQuery("CPF", CPF, null)
            val body = sentBody { it.reservations.searchByDocument(query, setOf("IN_HOUSE", "CONFIRMED"), null, 20) }
            val expected = JsonArray(listOf(JsonPrimitive("CONFIRMED"), JsonPrimitive("IN_HOUSE")))
            assertEquals(expected, body.field("statuses"))
        }

    @Test
    fun documentSearchWithoutStatusesSendsEmptyList() =
        runTest {
            val query = DocumentQuery("CPF", CPF, null)
            val body = sentBody { it.reservations.searchByDocument(query, emptySet(), null, 20) }
            assertEquals(JsonArray(emptyList()), body.field("statuses"))
        }

    private fun booking(
        childAges: List<Int>,
        nightlyRates: List<NightlyRateInput>,
    ) = BookingInput(
        holderGuestId = GUEST_A,
        origin = "FRONT_DESK",
        arrival = BusinessDate.parse("2026-12-10"),
        departure = BusinessDate.parse("2026-12-12"),
        adults = 2,
        childAges = childAges,
        roomTypeId = TYPE,
        ratePlanId = PLAN,
        segment = "LEISURE",
        specialRequests = null,
        nightlyRates = nightlyRates,
    )

    @Test
    fun newReservationSendsChildAgesEmptyGuestsAndNightlyRates() =
        runTest {
            val rates =
                listOf(
                    NightlyRateInput(BusinessDate.parse("2026-12-10"), Money.parse("180.50")),
                    NightlyRateInput(BusinessDate.parse("2026-12-11"), Money.parse("180.50")),
                )
            val body = sentBody { it.bookings.createBooking(booking(listOf(5, 8), rates), Intent()).run(null) }
            val reservation = body.singleReservation()
            assertEquals(ints(5, 8), reservation.field("childAges"))
            assertEquals(JsonArray(emptyList()), reservation.field("guests"))
            val expectedRates =
                JsonArray(
                    listOf("2026-12-10", "2026-12-11").map { date ->
                        JsonObject(mapOf("date" to JsonPrimitive(date), "roomAmount" to JsonPrimitive("180.50")))
                    },
                )
            assertEquals(expectedRates, reservation.field("nightlyRates"))
        }

    @Test
    fun newReservationWithoutChildrenNorRatesSendsEmptyLists() =
        runTest {
            val body = sentBody { it.bookings.createBooking(booking(emptyList(), emptyList()), Intent()).run(null) }
            val reservation = body.singleReservation()
            assertEquals(ints(), reservation.field("childAges"))
            assertEquals(JsonArray(emptyList()), reservation.field("guests"))
            assertEquals(JsonArray(emptyList()), reservation.field("nightlyRates"))
        }

    @Test
    fun cancelSendsWaivePenaltyTrueAndFalse() =
        runTest {
            for (waive in listOf(true, false)) {
                val body =
                    sentBody {
                        val input = CancelInput("GUEST_REQUEST", waive, null)
                        it.bookings.cancelReservation(RES, input, Intent()).run(null)
                    }
                assertEquals(JsonPrimitive(waive), body.field("waivePenalty"), "waivePenalty=$waive")
            }
        }

    @Test
    fun postingSendsQuantityAsDecimalNumber() =
        runTest {
            val body =
                sentBody { it.folios.post(FOLIO, PostingInput(CODE, 3, Money.parse("12.50"), null), Intent()) }
            assertEquals(JsonPrimitive(3.0), body.field("quantity"))
            assertEquals("3.0", body.field("quantity").toString())
        }

    @Test
    fun paymentSendsDepositTrueAndFalse() =
        runTest {
            for (deposit in listOf(true, false)) {
                val input = PaymentInput(PaymentMethod.CASH, Money.parse("80.00"), deposit = deposit)
                val body = sentBody { it.payments.receive(FOLIO, input, Intent()) }
                assertEquals(JsonPrimitive(deposit), body.field("deposit"), "deposit=$deposit")
            }
        }
}
