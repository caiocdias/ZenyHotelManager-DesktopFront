package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.RoomTypeAvailabilityResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val standardId = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val suiteId = Uuid.parse("10000000-0000-0000-0000-000000000002")

private fun day(
    date: String,
    available: Int = 3,
    overbooked: Boolean = false,
) = AvailabilityDayResponse(
    available = available,
    blocked = 1,
    date = date,
    outOfOrder = 0,
    overbooked = overbooked,
    overbookingLimit = 2,
    sold = 1,
    total = 4,
)

private fun roomType(
    id: Uuid,
    name: String,
    vararg days: AvailabilityDayResponse,
) = RoomTypeAvailabilityResponse(
    active = true,
    code = name.take(3).uppercase(),
    days = days.toList(),
    name = name,
    overbookedDates = emptyList(),
    roomTypeId = id,
)

private fun tag(
    id: Uuid,
    date: String,
) = "availability-cell-$id-$date"

private fun stateIs(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

private val noState = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)

@OptIn(ExperimentalTestApi::class)
class CalendarPanelTest {
    private class Rig(
        val source: FakeAvailabilitySource,
        val state: AvailabilityState,
        val quoted: MutableList<BusinessDate>,
    )

    private fun source(vararg types: RoomTypeAvailabilityResponse) =
        FakeAvailabilitySource().apply {
            availability = AvailabilityResponse(from = "2026-11-16", roomTypes = types.toList(), to = "2026-12-15")
        }

    private fun ComposeUiTest.panel(source: FakeAvailabilitySource): Rig {
        val quoted = mutableListOf<BusinessDate>()
        lateinit var state: AvailabilityState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { AvailabilityState(source, scope) }
            ZenyTheme { CalendarPanel(state, onQuoteFrom = { quoted += it }) }
        }
        state.start()
        waitForIdle()
        return Rig(source, state, quoted)
    }

    @Test
    fun calendarShowsCountsPerTypeAndDate() =
        runComposeUiTest {
            panel(
                source(
                    roomType(standardId, "Standard", day("2026-11-16", 3), day("2026-11-17", 2)),
                    roomType(suiteId, "Suíte", day("2026-11-16", 1)),
                ),
            )
            onNodeWithText("Standard").assertExists()
            onNodeWithText("Suíte").assertExists()
            onNodeWithText("seg 16/11").assertExists()
            onNodeWithText("ter 17/11").assertExists()
            onNodeWithTag(tag(standardId, "2026-11-16")).assertTextEquals("3")
            onNodeWithTag(tag(standardId, "2026-11-17")).assertTextEquals("2")
            onNodeWithTag(tag(suiteId, "2026-11-16")).assertTextEquals("1")
        }

    @Test
    fun missingDayShowsDash() =
        runComposeUiTest {
            panel(source(roomType(standardId, "Standard", day("2026-11-16", 3))))
            onNodeWithTag(tag(standardId, "2026-11-17")).assertTextEquals("—").assert(noState)
        }

    @Test
    fun overbookedCellIsFlagged() =
        runComposeUiTest {
            panel(source(roomType(standardId, "Standard", day("2026-11-16", -1, overbooked = true))))
            onNodeWithTag(tag(standardId, "2026-11-16")).assertTextEquals("-1").assert(stateIs("Sobrevendido"))
        }

    @Test
    fun soldOutCellIsFlagged() =
        runComposeUiTest {
            panel(source(roomType(standardId, "Standard", day("2026-11-16", 0), day("2026-11-17", 2))))
            onNodeWithTag(tag(standardId, "2026-11-16")).assertTextEquals("0").assert(stateIs("Esgotado"))
            onNodeWithTag(tag(standardId, "2026-11-17")).assert(noState)
        }

    @Test
    fun cellClickShowsInventoryAndOffersQuote() =
        runComposeUiTest {
            val rig = panel(source(roomType(standardId, "Standard", day("2026-11-17", 3))))
            onNodeWithTag(tag(standardId, "2026-11-17")).performClick()
            waitForIdle()
            onNodeWithText("17/11/2026").assertExists()
            onNodeWithText(AvailabilityText.inventory(day("2026-11-17", 3))).assertExists()
            onNodeWithText("Cotar a partir deste dia").performClick()
            assertEquals(listOf(BusinessDate.parse("2026-11-17")), rig.quoted)
        }

    @Test
    fun previousDisabledAtBusinessDay() =
        runComposeUiTest {
            val rig = panel(source(roomType(standardId, "Standard", day("2026-11-16", 3))))
            onNodeWithText("Anterior").assertIsNotEnabled()
            onNodeWithText("Próximo").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(BusinessDate.parse("2026-12-16"), rig.state.from)
            onNodeWithText("Anterior").assertIsEnabled()
        }

    @Test
    fun staleRowsUnderNewHeaderShowDashNeverZero() =
        runComposeUiTest {
            val rig = panel(source(roomType(standardId, "Standard", day("2026-11-16", 0))))
            rig.source.failures[FakeAvailabilitySource.Op.AVAILABILITY] = IOException("fora do ar")
            onNodeWithText("Próximo").performClick()
            waitForIdle()
            onNodeWithText("Tentar de novo").assertExists()
            onNodeWithTag(tag(standardId, "2026-12-16")).assertTextEquals("—").assert(noState)
            onNodeWithTag(tag(standardId, "2026-11-16")).assertDoesNotExist()
        }

    @Test
    fun retryAfterErrorReloads() =
        runComposeUiTest {
            val rig = panel(source())
            rig.source.failures[FakeAvailabilitySource.Op.AVAILABILITY] = IOException("fora do ar")
            onNodeWithText("Recarregar").performClick()
            waitForIdle()
            rig.source.failures.clear()
            val before = rig.source.availabilityCalls.size
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            assertEquals(before + 1, rig.source.availabilityCalls.size)
        }

    @Test
    fun navigationIsDisabledWhileLoading() =
        runComposeUiTest {
            val source = source(roomType(standardId, "Standard", day("2026-11-16", 3)))
            val rig = panel(source)
            val gate = source.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            onNodeWithText("Próximo").performClick()
            waitForIdle()
            onNodeWithText("Próximo").assertIsNotEnabled()
            onNodeWithText("Anterior").assertIsNotEnabled()
            onNodeWithText("Recarregar").assertIsNotEnabled()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithText("Próximo").assertIsEnabled()
            assertEquals(false, rig.state.loading)
        }

    @Test
    fun duplicatedTypesRenderBothRows() =
        runComposeUiTest {
            panel(
                source(
                    roomType(standardId, "Standard", day("2026-11-16", 3)),
                    roomType(standardId, "Standard", day("2026-11-16", 3)),
                ),
            )
            assertEquals(2, onAllNodesWithTag(tag(standardId, "2026-11-16")).fetchSemanticsNodes().size)
        }
}
