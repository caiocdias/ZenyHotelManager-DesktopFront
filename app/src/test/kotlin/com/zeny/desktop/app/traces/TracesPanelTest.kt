package com.zeny.desktop.app.traces

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val panelDept = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val panelReservation = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val panelUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val panelAll = setOf("frontdesk.traces.read", "frontdesk.traces.manage", "frontdesk.traces.resolve")

private fun panelTrace(
    n: Int,
    status: String = "OPEN",
    reservation: Boolean = true,
) = TraceResponse(
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(panelDept.id),
    departmentName = panelDept.name,
    dueOn = "2026-11-14",
    id = Uuid.parse("50000000-0000-0000-0000-00000000000$n"),
    reservationId = if (reservation) Option.Some(panelReservation) else Option.Undefined,
    status = status,
    text = "Levar toalhas $n",
    version = 1,
)

@OptIn(ExperimentalTestApi::class)
class TracesPanelTest {
    private fun source(permissions: Set<String> = panelAll) =
        FakeTracesSource().apply {
            this.permissions = permissions
            departments = listOf(panelDept)
            reservationCodes[panelReservation.toString()] = "R-77"
        }

    private fun ComposeUiTest.panel(
        source: FakeTracesSource,
        onNew: (() -> Unit)? = null,
        onOpenReservation: ((String) -> Unit)? = null,
        compact: Boolean = false,
        setup: (TracesListState) -> Unit = {},
    ): TracesListState {
        lateinit var list: TracesListState
        setContent {
            val scope = rememberCoroutineScope()
            list =
                remember {
                    TracesListState(source, scope, source.permissions).also {
                        setup(it)
                        it.start()
                    }
                }
            ZenyTheme { TracesPanel(list, onNew, {}, onOpenReservation, compact) }
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

    @Test
    fun panelShowsRowsWithoutIds() =
        runComposeUiTest {
            val source = source().apply { seed(panelTrace(1)) }
            panel(source)
            val all = texts().joinToString("\n")
            assertTrue("Levar toalhas 1" in all)
            assertTrue("Reserva R-77" in all)
            assertTrue("Aberto" in all)
            assertTrue("14/11/2026" in all)
            assertFalse(panelUuid.containsMatchIn(all))
            assertFalse("OPEN" in all)
        }

    @Test
    fun resolveButtonOnlyForOpenWithPermission() =
        runComposeUiTest {
            val source =
                source().apply {
                    seed(panelTrace(1))
                    seed(panelTrace(2, status = "RESOLVED"))
                }
            panel(source) { it.setStatus(null) }
            onNodeWithTag("trace-resolve-0").assertExists()
            onNodeWithTag("trace-edit-0").assertExists()
            onNodeWithTag("trace-resolve-1").assertDoesNotExist()
            onNodeWithTag("trace-edit-1").assertDoesNotExist()
            onNodeWithTag("trace-resolve-0").performClick()
            waitForIdle()
            assertTrue(source.calls.any { it is TraceCall.Resolve })
        }

    @Test
    fun noButtonsWithoutPermission() =
        runComposeUiTest {
            val source = source(setOf("frontdesk.traces.read")).apply { seed(panelTrace(1)) }
            panel(source)
            onNodeWithTag("trace-resolve-0").assertDoesNotExist()
            onNodeWithTag("trace-edit-0").assertDoesNotExist()
            onNodeWithTag("trace-new").assertDoesNotExist()
        }

    @Test
    fun deniedDepartmentsHideFilterAndShowText() =
        runComposeUiTest {
            val source =
                source().apply {
                    failNext(
                        TraceOp.DEPARTMENTS,
                        ApiError.Problem(403, "FORBIDDEN", null, emptyList()),
                    )
                }
            panel(source)
            onNodeWithTag("traces-department-filter").assertDoesNotExist()
            onNodeWithText(TraceText.DEPARTMENTS_DENIED).assertExists()
        }

    @Test
    fun newButtonCallsOnNew() =
        runComposeUiTest {
            var clicked = 0
            panel(source(), onNew = { clicked++ })
            onNodeWithTag("trace-new").performClick()
            assertEquals(1, clicked)
        }

    @Test
    fun rowClickOpensReservation() =
        runComposeUiTest {
            val source = source().apply { seed(panelTrace(1)) }
            var opened: String? = null
            panel(source, onOpenReservation = { opened = it })
            onNodeWithText("Levar toalhas 1").performClick()
            assertEquals(panelReservation.toString(), opened)
        }

    @Test
    fun compactHasNoFiltersAndNoLinkColumn() =
        runComposeUiTest {
            val source = source().apply { seed(panelTrace(1)) }
            panel(source, compact = true)
            onNodeWithTag("traces-department-filter").assertDoesNotExist()
            onNodeWithTag("traces-status-filter").assertDoesNotExist()
            onNodeWithTag("header-link").assertDoesNotExist()
            onNodeWithText("Levar toalhas 1").assertExists()
            onNodeWithText("14/11/2026 · Governança · Aberto").assertExists()
        }

    @Test
    fun errorBandShowsRetry() =
        runComposeUiTest {
            val source = source().apply { seed(panelTrace(1)) }
            val list = panel(source)
            source.failNext(TraceOp.RESOLVE, ApiError.Problem(409, "TRACE_ALREADY_RESOLVED", null, emptyList()))
            onNodeWithTag("trace-resolve-0").performClick()
            waitForIdle()
            onNodeWithTag("traces-error").assertExists()
            onNodeWithText("Este recado já foi resolvido.").assertExists()
            assertTrue(list.error != null)
        }
}
