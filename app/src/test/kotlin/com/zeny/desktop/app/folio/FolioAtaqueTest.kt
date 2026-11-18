package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.StatementDayResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val DASH = "—"
private const val REVERSE = "billing.postings.reverse"
private val ITEM = Uuid.parse("51000000-0000-0000-0000-000000000001")
private val BROKEN = Uuid.parse("51000000-0000-0000-0000-000000000002")

/**
 * Ataque da etapa à conta da hospedagem: valores do servidor fora do formato, quantidade e valor unitário nas bordas,
 * duplo clique e repetição com a mesma chave no lançamento e no estorno, e a aprovada perdida na rede.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por ataque
class FolioAtaqueTest {
    private val posted = mutableListOf<PostingResponse>()
    private var reversals = 0

    private fun TestScope.form(source: FakeFolioSource) =
        PostingFormState(source, backgroundScope, FOLIO_ID.toString(), { posted += it }).also {
            it.start()
            runCurrent()
        }

    private fun PostingFormState.fill(
        quantity: String = "1",
        price: String = "10,00",
    ) {
        codeId = codes.first().id
        this.quantity = quantity
        unitPrice = price
    }

    private fun TestScope.reversal(
        source: FakeFolioSource,
        approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>?,
    ) = ReversalState(source, backgroundScope, FOLIO_ID.toString(), ITEM.toString(), approve) { reversals++ }

    private fun approvalRequired() = ApiError.ApprovalRequired("POSTING_REVERSAL", REVERSE, "posting", null, null)

    // --- Entrada não confiável do servidor ---

    @Test
    fun malformedServerValuesShowDashAndKeepTheRestOfTheFolio() =
        runTest {
            val source = FakeFolioSource()
            source.folio = { folioOf(balance = "1e3", creditLimit = "abc") }
            source.postings = {
                listOf(
                    postingOf(
                        id = BROKEN,
                        amount = "12,50",
                        description = "Quebrado",
                    ).copy(businessDate = "18/11/2026"),
                    postingOf(id = ITEM, amount = "20.00", description = "Frigobar"),
                )
            }
            val state = FolioState(wiringOf(source), backgroundScope, FOLIO_ID.toString())
            state.start()
            runCurrent()

            assertNull(state.error, "um valor fora do formato derrubou a conta")
            val view = assertNotNull(state.view)
            assertEquals(DASH, view.balance)
            assertEquals(DASH, view.creditLimit)
            assertEquals("F-0001", view.number)
            val broken = state.postings.single { it.id == BROKEN.toString() }
            assertEquals(DASH, broken.amount)
            assertEquals(DASH, broken.date)
            assertEquals("Quebrado", broken.description)
            val good = state.postings.single { it.id == ITEM.toString() }
            assertEquals(FolioText.money("20.00"), good.amount)
            assertNotEquals(DASH, good.date)
        }

    @Test
    fun malformedStatementValuesShowDashAndKeepTheRestOfTheStatement() =
        runTest {
            val source = FakeFolioSource()
            source.statement = {
                val ok = statementOf(lines = listOf(lineOf(amount = "", description = "Linha quebrada"), lineOf()))
                ok.copy(
                    balance = "NaN",
                    chargesTotal = "1.234,56",
                    days =
                        ok.days +
                            StatementDayResponse(
                                balanceAtEnd = "x",
                                businessDate = "2026-13-40",
                                charges = "--1",
                                lines = emptyList(),
                                payments = "1.000",
                            ),
                )
            }
            val state = FolioState(wiringOf(source), backgroundScope, FOLIO_ID.toString())
            state.start()
            runCurrent()
            state.showStatement()
            runCurrent()

            assertNull(state.statementError, "um valor fora do formato derrubou o extrato")
            val shown = assertNotNull(state.statement)
            assertEquals(DASH, shown.balance)
            assertEquals(DASH, shown.charges)
            assertEquals(FolioText.money("20.00"), shown.payments)
            val (day, brokenDay) = shown.days
            assertEquals(DASH, day.lines.first().amount)
            assertEquals("Linha quebrada", day.lines.first().description)
            assertEquals(FolioText.money("20.00"), day.lines.last().amount)
            assertEquals(FolioText.money("140.00"), day.balanceAtEnd)
            assertEquals(
                listOf(DASH, DASH, DASH, DASH),
                brokenDay.run { listOf(date, charges, payments, balanceAtEnd) },
            )
        }

    // --- Quantidade e valor unitário nas bordas ---

    @Test
    fun quantityTypedOddlyNeverPostsOutsideOneTo9999() =
        runTest {
            val accepted = mapOf("1" to 1, "9999" to 9999, "+5" to 5, " 5 " to 5, "0005" to 5)
            val refused = listOf("0", "10000", "5.0", "5,0", "1e3", "-1", "", " ", "99999999999", "5 5", "0x10")
            accepted.forEach { (typed, expected) ->
                val source = FakeFolioSource()
                val form = form(source)
                form.fill(quantity = typed)
                form.confirm()
                runCurrent()
                assertEquals(
                    expected,
                    source.postCalls
                        .single()
                        .input.quantity,
                    "a quantidade \"$typed\"",
                )
            }
            refused.forEach { typed ->
                val source = FakeFolioSource()
                val form = form(source)
                form.fill(quantity = typed)
                form.confirm()
                runCurrent()
                assertTrue(source.postCalls.isEmpty(), "a quantidade \"$typed\" foi ao servidor")
                assertEquals("A quantidade é um número inteiro de 1 a 9999.", form.message, "a quantidade \"$typed\"")
            }
        }

    @Test
    fun unitPriceEdgesGoExactOrAreRefused() =
        runTest {
            val exact = mapOf("1.000.000,00" to "1000000.00", "0,01" to "0.01", "1000000" to "1000000.00")
            exact.forEach { (typed, wire) ->
                val source = FakeFolioSource()
                val form = form(source)
                form.fill(price = typed)
                form.confirm()
                runCurrent()
                assertEquals(
                    wire,
                    source.postCalls
                        .single()
                        .input.unitPrice
                        .toApi(),
                    "o valor \"$typed\"",
                )
            }
            listOf("-0,01", "0,00", "0", "-1.000.000,00", "1,001", "1.0.0,00", "abc").forEach { typed ->
                val source = FakeFolioSource()
                val form = form(source)
                form.fill(price = typed)
                form.confirm()
                runCurrent()
                assertTrue(source.postCalls.isEmpty(), "o valor \"$typed\" foi ao servidor")
                assertNotNull(form.message, "o valor \"$typed\" foi recusado sem texto")
            }
        }

    // --- Concorrência e idempotência ---

    @Test
    fun definitiveFailureGivesTheNextPostingANewKey() =
        runTest {
            val source = FakeFolioSource()
            var first = true
            source.post = {
                if (first) {
                    first = false
                    throw ApiError.Problem(422, "POSTING_INVALID", null, emptyList())
                }
                postingOf()
            }
            val form = form(source)
            form.fill()

            form.confirm()
            runCurrent()
            assertFalse(form.unconfirmed)
            assertNotNull(form.message)
            form.confirm()
            runCurrent()

            assertEquals(2, source.postCalls.size)
            assertNotEquals(source.postCalls[0].intent.key, source.postCalls[1].intent.key)
            assertEquals(1, posted.size)
        }

    @Test
    fun doubleConfirmOnReversalReversesOnce() =
        runTest {
            val source = FakeFolioSource()
            val late = LateAnswer<ApiResponse<PostingResponse>?>()
            val state = reversal(source) { late.await() }
            state.reason = "Cobrado em dobro"

            state.confirm()
            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()

            assertEquals(1, source.reverseCalls.size)
            assertTrue(state.busy)
            late.answer(ApiResponse(postingOf(type = "REVERSAL"), null, 201))
            runCurrent()
            assertTrue(state.done)
            state.confirm()
            runCurrent()
            assertEquals(1, source.reverseCalls.size, "confirmar depois de feito estornou de novo")
            assertEquals(1, reversals)
        }

    @Test
    fun approvedReversalLostOnTheNetworkRetriesWithTheSameIntent() =
        runTest {
            val source = FakeFolioSource().apply { requiresApproval = approvalRequired() }
            // A primeira repetição aprovada se perde na rede; a segunda chega.
            source.reverseOutcomes += ApiError.Network(IOException("caiu depois da aprovação"))
            val approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>? = { action ->
                try {
                    action.run(null)
                } catch (_: ApiError.ApprovalRequired) {
                    action.run("tok")
                }
            }
            val state = reversal(source, approve)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertTrue(state.unconfirmed)
            assertFalse(state.done)
            assertEquals(0, reversals)
            state.reason = "Outro motivo"
            assertEquals("Cobrado em dobro", state.reason, "as entradas destravaram sem resultado")

            state.confirm()
            runCurrent()

            assertEquals(2, source.reverseCalls.size)
            assertSame(source.reverseCalls[0].intent, source.reverseCalls[1].intent)
            assertEquals("Cobrado em dobro", source.reverseCalls[1].reason)
            assertTrue(state.done)
            assertEquals(1, reversals)
        }

    @Test
    fun cancelledApprovalKeepsTheKeyUnlessTheReasonChanges() =
        runTest {
            val source = FakeFolioSource().apply { requiresApproval = approvalRequired() }
            val state = reversal(source) { action -> runCatching { action.run(null) }.getOrNull() }
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()
            assertSame(source.reverseCalls[0].intent, source.reverseCalls[1].intent, "repetir trocou a intenção")

            state.reason = "Outro motivo"
            state.confirm()
            runCurrent()
            assertNotEquals(source.reverseCalls[1].intent.key, source.reverseCalls[2].intent.key)
            assertEquals("Outro motivo", source.reverseCalls[2].reason)
            assertEquals(0, reversals)
        }

    // --- Extrato GUEST e PDF interno ---

    @Test
    fun guestStatementWithoutInternalBlockShowsNoReason() =
        runTest {
            val source = FakeFolioSource()
            source.statement = { audience ->
                val guest = audience == StatementAudience.GUEST
                statementOf(
                    audience =
                        if (guest) FolioStatementResponse.Audience.GUEST else FolioStatementResponse.Audience.INTERNAL,
                    lines = listOf(lineOf(reason = if (guest) null else "Cortesia do gerente")),
                )
            }
            val state = FolioState(wiringOf(source), backgroundScope, FOLIO_ID.toString())
            state.start()
            runCurrent()

            state.audience = StatementAudience.INTERNAL
            state.showStatement()
            runCurrent()
            assertEquals(
                "Cortesia do gerente",
                state.statement
                    ?.days
                    ?.single()
                    ?.lines
                    ?.single()
                    ?.reason,
            )

            state.audience = StatementAudience.GUEST
            state.showStatement()
            runCurrent()
            val guest = assertNotNull(state.statement)
            assertTrue(guest.days.flatMap { it.lines }.all { it.reason == null }, "o extrato do hóspede tem motivo")
            assertFalse(guest.toString().contains(USER_ID.toString()), "o extrato mostra o id do usuário")
        }

    @Test
    fun internalPdfIsNeverAskedWithoutTheInternalButton() =
        runTest {
            val asked = mutableListOf<String>()
            val source = FakeFolioSource()
            val wiring =
                FolioWiring(
                    source,
                    setOf("billing.folios.read"),
                    { it.run(null) },
                    guestPdf = { _, number ->
                        asked += "guest:$number"
                        error("só conta a chamada")
                    },
                    internalPdf = { _, number ->
                        asked += "internal:$number"
                        error("só conta a chamada")
                    },
                )
            val state = FolioState(wiring, backgroundScope, FOLIO_ID.toString())
            state.start()
            runCurrent()

            state.audience = StatementAudience.INTERNAL
            state.showStatement()
            runCurrent()
            runCatching { state.openPdf(StatementAudience.GUEST) }

            assertEquals(listOf("guest:F-0001"), asked, "a audiência da tela escolheu o PDF")
            assertTrue(source.pdfCalls.isEmpty())
        }
}
