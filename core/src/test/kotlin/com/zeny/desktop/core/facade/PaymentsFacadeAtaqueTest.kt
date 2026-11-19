package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PAY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f11"
private const val PAY_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f12"
private const val SHIFT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f13"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f14"

/** Números de cartão de teste, com espaços e traços, como uma operadora colaria no NSU ou na autorização. */
private val CARD_LIKE =
    listOf("4111 1111 1111 1111", "4111-1111-1111-1111", "4111111111111111", " 5555555555554444 ", "4111 1111 1111 1")

/** Ataque da etapa à `PaymentsFacade`: dado de cartão, bordas do valor e a aprovação perdida no reembolso. */
class PaymentsFacadeAtaqueTest {
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

    private fun payment(
        id: String = PAY,
        type: String = "PAYMENT",
        amount: String = "12.50",
    ) = """{"amount":"$amount","businessDate":"2026-11-18","cashierShiftId":"$SHIFT","deposit":false,
        "folioId":"$FOLIO","id":"$id","method":"CASH","receivedAt":"2026-11-18T10:00:00Z","receivedBy":"$USER",
        "type":"$type"}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"billing.payments.refund","permission":"p",
        "resourceRef":"r"}"""

    private fun refundOf(
        nsu: String? = null,
        authorizationCode: String? = null,
    ) = RefundInput(Money.parse("5.00"), "cobrado em duplicidade", nsu, authorizationCode)

