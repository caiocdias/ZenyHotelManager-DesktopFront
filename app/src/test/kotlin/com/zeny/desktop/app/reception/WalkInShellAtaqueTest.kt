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
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val wsType = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val wsRoom = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val wsGuestId = Uuid.parse("70000000-0000-0000-0000-000000000001")
private val wsUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val wsAll =
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

private val wsOffer =
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
        roomTypeId = wsType,
        roomTypeName = "Standard",
        total = "300.00",
    )

private fun wsResponse() =
    WalkInResponse(
        bookingCode = "B-12",
        bookingId = Uuid.random(),
        holderGuestId = wsGuestId,
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
                roomTypeId = wsType,
                status = "CHECKED_IN",
                version = 2,
            ),
        restrictionAlerts = emptyList(),
        room =
            StayRoomResponse(
                housekeepingStatus = StayRoomResponse.HousekeepingStatus.CLEAN,
                inspectionPending = false,
                number = "101",
                occupancy = StayRoomResponse.Occupancy.OCCUPIED,
                roomId = wsRoom,
                version = 3,
            ),
        total = "600.00",
    )

private fun wsSource() =
    FakeReceptionSource().apply {
        permissions = wsAll
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        stayQuote =
            QuoteResponse(
                adults = 1,
                arrival = "2026-11-13",
                channel = "DIRECT",
                childAges = emptyList(),
                departure = "2026-11-15",
                nights = 2,
                offers = listOf(wsOffer),
            )
        roomStatusList +=
            RoomStatusResponse(
                active = true,
                changedAt = "2026-11-12T10:00:00Z",
                housekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
                inspectionPending = false,
                number = "101",
                occupancy = RoomStatusResponse.Occupancy.VACANT,
                readyForCheckIn = true,
                roomId = wsRoom,
                roomTypeId = wsType,
                serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
                version = 1,
            )
        walkInResponse = wsResponse()
        guestsByName["Ana"] = listOf(GuestPick(wsGuestId.toString(), HolderSummary("Ana Teste", "CPF ***.***.***-01")))
    }

private class WsSettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-1"
}

private class WsConn(
    private val reception: ReceptionSource,
    initial: SessionState = SessionState.LoggedOut,
) : ServerConnection {
    override val state = MutableStateFlow(initial)
    val grants = mutableListOf<ApprovalInput>()

    override suspend fun login(
        login: String,
        password: String,
    ) {
        state.value = SessionState.Active("u-1", "s-1", false)
    }

    override suspend fun changePassword(
        current: String,
        new: String,
    ) = error("não usado")

    override suspend fun retryPasswordChangeRefresh() = Unit

    override suspend fun logout() {
        state.value = SessionState.LoggedOut
    }

    override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", wsAll)

    override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

    override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

    override suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String {
        grants += input
        return "token-1"
    }

    override fun roomsSource(): RoomsSource? = null

    override fun receptionSource(): ReceptionSource = reception

    override fun close() = Unit
}

private val wsRequired =
    ApiError.ApprovalRequired("frontdesk.walkin", "frontdesk.stays.approve", "walkin:1", null, null)

