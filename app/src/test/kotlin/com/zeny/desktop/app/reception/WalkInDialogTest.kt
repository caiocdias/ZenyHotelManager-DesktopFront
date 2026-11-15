package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRestrictionAlertResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val wdTypeStd = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val wdRoom101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val wdRoom103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val wdGuestUuid = Uuid.parse("70000000-0000-0000-0000-000000000001")
private val wdUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private const val WD_RAW_CODE = "ROOM_NOT_READY"
private const val WD_FULL_DOCUMENT = "123.456.789-01"
private val wdGuest = GuestPick(wdGuestUuid.toString(), HolderSummary("Ana Teste", "CPF ***.***.***-01"))
private val wdAll =
    setOf(
        "frontdesk.stays.checkin",
        "reservations.reservations.manage",
        "reservations.assignments.manage",
        "reservations.availability.read",
        "reservations.reservations.read",
        "rooms.status.read",
        "guests.profiles.read",
        "guests.profiles.manage",
    )
private val wdButtonPermissions = wdAll - "guests.profiles.manage"

private fun wdOffer() =
    QuoteOfferResponse(
        accommodationTotal = "300.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = Uuid.random(),
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = Uuid.random(),
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "BAR",
        ratePlanId = Uuid.random(),
        ratePlanName = "Balcão",
        roomTotal = "300.00",
        roomTypeCode = "STD",
        roomTypeId = wdTypeStd,
        roomTypeName = "Standard",
        total = "300.00",
    )

private fun wdQuote(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 1,
        arrival = "2026-11-13",
        channel = "DIRECT",
        childAges = emptyList(),
        departure = "2026-11-15",
        nights = 2,
        offers = offers.toList(),
    )

private fun wdStatus(
    room: Uuid,
    number: String,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
    inspectionPending = false,
    number = number,
    occupancy = RoomStatusResponse.Occupancy.VACANT,
    readyForCheckIn = true,
    roomId = room,
    roomTypeId = wdTypeStd,
    serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
    version = 1,
)

private fun wdResponse() =
    WalkInResponse(
        bookingCode = "B-12",
        bookingId = Uuid.random(),
        holderGuestId = wdGuestUuid,
        reservation =
            StayReservationResponse(
                adults = 1,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                children = 0,
                code = "R-90",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guests = emptyList(),
                id = Uuid.random(),
                roomTypeId = wdTypeStd,
                status = "CHECKED_IN",
                version = 2,
            ),
        restrictionAlerts =
            listOf(StayRestrictionAlertResponse(guestId = wdGuestUuid, reason = "Fumante", since = "2026-11-01")),
        room =
            StayRoomResponse(
                housekeepingStatus = StayRoomResponse.HousekeepingStatus.CLEAN,
                inspectionPending = false,
                number = "101",
                occupancy = StayRoomResponse.Occupancy.OCCUPIED,
                roomId = wdRoom101,
                version = 3,
            ),
        total = "600.00",
    )

@OptIn(ExperimentalTestApi::class)
class WalkInDialogTest {
    private var changes = 0
    private val standard = wdOffer()

    private fun source(permissions: Set<String> = wdAll) =
        FakeReceptionSource().apply {
            this.permissions = permissions
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
            stayQuote = wdQuote(standard)
            roomStatusList += listOf(wdStatus(wdRoom103, "103"), wdStatus(wdRoom101, "101"))
            walkInResponse = wdResponse()
            guestsByName["Ana"] = listOf(wdGuest)
        }

