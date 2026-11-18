package com.zeny.desktop.app.reception

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.traces.FakeTracesSource
import com.zeny.desktop.app.traces.TraceCall
import com.zeny.desktop.app.traces.TraceFormState
import com.zeny.desktop.app.traces.TraceOp
import com.zeny.desktop.app.traces.TraceText
import com.zeny.desktop.app.traces.TracesListState
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val atDept = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val atAllTraces = setOf("frontdesk.traces.read", "frontdesk.traces.manage", "frontdesk.traces.resolve")

private val atPlanId = Uuid.parse("40000000-0000-0000-0000-000000000001")

private fun atResId(n: Int) = Uuid.parse("20000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

private val atGuestId = Uuid.parse("30000000-0000-0000-0000-000000000001")

private fun atListItem(n: Int = 1) =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-$n",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "R-$n",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = atGuestId,
        id = atResId(n),
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = atPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

private fun atDetail(n: Int = 1) =
    ReservationDetailResponse(
        bookingCode = "B-$n",
        holderGuestId = atGuestId,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = emptyList(),
                code = "R-$n",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = atResId(n),
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = atPlanId,
                roomTotal = "500.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private fun atTrace(
    n: Int,
    reservation: Int?,
    text: String = "Levar toalhas $n",
) = TraceResponse(
    createdAt = "2026-11-13T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(atDept.id),
    departmentName = atDept.name,
    dueOn = "2026-11-13",
    id = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0')),
    reservationId = reservation?.let { Option.Some(atResId(it)) } ?: Option.Undefined,
    status = "OPEN",
    text = text,
    version = 1,
)

/** Ataques à recepção com recados: foco da janela, permissão, 409 entre aba e seção, formulário e vazamento de id. */
@OptIn(ExperimentalTestApi::class)
class ReceptionTracesAtaqueTest {
    private val sections = mutableListOf<TracesListState>()
    private val forms = mutableListOf<TraceFormState>()
    private val atUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private class Window(
        start: Boolean,
    ) : WindowInfo {
        var focused by mutableStateOf(start)
        override val isWindowFocused: Boolean get() = focused
    }

    private fun reception() =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
            types += roomType(typeId(1), "Standard")
            pages[DayList.ARRIVALS to null] = Page(listOf(atListItem(1), atListItem(2)), null)
            ratePlans[atPlanId.toString()] = "Tarifa Balcão"
            for (n in 1..2) reservations[atResId(n).toString()] = atDetail(n)
            holders[atGuestId.toString()] = HolderSummary("Hóspede 1", "CPF ***.***.***-11")
        }

    private fun traces(
        permissions: Set<String> = atAllTraces,
        seeded: Boolean = true,
    ) = FakeTracesSource().apply {
        this.permissions = permissions
        departments = listOf(atDept)
        reservationCodes[atResId(1).toString()] = "R-1"
        reservationCodes[atResId(2).toString()] = "R-2"
        if (seeded) {
            seed(atTrace(1, 1))
            seed(atTrace(2, 2))
            seed(atTrace(3, null, "Trocar lâmpada"))
        }
    }

    private fun ComposeUiTest.screen(
        traces: FakeTracesSource,
        window: Window? = null,
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { reception().let { DayPanelState(it, scope, it.permissions) } }
            val detail = remember { ReservationDetailState(reception(), scope) }
            val wired =
                remember {
                    val list = TracesListState(traces, scope, traces.permissions).also { it.start() }
                    ReceptionTraces(
                        list,
                        forReservation = { id ->
                            TracesListState(traces, scope, traces.permissions, id).also { sections += it }
                        },
                        form = { target, onSaved ->
                            TraceFormState(traces, scope, target, list.departments, list.departmentsDenied, onSaved)
                                .also { forms += it }
                        },
                    )
                }
            val content = @Composable { ZenyTheme { ReceptionScreen(panel, detail, traces = wired) } }
            if (window == null) content() else CompositionLocalProvider(LocalWindowInfo provides window) { content() }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openReservation(n: Int) {
        onNodeWithTag("row-${atResId(n)}").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.inDetail(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("reservation-detail")))

    private fun ComposeUiTest.inDetailTag(tag: String) =
        onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("reservation-detail")))

    private fun ComposeUiTest.inTabTag(tag: String) =
        onNode(hasTestTag(tag) and !hasAnyAncestor(hasTestTag("reservation-detail")))

    private fun ComposeUiTest.texts(): List<String> = collect(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun collect(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap { collect(it) }

    private fun pagesOf(
        traces: FakeTracesSource,
        from: Int,
    ) = traces.calls.drop(from).filterIsInstance<TraceCall.Page>()

    @Test
    fun windowFocusRefreshesTabAndSectionButNotTheInitialEmission() =
        runComposeUiTest {
            val traces = traces()
            val window = Window(start = true)
            screen(traces, window)
            openReservation(1)
            val before = traces.calls.size
            waitForIdle()
            assertEquals(before, traces.calls.size, "a emissão inicial do foco atualizou os recados")

            window.focused = false
            waitForIdle()
            assertEquals(before, traces.calls.size, "perder o foco atualizou os recados")

            window.focused = true
            waitForIdle()
            val pages = pagesOf(traces, before)
            assertTrue(pages.any { it.filter.reservationId == null }, "o foco não atualizou a aba")
            assertTrue(pages.any { it.filter.reservationId == atResId(1).toString() }, "o foco não atualizou a seção")
        }

    @Test
    fun windowFocusRefreshShowsTheRemoteChangeInTabAndSection() =
        runComposeUiTest {
            val traces = traces()
            val window = Window(start = true)
            screen(traces, window)
            openReservation(1)
            traces.seed(atTrace(4, 1, "Recado remoto"))
            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()
            inDetail("Recado remoto").assertExists()
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            inTabTag("traces-panel").assertExists()
            onNode(hasText("Recado remoto") and !hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
        }

    @Test
    fun windowFocusDoesNotReadSectionOfTheClosedReservation() =
        runComposeUiTest {
            val traces = traces()
            val window = Window(start = true)
            screen(traces, window)
            val before = traces.calls.size
            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()
            val pages = pagesOf(traces, before)
            assertTrue(pages.isNotEmpty(), "a aba não atualizou")
            assertTrue(pages.all { it.filter.reservationId == null }, "leu seção sem reserva aberta")
        }

    @Test
    fun sectionVanishesWhenOnlyManagePermissionAndIdsNeverLeak() =
        runComposeUiTest {
            screen(traces(setOf("frontdesk.traces.manage", "frontdesk.traces.resolve")))
            openReservation(1)
            assertEquals(0, sections.size, "criou a seção sem leitura de recados")
            onNode(hasText("Recados")).assertDoesNotExist()
            onNodeWithText("Levar toalhas 1").assertDoesNotExist()
        }

    @Test
    fun conflictOnResolveInTabRefreshesOpenSection() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            // Outra pessoa resolve o recado 1: a aba ainda o mostra aberto.
            runBlocking { traces.resolve(atTrace(1, 1).id.toString()) }
            val before = traces.calls.size
            inTabTag("trace-resolve-0").performClick()
            waitForIdle()
            onNodeWithText("Este recado já foi resolvido.").assertExists()
            val pages = pagesOf(traces, before)
            assertTrue(
                pages.any { it.filter.reservationId == atResId(1).toString() },
                "a seção não atualizou após o 409",
            )
            assertEquals(
                "Resolvido",
                sections[0]
                    .table.rows
                    .single()
                    .status.label,
            )
            inDetailTag("trace-resolve-0").assertDoesNotExist()
        }

    @Test
    fun conflictOnResolveInSectionRefreshesTab() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            runBlocking { traces.resolve(atTrace(1, 1).id.toString()) }
            // A seção ainda mostra o recado aberto; a aba mostra apenas o que há de aberto agora.
            val before = traces.calls.size
            inDetailTag("trace-resolve-0").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            inDetail("Este recado já foi resolvido.").assertExists()
            val pages = pagesOf(traces, before)
            assertTrue(pages.any { it.filter.reservationId == null }, "a aba não atualizou após o 409")
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            onNode(
                hasText("Levar toalhas 1") and !hasAnyAncestor(hasTestTag("reservation-detail")),
            ).assertDoesNotExist()
        }

    @Test
    fun failedResolveWithoutConflictDoesNotRefreshTheOtherList() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            traces.failNext(TraceOp.RESOLVE, ApiError.Problem(500, "BOOM", null, emptyList()))
            val before = traces.calls.size
            inDetailTag("trace-resolve-0").performScrollTo().performClick()
            waitForIdle()
            val pages = pagesOf(traces, before)
            assertTrue(pages.none { it.filter.reservationId == null }, "falha comum atualizou a aba")
        }

    @Test
    fun cancelClosesFormStateAndDialog() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            inDetailTag("trace-new").performScrollTo().performClick()
            waitForIdle()
            val state = forms.single()
            onNodeWithTag("trace-text").performTextInput("Rascunho")
            assertEquals("Rascunho", state.text)
            onNodeWithTag("trace-cancel").performClick()
            waitForIdle()
            onNodeWithTag("trace-form").assertDoesNotExist()
            state.text = "depois de fechar"
            assertEquals("Rascunho", state.text, "o estado do formulário seguiu aberto depois do cancelar")
            state.save()
            waitForIdle()
            assertTrue(traces.calls.none { it is TraceCall.Create }, "salvou depois de fechado")
        }

    @Test
    fun editFromSectionOpensTheFormAndReopeningStartsClean() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            inDetailTag("trace-edit-0").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            onNode(hasText("Editar recado") and hasAnyAncestor(hasTestTag("trace-form"))).assertExists()
            onNodeWithTag("trace-cancel").performClick()
            waitForIdle()
            inDetailTag("trace-new").performScrollTo().performClick()
            waitForIdle()
            assertEquals(2, forms.size)
            assertNotSame(forms[0], forms[1])
            onNode(hasText("Novo recado") and hasAnyAncestor(hasTestTag("trace-form"))).assertExists()
            assertEquals("", forms[1].text)
        }

    @Test
    fun sectionWithoutTracesShowsEmptyMessageAndNoUuidAnywhere() =
        runComposeUiTest {
            screen(traces(seeded = false))
            openReservation(1)
            inDetail("Nenhum recado para esta reserva.").assertExists()
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            val all = texts().joinToString("\n")
            assertFalse(atUuid.containsMatchIn(all), all)
            assertFalse("OPEN" in all)
        }

    @Test
    fun noUuidRawCodeOrTraceIdWithTabAndSectionOpen() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            traces.failNext(TraceOp.RESOLVE, ApiError.Problem(500, "RAW_CODE_X", null, emptyList()))
            inDetailTag("trace-resolve-0").performScrollTo().performClick()
            waitForIdle()
            val all = texts().joinToString("\n")
            assertFalse(atUuid.containsMatchIn(all), all)
            assertFalse("RAW_CODE_X" in all, all)
            assertFalse("OPEN" in all, all)
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            val tab = texts().joinToString("\n")
            assertFalse(atUuid.containsMatchIn(tab), tab)
            assertFalse("OPEN" in tab, tab)
        }

    @Test
    fun deniedDepartmentsHideNewInSectionAndShowTextInTabOnly() =
        runComposeUiTest {
            // 403 de permissão vale para a aba e para a seção: cada lista lê os setores por conta própria.
            val denied = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
            val traces = traces().apply { repeat(2) { failNext(TraceOp.DEPARTMENTS, denied) } }
            screen(traces)
            openReservation(1)
            inDetailTag("trace-new").assertDoesNotExist()
            onNodeWithText(TraceText.DEPARTMENTS_DENIED).assertDoesNotExist()
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            onNodeWithText(TraceText.DEPARTMENTS_DENIED).assertExists()
            onNodeWithTag("trace-new").assertDoesNotExist()
        }

    @Test
    fun sectionRetryAfterLoadErrorWorksByPointerClick() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            traces.failNext(TraceOp.PAGE, ApiError.Problem(500, "BOOM", null, emptyList()))
            openReservation(1)
            inDetailTag("table-retry").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            inDetail("Levar toalhas 1").assertExists()
        }
}
