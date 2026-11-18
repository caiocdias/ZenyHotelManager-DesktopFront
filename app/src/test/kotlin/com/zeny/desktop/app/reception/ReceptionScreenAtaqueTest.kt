package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.ReservationStateResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val FULL_DOCUMENT = "123.456.789-34"
private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private val planUuid = Uuid.parse("40000000-0000-0000-0000-000000000001")

private fun reservationUuid(n: Int) = Uuid.parse("20000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

private fun guestUuid(n: Int) = Uuid.parse("30000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

private fun listItem(n: Int) =
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
        holderGuestId = guestUuid(n),
        id = reservationUuid(n),
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = Uuid.random(),
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

private fun stateOf(status: String) =
    ReservationStateResponse(
        additionalPackagesTotal = "0.00",
        adults = 2,
        arrival = "2026-11-13",
        cancellationPolicyId = Uuid.random(),
        cancellationPolicyVersion = 1,
        childAges = emptyList(),
        departure = "2026-11-15",
        guaranteePolicyId = Uuid.random(),
        guaranteePolicyVersion = 1,
        hasSpecialRequests = false,
        nights = emptyList(),
        ratePlanId = Uuid.random(),
        roomTotal = "500.00",
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = status,
        stayGuests = emptyList(),
        total = "500.00",
    )

private fun historyEntry(
    kind: String,
    occurredAt: String,
    version: Long,
) = ReservationHistoryResponse(
    actorId = Option.Some(Uuid.random()),
    after = stateOf("CONFIRMED"),
    approvalId = Option.Undefined,
    before = stateOf("CONFIRMED"),
    businessDate = "2026-11-13",
    changedFields = emptyList(),
    id = Uuid.random(),
    kind = kind,
    occurredAt = occurredAt,
    version = version,
)

private fun detailOf(n: Int) =
    ReservationDetailResponse(
        bookingCode = "B-$n",
        holderGuestId = guestUuid(n),
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
                id = reservationUuid(n),
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = planUuid,
                roomTotal = "500.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private const val RAW_CODE = "RAW_SERVER_CODE_XYZ"
private const val RAW_JSON = "{\"type\":\"urn:zeny\"}"

private fun rawProblem() = ApiError.Problem(500, RAW_CODE, RAW_JSON, emptyList())

private fun assignmentOf(
    reason: String = "INITIAL",
    release: String? = null,
    active: Boolean = false,
) = RoomAssignmentResponse(
    active = active,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = false,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = reason,
    releaseReason = release?.let { Option.Some(it) } ?: Option.Undefined,
    reservationId = reservationUuid(1),
    roomId = Uuid.random(),
    roomNumber = Option.Undefined,
    startsOn = "2026-11-13",
)

@OptIn(ExperimentalTestApi::class)
class ReceptionScreenAtaqueTest {
    private fun source() =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
            types += roomType(typeId(1), "Standard")
            pages[DayList.ARRIVALS to null] = Page(listOf(listItem(1), listItem(2)), null)
            pages[DayList.DEPARTURES to null] = Page(listOf(listItem(3)), null)
            ratePlans[planUuid.toString()] = "Tarifa Balcão"
            for (n in 1..3) {
                reservations[reservationUuid(n).toString()] = detailOf(n)
                holders[guestUuid(n).toString()] = HolderSummary("Hóspede $n", "CPF ***.***.***-$n$n")
                histories[reservationUuid(n).toString()] =
                    listOf(historyEntry("CONFIRM", "2026-11-11T12:00:00Z", 2))
            }
        }

    private fun ComposeUiTest.screen(source: FakeReceptionSource): Pair<DayPanelState, ReservationDetailState> {
        lateinit var panel: DayPanelState
        lateinit var detail: ReservationDetailState
        setContent {
            val scope = rememberCoroutineScope()
            panel = remember { DayPanelState(source, scope, source.permissions) }
            detail = remember { ReservationDetailState(source, scope) }
            ZenyTheme { ReceptionScreen(panel, detail) }
        }
        waitForIdle()
        return panel to detail
    }

    private fun ComposeUiTest.texts(): List<String> = collect(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun collect(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } +
            node.config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
                ?.let { listOf(it) }
                .orEmpty() +
            node.children.flatMap { collect(it) }

    private fun ComposeUiTest.inDetail(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("reservation-detail")))

    private fun ComposeUiTest.open(n: Int) {
        onNodeWithTag("row-${reservationUuid(n)}").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.assertClean() {
        val all = texts()
        assertTrue(all.none { uuidRegex.containsMatchIn(it) }, "UUID na tela: $all")
        assertTrue(all.none { RAW_CODE in it || "{" in it || "urn:zeny" in it }, "erro cru na tela: $all")
        assertTrue(all.none { FULL_DOCUMENT in it }, "documento completo: $all")
    }

    @Test
    fun clickingSecondRowOpensItsDetail() =
        runComposeUiTest {
            screen(source())
            open(2)
            onNode(hasText("R-2") and hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
            inDetail("Hóspede 2").assertExists()
            inDetail("CPF ***.***.***-22").assertExists()
            inDetail("Hóspede 1").assertDoesNotExist()
        }

    @Test
    fun noDetailPanelBeforeClick() =
        runComposeUiTest {
            screen(source())
            onNodeWithTag("reservation-detail").assertDoesNotExist()
            onNodeWithText("Fechar").assertDoesNotExist()
        }

    @Test
    fun switchingToAnotherRowReplacesDetail() =
        runComposeUiTest {
            screen(source())
            open(1)
            open(2)
            inDetail("Hóspede 2").assertExists()
            inDetail("Hóspede 1").assertDoesNotExist()
        }

    @Test
    fun detailShowsLoadingWhileReservationIsHeld() =
        runComposeUiTest {
            val source = source().apply { holdReservation = true }
            screen(source)
            open(1)
            onNode(hasText("Carregando…") and hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
            onNodeWithText("Alocações").assertDoesNotExist()
            onNodeWithText("Fechar").assertExists()
        }

    @Test
    fun detailErrorIsFriendlyAndRetries() =
        runComposeUiTest {
            val source = source().apply { reservationError = rawProblem() }
            screen(source)
            open(1)
            assertClean()
            onNodeWithTag("reservation-detail").assertExists()
            onNode(hasText("Tentar de novo") and hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
            source.reservationError = null
            onNode(hasText("Tentar de novo") and hasAnyAncestor(hasTestTag("reservation-detail"))).performClick()
            waitForIdle()
            inDetail("Hóspede 1").assertExists()
        }

    @Test
    fun historyErrorShowsSectionRetryAndRecovers() =
        runComposeUiTest {
            val source = source().apply { historyError = rawProblem() }
            screen(source)
            open(1)
            assertClean()
            onNodeWithText("Histórico").assertExists()
            onNodeWithText("Reserva criada", substring = true).assertDoesNotExist()
            val before = source.historyCalls.size
            source.historyError = null
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            assertTrue(source.historyCalls.size > before)
            onNodeWithText("Reserva criada", substring = true).assertExists()
        }

    @Test
    fun assignmentsErrorIsFriendlyAndKeepsHistory() =
        runComposeUiTest {
            val source = source().apply { assignmentsError = rawProblem() }
            screen(source)
            open(1)
            assertClean()
            onNodeWithText("Pré-reserva confirmada", substring = true).assertExists()
            onNodeWithText("Tentar de novo").assertExists()
        }

    @Test
    fun bothSectionErrorsShowTwoRetries() =
        runComposeUiTest {
            val source =
                source().apply {
                    assignmentsError = rawProblem()
                    historyError = rawProblem()
                }
            screen(source)
            open(1)
            assertClean()
            assertEquals(2, onAllNodesWithText("Tentar de novo").fetchSemanticsNodes().size)
        }

    @Test
    fun unknownEnumValuesNeverShowUuidOrRawFields() =
        runComposeUiTest {
            val source = source()
            val id = reservationUuid(1).toString()
            source.reservations[id] =
                detailOf(1).let {
                    it.copy(reservation = it.reservation.copy(status = "ALIEN_STATUS"))
                }
            source.histories[id] =
                listOf(
                    historyEntry("ALIEN_KIND", "2026-11-12T12:00:00Z", 2)
                        .copy(changedFields = listOf("mysteryField", "status")),
                )
            source.assignments[id] = listOf(assignmentOf(reason = "ALIEN_REASON", release = "ALIEN_RELEASE"))
            screen(source)
            open(1)
            assertClean()
            val all = texts()
            assertTrue(all.none { "mysteryField" in it }, all.toString())
            assertTrue(all.any { "ALIEN_STATUS" in it }, "status desconhecido deveria sair como Outro (...)")
        }

    @Test
    fun unknownStatusInListRowShowsOtherLabel() =
        runComposeUiTest {
            val source = source()
            source.pages[DayList.ARRIVALS to null] =
                Page(listOf(listItem(1).copy(status = "ALIEN_STATUS")), null)
            screen(source)
            assertClean()
            assertTrue(texts().any { it.contains("Outro") && it.contains("ALIEN_STATUS") }, texts().toString())
        }

    @Test
    fun holderWithoutAccessShowsDashNotUuid() =
        runComposeUiTest {
            val source =
                source().apply {
                    holderErrors[guestUuid(1).toString()] = ApiError.Problem(403, "ACCESS_DENIED", null, emptyList())
                }
            screen(source)
            open(1)
            assertClean()
            onNodeWithText("Documento").assertDoesNotExist()
            onNodeWithText("Alocações").assertExists()
        }

    @Test
    fun noUuidOnListScreenEvenWithoutDetail() =
        runComposeUiTest {
            screen(source())
            tabsAll()
            assertClean()
        }

    private fun ComposeUiTest.tabsAll() {
        onNodeWithTag("tab-DEPARTURES").performClick()
        waitForIdle()
        onNodeWithTag("tab-IN_HOUSE").performClick()
        waitForIdle()
    }

    @Test
    fun refreshButtonReloadsOpenDetail() =
        runComposeUiTest {
            val source = source()
            screen(source)
            open(1)
            val reads = source.reservationCalls.size
            val histories = source.historyCalls.size
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            assertEquals(reads + 1, source.reservationCalls.size)
            assertEquals(histories + 1, source.historyCalls.size)
        }

    @Test
    fun refreshWithoutOpenDetailReadsNoReservation() =
        runComposeUiTest {
            val source = source()
            screen(source)
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            assertTrue(source.reservationCalls.isEmpty())
        }

    @Test
    fun refreshKeepsSelectedTab() =
        runComposeUiTest {
            screen(source())
            onNodeWithTag("tab-DEPARTURES").performClick()
            waitForIdle()
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            onNodeWithText("R-3").assertExists()
            onNodeWithText("R-1").assertDoesNotExist()
        }

    @Test
    fun dateErrorNeverCallsDayPageAndHidesTable() =
        runComposeUiTest {
            val source = source().apply { businessDayError = rawProblem() }
            screen(source)
            assertClean()
            assertTrue(source.dayPageCalls.isEmpty())
            onNodeWithTag("table-filter").assertDoesNotExist()
            onNodeWithText("Data operacional: 13/11/2026").assertDoesNotExist()
            onNodeWithTag("panel-error").assertDoesNotExist()
        }

    @Test
    fun dayPageNeverCalledBeforeDateEvenAfterRefreshWhileHeld() =
        runComposeUiTest {
            val source = source().apply { holdBusinessDay = true }
            screen(source)
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            assertTrue(source.dayPageCalls.isEmpty())
            onNodeWithText("Nenhuma reserva nesta lista.").assertDoesNotExist()
        }

    @Test
    fun listErrorWithKnownDayShowsFriendlyBannerWithoutRawCode() =
        runComposeUiTest {
            val source = source()
            screen(source)
            source.dayPageError = rawProblem()
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            assertClean()
            onNodeWithText("R-1").assertExists()
        }

    @Test
    fun searchWithoutMatchShowsEmptyMessageAndKeepsTabs() =
        runComposeUiTest {
            screen(source())
            onNodeWithTag("table-filter").performTextInput("ZZZ-NAO-EXISTE")
            waitForIdle()
            onNodeWithTag("row-${reservationUuid(1)}").assertDoesNotExist()
            onNodeWithTag("tab-ARRIVALS").assertExists()
        }

    @Test
    fun closeThenReopenShowsFreshDetail() =
        runComposeUiTest {
            screen(source())
            open(1)
            onNodeWithText("Fechar").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertDoesNotExist()
            open(2)
            inDetail("Hóspede 2").assertExists()
            inDetail("Hóspede 1").assertDoesNotExist()
        }

    @Test
    fun historyOrderedEvenWhenServerReturnsNewestFirst() =
        runComposeUiTest {
            val source = source()
            source.histories[reservationUuid(1).toString()] =
                listOf(
                    historyEntry("CHECK_OUT", "2026-11-14T10:00:00Z", 4),
                    historyEntry("CHECK_IN", "2026-11-13T10:00:00Z", 3),
                    historyEntry("CONFIRM", "2026-11-11T12:00:00Z", 2),
                )
            screen(source)
            open(1)

            fun y(t: String) =
                onNodeWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            val ys = listOf("Reserva criada", "Pré-reserva confirmada", "Check-in", "Check-out").map { y(it) }
            assertEquals(ys.sorted(), ys)
        }
}