    private fun ComposeUiTest.screen(
        source: FakeReceptionSource,
        withWalkIn: Boolean = true,
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope) }
            val detail = remember { ReservationDetailState(source, scope) }
            val factory: (() -> WalkInState)? =
                if (withWalkIn) {
                    {
                        val approve: suspend (ApprovableAction<WalkInResponse>) -> ApiResponse<WalkInResponse>? =
                            { it.run(null) }
                        WalkInState(source, scope, panel.permissions, approve) {
                            changes++
                            panel.refresh()
                        }
                    }
                } else {
                    null
                }
            ZenyTheme {
                if (factory ==
                    null
                ) {
                    ReceptionScreen(panel, detail)
                } else {
                    ReceptionScreen(panel, detail, walkIn = factory)
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openDialog() {
        onNodeWithTag("open-walk-in").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    /** Os botões do rodapé do diálogo ficam fora da área rolável. */
    private fun ComposeUiTest.clickFooter(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.quote() {
        onNodeWithTag("walk-in-departure").performScrollTo().performTextInput("15/11/2026")
        click("walk-in-quote")
    }

    private fun ComposeUiTest.chooseGuest() {
        onNodeWithTag("guest-query").performScrollTo().performTextInput("Ana")
        click("guest-search")
        click("guest-pick-0")
    }

    private fun ComposeUiTest.readyToConfirm() {
        openDialog()
        quote()
        click("offer-choose-0")
        click("room-choose-0")
        click("swap-guest")
        chooseGuest()
    }

    private fun collectTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } +
            node.config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
                ?.let { listOf(it) }
                .orEmpty() +
            node.children.flatMap { collectTexts(it) }

    private fun ComposeUiTest.allTexts() =
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { collectTexts(it) }

    @Test
    fun walkInButtonFollowsPermissions() =
        runComposeUiTest {
            screen(source(wdButtonPermissions))
            onNodeWithTag("open-walk-in").assertExists()
        }

    @Test
    fun walkInButtonHiddenWithoutAnyRequiredPermission() {
        wdButtonPermissions.forEach { missing ->
            runComposeUiTest {
                screen(source(wdAll - missing))
                onNodeWithTag("open-walk-in").assertDoesNotExist()
            }
        }
    }

    @Test
    fun existingCallsHaveNoWalkInButton() =
        runComposeUiTest {
            screen(source(), withWalkIn = false)
            onNodeWithTag("open-walk-in").assertDoesNotExist()
        }

    @Test
    fun dialogShowsArrivalAndStayControls() =
        runComposeUiTest {
            screen(source())
            openDialog()
            onNodeWithText("Chegada: 13/11/2026").assertExists()
            onNodeWithTag("walk-in-quote").assertExists()
        }

    @Test
    fun quoteShowsOffers() =
        runComposeUiTest {
            screen(source())
            openDialog()
            quote()
            onNodeWithText(ActionText.offer(standard)).assertExists()
            onNodeWithTag("offer-choose-0").assertExists()
        }

    @Test
    fun noOffersShowsMessageAndNoGuestStep() =
        runComposeUiTest {
            screen(source().apply { stayQuote = wdQuote() })
            openDialog()
            quote()
            onNodeWithText(ActionText.noOffers()).assertExists()
            onNodeWithText("Hóspede principal").assertDoesNotExist()
            onNodeWithTag("offer-choose-0").assertDoesNotExist()
        }

    @Test
    fun chooseOfferAndRoomShowsGuestStep() =
        runComposeUiTest {
            screen(source())
            openDialog()
            quote()
            onNodeWithText("Hóspede principal").assertDoesNotExist()
            click("offer-choose-0")
            onNodeWithTag("room-choose-0").assertExists()
            onNodeWithText("101").assertExists()
            onNodeWithText("Hóspede principal").assertDoesNotExist()
            click("room-choose-0")
            onNodeWithText("Hóspede principal").assertExists()
            onNodeWithText("Escolha o hóspede").assertExists()
            onNodeWithTag("walk-in-confirm").assertIsNotEnabled()
        }

    @Test
    fun confirmShowsDoneAndRefreshesPanel() =
        runComposeUiTest {
            val source = source()
            screen(source)
            readyToConfirm()
            onNodeWithTag("walk-in-confirm").assertIsEnabled()
            clickFooter("walk-in-confirm")
            val period = ReceptionText.period("2026-11-13", "2026-11-15", false)
            val total = ReceptionText.money("600.00")
            onNodeWithText("Walk-in concluído: reserva R-90, UH 101, $period, total $total.").assertExists()
            onNodeWithText("Fumante", substring = true).assertExists()
            assertEquals(1, changes)
            clickFooter("walk-in-close")
            onNodeWithTag("walk-in-dialog").assertDoesNotExist()
        }

    @Test
    fun controlsDisabledWhileBusy() =
        runComposeUiTest {
            val source = source().apply { holdWalkIn = true }
            screen(source)
            readyToConfirm()
            clickFooter("walk-in-confirm")
            listOf(
                "walk-in-departure",
                "walk-in-quote",
                "offer-choose-0",
                "room-choose-0",
                "walk-in-close",
                "walk-in-confirm",
            ).forEach { onNodeWithTag(it).assertIsNotEnabled() }
            onNodeWithTag("guest-query").assertDoesNotExist()
            source.heldWalkIns.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("walk-in-done").assertExists()
        }

    @Test
    fun ruleErrorShowsNextStep() =
        runComposeUiTest {
            val source = source()
            source.walkInOutcomes += ApiError.Problem(422, WD_RAW_CODE, "detalhe do servidor", emptyList())
            screen(source)
            readyToConfirm()
            clickFooter("walk-in-confirm")
            val text = ActionText.walkInError(ApiError.Problem(422, WD_RAW_CODE, "x", emptyList()))
            onNodeWithTag("walk-in-error").assertExists()
            onNodeWithText(text, substring = true).assertExists()
            onNodeWithTag("walk-in-done").assertDoesNotExist()
        }

    @Test
    fun unconfirmedLocksGuestPanelButKeepsCloseEnabled() =
        runComposeUiTest {
            val source = source()
            source.walkInOutcomes += ApiError.Network(java.io.IOException("sem rede"))
            screen(source)
            readyToConfirm()
            clickFooter("walk-in-confirm")
            onNodeWithText("Não foi possível confirmar o walk-in.", substring = true).assertExists()
            onNodeWithTag("guest-query").assertDoesNotExist()
            onNodeWithTag("swap-guest").assertIsNotEnabled()
            onNodeWithTag("offer-choose-0").assertIsNotEnabled()
            onNodeWithTag("walk-in-close").assertIsEnabled()
        }

    @Test
    fun noUuidOrRawCodeInDialog() =
        runComposeUiTest {
            val source = source()
            source.walkInOutcomes += ApiError.Problem(422, WD_RAW_CODE, "detalhe do servidor", emptyList())
            screen(source)
            readyToConfirm()
            clickFooter("walk-in-confirm")
            val texts = allTexts()
            assertTrue(texts.isNotEmpty())
            assertFalse(texts.any { wdUuid.containsMatchIn(it) }, "uuid na tela")
            assertFalse(texts.any { WD_RAW_CODE in it }, "código cru na tela")
            assertFalse(texts.any { WD_FULL_DOCUMENT in it }, "documento inteiro na tela")
        }
}
