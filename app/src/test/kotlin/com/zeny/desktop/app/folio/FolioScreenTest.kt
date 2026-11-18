package com.zeny.desktop.app.folio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinatorOrNull
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.reception.DayPanelState
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionScreen
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.reservations.FakeReservationSearchSource
import com.zeny.desktop.app.reservations.ReservationSearchState
import com.zeny.desktop.app.reservations.ReservationsActions
import com.zeny.desktop.app.reservations.ReservationsScreen
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RESERVATIONS_READ = "reservations.reservations.read"
private const val CREATE = "billing.postings.create"
private const val REVERSE = "billing.postings.reverse"
private val FIRST = Uuid.parse("20000000-0000-0000-0000-0000000000f1")
private val SECOND = Uuid.parse("20000000-0000-0000-0000-0000000000f2")
private const val HOLDER = "30000000-0000-0000-0000-0000000000f1"

/** A conta na casca: a aba "Contas" da área "Contas e caixa" e o botão "Conta" do detalhe da reserva. */
@OptIn(ExperimentalTestApi::class)
class FolioScreenTest {
    private fun folios() =
        FakeFolioSource().apply {
            postings = { listOf(postingOf(description = "Frigobar", amount = "20.00")) }
        }

    private fun reception() =
        FakeReceptionSource().apply {
            permissions = permissions + READ
            pages[DayList.ARRIVALS to null] =
                Page(listOf(searchItemOf("R-1").copy(id = FIRST), searchItemOf("R-2").copy(id = SECOND)), null)
            reservations[FIRST.toString()] = confirmDetail(FIRST, "R-1", status = "CONFIRMED", guaranteeType = "NONE")
            reservations[SECOND.toString()] = confirmDetail(SECOND, "R-2", status = "CONFIRMED", guaranteeType = "NONE")
            holderError = ApiError.Problem(404, null, null, emptyList())
            ratePlanError = ApiError.Problem(404, null, null, emptyList())
        }

