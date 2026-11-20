package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ErrorCatalog
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val CODE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val CPF = "529.982.247-25"

/**
 * Ataque da M0 E04 (testador): o que o `ExplicitDefaultFieldsTest` não amarra. A alteração de reserva fica fora do
 * ADR 0014 e não ganha campo; o documento do hóspede vai com o valor da tela, não só com o tipo; e o
 * `409 CONCURRENT_UPDATE` chega ao usuário pela mensagem do catálogo numa única tentativa, sem repetição automática.
 */
class SyncE04AttackTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(
        status: HttpStatusCode,
        code: String,
    ) = ZenyCore(
        checkNotNull(ServerUrl.parse("http://servidor.teste")),
        MemorySettings(),
        MockEngine { request ->
            requests += request
            problem(status, code)
        },
    )

    private suspend fun sentBody(call: suspend (ZenyCore) -> Unit): JsonObject {
        requests.clear()
        core(HttpStatusCode.UnprocessableEntity, "VALIDATION_FAILED").use { core ->
            try {
                call(core)
            } catch (_: ApiError.Problem) {
                // a recusa é o esperado: o teste olha só o corpo enviado
            }
        }
        val raw = requests.single().body.toByteArray()
        return Json.parseToJsonElement(raw.decodeToString()).jsonObject
    }

    private fun JsonObject.singleDocument(): JsonObject = getValue("documents").jsonArray.single().jsonObject

    private fun change(
        childAges: List<Int>,
        nightlyRates: List<NightlyRateInput>,
    ) = ReservationChangeInput(
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
    fun reservationChangeKeepsEmptyChildAgesAndNightlyRatesAndGainsNoGuestsField() =
        runTest {
            val input = change(emptyList(), emptyList())
            val body = sentBody { it.bookings.changeReservation(RES, input, "\"3\"").run(null) }

            assertEquals(JsonArray(emptyList()), body["childAges"], "childAges vazio sai []")
            assertEquals(JsonArray(emptyList()), body["nightlyRates"], "nightlyRates vazio sai []")
            assertNull(body["guests"], "a alteração não ganha guests: ReservationChangeRequest está fora do ADR 0014")
            assertEquals(requests.single().method, HttpMethod.Put)
        }

    @Test
    fun reservationChangeSendsTheChildAgesAndRatesTheScreenBuilt() =
        runTest {
            val rates = listOf(NightlyRateInput(BusinessDate.parse("2026-12-11"), Money.parse("99.90")))
            val input = change(listOf(3, 11), rates)
            val body = sentBody { it.bookings.changeReservation(RES, input, "\"3\"").run(null) }

            assertEquals(JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(11))), body["childAges"])
            val expected =
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf("date" to JsonPrimitive("2026-12-11"), "roomAmount" to JsonPrimitive("99.90")),
                        ),
                    ),
                )
            assertEquals(expected, body["nightlyRates"])
        }

    @Test
    fun createGuestSendsTheDocumentNumberAndIssuingCountryTheScreenTyped() =
        runTest {
            val birth = BusinessDate.parse("1990-01-02")
            val passport = NewGuest("Fulana Fictícia", "PASSPORT", "X1234567", "AR", "AR", birth)
            val withCountry = sentBody { it.guests.create(passport) }.singleDocument()
            assertEquals(JsonPrimitive("PASSPORT"), withCountry["type"])
            assertEquals(JsonPrimitive("X1234567"), withCountry["number"])
            assertEquals(JsonPrimitive("AR"), withCountry["issuingCountry"])

            val cpf = NewGuest("Fulana Fictícia", "CPF", CPF, null, "BR", birth)
            val withoutCountry = sentBody { it.guests.create(cpf) }.singleDocument()
            assertEquals(JsonPrimitive(CPF), withoutCountry["number"])
            assertNull(withoutCountry["issuingCountry"], "país emissor sem valor fica fora do corpo (Option.Undefined)")
        }

    @Test
    fun postingQuantityKeepsTheIntegerTheScreenTypedForLargeValues() =
        runTest {
            val input = PostingInput(CODE, 1_000, Money.parse("0.01"), null)
            val body = sentBody { it.folios.post(FOLIO, input, Intent()) }

            assertEquals("1000.0", body["quantity"].toString())
            assertEquals(JsonPrimitive("0.01"), body["unitPrice"], "o preço unitário não muda de forma")
        }

    @Test
    fun concurrentUpdateOnAWriteIsOneRequestWithTheCatalogMessageAndDefinitive() =
        runTest {
            requests.clear()
            val error =
                core(HttpStatusCode.Conflict, "CONCURRENT_UPDATE").use { core ->
                    assertFailsWith<ApiError.Problem> {
                        val input = PaymentInput(PaymentMethod.CASH, Money.parse("80.00"))
                        core.payments.receive(FOLIO, input, Intent())
                    }
                }

            assertEquals(1, requests.size, "nenhuma repetição automática no 409 CONCURRENT_UPDATE")
            assertEquals("CONCURRENT_UPDATE", error.code)
            val message = ErrorCatalog.message("CONCURRENT_UPDATE")
            assertEquals(message, error.userMessage, "mensagem do catálogo, não o detail")
            assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(error))
        }

    @Test
    fun concurrentUpdateOnAnIdempotentWriteIsNotRetriedWithTheSameKey() =
        runTest {
            requests.clear()
            core(HttpStatusCode.Conflict, "CONCURRENT_UPDATE").use { core ->
                val input = CancelInput("GUEST_REQUEST", false, null)
                assertFailsWith<ApiError.Problem> { core.bookings.cancelReservation(RES, input, Intent()).run(null) }
            }

            assertEquals(1, requests.size, "cancelamento com Idempotency-Key também não repete sozinho")
        }
}
