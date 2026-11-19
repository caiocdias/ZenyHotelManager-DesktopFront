package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"
private const val ZONE_MISSING = "Não foi possível ler o fuso do hotel. Feche e abra de novo."
private const val VALIDITY_RANGE = "A validade vai de hoje até 89 dias."

/** Noite de 18/11/2026 em Honolulu (UTC-10, sem horário de verão), já 19/11 em UTC e no fuso da máquina. */
private val NOW: Instant = Instant.parse("2026-11-19T05:00:00Z")
private val HOTEL_ZONE: ZoneId = ZoneId.of("Pacific/Honolulu")

/** O diálogo "Registrar caução" ([PreAuthorizationFormState]). */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do diálogo
class PreAuthorizationFormStateTest {
    private val registered = mutableListOf<Unit>()
    private val refused = mutableListOf<String>()

    private fun TestScope.form(
        source: FakePaymentSource,
        zone: ZoneId? = HOTEL_ZONE,
    ) = PreAuthorizationFormState(
        source,
        backgroundScope,
        FOLIO_ID.toString(),
        zone,
        Clock.fixed(NOW, ZoneOffset.UTC),
        onRegistered = { registered += Unit },
        onRefused = { refused += it },
    )

    private fun PreAuthorizationFormState.fill(
        amount: String = "300,00",
        expiresOn: String = "20/11/2026",
        authorizationCode: String = "AUT123",
    ) {
        this.amount = amount
        this.expiresOn = expiresOn
        this.authorizationCode = authorizationCode
    }

    private fun holder(
        source: FakePaymentSource,
        scope: TestScope,
        onChanged: () -> Unit = {},
    ) = PreAuthorizationsState(
        wiringOf(FakeFolioSource(), setOf(READ, RECEIVE), payments = source),
        scope.backgroundScope,
        FOLIO_ID.toString(),
        folioOpen = { true },
        otherDialogOpen = { false },
        notify = {},
        onChanged = onChanged,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    @Test
    fun doubleConfirmRegistersOnce() =
        runTest {
            val gate = CompletableDeferred<CardPreAuthorizationResponse>()
            val source = FakePaymentSource().apply { register = { gate.await() } }
            val form = form(source).apply { fill() }

            form.confirm()
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            form.confirm()
            gate.complete(preAuthorizationOf())
            runCurrent()

            assertEquals(1, source.registerCalls.size)
            assertEquals(1, registered.size)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakePaymentSource()
            var failures = 1
            source.register = {
                if (failures-- > 0) throw ApiError.Network(IOException("caiu"))
                preAuthorizationOf()
            }
            val form = form(source).apply { fill() }

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertEquals(
                "Não foi possível confirmar o registro da caução. Registre de novo sem mudar nada; " +
                    "se fechar, confira a conta antes de registrar de novo.",
                form.message,
            )
            form.confirm()
            runCurrent()

            val (first, second) = source.registerCalls
            assertSame(first.intent, second.intent)
            assertEquals(1, registered.size)
        }

    @Test
    fun amountGoesAsExactDecimal() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source)
            form.fill(amount = "1.234,56")
            form.nsu = " 123456 "
            form.cardBrand = "Visa"
            form.cardLast4 = "1234"

            form.confirm()
            runCurrent()