    private fun ComposeUiTest.accounts(
        source: FolioSource?,
        permissions: Set<String> = setOf(READ, OPERATE),
        connectionSession: String = "A",
    ) {
        val connection = FolioConnection(source, cashier = FakeCashierSource(), sessionId = connectionSession)
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", permissions) } }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** O conteúdo com o coordenador de aprovação da casca e o diálogo dele, como o `AppRoot` monta. */
    private fun ComposeUiTest.withApprovals(content: @Composable () -> Unit) {
        setContent {
            val scope = rememberCoroutineScope()
            val approvals = remember { ApprovalCoordinator({ _, _ -> "tok" }, emptyFlow(), scope) }
            ZenyTheme {
                CompositionLocalProvider(LocalApprovalCoordinatorOrNull provides approvals) {
                    content()
                    ApprovalHost(approvals)
                }
            }
        }
        waitForIdle()
    }

    /** Rola o painel da conta até a linha [tag] e clica nela. */
    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    /** Pede o estorno do lançamento da linha [index] com um motivo. */
    private fun ComposeUiTest.reverse(index: Int) {
        scrollAndClick("posting-actions-$index")
        click("posting-reverse-$index")
        onNodeWithTag("reversal-reason").performTextInput("Cobrado em dobro")
        click("reversal-submit")
    }

    private fun ComposeUiTest.openFolioTab() {
        onNodeWithText("Contas").performClick()
        waitForIdle()
    }

    @Test
    fun cashierTabStaysTheDefault() =
        runComposeUiTest {
            val source = folios()
            accounts(source)

            onNodeWithText("Caixa").assertExists()
            onNodeWithText("Contas").assertExists()
            onNodeWithText("Turno de caixa").assertExists()
            assertTrue(source.pageCalls.isEmpty())
        }

    @Test
    fun noFoliosTabWithoutRead() =
        runComposeUiTest {
            val source = folios()
            accounts(source, permissions = setOf(OPERATE))

            onNodeWithText("Turno de caixa").assertExists()
            onNodeWithText("Contas").assertDoesNotExist()
            assertTrue(source.pageCalls.isEmpty())
        }

    @Test
    fun otherSessionDoesNotDraw() =
        runComposeUiTest {
            val source = folios()
            accounts(source, connectionSession = "B")

            onNodeWithText("Contas").assertDoesNotExist()
            onNodeWithText("Turno de caixa").assertDoesNotExist()
            assertTrue(source.pageCalls.isEmpty())
        }

    @Test
    fun choosingAFolioShowsPostingsAndBalance() =
        runComposeUiTest {
            val source = folios()
            accounts(source)
            openFolioTab()

            onNodeWithText("F-0001", substring = true).assertExists()
            click("folio-row-$FOLIO_ID")

            onNodeWithTag("folio-panel").assertExists()
            onNodeWithText("Saldo: R$ 150,00").assertExists()
            onNodeWithText("Frigobar", substring = true).assertExists()
            assertEquals(listOf(FOLIO_ID.toString()), source.folioCalls)
        }

    @Test
    fun recomposingTheAreaKeepsTheSameFolioState() =
        runComposeUiTest {
            val source = folios()
            var tick by mutableStateOf(0)
            val connection = FolioConnection(source, cashier = FakeCashierSource())
            setContent {
                val current = tick
                ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", setOf(READ, OPERATE), { current.hashCode() }) }
            }
            waitForIdle()
            openFolioTab()
            click("folio-row-$FOLIO_ID")
            onNodeWithText("Frigobar", substring = true).assertExists()

            tick++
            waitForIdle()
            tick++
            waitForIdle()

            onNodeWithText("Frigobar", substring = true).assertExists()
            assertEquals(1, source.folioCalls.size)
            assertEquals(1, source.pageCalls.size)
        }

    @Test
    fun guestPdfOpensTheViewerWithoutAFile() =
        runComposeUiTest {
            val tmp = File(System.getProperty("java.io.tmpdir"))
            val before =
                tmp
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .toSet()
            val source = folios()
            accounts(source)
            openFolioTab()
            click("folio-row-$FOLIO_ID")

            click("folio-pdf-guest")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("pdf-viewer").fetchSemanticsNodes().isNotEmpty() }

            onNodeWithText("Extrato — F-0001").assertExists()
            assertEquals(
                listOf(FOLIO_ID.toString() to com.zeny.desktop.core.facade.StatementAudience.GUEST),
                source.pdfCalls,
            )
            val created =
                tmp
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .toSet() - before
            assertTrue(created.none { it.endsWith(".pdf") }, "arquivo criado: $created")
        }

    @Test
    fun folioButtonInReceptionNeedsReadAndOpensTheDialog() {
        runComposeUiTest {
            val source = folios()
            val connection = FolioConnection(source, reception = reception())
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", reception().permissions) } }
            waitForIdle()
            click("row-$FIRST")

            click("open-folio")

            onNodeWithTag("folio-dialog").assertExists()
            onNodeWithText("Frigobar", substring = true).assertExists()
            assertEquals(listOf(FIRST.toString()), source.reservationCalls)
        }
        runComposeUiTest {
            val connection = FolioConnection(folios(), reception = reception())
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", reception().permissions - READ) } }
            waitForIdle()
            click("row-$FIRST")

            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-folio").assertDoesNotExist()
        }
    }

    @Test
    fun folioButtonInReservationsNeedsReadAndOpensTheDialog() {
        val search =
            FakeReservationSearchSource().apply {
                reservations += searchItemOf("R-1", holderGuestId = HOLDER).copy(id = FIRST)
                holders[HOLDER] = HolderSummary("Ana Fictícia", null)
            }
        runComposeUiTest {
            val source = folios()
            val connection = FolioConnection(source, reception = reception(), search = search)
            setContent { ZenyTheme { AreaContent(Area.RESERVATIONS, connection, "A", setOf(RESERVATIONS_READ, READ)) } }
            waitForIdle()
            click("row-$FIRST")

            click("open-folio")

            onNodeWithTag("folio-dialog").assertExists()
            onNodeWithText("Frigobar", substring = true).assertExists()
        }
        runComposeUiTest {
            val connection = FolioConnection(folios(), reception = reception(), search = search)
            setContent { ZenyTheme { AreaContent(Area.RESERVATIONS, connection, "A", setOf(RESERVATIONS_READ)) } }
            waitForIdle()
            click("row-$FIRST")

            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-folio").assertDoesNotExist()
        }
    }

