package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.traces.FakeTracesSource
import com.zeny.desktop.app.traces.TraceCall
import com.zeny.desktop.app.traces.TraceFormState
import com.zeny.desktop.app.traces.TraceTarget
import com.zeny.desktop.app.traces.TracesListState
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
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val trDept = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val allTraces = setOf("frontdesk.traces.read", "frontdesk.traces.manage", "frontdesk.traces.resolve")

private val trPlanId = Uuid.parse("40000000-0000-0000-0000-000000000001")

private fun trResId(n: Int) = Uuid.parse("20000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

private val trGuestId = Uuid.parse("30000000-0000-0000-0000-000000000001")

private fun trListItem(n: Int = 1) =
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
        holderGuestId = trGuestId,
        id = trResId(n),
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = trPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

private fun trDetail(n: Int = 1) =
    ReservationDetailResponse(
        bookingCode = "B-$n",
        holderGuestId = trGuestId,
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
                id = trResId(n),
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = trPlanId,
                roomTotal = "500.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private fun trace(
    n: Int,
    reservation: Int?,
    text: String = "Levar toalhas $n",
) = TraceResponse(
    createdAt = "2026-11-13T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(trDept.id),
    departmentName = trDept.name,
    dueOn = "2026-11-13",
    id = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0')),
    reservationId = reservation?.let { Option.Some(trResId(it)) } ?: Option.Undefined,
    status = "OPEN",
    text = text,
    version = 1,
)

/** A recepção com os recados: a aba "Recados" e a seção do detalhe da reserva aberta. */
@OptIn(ExperimentalTestApi::class)
class ReceptionTracesTest {
    private val sections = mutableListOf<TracesListState>()
    private val targets = mutableListOf<TraceTarget>()

    private fun reception() =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
            types += roomType(typeId(1), "Standard")
            pages[DayList.ARRIVALS to null] = Page(listOf(trListItem(1), trListItem(2)), null)
            ratePlans[trPlanId.toString()] = "Tarifa Balcão"
            for (n in 1..2) {
                reservations[trResId(n).toString()] = trDetail(n)
            }
            holders[trGuestId.toString()] = HolderSummary("Hóspede 1", "CPF ***.***.***-11")
        }

    private fun traces(permissions: Set<String> = allTraces) =
        FakeTracesSource().apply {
            this.permissions = permissions
            departments = listOf(trDept)
            reservationCodes[trResId(1).toString()] = "R-1"
            reservationCodes[trResId(2).toString()] = "R-2"
            seed(trace(1, 1))
            seed(trace(2, 2))
            seed(trace(3, null, "Trocar lâmpada"))
        }

    private fun ComposeUiTest.screen(
        traces: FakeTracesSource,
        reception: FakeReceptionSource = reception(),
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(reception, scope) }
            val detail = remember { ReservationDetailState(reception, scope) }
            val wired =
                remember {
                    val list = TracesListState(traces, scope).also { it.start() }
                    ReceptionTraces(
                        list,
                        forReservation = { id -> TracesListState(traces, scope, id).also { sections += it } },
                        form = { target ->
                            targets += target
                            TraceFormState(traces, scope, target, list.departments, list.departmentsDenied) {}
                        },
                    )
                }
            ZenyTheme { ReceptionScreen(panel, detail, traces = wired) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openReservation(n: Int) {
        onNodeWithTag("row-${trResId(n)}").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.inDetail(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("reservation-detail")))

    private fun ComposeUiTest.inDetailTag(tag: String) =
        onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("reservation-detail")))

    @Test
    fun tracesTabHiddenWithoutReadPermission() =
        runComposeUiTest {
            screen(traces(setOf("frontdesk.traces.manage")))
            onNodeWithTag("tab-ARRIVALS").assertExists()
            onNodeWithTag("tab-TRACES").assertDoesNotExist()
            openReservation(1)
            onNodeWithText("Recados").assertDoesNotExist()
        }

    @Test
    fun tracesTabShowsPanel() =
        runComposeUiTest {
            screen(traces())
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            onNodeWithTag("traces-panel").assertExists()
            onNodeWithText("Trocar lâmpada").assertExists()
            onNodeWithTag("row-${trResId(1)}").assertDoesNotExist()
            onNodeWithTag("tab-ARRIVALS").performClick()
            waitForIdle()
            onNodeWithTag("traces-panel").assertDoesNotExist()
            onNodeWithTag("row-${trResId(1)}").assertExists()
        }

    @Test
    fun newTraceFromTabIsForRoom() =
        runComposeUiTest {
            screen(traces())
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            onNodeWithTag("trace-new").performClick()
            waitForIdle()
            onNodeWithTag("trace-form").assertExists()
            assertEquals(listOf<TraceTarget>(TraceTarget.ForRoom), targets)
        }

    @Test
    fun detailShowsReservationTraces() =
        runComposeUiTest {
            screen(traces())
            openReservation(1)
            inDetail("Recados").assertExists()
            inDetail("Levar toalhas 1").assertExists()
            onNodeWithText("Levar toalhas 2").assertDoesNotExist()
        }

    @Test
    fun newTraceFromDetailLinksReservation() =
        runComposeUiTest {
            screen(traces())
            openReservation(1)
            inDetailTag("trace-new").performClick()
            waitForIdle()
            assertEquals(listOf<TraceTarget>(TraceTarget.ForReservation(trResId(1).toString(), "R-1")), targets)
            onNodeWithText("Reserva R-1").assertExists()
        }

    @Test
    fun savingRefreshesTabAndDetail() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            inDetailTag("trace-new").performClick()
            waitForIdle()
            onNodeWithTag("trace-text").performTextInput("Chamar o chaveiro")
            onNodeWithTag("department-0").performClick()
            val before = traces.calls.size
            onNodeWithTag("trace-save").performClick()
            waitForIdle()
            val pages = traces.calls.drop(before).filterIsInstance<TraceCall.Page>()
            assertTrue(pages.any { it.filter.reservationId == trResId(1).toString() }, "a seção não atualizou")
            assertTrue(pages.any { it.filter.reservationId == null }, "a aba não atualizou")
            assertTrue(sections[0].table.rows.any { it.text == "Chamar o chaveiro" }, "a seção não mostra o novo")
            onNodeWithTag("trace-form").assertDoesNotExist()
        }

    @Test
    fun headerRefreshUpdatesTraces() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            traces.seed(trace(4, 1, "Recado novo"))
            val before = traces.calls.size
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            val pages = traces.calls.drop(before).filterIsInstance<TraceCall.Page>()
            assertTrue(pages.any { it.filter.reservationId == null }, "a aba não atualizou")
            assertTrue(pages.any { it.filter.reservationId == trResId(1).toString() }, "a seção não atualizou")
            onNode(hasText("Recado novo") and !hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
            inDetail("Recado novo").assertExists()
        }

    @Test
    fun switchingReservationClosesPreviousSection() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            assertEquals(1, sections.size)
            openReservation(2)
            assertEquals(2, sections.size)
            val before = traces.calls.size
            sections[0].refresh()
            waitForIdle()
            assertEquals(before, traces.calls.size, "a seção anterior ainda carrega")
            inDetail("Levar toalhas 2").assertExists()
            onNodeWithText("Levar toalhas 1").assertDoesNotExist()
        }

    @Test
    fun resolvingRefreshesTabAndDetail() =
        runComposeUiTest {
            val traces = traces()
            screen(traces)
            openReservation(1)
            val section = trResId(1).toString()

            // Resolver na seção atualiza a aba.
            var before = traces.calls.size
            inDetailTag("trace-resolve-0").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            var pages = traces.calls.drop(before).filterIsInstance<TraceCall.Page>()
            assertTrue(pages.any { it.filter.reservationId == null }, "a aba não atualizou")

            // Resolver na aba atualiza a seção.
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            before = traces.calls.size
            onNode(hasTestTag("trace-resolve-0") and !hasAnyAncestor(hasTestTag("reservation-detail"))).performClick()
            waitForIdle()
            pages = traces.calls.drop(before).filterIsInstance<TraceCall.Page>()
            assertTrue(pages.any { it.filter.reservationId == section }, "a seção não atualizou")
        }
}