    @Test
    fun cardNumbersWithSpacesAndDashesNeverReachTheNetworkNorTheMessage() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                val failures = mutableListOf<IllegalArgumentException>()
                CARD_LIKE.forEach { code ->
                    PaymentMethod.entries.filter { it != PaymentMethod.CASH }.forEach { method ->
                        failures +=
                            assertFailsWith<IllegalArgumentException>("NSU $method") {
                                core.payments.receive(
                                    FOLIO,
                                    PaymentInput(method, Money.parse("1.00"), nsu = code),
                                    Intent(),
                                )
                            }
                        failures +=
                            assertFailsWith<IllegalArgumentException>("autorização $method") {
                                core.payments.receive(
                                    FOLIO,
                                    PaymentInput(method, Money.parse("1.00"), authorizationCode = code),
                                    Intent(),
                                )
                            }
                    }
                    failures +=
                        assertFailsWith<IllegalArgumentException>("últimos dígitos") {
                            core.payments.receive(
                                FOLIO,
                                PaymentInput(PaymentMethod.CREDIT_CARD, Money.parse("1.00"), cardLast4 = code),
                                Intent(),
                            )
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.refund(
                                FOLIO,
                                PAY,
                                refundOf(nsu = code),
                                Intent(),
                            )
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.refund(FOLIO, PAY, refundOf(authorizationCode = code), Intent())
                        }
                }
                val digits = CARD_LIKE.map { it.filter(Char::isDigit) }
                failures.forEach { failure ->
                    val text = failure.message.orEmpty() + failure.toString()
                    CARD_LIKE.forEach { assertFalse(it.trim() in text, text) }
                    digits.forEach { assertFalse(it.take(12) in text.filter(Char::isDigit), text) }
                }
                assertTrue(requests.isEmpty(), "nenhum número de cartão chega à rede")
            }
        }

    @Test
    fun cardLikeValuesNeverShowInTheInputsToString() {
        CARD_LIKE.forEach { code ->
            val input =
                PaymentInput(
                    PaymentMethod.DEBIT_CARD,
                    Money.parse("1.00"),
                    cardBrand = code,
                    cardLast4 = code,
                    nsu = code,
                    authorizationCode = code,
                )
            val text = input.toString() + refundOf(nsu = code, authorizationCode = code).toString()
            assertFalse(code.trim() in text, text)
            assertFalse(code.filter(Char::isDigit).take(12) in text.filter(Char::isDigit), text)
        }
    }

    @Test
    fun amountEdgesGoAsExactDecimalsOrNeverLeave() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                listOf("0.01", "1000000.00", "999999999.99").forEach { amount ->
                    core.payments.receive(
                        FOLIO,
                        PaymentInput(PaymentMethod.CASH, Money.parse(amount), tendered = Money.parse(amount)),
                        Intent(),
                    )
                }
                val sent =
                    requests.map {
                        val body = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                        body.getValue("amount").jsonPrimitive.content to body.getValue("tendered").jsonPrimitive.content
                    }
                assertEquals(
                    listOf("0.01" to "0.01", "1000000.00" to "1000000.00", "999999999.99" to "999999999.99"),
                    sent,
                )
                requests.clear()
                listOf("0.00", "-0.01", "-1000000.00").forEach { amount ->
                    assertFailsWith<IllegalArgumentException>(amount) {
                        core.payments.receive(FOLIO, PaymentInput(PaymentMethod.PIX, Money.parse(amount)), Intent())
                    }
                    assertFailsWith<IllegalArgumentException>(amount) {
                        core.payments.refund(FOLIO, PAY, RefundInput(Money.parse(amount), "motivo"), Intent())
                    }
                }
                assertFailsWith<IllegalArgumentException>("entregue menor por um centavo") {
                    core.payments.receive(
                        FOLIO,
                        PaymentInput(PaymentMethod.CASH, Money.parse("10.00"), tendered = Money.parse("9.99")),
                        Intent(),
                    )
                }
                assertTrue(requests.isEmpty(), "valor fora da regra não vai à rede")
            }
        }

    @Test
    fun moreThanTwoDecimalsIsNeverRounded() {
        listOf("12.345", "0.001", "1000000.005", "1,50", "1.000,00").forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { Money.parse(text) }
        }
    }

    /**
     * Aprovação perdida: o Gerente aprovou, a repetição com o token chegou ao servidor e a resposta se perdeu. O
     * reenvio da mesma intenção (uma ação nova, montada como o `RefundState` monta) leva a mesma chave da tentativa
     * aprovada e o token, para o servidor devolver o reembolso guardado em vez de fazer outro.
     */
    @Test
    fun lostApprovedRefundRetriesWithTheApprovedKeyAndToken() =
        runTest {
            var lose = true
            core { request ->
                when {
                    request.headers["X-Approval-Token"] == null -> {
                        json(approvalProblem, HttpStatusCode.Forbidden)
                    }

                    lose -> {
                        lose = false
                        throw IOException("resposta perdida")
                    }

                    else -> {
                        json(payment(PAY_2, "REFUND"), HttpStatusCode.Created)
                    }
                }
            }.use { core ->
                val intent = Intent()
                val first = core.payments.refund(FOLIO, PAY, refundOf(), intent)
                assertFailsWith<ApiError.ApprovalRequired> { first.run(null) }
                val lost = runCatching { first.run("tok-gerente") }.exceptionOrNull()
                assertNotNull(lost, "a resposta aprovada se perdeu")
                assertTrue(lost is ApiError, "a perda vira ApiError: $lost")

                val again = core.payments.refund(FOLIO, PAY, refundOf(), intent)
                val done = again.run(null)

                assertEquals(PAY_2, done.value.id.toString())
                assertEquals(3, requests.size)
                val approvedKey = requests[1].headers["Idempotency-Key"]
                assertNotEquals(requests[0].headers["Idempotency-Key"], approvedKey)
                assertEquals(
                    approvedKey,
                    requests[2].headers["Idempotency-Key"],
                    "o reenvio reaproveita a chave aprovada",
                )
                assertEquals("tok-gerente", requests[2].headers["X-Approval-Token"], "o reenvio leva o token")
            }
        }

    @Test
    fun receiveRepeatedAfterNetworkFailureKeepsTheKey() =
        runTest {
            var lose = true
            core {
                if (lose) {
                    lose = false
                    throw IOException("resposta perdida")
                }
                json(payment(), HttpStatusCode.Created)
            }.use { core ->
                val intent = Intent()
                val input = PaymentInput(PaymentMethod.PIX, Money.parse("12.50"))
                assertTrue(runCatching { core.payments.receive(FOLIO, input, intent) }.isFailure)
                core.payments.receive(FOLIO, input, intent)
                assertEquals(2, requests.size)
                val keys = requests.map { assertNotNull(it.headers["Idempotency-Key"]) }
                assertEquals(keys[0], keys[1])
            }
        }
}