    @Test
    fun switchingReservationClosesTheDialogInReception() =
        runComposeUiTest {
            val source = folios()
            val reception = reception()
            lateinit var detail: ReservationDetailState
            setContent {
                val scope = rememberCoroutineScope()
                val panel = remember { DayPanelState(reception, scope, reception.permissions) }
                detail = remember { ReservationDetailState(reception, scope) }
                ZenyTheme { ReceptionScreen(panel, detail, folio = wiringOf(source)) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")
            onNodeWithTag("folio-dialog").assertExists()

            detail.open(SECOND.toString())
            waitForIdle()

            onNodeWithTag("folio-dialog").assertDoesNotExist()
            // Voltar à primeira reserva não traz o diálogo de volta.
            detail.open(FIRST.toString())
            waitForIdle()
            onNodeWithTag("folio-dialog").assertDoesNotExist()
        }

    @Test
    fun switchingReservationClosesTheDialogInReservations() =
        runComposeUiTest {
            val source = folios()
            val reception = reception()
            val search =
                FakeReservationSearchSource().apply {
                    reservations += searchItemOf("R-1", holderGuestId = HOLDER).copy(id = FIRST)
                    holders[HOLDER] = HolderSummary("Ana Fictícia", null)
                }
            lateinit var detail: ReservationDetailState
            setContent {
                val scope = rememberCoroutineScope()
                val state = remember { ReservationSearchState(search, scope).also { it.start() } }
                detail = remember { ReservationDetailState(reception, scope) }
                val actions =
                    ReservationsActions(setOf(RESERVATIONS_READ, READ), null, null, null, folio = wiringOf(source))
                ZenyTheme { ReservationsScreen(state, detail, actions) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")
            onNodeWithTag("folio-dialog").assertExists()

            detail.open(SECOND.toString())
            waitForIdle()

            onNodeWithTag("folio-dialog").assertDoesNotExist()
        }

    @Test
    fun reservationWithoutFolioSaysSoInTheDialog() =
        runComposeUiTest {
            val source = folios().apply { byReservation = { emptyList() } }
            val connection = FolioConnection(source, reception = reception())
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", reception().permissions) } }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")

            onNodeWithText("Esta reserva ainda não tem conta.").assertExists()
            click("folio-dialog-close")
            onNodeWithTag("folio-dialog").assertDoesNotExist()
        }

    @Test
    fun postButtonNeedsCreateAndAnOpenFolio() {
        runComposeUiTest {
            accounts(folios())
            openFolioTab()
            click("folio-row-$FOLIO_ID")
            onNodeWithTag("folio-balance").assertExists()
            onNodeWithTag("folio-post").assertDoesNotExist()
        }
        runComposeUiTest {
            accounts(folios(), permissions = setOf(READ, OPERATE, CREATE))
            openFolioTab()
            click("folio-row-$FOLIO_ID")
            onNodeWithTag("folio-post").assertExists()
        }
        runComposeUiTest {
            val source = folios().apply { folio = { folioOf(status = FolioResponse.Status.CLOSED) } }
            accounts(source, permissions = setOf(READ, OPERATE, CREATE, REVERSE))
            openFolioTab()
            click("folio-row-$FOLIO_ID")
            onNodeWithText("Situação: Conta encerrada").assertExists()
            onNodeWithTag("folio-post").assertDoesNotExist()
            onNodeWithTag("posting-actions-0").assertDoesNotExist()
        }
    }

    @Test
    fun reverseOnlyOnRegularNotReversedPostings() {
        val item = Uuid.random()
        val source =
            folios().apply {
                postings = {
                    listOf(
                        postingOf(item, "Bar"),
                        postingOf(description = "Taxa", type = "SERVICE_CHARGE", serviceChargeFor = item),
                        postingOf(description = "Lavanderia", reversedBy = Uuid.random()),
                        postingOf(description = "Estorno", type = "REVERSAL"),
                    )
                }
            }
        listOf(setOf(READ, OPERATE, CREATE), setOf(READ, OPERATE, REVERSE)).forEach { permissions ->
            runComposeUiTest {
                accounts(source, permissions = permissions)
                openFolioTab()
                click("folio-row-$FOLIO_ID")
                onNodeWithTag("posting-actions-0").assertExists()
                (1..3).forEach { onNodeWithTag("posting-actions-$it").assertDoesNotExist() }
            }
        }
        runComposeUiTest {
            accounts(source)
            openFolioTab()
            click("folio-row-$FOLIO_ID")
            onNodeWithTag("posting-0").assertExists()
            onNodeWithTag("posting-actions-0").assertDoesNotExist()
        }
    }

    @Test
    fun newPostingShowsInTheTableAfterConfirm() =
        runComposeUiTest {
            val source = folios()
            accounts(source, permissions = setOf(READ, OPERATE, CREATE))
            openFolioTab()
            click("folio-row-$FOLIO_ID")

            click("folio-post")
            onNodeWithTag("posting-dialog").assertExists()
            onNodeWithText("FRIGO — Frigobar").assertExists()
            click("posting-code-0")
            onNodeWithTag("posting-price").performTextInput("25,00")
            source.postings = {
                listOf(postingOf(description = "Frigobar", amount = "20.00"), postingOf(description = "Café"))
            }
            click("posting-submit")

            onNodeWithTag("posting-dialog").assertDoesNotExist()
            onNodeWithText("Café", substring = true).assertExists()
            assertEquals(1, source.postCalls.size)
            assertEquals(
                "25.00",
                source.postCalls
                    .single()
                    .input.unitPrice
                    .toApi(),
            )
        }

    @Test
    fun reversalApprovalOpensTheShellDialogFromTheAccountsTab() =
        runComposeUiTest {
            val source = folios().apply { requiresApproval = reversalApproval() }
            val connection = FolioConnection(source, cashier = FakeCashierSource())
            withApprovals { AreaContent(Area.ACCOUNTS, connection, "A", setOf(READ, OPERATE, CREATE)) }
            openFolioTab()
            click("folio-row-$FOLIO_ID")

            reverse(0)

            onNodeWithTag("approval-login").assertExists()
            assertEquals(listOf<String?>(null), source.reverseRuns.map { it.token })
        }

    @Test
    fun reversalApprovalOpensTheShellDialogFromReception() =
        runComposeUiTest {
            val source = folios().apply { requiresApproval = reversalApproval() }
            val connection = FolioConnection(source, reception = reception())
            withApprovals { AreaContent(Area.RECEPTION, connection, "A", reception().permissions + CREATE) }
            click("row-$FIRST")
            click("open-folio")

            reverse(0)

            onNodeWithTag("approval-login").assertExists()
            assertEquals(listOf<String?>(null), source.reverseRuns.map { it.token })
        }

    @Test
    fun actionsMenuFollowsThePostingNotThePosition() =
        runComposeUiTest {
            val frigobar = Uuid.random()
            val source = folios().apply { postings = { listOf(postingOf(frigobar, "Frigobar")) } }
            lateinit var state: FolioState
            setContent {
                val scope = rememberCoroutineScope()
                state =
                    remember {
                        FolioState(
                            wiringOf(source, setOf(READ, CREATE)),
                            scope,
                            FOLIO_ID.toString(),
                        ).also { it.start() }
                    }
                ZenyTheme { FolioPanel(state) }
            }
            waitForIdle()
            click("posting-actions-0")
            onNodeWithTag("posting-reverse-0").assertExists()

            source.postings = { listOf(postingOf(description = "Bar"), postingOf(frigobar, "Frigobar")) }
            state.reload()
            waitForIdle()

            onNodeWithTag("posting-reverse-0").assertDoesNotExist()
            onNodeWithTag("posting-reverse-1").assertExists()
        }

    private fun reversalApproval() = ApiError.ApprovalRequired("POSTING_REVERSAL", REVERSE, "posting", null, null)
}
