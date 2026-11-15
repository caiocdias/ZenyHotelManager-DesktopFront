package com.zeny.desktop.app.traces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val atDept = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val atReservation = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val atUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val atAll = setOf("frontdesk.traces.read", "frontdesk.traces.manage", "frontdesk.traces.resolve")

private fun atTrace(
    n: Int,
    status: String = "OPEN",
) = TraceResponse(
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(atDept.id),
    departmentName = atDept.name,
    dueOn = "2026-11-14",
    id = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0')),
    reservationId = Option.Some(atReservation),
    status = status,
    text = "Recado $n",
    version = 1,
)

/** Ataques à [TracesPanel] com clique de ponteiro: tentar de novo, texto de setores, estados do modo compacto. */
@OptIn(ExperimentalTestApi::class)
class TracesPanelAtaqueTest {
    private fun source(permissions: Set<String> = atAll) =
        FakeTracesSource().apply {
            this.permissions = permissions
            departments = listOf(atDept)
            reservationCodes[atReservation.toString()] = "R-77"
        }

    private fun ComposeUiTest.panel(
        source: FakeTracesSource,
        onNew: (() -> Unit)? = null,
        onEdit: (TraceRow) -> Unit = {},
        onOpenReservation: ((String) -> Unit)? = null,
        compact: Boolean = false,
    ): TracesListState {
        lateinit var list: TracesListState
        setContent {
            val scope = rememberCoroutineScope()
            list =
                remember {
                    TracesListState(source, scope, if (compact) atReservation.toString() else null).also { it.start() }
                }
            ZenyTheme {
                // Na tela, a seção compacta fica dentro do corpo rolável do detalhe.
                if (compact) {
                    Box(Modifier.width(380.dp).height(400.dp).verticalScroll(rememberScrollState())) {
                        TracesPanel(list, onNew, onEdit, onOpenReservation, true)
                    }
                } else {
                    TracesPanel(list, onNew, onEdit, onOpenReservation, false)
                }
            }
        }
        waitForIdle()
        return list
    }

    private fun ComposeUiTest.texts(): List<String> = collect(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun collect(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap { collect(it) }

    private fun pages(source: FakeTracesSource) = source.calls.filterIsInstance<TraceCall.Page>().size

    private fun serverFailure() = ApiError.Problem(500, "RAW_INTERNAL_CODE", null, emptyList())

    @Test
    fun panelBandRetryRefreshesAndClearsTheActionError() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            val list = panel(source)
            source.failNext(TraceOp.RESOLVE, serverFailure())
            onNodeWithTag("trace-resolve-0").performClick()
            waitForIdle()
            onNodeWithTag("traces-error").assertIsDisplayed()
            val before = pages(source)
            onNode(hasText("Tentar de novo") and hasAnyAncestor(hasTestTag("traces-error"))).performClick()
            waitForIdle()
            assertTrue(pages(source) > before, "o Tentar de novo da faixa não releu a lista")
            onNodeWithTag("traces-error").assertDoesNotExist()
            assertNull(list.error)
        }

    @Test
    fun compactBandRetryRefreshesAndClearsTheActionError() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            val list = panel(source, compact = true)
            source.failNext(TraceOp.RESOLVE, serverFailure())
            onNodeWithTag("trace-resolve-0").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithTag("traces-error").assertIsDisplayed()
            val before = pages(source)
            onNode(hasText("Tentar de novo") and hasAnyAncestor(hasTestTag("traces-error"))).performClick()
            waitForIdle()
            assertTrue(pages(source) > before, "o Tentar de novo da faixa compacta não releu a lista")
            onNodeWithTag("traces-error").assertDoesNotExist()
            assertNull(list.error)
        }