@OptIn(ExperimentalTestApi::class)
class WalkInShellAtaqueTest {
    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.footer(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.fillAndChoose() {
        onNodeWithTag("walk-in-departure").performScrollTo().performTextInput("15/11/2026")
        click("walk-in-quote")
        click("offer-choose-0")
        click("room-choose-0")
        click("swap-guest")
        onNodeWithTag("guest-query").performScrollTo().performTextInput("Ana")
        click("guest-search")
        click("guest-pick-0")
    }

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

    private fun ComposeUiTest.allTexts() =
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { collect(it) }

    private fun shell(
        conn: WsConn,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(WsSettings(), { conn }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        onNodeWithText("Recepção").performClick()
        waitForIdle()
        onNodeWithTag("open-walk-in").performClick()
        waitForIdle()
        after()
    }

    @Test
    fun approvalRequiredInWalkInOpensCoordinatorAndApprovedRepeatCompletesAndRefreshes() {
        val source = wsSource().apply { walkInRequiresApproval = wsRequired }
        val conn = WsConn(source)
        shell(conn) {
            fillAndChoose()
            val pages = source.dayPageCalls.size
            footer("walk-in-confirm")
            onNodeWithText("Aprovação de superior").assertExists()
            onNodeWithTag("approval-login").performTextInput("joana.gerente")
            onNodeWithTag("approval-pin").performTextInput("1234")
            onNodeWithTag("approval-reason").performTextInput("Cortesia")
            onNodeWithTag("approval-confirm").performClick()
            waitForIdle()

            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            onNodeWithTag("walk-in-done").assertExists()
            assertEquals(listOf(ApprovalInput("joana.gerente", "1234", "Cortesia")), conn.grants)
            assertEquals(listOf(null, "token-1"), source.walkInRuns.map { it.token })
            assertEquals(
                1,
                source.walkInRuns
                    .map { it.key }
                    .toSet()
                    .size,
                "a repetição trocou a Idempotency-Key",
            )
            assertTrue(source.dayPageCalls.size > pages, "o painel não foi atualizado")
        }
    }

    @Test
    fun cancelledApprovalDoesNotRerunWalkInNorShowErrorOrDone() {
        val source = wsSource().apply { walkInRequiresApproval = wsRequired }
        shell(WsConn(source)) {
            fillAndChoose()
            footer("walk-in-confirm")
            onNodeWithText("Cancelar").performClick()
            waitForIdle()

            assertEquals(listOf<String?>(null), source.walkInRuns.map { it.token }, "a ação foi refeita")
            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            onNodeWithTag("walk-in-error").assertDoesNotExist()
            onNodeWithTag("walk-in-done").assertDoesNotExist()
            onNodeWithTag("walk-in-confirm").assertIsEnabled()
            onNodeWithTag("walk-in-close").assertIsEnabled()
            onNodeWithTag("offer-choose-0").assertExists()
        }
    }

    @Test
    fun outsideTheShellWalkInRunsWithoutCoordinator() =
        runComposeUiTest {
            val source = wsSource()
            val conn = WsConn(source, SessionState.Active("u-1", "s-1", false))
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, conn, "s-1", emptySet()) } }
            waitForIdle()
            onNodeWithTag("open-walk-in").performClick()
            waitForIdle()
            fillAndChoose()
            footer("walk-in-confirm")
            onNodeWithTag("walk-in-done").assertExists()
            assertEquals(listOf<String?>(null), source.walkInRuns.map { it.token })
        }

    @Test
    fun outsideTheShellApprovalRequiredShowsNoCoordinatorAndKeepsTheDialogUsable() =
        runComposeUiTest {
            val source = wsSource().apply { walkInRequiresApproval = wsRequired }
            val conn = WsConn(source, SessionState.Active("u-1", "s-1", false))
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, conn, "s-1", emptySet()) } }
            waitForIdle()
            onNodeWithTag("open-walk-in").performClick()
            waitForIdle()
            fillAndChoose()
            footer("walk-in-confirm")
            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            onNodeWithTag("walk-in-done").assertDoesNotExist()
            onNodeWithTag("walk-in-close").assertIsEnabled()
            assertEquals(1, source.walkInRuns.size)
        }

    @Test
    fun startRunsOnceAndCloseRefreshesPanelAndRemovesDialog() {
        val source = wsSource()
        shell(WsConn(source)) {
            val days = source.businessDayCalls
            fillAndChoose()
            assertEquals(days, source.businessDayCalls, "start() de novo ao digitar ou escolher")
            val pages = source.dayPageCalls.size
            onNodeWithTag("walk-in-close").performClick()
            waitForIdle()
            onNodeWithTag("walk-in-dialog").assertDoesNotExist()
            assertTrue(source.dayPageCalls.size > pages, "fechar não atualizou o painel")
            assertTrue(source.walkInRuns.isEmpty())
            onNodeWithTag("open-walk-in").performClick()
            waitForIdle()
            onNodeWithTag("guest-query").assertDoesNotExist()
            onNodeWithTag("walk-in-confirm").assertIsNotEnabled()
        }
    }

    @Test
    fun unconfirmedLocksEveryEntryIncludingSegmentAndStayFields() {
        val source = wsSource()
        source.walkInOutcomes += ApiError.Network(java.io.IOException("sem rede"))
        shell(WsConn(source)) {
            fillAndChoose()
            footer("walk-in-confirm")
            onNodeWithText("Não foi possível confirmar o walk-in.", substring = true).assertExists()
            listOf(
                "walk-in-departure",
                "walk-in-adults",
                "walk-in-children",
                "walk-in-segment-LEISURE",
                "walk-in-segment-BUSINESS",
                "walk-in-quote",
                "offer-choose-0",
                "room-choose-0",
                "swap-guest",
            ).forEach { onNodeWithTag(it).assertIsNotEnabled() }
            onNodeWithTag("guest-query").assertDoesNotExist()
            onNodeWithTag("guest-create").assertDoesNotExist()
            onNodeWithTag("guest-search").assertDoesNotExist()
            onNodeWithTag("walk-in-confirm").assertIsEnabled()
        }
    }

    @Test
    fun busyDisablesAdultsChildrenAndSegment() {
        val source = wsSource().apply { holdWalkIn = true }
        shell(WsConn(source)) {
            fillAndChoose()
            footer("walk-in-confirm")
            listOf("walk-in-adults", "walk-in-children", "walk-in-segment-EVENT", "swap-guest")
                .forEach { onNodeWithTag(it).assertIsNotEnabled() }
            onNodeWithTag("walk-in-close").assertIsNotEnabled()
            source.heldWalkIns.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("walk-in-done").assertExists()
            onNodeWithTag("walk-in-close").assertIsEnabled()
            onNodeWithTag("guest-query").assertDoesNotExist()
        }
    }

    @Test
    fun closeIsDisabledWhileGuestIsBeingCreated() {
        val source = wsSource().apply { holdCreateGuest = true }
        shell(WsConn(source)) {
            onNodeWithTag("walk-in-departure").performScrollTo().performTextInput("15/11/2026")
            click("walk-in-quote")
            click("offer-choose-0")
            click("room-choose-0")
            click("swap-guest")
            onNodeWithTag("field-fullName").performScrollTo().performTextInput("Carla Ficticia")
            onNodeWithTag("field-documentNumber").performScrollTo().performTextInput("52998224725")
            onNodeWithTag("field-birthDate").performScrollTo().performTextInput("10/05/1990")
            click("guest-create")
            assertEquals(1, source.createGuestCalls)
            onNodeWithTag("walk-in-close").assertIsNotEnabled()
            source.heldCreateGuests.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("walk-in-close").assertIsEnabled()
        }
    }

    @Test
    fun guestCreationHiddenWithoutManagePermissionButSearchStays() {
        val source = wsSource().apply { permissions = wsAll - "guests.profiles.manage" }
        shell(WsConn(source)) {
            onNodeWithTag("walk-in-departure").performScrollTo().performTextInput("15/11/2026")
            click("walk-in-quote")
            click("offer-choose-0")
            click("room-choose-0")
            click("swap-guest")
            onNodeWithTag("guest-query").assertExists()
            onNodeWithTag("guest-create").assertDoesNotExist()
        }
    }

    @Test
    fun sessionSwitchWithWalkInHeldDoesNotRefreshOldPanelNorShowDone() {
        val source = wsSource().apply { holdWalkIn = true }
        val conn = WsConn(source)
        shell(conn) {
            fillAndChoose()
            footer("walk-in-confirm")
            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()
            val pages = source.dayPageCalls.size
            source.heldWalkIns.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("walk-in-dialog").assertDoesNotExist()
            onNodeWithTag("walk-in-done").assertDoesNotExist()
            assertEquals(pages, source.dayPageCalls.size, "o walk-in da sessão velha atualizou o painel")
        }
    }

    @Test
    fun noUuidRawCodeOrFullDocumentAnywhereInTheShellDialog() {
        val source = wsSource()
        source.walkInOutcomes += ApiError.Problem(422, "ROOM_NOT_READY", "detalhe", emptyList())
        shell(WsConn(source)) {
            fillAndChoose()
            footer("walk-in-confirm")
            val texts = allTexts()
            assertFalse(texts.any { wsUuid.containsMatchIn(it) }, "uuid na tela")
            assertFalse(texts.any { "ROOM_NOT_READY" in it || "walkin:1" in it }, "código cru na tela")
            assertFalse(texts.any { "123.456.789-01" in it }, "documento inteiro")
            onNodeWithTag("walk-in-error").assertExists()
        }
    }
}
