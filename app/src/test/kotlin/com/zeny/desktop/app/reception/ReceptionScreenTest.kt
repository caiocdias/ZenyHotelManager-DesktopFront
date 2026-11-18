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
import de.quati.kotlin.util.Option
import java.io.IOException
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

@OptIn(ExperimentalTestApi::class)
class ReceptionScreenTest {
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
                    listOf(
                        historyEntry("CHECK_OUT", "2026-11-13T18:00:00Z", 3),
                        historyEntry("CONFIRM", "2026-11-11T12:00:00Z", 2),
                    )
            }
        }

    private fun ComposeUiTest.screen(source: FakeReceptionSource) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope, source.permissions) }
            val detail = remember { ReservationDetailState(source, scope) }
            ZenyTheme { ReceptionScreen(panel, detail) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.texts(): List<String> = collectTexts(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun collectTexts(node: SemanticsNode): List<String> =
        (
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        ) +
            (
                node.config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text
                    ?.let { listOf(it) }
                    .orEmpty()
            ) +
            node.children.flatMap { collectTexts(it) }

    private fun ComposeUiTest.openFirst() {
        onNodeWithTag("row-${reservationUuid(1)}").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.y(text: String) =
        onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun tabsShowCounts() =
        runComposeUiTest {
            screen(source())
            onNodeWithText("Recepção").assertExists()
            onNodeWithText("Data operacional: 13/11/2026").assertExists()
            onNodeWithTag("tab-ARRIVALS").assertExists()
            onNodeWithText("Chegadas (2)").assertExists()
            onNodeWithText("Saídas (1)").assertExists()
            onNodeWithText("Hospedados (0)").assertExists()
        }

    @Test
    fun switchingTabShowsItsRows() =
        runComposeUiTest {
            screen(source())
            onNodeWithText("R-1").assertExists()
            onNodeWithTag("tab-DEPARTURES").performClick()
            waitForIdle()
            onNodeWithText("R-3").assertExists()
            onNodeWithText("R-1").assertDoesNotExist()
        }

    @Test
    fun emptyTabMessage() =
        runComposeUiTest {
            screen(source())
            onNodeWithTag("tab-IN_HOUSE").performClick()
            waitForIdle()
            onNodeWithText("Nenhuma reserva nesta lista.").assertExists()
        }

    @Test
    fun noTableBeforeBusinessDay() =
        runComposeUiTest {
            val source = source().apply { holdBusinessDay = true }
            screen(source)
            onNodeWithText("Carregando…").assertExists()
            onNodeWithTag("table-filter").assertDoesNotExist()
            assertTrue(source.dayPageCalls.isEmpty())
        }

    @Test
    fun searchFiltersRows() =
        runComposeUiTest {
            screen(source())
            onNodeWithTag("table-filter").performTextInput("R-2")
            waitForIdle()
            onNodeWithTag("row-${reservationUuid(2)}").assertExists()
            onNodeWithTag("row-${reservationUuid(1)}").assertDoesNotExist()
        }

    @Test
    fun clickOpensDetailWithMaskedDocument() =
        runComposeUiTest {
            screen(source())
            openFirst()
            onNodeWithText("CPF ***.***.***-11").assertExists()
            onNode(hasText("Confirmada") and hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
            onNodeWithText("Alocações").assertExists()
            onNodeWithText("Histórico").assertExists()
            assertTrue(texts().none { FULL_DOCUMENT in it })
            onNodeWithText("Fechar").performClick()
            waitForIdle()
            onNodeWithText("Alocações").assertDoesNotExist()
        }

    @Test
    fun historyInOrder() =
        runComposeUiTest {
            screen(source())
            openFirst()
            val ordered = listOf("Reserva criada", "Pré-reserva confirmada", "Check-out").map { y(it) }
            assertEquals(ordered.sorted(), ordered)
        }

    @Test
    fun noUuidOnScreen() =
        runComposeUiTest {
            screen(source())
            openFirst()
            val all = texts()
            assertTrue(all.isNotEmpty())
            assertTrue(all.none { uuidRegex.containsMatchIn(it) }, all.toString())
        }

    @Test
    fun dateErrorShowsRetry() =
        runComposeUiTest {
            val source = source().apply { businessDayError = IOException("offline") }
            screen(source)
            onNodeWithText("Não foi possível falar com o servidor. Confira o endereço e a rede.").assertExists()
            source.businessDayError = null
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            onNodeWithText("Data operacional: 13/11/2026").assertExists()
        }

    @Test
    fun dateErrorWithKnownDayShowsBanner() =
        runComposeUiTest {
            val source = source()
            screen(source)
            source.businessDayError = IOException("offline")
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            onNodeWithTag("panel-error").assertExists()
            onNodeWithText("R-1").assertExists()
            source.businessDayError = null
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            onNodeWithTag("panel-error").assertDoesNotExist()
        }

    @Test
    fun refreshButtonRefreshes() =
        runComposeUiTest {
            val source = source()
            screen(source)
            val before = source.businessDayCalls
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            assertEquals(before + 1, source.businessDayCalls)
            assertEquals(0, onAllNodesWithText("Carregando…").fetchSemanticsNodes().size)
        }
}
