package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val CREATE = "billing.postings.create"
private const val UNCONFIRMED =
    "Não foi possível confirmar o lançamento. Lance de novo sem mudar nada; " +
        "se fechar, confira a conta antes de lançar de novo."
private const val SERVER_UNCONFIRMED =
    "O servidor não confirmou o lançamento. Feche e confira a conta antes de lançar de novo."
private const val QUANTITY_TEXT = "A quantidade é um número inteiro de 1 a 9999."

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class PostingFormStateTest {
    private val posted = mutableListOf<PostingResponse>()

    private fun TestScope.form(source: FakeFolioSource) =
        PostingFormState(source, backgroundScope, FOLIO_ID.toString(), { posted += it }).also {
            it.start()
            runCurrent()
        }

    /** O formulário preenchido com o primeiro código oferecido. */
    private fun PostingFormState.fill(
        quantity: String = "2",
        price: String = "12,50",
    ) {
        codeId = codes.first().id
        this.quantity = quantity
        unitPrice = price
    }

    /** A conta aberta com a permissão de lançar e o diálogo de lançamento aberto por ela. */
    private fun TestScope.panel(source: FakeFolioSource): FolioState =
        FolioState(wiringOf(source, setOf(READ, CREATE)), backgroundScope, FOLIO_ID.toString()).also {
            it.start()
            runCurrent()
            it.startPosting()
            runCurrent()
        }

    @Test
    fun doubleConfirmPostsOnce() =
        runTest {
            val source = FakeFolioSource()
            val late = LateAnswer<PostingResponse>()
            source.post = { late.await() }
            val form = form(source)
            form.fill()

            form.confirm()
            form.confirm()
            runCurrent()
            form.confirm()
            runCurrent()

            assertEquals(1, source.postCalls.size)
            assertTrue(form.busy)
            late.answer(postingOf())
            runCurrent()
            assertEquals(1, posted.size)
            assertEquals(1, source.postCalls.size)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakeFolioSource()
            var failures = 1
            source.post = {
                if (failures-- > 0) throw ApiError.Network(IOException("sem rede"))
                postingOf()
            }
            val form = form(source)
            form.fill()

            form.confirm()
            runCurrent()

            assertTrue(form.unconfirmed)
            assertFalse(form.closeOnly)
            assertEquals(UNCONFIRMED, form.message)
            form.quantity = "3"
            assertEquals("2", form.quantity, "as entradas ficam travadas sem resultado")

            form.confirm()
            runCurrent()

            assertEquals(2, source.postCalls.size)
            assertSame(source.postCalls[0].intent, source.postCalls[1].intent)
            assertEquals(source.postCalls[0].intent.key, source.postCalls[1].intent.key)
            assertEquals(1, posted.size)
            assertFalse(form.unconfirmed)
        }

    @Test
    fun unitPriceGoesAsExactDecimal() =
        runTest {
            val source = FakeFolioSource()
            val form = form(source)
            form.fill(quantity = "1", price = "1,234")

            form.confirm()
            runCurrent()

            assertTrue(source.postCalls.isEmpty(), "três casas não vão ao servidor")
            assertEquals("Use no máximo 2 casas decimais.", form.message)

            form.unitPrice = "1.234,56"
            form.description = "  Garrafa de vinho  "
            form.confirm()
            runCurrent()

            val input = source.postCalls.single().input
            assertEquals(Money.parse("1234.56"), input.unitPrice)
            assertEquals("1234.56", input.unitPrice.toApi())
            assertEquals(1, input.quantity)
            assertEquals(CODE_ID.toString(), input.transactionCodeId)
            assertEquals("Garrafa de vinho", input.description)
        }

    @Test
    fun zeroOrNegativeUnitPriceIsRefused() =
        runTest {
            val source = FakeFolioSource()
            val form = form(source)

            listOf("0,00", "-5,00", "").forEach { price ->
                form.fill(price = price)
                form.confirm()
                runCurrent()
                assertNotNull(form.message, "o valor \"$price\" foi aceito")
            }

            assertTrue(source.postCalls.isEmpty())
            form.unitPrice = "0,01"
            form.confirm()
            runCurrent()
            assertEquals(
                Money.parse("0.01"),
                source.postCalls
                    .single()
                    .input.unitPrice,
            )
        }

    @Test
    fun serviceChargeCodesAreNotOffered() =
        runTest {
            val source = FakeFolioSource()
            val charge = Uuid.random()
            source.codes = {
                listOf(
                    codeOf(),
                    codeOf(charge, "TAXA", "Taxa de serviço", TransactionCodeResponse.Kind.SERVICE_CHARGE),
                    codeOf(Uuid.random(), "LAV", "Lavanderia", serviceCharge = false),
                )
            }

            val form = form(source)

            assertEquals(listOf("FRIGO — Frigobar", "LAV — Lavanderia"), form.codes.map { it.label })
            form.codeId = charge.toString()
            assertNull(form.codeId, "um código fora da lista não é escolhido")
        }

    @Test
    fun quantityOutsideRangeIsRefused() =
        runTest {
            val source = FakeFolioSource()
            val form = form(source)
            assertEquals("1", form.quantity, "a quantidade padrão é 1")

            listOf("0", "10000", "1,5", "-1", "").forEach { quantity ->
                form.fill(quantity = quantity)
                form.confirm()
                runCurrent()
                assertEquals(QUANTITY_TEXT, form.message, "a quantidade \"$quantity\"")
            }
            assertTrue(source.postCalls.isEmpty())

            form.fill(quantity = "9999")
            form.confirm()
            runCurrent()
            assertEquals(
                9999,
                source.postCalls
                    .single()
                    .input.quantity,
            )
        }

    @Test
    fun codesForbiddenShowsText() =
        runTest {
            val source = FakeFolioSource()
            source.codes = { throw ApiError.Problem(403, "FORBIDDEN", null, emptyList()) }

            val form = form(source)

            assertEquals("Seu perfil não pode listar os códigos de lançamento.", form.codesError)
            assertFalse(form.codesRetryable)
            assertFalse(form.canConfirm)
            form.quantity = "1"
            form.unitPrice = "10,00"
            form.confirm()
            runCurrent()
            assertTrue(source.postCalls.isEmpty())
        }

    @Test
    fun codesFailureCanBeRetried() =
        runTest {
            val source = FakeFolioSource()
            source.codes = { throw ApiError.Network(IOException("sem rede")) }
            val form = form(source)
            assertNotNull(form.codesError)
            assertTrue(form.codesRetryable)

            source.codes = { listOf(codeOf()) }
            form.start()
            runCurrent()

            assertNull(form.codesError)
            assertEquals(listOf("FRIGO — Frigobar"), form.codes.map { it.label })
            assertEquals(2, source.codeCalls)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source = FakeFolioSource()
            source.post = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) }
            val form = form(source)
            form.fill()

            form.confirm()
            runCurrent()

            assertTrue(form.closeOnly)
            assertEquals(SERVER_UNCONFIRMED, form.message)
            assertFalse(form.canConfirm)
            form.confirm()
            runCurrent()
            form.unitPrice = "99,00"
            assertEquals(1, source.postCalls.size)
            assertEquals("12,50", form.unitPrice)
        }

    @Test
    fun storedFailureRereadsTheFolioOnClose() =
        runTest {
            val source = FakeFolioSource()
            source.post = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) }
            val state = panel(source)
            val form = assertNotNull(state.posting)
            form.fill()
            form.confirm()
            runCurrent()
            assertTrue(form.closeOnly)

            state.dismissPosting()
            runCurrent()

            assertNull(state.posting)
            assertEquals(2, source.folioCalls.size, "fechar relê a conta para conferir")
        }

    @Test
    fun closedFolioShowsMessageAndReloads() =
        runTest {
            val source = FakeFolioSource()
            source.post = { throw ApiError.Problem(409, "FOLIO_CLOSED", "detalhe", emptyList()) }
            val state = panel(source)
            val form = assertNotNull(state.posting)
            form.fill()
            source.folio = { folioOf(status = FolioResponse.Status.CLOSED) }

            form.confirm()
            runCurrent()

            assertEquals("A conta já está fechada.", form.message)
            assertFalse(form.busy)
            assertFalse(form.unconfirmed)
            assertEquals(2, source.folioCalls.size)
            assertEquals(true, state.view?.closed)
            assertFalse(state.canPost)
        }

    @Test
    fun successReloadsTheFolio() =
        runTest {
            val source = FakeFolioSource()
            val state = panel(source)
            val form = assertNotNull(state.posting)
            form.fill()
            val item = Uuid.random()
            source.post = { postingOf(item, "Café", "25.00") }
            source.folio = { folioOf(balance = "177.50") }
            source.postings = {
                listOf(
                    postingOf(item, "Café", "25.00"),
                    postingOf(description = "Taxa", amount = "2.50", type = "SERVICE_CHARGE", serviceChargeFor = item),
                )
            }

            form.confirm()
            runCurrent()

            assertNull(state.posting, "o diálogo fecha depois do lançamento")
            assertEquals("R$ 177,50", state.view?.balance)
            assertEquals(listOf("R$ 25,00", "R$ 2,50"), state.postings.map { it.amount })
            assertTrue(state.postings[1].serviceCharge)
            assertEquals(2, source.folioCalls.size)
            assertNull(state.notice)
        }

    @Test
    fun routedPostingSaysWhereItWent() =
        runTest {
            val source = FakeFolioSource()
            source.folio = { id ->
                if (id == OTHER_FOLIO_ID.toString()) folioOf(OTHER_FOLIO_ID, number = "F-0002") else folioOf()
            }
            source.post = { postingOf(folioId = OTHER_FOLIO_ID) }
            val state = panel(source)
            assertNotNull(state.posting).fill()

            assertNotNull(state.posting).confirm()
            runCurrent()

            assertEquals("Lançado na conta F-0002 pelo roteamento.", state.notice)
            assertTrue(OTHER_FOLIO_ID.toString() in source.folioCalls)

            source.folio = { id ->
                if (id == OTHER_FOLIO_ID.toString()) throw ApiError.Network(IOException("sem rede")) else folioOf()
            }
            state.startPosting()
            runCurrent()
            assertNull(state.notice, "um lançamento novo apaga o aviso anterior")
            assertNotNull(state.posting).fill()
            assertNotNull(state.posting).confirm()
            runCurrent()

            assertEquals("Lançado em outra conta pelo roteamento.", state.notice)
        }
}