    @Test
    fun compactLoadErrorRetryReloadsAndShowsTheCards() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            source.failNext(TraceOp.PAGE, serverFailure())
            panel(source, compact = true)
            onNodeWithTag("table-retry").assertIsDisplayed()
            onNodeWithText("Recado 1").assertDoesNotExist()
            onNodeWithTag("table-retry").performClick()
            waitForIdle()
            onNodeWithText("Recado 1").assertIsDisplayed()
            onNodeWithTag("table-retry").assertDoesNotExist()
        }

    @Test
    fun compactEmptyShowsReservationMessageOnly() =
        runComposeUiTest {
            panel(source(), compact = true)
            onNodeWithText("Nenhum recado para esta reserva.").assertIsDisplayed()
            onNodeWithTag("load-more").assertDoesNotExist()
            onNodeWithTag("table-loading").assertDoesNotExist()
        }

    @Test
    fun compactShowsLoadingUntilThePageArrives() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            val gate = CompletableDeferred<Unit>()
            source.pageGates.addLast(gate)
            panel(source, compact = true)
            onNodeWithTag("table-loading").assertIsDisplayed()
            onNodeWithText("Nenhum recado para esta reserva.").assertDoesNotExist()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("table-loading").assertDoesNotExist()
            onNodeWithText("Recado 1").assertIsDisplayed()
        }

    @Test
    fun compactLoadMoreFetchesTheNextPageByClick() =
        runComposeUiTest {
            val source = source()
            for (n in 1..51) source.seed(atTrace(n))
            val list = panel(source, compact = true)
            assertEquals(50, list.table.rows.size)
            onNodeWithTag("load-more").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(51, list.table.rows.size)
            onNodeWithTag("load-more").assertDoesNotExist()
            onNodeWithText("Recado 51").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun compactLoadMoreFailureShowsRetryAndKeepsTheCards() =
        runComposeUiTest {
            val source = source()
            for (n in 1..51) source.seed(atTrace(n))
            val list = panel(source, compact = true)
            source.failNext(TraceOp.PAGE, serverFailure())
            onNodeWithTag("load-more").performScrollTo().performClick()
            waitForIdle()
            assertEquals(50, list.table.rows.size)
            onNodeWithTag("table-retry").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(51, list.table.rows.size, "o tentar de novo não buscou a página que falhou")
        }

    @Test
    fun deniedDepartmentsTextOnlyWhenNewIsNull() =
        runComposeUiTest {
            val denied = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
            val withNew = source().apply { failNext(TraceOp.DEPARTMENTS, denied) }
            panel(withNew, onNew = {})
            onNodeWithText(TraceText.DEPARTMENTS_DENIED).assertDoesNotExist()
            onNodeWithTag("trace-new").assertExists()
        }

    @Test
    fun deniedDepartmentsTextShownWithoutNewButNotInCompact() =
        runComposeUiTest {
            val denied = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
            panel(source().apply { failNext(TraceOp.DEPARTMENTS, denied) }, compact = true)
            onNodeWithText(TraceText.DEPARTMENTS_DENIED).assertDoesNotExist()
        }

    @Test
    fun rowActionsDoNotOpenTheReservation() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            var opened: String? = null
            val edited = mutableListOf<String>()
            panel(source, onEdit = { edited += it.id }, onOpenReservation = { opened = it })
            onNodeWithTag("trace-edit-0").assertIsDisplayed().performClick()
            assertEquals(1, edited.size)
            assertNull(opened, "Editar também abriu a reserva")
            onNodeWithTag("trace-resolve-0").assertIsDisplayed().performClick()
            waitForIdle()
            assertNull(opened, "Resolver também abriu a reserva")
            assertTrue(source.calls.any { it is TraceCall.Resolve })
        }

    @Test
    fun resolveIsBlockedWhileAnotherResolveIsInFlight() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            val gate = CompletableDeferred<Unit>()
            source.resolveGate = gate
            panel(source)
            onNodeWithTag("trace-resolve-0").performClick()
            waitForIdle()
            onNodeWithTag("trace-resolve-0").performClick()
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()
            assertEquals(1, source.calls.count { it is TraceCall.Resolve }, "duplo clique resolveu duas vezes")
        }

    @Test
    fun noUuidRawCodeOrStatusCodeAnywhereInPanelOrCompact() =
        runComposeUiTest {
            val source = source().apply { seed(atTrace(1)) }
            panel(source, compact = true)
            source.failNext(TraceOp.RESOLVE, serverFailure())
            onNodeWithTag("trace-resolve-0").performScrollTo().performClick()
            waitForIdle()
            val all = texts().joinToString("\n")
            assertFalse(atUuid.containsMatchIn(all), all)
            assertFalse("RAW_INTERNAL_CODE" in all, all)
            assertFalse("OPEN" in all, all)
            assertFalse("TRACE_" in all, all)
        }
}
