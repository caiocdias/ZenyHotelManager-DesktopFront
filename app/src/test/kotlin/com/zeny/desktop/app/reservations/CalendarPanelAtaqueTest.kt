package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.RoomTypeAvailabilityResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

private val aId = Uuid.parse("20000000-0000-0000-0000-00000000000a")
private val bId = Uuid.parse("20000000-0000-0000-0000-00000000000b")

private fun d(
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

private fun rt(
    id: Uuid,
    name: String,
    vararg days: AvailabilityDayResponse,
) = RoomTypeAvailabilityResponse(
    active = true,
    code = "CODEXYZ",
    days = days.toList(),
    name = name,
    overbookedDates = emptyList(),
    roomTypeId = id,
)

private fun cell(
    id: Uuid,
    date: String,
) = "availability-cell-$id-$date"

private fun dateOf(offset: Int): String {
    val first = BusinessDate.parse("2026-11-16")
    return first.value
        .plusDays(offset.toLong())
        .toString()
}

private fun sd(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

private val noSd = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
private val noClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
private val hasClick = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

@OptIn(ExperimentalTestApi::class)
class CalendarPanelAtaqueTest {
    private class Rig(
        val source: FakeAvailabilitySource,
        val state: AvailabilityState,
        val quoted: MutableList<BusinessDate>,
    )

    private fun src(vararg types: RoomTypeAvailabilityResponse) =
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
    fun missingDayIsNeverZeroNorSoldOutNorClickable() =
        runComposeUiTest {
            val rig = panel(src(rt(aId, "Alfa", d(dateOf(0), 0))))
            onNodeWithTag(cell(aId, dateOf(1))).assertTextEquals("—").assert(noSd).assert(noClick)
            onNodeWithTag(cell(aId, dateOf(1))).performClick()
            waitForIdle()
            assertNull(rig.state.selected)
            onNodeWithText("Cotar a partir deste dia").assertDoesNotExist()
        }

    @Test
    fun staleRowsAfterFailedNavigationShowDashesOnEveryNewColumn() =
        runComposeUiTest {
            val rig = panel(src(rt(aId, "Alfa", d(dateOf(0), 0), d(dateOf(1), -2, overbooked = true))))
            rig.source.failures[FakeAvailabilitySource.Op.AVAILABILITY] = IOException("x")
            onNodeWithText("Próximo").performClick()
            waitForIdle()
            for (i in 30 until 60) {
                onNodeWithTag(cell(aId, dateOf(i))).assertTextEquals("—").assert(noSd).assert(noClick)
            }
            onAllNodesWithText("0").assertCountEquals(0)
        }

    @Test
    fun overbookedHasPrecedenceOverSoldOut() =
        runComposeUiTest {
            panel(src(rt(aId, "Alfa", d(dateOf(0), 0, overbooked = true), d(dateOf(1), -3, overbooked = true))))
            onNodeWithTag(cell(aId, dateOf(0))).assertTextEquals("0").assert(sd("Sobrevendido"))
            onNodeWithTag(cell(aId, dateOf(1))).assertTextEquals("-3").assert(sd("Sobrevendido"))
        }

    @Test
    fun negativeAvailableWithoutOverbookedIsShownPlainWithoutState() =
        runComposeUiTest {
            // Servidor incoerente: documenta o comportamento atual (número cru, sem estado).
            panel(src(rt(aId, "Alfa", d(dateOf(0), -1, overbooked = false))))
            onNodeWithTag(cell(aId, dateOf(0))).assertTextEquals("-1").assert(noSd)
        }

    @Test
    fun thirtyColumnsWithHeadersAndLastColumnReachableByScroll() =
        runComposeUiTest {
            panel(src(rt(aId, "Alfa", *Array(30) { d(dateOf(it), it) })))
            onNodeWithText(AvailabilityText.dayHeader(BusinessDate.parse(dateOf(0)))).assertExists()
            val last = BusinessDate.parse(dateOf(29))
            onNodeWithText(AvailabilityText.dayHeader(last)).performScrollTo().assertExists()
            onNodeWithTag(cell(aId, dateOf(29))).performScrollTo().assertTextEquals("29")
            onNodeWithTag(cell(aId, dateOf(30))).assertDoesNotExist()
        }

    @Test
    fun typeWithoutDaysGetsARowOfDashes() =
        runComposeUiTest {
            panel(src(rt(aId, "Alfa", d(dateOf(0), 4)), rt(bId, "Beta")))
            onNodeWithText("Beta").assertExists()
            onNodeWithTag(cell(bId, dateOf(0))).assertTextEquals("—").assert(noSd)
            onNodeWithTag(cell(bId, dateOf(5))).assertTextEquals("—")
        }

    @Test
    fun clickingSecondRowCellShowsThatTypeAndQuotesItsDate() =
        runComposeUiTest {
            val rig = panel(src(rt(aId, "Alfa", d(dateOf(2), 9)), rt(bId, "Beta", d(dateOf(2), 1))))
            onNodeWithTag(cell(bId, dateOf(2))).assert(hasClick).performClick()
            waitForIdle()
            onNodeWithText(AvailabilityText.inventory(d(dateOf(2), 1))).assertExists()
            onAllNodesWithText("Beta").assertCountEquals(2)
            onNodeWithText("18/11/2026").assertExists()
            onNodeWithText("Cotar a partir deste dia").performClick()
            assertEquals(listOf(BusinessDate.parse(dateOf(2))), rig.quoted)
        }

    @Test
    fun navigationButtonsAreDisabledWhileInitialLoading() =
        runComposeUiTest {
            val source = src(rt(aId, "Alfa", d(dateOf(0), 3)))
            val gate = source.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            panel(source)
            onNodeWithText("Anterior").assertIsNotEnabled()
            onNodeWithText("Próximo").assertIsNotEnabled()
            onNodeWithText("Recarregar").assertIsNotEnabled()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithText("Recarregar").assertIsEnabled()
        }

    @Test
    fun previousDisabledDuringLoadingEvenAwayFromBusinessDay() =
        runComposeUiTest {
            val source = src(rt(aId, "Alfa", d(dateOf(0), 3)))
            panel(source)
            onNodeWithText("Próximo").performClick()
            waitForIdle()
            onNodeWithText("Anterior").assertIsEnabled()
            source.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            onNodeWithText("Recarregar").performClick()
            waitForIdle()
            onNodeWithText("Anterior").assertIsNotEnabled()
        }

    @Test
    fun retryAfterErrorClearsTheError() =
        runComposeUiTest {
            val rig = panel(src(rt(aId, "Alfa", d(dateOf(0), 3))))
            rig.source.failures[FakeAvailabilitySource.Op.AVAILABILITY] = IOException("x")
            onNodeWithText("Recarregar").performClick()
            waitForIdle()
            onNodeWithText("Tentar de novo").assertExists()
            rig.source.failures.clear()
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            onNodeWithText("Tentar de novo").assertDoesNotExist()
            assertNull(rig.state.error)
        }

    @Test
    fun screenNeverShowsUuidOrCode() =
        runComposeUiTest {
            panel(src(rt(aId, "Alfa", d(dateOf(0), 3)), rt(bId, "Beta")))
            onAllNodesWithText("CODEXYZ", substring = true).assertCountEquals(0)
            onAllNodesWithText("20000000", substring = true).assertCountEquals(0)
            onNodeWithTag(cell(aId, dateOf(0))).performClick()
            waitForIdle()
            onAllNodesWithText("CODEXYZ", substring = true).assertCountEquals(0)
            onAllNodesWithText("20000000", substring = true).assertCountEquals(0)
        }
}