            val input = source.registerCalls.single().input
            assertEquals("1234.56", input.amount.toApi())
            assertEquals("AUT123", input.authorizationCode)
            assertEquals("123456", input.nsu)
            assertEquals("Visa", input.cardBrand)
            assertEquals("1234", input.cardLast4)
        }

    @Test
    fun authorizationIsRequired() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source).apply { fill(authorizationCode = "  ") }

            form.confirm()
            runCurrent()

            assertTrue(source.registerCalls.isEmpty())
            assertEquals(
                "A autorização tem até 20 letras e números, no máximo 12 dígitos.",
                form.errors[PaymentProblem.AUTHORIZATION_CODE],
            )
        }

    @Test
    fun cardLikeCodesAreRefusedWithText() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source).apply { fill(authorizationCode = "4111111111111111") }
            form.nsu = "4111111111111111"
            form.cardLast4 = "4111111111111111"

            form.confirm()
            runCurrent()

            assertTrue(source.registerCalls.isEmpty(), "nada vai à rede")
            assertEquals(
                setOf(PaymentProblem.AUTHORIZATION_CODE, PaymentProblem.NSU, PaymentProblem.CARD_LAST4),
                form.errors.keys,
            )
            assertTrue(form.errors.values.none { "4111" in it }, "o texto não repete o digitado")
            assertFalse("4111" in form.toString())
        }

    @Test
    fun formOffersNoCardNumberField() {
        val expected =
            setOf(
                "busy",
                "unconfirmed",
                "closeOnly",
                "message",
                "canConfirm",
                "closesOnEscape",
                "errors",
                "validityError",
                "locked",
                "amount",
                "expiresOn",
                "authorizationCode",
                "nsu",
                "cardBrand",
                "cardLast4",
            )
        val properties =
            PreAuthorizationFormState::class.java.methods
                .filter { it.declaringClass == PreAuthorizationFormState::class.java && it.parameterCount == 0 }
                .map { it.name }
                .filter { it.startsWith("get") }
                .map { it.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
                .toSet()

        assertEquals(expected, properties)
    }

    @Test
    fun validityWindowFollowsTheHotelZone() =
        runTest {
            val source = FakePaymentSource()

            val today = form(source).apply { fill(expiresOn = "18/11/2026") }
            today.confirm()
            runCurrent()
            assertEquals(
                Instant.parse("2026-11-19T09:59:59Z"),
                source.registerCalls
                    .last()
                    .input.expiresAt,
            )

            val last = form(source).apply { fill(expiresOn = "15/02/2027") }
            last.confirm()
            runCurrent()
            assertEquals(
                Instant.parse("2027-02-16T09:59:59Z"),
                source.registerCalls
                    .last()
                    .input.expiresAt,
            )

            listOf("17/11/2026", "16/02/2027").forEach { date ->
                val out = form(source).apply { fill(expiresOn = date) }
                out.confirm()
                runCurrent()
                assertEquals(VALIDITY_RANGE, out.validityError, date)
            }
            assertEquals(2, source.registerCalls.size, "fora do intervalo, nada vai à rede")
        }

    @Test
    fun missingZoneShowsTextAndDoesNotConfirm() =
        runTest {
            val source =
                FakePaymentSource().apply { businessDay = { BusinessDay(BusinessDate.parse("2026-11-18"), null) } }
            val holder = holder(source, this).also { it.reload() }
            runCurrent()

            source.businessDay = { BusinessDay(BusinessDate.parse("2026-11-18"), HOTEL_ZONE) }
            holder.startRegister()
            val blind = assertNotNull(holder.register)
            assertEquals(ZONE_MISSING, blind.message)
            assertFalse(blind.canConfirm)
            blind.fill()
            blind.confirm()
            runCurrent()
            assertTrue(source.registerCalls.isEmpty())
            assertEquals(2, source.businessDayCalls, "abrir sem o fuso relê")

            holder.finishRegister()
            holder.startRegister()
            val form = assertNotNull(holder.register)
            assertNull(form.message)
            form.fill()
            form.confirm()
            runCurrent()
            assertEquals(1, source.registerCalls.size)
        }

    @Test
    fun fieldsLockWhileBusyAndUnknown() =
        runTest {
            val gate = CompletableDeferred<CardPreAuthorizationResponse>()
            val source = FakePaymentSource().apply { register = { gate.await() } }
            val form = form(source).apply { fill() }

            form.confirm()
            runCurrent()
            assertTrue(form.locked)
            form.amount = "1,00"
            form.expiresOn = "19/11/2026"
            form.authorizationCode = "OUTRA"
            assertEquals("300,00", form.amount)
            assertEquals("20/11/2026", form.expiresOn)
            assertEquals("AUT123", form.authorizationCode)

            gate.completeExceptionally(ApiError.Network(IOException("caiu")))
            runCurrent()
            assertTrue(form.unconfirmed)
            assertTrue(form.locked)
            form.nsu = "999"
            form.cardBrand = "Elo"
            form.cardLast4 = "9999"
            assertEquals("", form.nsu)
            assertEquals("", form.cardBrand)
            assertEquals("", form.cardLast4)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source =
                FakePaymentSource().apply {
                    register = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) }
                }
            val form = form(source).apply { fill() }

            form.confirm()
            runCurrent()

            assertTrue(form.closeOnly)
            assertFalse(form.canConfirm)
            assertTrue(form.closesOnEscape)
            assertEquals(
                "O servidor não confirmou o registro da caução. Feche e confira a conta antes de registrar de novo.",
                form.message,
            )
            form.confirm()
            runCurrent()
            assertEquals(1, source.registerCalls.size)
        }

    @Test
    fun closedFolioShowsMessageAndReloads() =
        runTest {
            val failure = ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
            val source = FakePaymentSource().apply { register = { throw failure } }
            var changed = 0
            val holder = holder(source, this) { changed++ }.also { it.reload() }
            runCurrent()

            holder.startRegister()
            val form = assertNotNull(holder.register)
            form.fill()
            form.confirm()
            runCurrent()

            assertEquals(failure.userMessage, form.message)
            assertEquals(1, changed, "a conta é relida")
            assertFalse(form.unconfirmed)
        }
}
