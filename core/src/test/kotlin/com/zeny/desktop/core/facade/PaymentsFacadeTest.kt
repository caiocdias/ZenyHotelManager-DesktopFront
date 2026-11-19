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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PAY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f11"
private const val PAY_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f12"
private const val SHIFT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f13"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f14"
private const val PRE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f21"
private const val PRE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f22"
private const val SECRET_ID = "id-secreto-que-nao-e-uuid"
private const val NSU_13 = "1234567890123"
private const val AUTH_19 = "1234567890123456789"

/** Os pagamentos da `PaymentsFacade`, contra o `MockEngine`. */
class PaymentsFacadeTest {
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
    ) = """{"amount":"12.50","businessDate":"2026-11-18","cashierShiftId":"$SHIFT","deposit":false,
        "folioId":"$FOLIO","id":"$id","method":"CASH","receivedAt":"2026-11-18T10:00:00Z","receivedBy":"$USER",
        "type":"$type"}"""

    private fun page(
        items: String,
        next: String? = null,
    ): String {
        val cursor = if (next == null) "" else ""","nextCursor":"$next""""
        return """{"items":[$items]$cursor}"""
    }

    private fun preAuthorization(id: String = PRE) =
        """{"amount":"300.00","authorizationCode":"AUT123","businessDate":"2026-11-18","capturableAmount":"300.00",
        "capturePaymentIds":[],"capturedAmount":"0.00","expiresAt":"2026-11-20T02:59:59Z","folioId":"$FOLIO",
        "id":"$id","registeredAt":"2026-11-18T10:00:00Z","registeredBy":"$USER","status":"ACTIVE"}"""

    private fun register(
        authorizationCode: String = "AUT123",
        nsu: String? = null,
        cardBrand: String? = null,
        cardLast4: String? = null,
        amount: String = "300.00",
    ) = PreAuthorizationInput(
        Money.parse(amount),
        Instant.parse("2026-11-20T02:59:59Z"),
        authorizationCode,
        nsu,
        cardBrand,
        cardLast4,
    )

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"billing.payments.refund","permission":"p",
        "resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun cash(
        amount: String = "12.50",
        tendered: String? = "20.00",
    ) = PaymentInput(PaymentMethod.CASH, Money.parse(amount), tendered = tendered?.let { Money.parse(it) })

    private fun refund(
        reason: String = "cobrado em duplicidade",
        nsu: String? = null,
        authorizationCode: String? = null,
    ) = RefundInput(Money.parse("5.00"), reason, nsu, authorizationCode)

    @Test
    fun paymentsReadAllPages() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json(page(payment(PAY), "next-2"))
                } else {
                    json(page(payment(PAY_2)))
                }
            }.use { core ->
                val all = core.payments.payments(FOLIO)
                assertEquals(listOf(PAY, PAY_2), all.map { it.id.toString() })
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.url.encodedPath.endsWith("/folios/$FOLIO/payments") })
                assertEquals("200", requests[0].url.parameters["limit"])
                assertEquals("next-2", requests[1].url.parameters["cursor"])
            }
        }

    @Test
    fun receiveSendsExactDecimalsAndIdempotencyKey() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                core.payments.receive(FOLIO, cash(), Intent())
                val body = bodyOf(requests.single())
                assertEquals("12.50", body.getValue("amount").jsonPrimitive.content)
                assertTrue(body.getValue("amount").jsonPrimitive.isString)
                assertEquals("CASH", body.getValue("method").jsonPrimitive.content)
                assertEquals("false", body.getValue("deposit").jsonPrimitive.content)
                assertEquals("20.00", body.getValue("tendered").jsonPrimitive.content)
                listOf("cardBrand", "cardLast4", "nsu", "authorizationCode").forEach { assertNull(body[it]) }
                assertTrue(requests.single().headers["Idempotency-Key"]?.isNotBlank() == true)
            }
        }

    @Test
    fun receiveCardSendsOnlyBrandLast4NsuAndAuthorization() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                val input =
                    PaymentInput(
                        PaymentMethod.CREDIT_CARD,
                        Money.parse("80.00"),
                        cardBrand = "Visa",
                        cardLast4 = "1234",
                        nsu = "ABC123",
                        authorizationCode = "AUT456",
                    )
                core.payments.receive(FOLIO, input, Intent())
                val body = bodyOf(requests.single())
                assertEquals("CREDIT_CARD", body.getValue("method").jsonPrimitive.content)
                assertEquals("Visa", body.getValue("cardBrand").jsonPrimitive.content)
                assertEquals("1234", body.getValue("cardLast4").jsonPrimitive.content)
                assertEquals("ABC123", body.getValue("nsu").jsonPrimitive.content)
                assertEquals("AUT456", body.getValue("authorizationCode").jsonPrimitive.content)
                assertNull(body["tendered"])
                assertEquals(
                    setOf("amount", "method", "deposit", "cardBrand", "cardLast4", "nsu", "authorizationCode"),
                    body.keys,
                )
            }
        }

    @Test
    fun blankOptionalTextIsNotSent() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                val input =
                    PaymentInput(
                        PaymentMethod.DEBIT_CARD,
                        Money.parse("1.00"),
                        cardBrand = "  ",
                        cardLast4 = "",
                        nsu = " ",
                    )
                core.payments.receive(FOLIO, input, Intent())
                val body = bodyOf(requests.single())
                assertEquals(setOf("amount", "method", "deposit"), body.keys)
            }
        }

    @Test
    fun sameIntentKeepsTheKey() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                val intent = Intent()
                core.payments.receive(FOLIO, cash(), intent)
                core.payments.receive(FOLIO, cash(), intent)
                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun refundAsksApprovalThenRetriesWithTokenAndNewKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(payment(PAY_2, "REFUND"), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.payments.refund(FOLIO, PAY, refund(), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                val done = action.run("tok-gerente")
                assertEquals(PAY_2, done.value.id.toString())
                assertEquals(2, requests.size)
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals("tok-gerente", requests[1].headers["X-Approval-Token"])
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                val body = bodyOf(requests[1])
                assertEquals("5.00", body.getValue("amount").jsonPrimitive.content)
                assertEquals("cobrado em duplicidade", body.getValue("reason").jsonPrimitive.content)
                assertTrue(requests[1].url.encodedPath.endsWith("/folios/$FOLIO/payments/$PAY/refund"))
            }
        }

    @Test
    fun cardLikeCodesNeverReachTheNetwork() =
        runTest {
            core { json(payment(), HttpStatusCode.Created) }.use { core ->
                val failures = mutableListOf<IllegalArgumentException>()
                listOf(NSU_13, AUTH_19).forEach { code ->
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.receive(
                                FOLIO,
                                PaymentInput(PaymentMethod.CREDIT_CARD, Money.parse("1.00"), nsu = code),
                                Intent(),
                            )
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.receive(
                                FOLIO,
                                PaymentInput(PaymentMethod.CREDIT_CARD, Money.parse("1.00"), authorizationCode = code),
                                Intent(),
                            )
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.refund(FOLIO, PAY, refund(nsu = code), Intent())
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.refund(FOLIO, PAY, refund(authorizationCode = code), Intent())
                        }
                }
                failures.forEach {
                    assertFalse(NSU_13 in it.message.orEmpty())
                    assertFalse(AUTH_19 in it.message.orEmpty())
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun problemsFollowTheContract() {
        fun card(
            method: PaymentMethod = PaymentMethod.CREDIT_CARD,
            amount: String = "1.00",
            brand: String? = null,
            last4: String? = null,
            nsu: String? = null,
            auth: String? = null,
        ) = PaymentInput(
            method,
            Money.parse(amount),
            cardBrand = brand,
            cardLast4 = last4,
            nsu = nsu,
            authorizationCode = auth,
        )

        val digits12 = "123456789012"
        val table: List<Pair<Set<PaymentProblem>, Set<PaymentProblem>>> =
            listOf(
                card(amount = "0.00").problems() to setOf(PaymentProblem.AMOUNT),
                card(amount = "-1.00").problems() to setOf(PaymentProblem.AMOUNT),
                cash(tendered = "0.00").problems() to setOf(PaymentProblem.TENDERED),
                cash(tendered = "12.49").problems() to setOf(PaymentProblem.TENDERED),
                cash(tendered = "12.50").problems() to emptySet(),
                cash(tendered = null).problems() to emptySet(),
                PaymentInput(PaymentMethod.PIX, Money.parse("1.00"), tendered = Money.parse("2.00")).problems() to
                    setOf(PaymentProblem.TENDERED),
                card(brand = "Visa 3").problems() to setOf(PaymentProblem.CARD_BRAND),
                card(brand = "A".repeat(21)).problems() to setOf(PaymentProblem.CARD_BRAND),
                card(brand = "Mastercard Elo").problems() to emptySet(),
                card(last4 = "123").problems() to setOf(PaymentProblem.CARD_LAST4),
                card(last4 = "12345").problems() to setOf(PaymentProblem.CARD_LAST4),
                card(last4 = "1234").problems() to emptySet(),
                card(PaymentMethod.PIX, brand = "Visa").problems() to setOf(PaymentProblem.CARD_BRAND),
                card(PaymentMethod.PIX, last4 = "1234").problems() to setOf(PaymentProblem.CARD_LAST4),
                card(PaymentMethod.CASH, nsu = "ABC1").problems() to setOf(PaymentProblem.NSU),
                card(PaymentMethod.CASH, auth = "ABC1").problems() to setOf(PaymentProblem.AUTHORIZATION_CODE),
                card(nsu = "A".repeat(21)).problems() to setOf(PaymentProblem.NSU),
                card(auth = "A".repeat(21)).problems() to setOf(PaymentProblem.AUTHORIZATION_CODE),
                card(nsu = digits12, auth = digits12).problems() to emptySet(),
                card(nsu = NSU_13).problems() to setOf(PaymentProblem.NSU),
                card(auth = "ABC-12").problems() to setOf(PaymentProblem.AUTHORIZATION_CODE),
                card(brand = " ", last4 = " ", nsu = " ", auth = " ").problems() to emptySet(),
                card(PaymentMethod.CASH, brand = " ", nsu = " ").problems() to emptySet(),
                refund().problems() to emptySet(),
                RefundInput(Money.parse("0.00"), "x").problems() to setOf(PaymentProblem.AMOUNT),
                refund(reason = "").problems() to setOf(PaymentProblem.REASON),
                refund(reason = "   ").problems() to setOf(PaymentProblem.REASON),
                refund(reason = "a".repeat(201)).problems() to setOf(PaymentProblem.REASON),
                refund(reason = "a".repeat(200)).problems() to emptySet(),
                refund(nsu = NSU_13).problems() to setOf(PaymentProblem.NSU),
                refund(authorizationCode = AUTH_19).problems() to setOf(PaymentProblem.AUTHORIZATION_CODE),
                refund(nsu = " ", authorizationCode = " ").problems() to emptySet(),
                refund(nsu = digits12, authorizationCode = "ABC123").problems() to emptySet(),
            )
        table.forEachIndexed { index, (actual, expected) -> assertEquals(expected, actual, "linha $index") }
    }

    @Test
    fun reasonLimitCountsTheTrimmedText() {
        assertEquals(emptySet(), refund(reason = "  ${"a".repeat(200)}  ").problems(), "o servidor recebe 200")
        assertEquals(setOf(PaymentProblem.REASON), refund(reason = "  ${"a".repeat(201)}  ").problems())
    }

    @Test
    fun toStringHidesCardData() {
        val input =
            PaymentInput(
                PaymentMethod.CREDIT_CARD,
                Money.parse("1.00"),
                cardBrand = "MarcaSecreta",
                cardLast4 = "9876",
                nsu = "NSUSECRETO",
                authorizationCode = "AUTSECRETA",
            )
        val refund = RefundInput(Money.parse("1.00"), "motivo", nsu = "NSUSECRETO", authorizationCode = "AUTSECRETA")
        listOf(input.toString(), refund.toString()).forEach { text ->
            listOf("MarcaSecreta", "9876", "NSUSECRETO", "AUTSECRETA").forEach { assertFalse(it in text, text) }
        }
    }

    @Test
    fun nonUuidIdFailsBeforeTheNetwork() =
        runTest {
            core { json(payment()) }.use { core ->
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { core.payments.payments(SECRET_ID) },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.receive(
                                SECRET_ID,
                                cash(),
                                Intent(),
                            )
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.refund(SECRET_ID, PAY, refund(), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.refund(FOLIO, SECRET_ID, refund(), Intent())
                        },
                    )
                failures.forEach { assertTrue(SECRET_ID !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun preAuthorizationsReadAllPages() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json(page(preAuthorization(PRE), "next-2"))
                } else {
                    json(page(preAuthorization(PRE_2)))
                }
            }.use { core ->
                val all = core.payments.preAuthorizations(FOLIO)
                assertEquals(listOf(PRE, PRE_2), all.map { it.id.toString() })
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.url.encodedPath.endsWith("/folios/$FOLIO/card-pre-authorizations") })
                assertEquals("200", requests[0].url.parameters["limit"])
                assertEquals("next-2", requests[1].url.parameters["cursor"])
            }
        }

    @Test
    fun registerSendsExactDecimalsExpiryAndKey() =
        runTest {
            core { json(preAuthorization(), HttpStatusCode.Created) }.use { core ->
                val done =
                    core.payments.registerPreAuthorization(
                        FOLIO,
                        register(nsu = "NSU1", cardBrand = "Visa", cardLast4 = "1234"),
                        Intent(),
                    )
                assertEquals(PRE, done.id.toString())
                val request = requests.single()
                assertEquals("POST", request.method.value)
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/card-pre-authorizations"))
                val body = bodyOf(request)
                assertEquals("300.00", body.getValue("amount").jsonPrimitive.content)
                assertTrue(body.getValue("amount").jsonPrimitive.isString)
                assertEquals("2026-11-20T02:59:59Z", body.getValue("expiresAt").jsonPrimitive.content)
                assertEquals("AUT123", body.getValue("authorizationCode").jsonPrimitive.content)
                assertEquals("NSU1", body.getValue("nsu").jsonPrimitive.content)
                assertEquals("Visa", body.getValue("cardBrand").jsonPrimitive.content)
                assertEquals("1234", body.getValue("cardLast4").jsonPrimitive.content)
                assertEquals(
                    setOf("amount", "expiresAt", "authorizationCode", "nsu", "cardBrand", "cardLast4"),
                    body.keys,
                )
                assertTrue(request.headers["Idempotency-Key"]?.isNotBlank() == true)
            }
        }

    @Test
    fun registerWithoutAuthorizationFailsBeforeTheNetwork() =
        runTest {
            core { json(preAuthorization(), HttpStatusCode.Created) }.use { core ->
                listOf("", "   ").forEach { blank ->
                    assertEquals(
                        setOf(PaymentProblem.AUTHORIZATION_CODE),
                        register(authorizationCode = blank).problems(),
                    )
                    assertFailsWith<IllegalArgumentException> {
                        core.payments.registerPreAuthorization(FOLIO, register(authorizationCode = blank), Intent())
                    }
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun captureSendsAmountAndKey() =
        runTest {
            core { json("""{"payment":${payment()},"preAuthorization":${preAuthorization()}}""") }.use { core ->
                val intent = Intent()
                val done =
                    core.payments.capturePreAuthorization(
                        FOLIO,
                        PRE,
                        CaptureInput(Money.parse("120.00")),
                        intent,
                    )
                assertEquals(PRE, done.preAuthorization.id.toString())
                assertEquals(PAY, done.payment.id.toString())
                val request = requests.single()
                assertEquals("POST", request.method.value)
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/card-pre-authorizations/$PRE/capture"))
                val body = bodyOf(request)
                assertEquals(setOf("amount"), body.keys)
                assertEquals("120.00", body.getValue("amount").jsonPrimitive.content)
                assertTrue(body.getValue("amount").jsonPrimitive.isString)
                assertEquals(intent.key, request.headers["Idempotency-Key"])
            }
        }

    @Test
    fun captureSendsCodesOnlyWhenPresent() =
        runTest {
            core { json("""{"payment":${payment()},"preAuthorization":${preAuthorization()}}""") }.use { core ->
                val input = CaptureInput(Money.parse("1.00"), nsu = "NSU9", authorizationCode = " AUT9 ")
                core.payments.capturePreAuthorization(FOLIO, PRE, input, Intent())
                val body = bodyOf(requests.single())
                assertEquals(setOf("amount", "nsu", "authorizationCode"), body.keys)
                assertEquals("NSU9", body.getValue("nsu").jsonPrimitive.content)
                assertEquals("AUT9", body.getValue("authorizationCode").jsonPrimitive.content)
            }
        }

    @Test
    fun releaseSendsOnlyTheKey() =
        runTest {
            core { json(preAuthorization()) }.use { core ->
                val intent = Intent()
                val done = core.payments.releasePreAuthorization(FOLIO, PRE, intent)
                assertEquals(PRE, done.id.toString())
                val request = requests.single()
                assertEquals("POST", request.method.value)
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/card-pre-authorizations/$PRE/release"))
                assertTrue(request.body.toByteArray().isEmpty())
                assertEquals(intent.key, request.headers["Idempotency-Key"])
            }
        }

    @Test
    fun cardLikePreAuthorizationCodesNeverReachTheNetwork() =
        runTest {
            core { json(preAuthorization()) }.use { core ->
                val failures = mutableListOf<IllegalArgumentException>()
                listOf(NSU_13, AUTH_19).forEach { code ->
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.registerPreAuthorization(FOLIO, register(authorizationCode = code), Intent())
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.registerPreAuthorization(FOLIO, register(nsu = code), Intent())
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.registerPreAuthorization(FOLIO, register(cardLast4 = code), Intent())
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.capturePreAuthorization(
                                FOLIO,
                                PRE,
                                CaptureInput(Money.parse("1.00"), nsu = code),
                                Intent(),
                            )
                        }
                    failures +=
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.capturePreAuthorization(
                                FOLIO,
                                PRE,
                                CaptureInput(Money.parse("1.00"), authorizationCode = code),
                                Intent(),
                            )
                        }
                }
                failures.forEach {
                    assertFalse(NSU_13 in it.message.orEmpty())
                    assertFalse(AUTH_19 in it.message.orEmpty())
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun preAuthorizationProblemsFollowTheContract() {
        val table: List<Pair<Set<PaymentProblem>, Set<PaymentProblem>>> =
            listOf(
                register().problems() to emptySet(),
                register(amount = "0.00").problems() to setOf(PaymentProblem.AMOUNT),
                register(amount = "-1.00").problems() to setOf(PaymentProblem.AMOUNT),
                register(authorizationCode = "ABC-12").problems() to setOf(PaymentProblem.AUTHORIZATION_CODE),
                register(authorizationCode = "A".repeat(21)).problems() to setOf(PaymentProblem.AUTHORIZATION_CODE),
                register(authorizationCode = "123456789012").problems() to emptySet(),
                register(nsu = NSU_13).problems() to setOf(PaymentProblem.NSU),
                register(nsu = " ").problems() to emptySet(),
                register(cardBrand = "Visa 3").problems() to setOf(PaymentProblem.CARD_BRAND),
                register(cardBrand = "A".repeat(21)).problems() to setOf(PaymentProblem.CARD_BRAND),
                register(cardBrand = "Mastercard Elo").problems() to emptySet(),
                register(cardLast4 = "123").problems() to setOf(PaymentProblem.CARD_LAST4),
                register(cardLast4 = "1234").problems() to emptySet(),
                register(cardBrand = " ", cardLast4 = " ").problems() to emptySet(),
                CaptureInput(Money.parse("1.00")).problems() to emptySet(),
                CaptureInput(Money.parse("0.00")).problems() to setOf(PaymentProblem.AMOUNT),
                CaptureInput(Money.parse("1.00"), nsu = NSU_13).problems() to setOf(PaymentProblem.NSU),
                CaptureInput(Money.parse("1.00"), authorizationCode = AUTH_19).problems() to
                    setOf(PaymentProblem.AUTHORIZATION_CODE),
                CaptureInput(Money.parse("1.00"), nsu = " ", authorizationCode = " ").problems() to emptySet(),
            )
        table.forEachIndexed { index, (actual, expected) -> assertEquals(expected, actual, "linha $index") }
    }

    @Test
    fun preAuthorizationInputsHideCardDataInToString() {
        val register =
            register(
                authorizationCode = "AUTSECRETA",
                nsu = "NSUSECRETO",
                cardBrand = "MarcaSecreta",
                cardLast4 = "9876",
            )
        val capture = CaptureInput(Money.parse("1.00"), nsu = "NSUSECRETO", authorizationCode = "AUTSECRETA")
        listOf(register.toString(), capture.toString()).forEach { text ->
            listOf("MarcaSecreta", "9876", "NSUSECRETO", "AUTSECRETA").forEach { assertFalse(it in text, text) }
        }
    }

    @Test
    fun preAuthorizationNonUuidIdsFailBeforeTheNetwork() =
        runTest {
            core { json(preAuthorization()) }.use { core ->
                val capture = CaptureInput(Money.parse("1.00"))
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { core.payments.preAuthorizations(SECRET_ID) },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.registerPreAuthorization(SECRET_ID, register(), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.capturePreAuthorization(SECRET_ID, PRE, capture, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.capturePreAuthorization(FOLIO, SECRET_ID, capture, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.releasePreAuthorization(SECRET_ID, PRE, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.payments.releasePreAuthorization(FOLIO, SECRET_ID, Intent())
                        },
                    )
                failures.forEach { assertTrue(SECRET_ID !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }
}
