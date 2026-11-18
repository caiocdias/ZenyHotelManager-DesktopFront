package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val shResId = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private val shHolderId = Uuid.parse("30000000-0000-0000-0000-0000000000a1")
private val shRoomA = Uuid.parse("50000000-0000-0000-0000-0000000000d1")
private val shPlanId = Uuid.parse("40000000-0000-0000-0000-0000000000a1")
private val shPerms =
    setOf(
        "reservations.reservations.read",
        "frontdesk.stays.checkin",
        "reservations.assignments.manage",
        "guests.profiles.read",
        "rooms.status.read",
    )

private fun shItem() =
    ReservationListItemResponse(
        adults = 1,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-A1",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "R-A1",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = shHolderId,
        id = shResId,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = shPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

private fun shDetail() =
    ReservationDetailResponse(
        bookingCode = "B-A1",
        holderGuestId = shHolderId,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 1,
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
                code = "R-A1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
                guests = emptyList(),
                id = shResId,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = shPlanId,
                roomAssignment =
                    Option.Some(
                        com.zeny.desktop.sdk.model.RoomAssignmentResponse(
                            active = true,
                            assignedAt = "2026-11-13T15:00:00Z",
                            dayUse = false,
                            doNotMove = false,
                            endsOn = "2026-11-15",
                            id = Uuid.random(),
                            reason = "INITIAL",
                            reservationId = shResId,
                            roomId = shRoomA,
                            roomNumber = Option.Some("101"),
                            startsOn = "2026-11-13",
                        ),
                    ),
                roomTotal = "500.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private fun shResponse() =
    CheckInResponse(
        reservation =
            StayReservationResponse(
                adults = 1,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                children = 0,
                code = "R-A1",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guests = emptyList(),
                id = shResId,
                roomTypeId = Uuid.random(),
                status = "CHECKED_IN",
                version = 2,
            ),
        restrictionAlerts = emptyList(),
        room =
            StayRoomResponse(
                housekeepingStatus = StayRoomResponse.HousekeepingStatus.INSPECTED,
                inspectionPending = false,
                number = "101",
                occupancy = StayRoomResponse.Occupancy.OCCUPIED,
                roomId = shRoomA,
                version = 3,
            ),
    )

private fun shSource() =
    FakeReceptionSource().apply {
        permissions = shPerms
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        pages[DayList.ARRIVALS to null] = Page(listOf(shItem()), null)
        reservations[shResId.toString()] = shDetail()
        ratePlans[shPlanId.toString()] = "Tarifa Balcão"
        holders[shHolderId.toString()] = HolderSummary("Ana Titular", "CPF ***.111.222-**")
        checkInResponse = shResponse()
        roomStatusList += roomStatus(shRoomA.toString(), "101").copy(readyForCheckIn = true)
    }

private class ShSettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-1"
}

private class ShConn(
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

    override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", shPerms)

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

private val shRequired =
    ApiError.ApprovalRequired("frontdesk.stay.checkin", "frontdesk.stays.approve", "reservation:a1", null, null)

/** O check-in dentro da casca real (AppRoot) e fora dela (AreaContent), com um servidor de mentira. */
@OptIn(ExperimentalTestApi::class)
class CheckInShellAtaqueTest {
    private fun keysOf(source: FakeReceptionSource): Int {
        val keys = mutableSetOf<String>()
        source.checkInRuns.forEach { keys += it.key }
        return keys.size
    }

    private fun shell(
        conn: ShConn,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(ShSettings(), { conn }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        onNodeWithText("Recepção").performClick()
        waitForIdle()
        onNodeWithTag("row-$shResId").performClick()
        waitForIdle()
        onNodeWithTag("open-check-in").performClick()
        waitForIdle()
        after()
    }

    @Test
    fun approvalRequiredInsideShellOpensCoordinatorDialogAndApprovedRepeatCompletes() {
        val source = shSource().apply { checkInRequiresApproval = shRequired }
        val conn = ShConn(source)
        shell(conn) {
            val pagesBefore = source.dayPageCalls.size
            val reads = source.reservationCalls.size
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            onNodeWithText("Aprovação de superior").assertExists()
            onNodeWithTag("approval-login").performTextInput("joana.gerente")
            onNodeWithTag("approval-pin").performTextInput("1234")
            onNodeWithTag("approval-reason").performTextInput("Cortesia")
            onNodeWithTag("approval-confirm").performClick()
            waitForIdle()

            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            onNodeWithText("Check-in feito na UH 101.").assertExists()
            assertEquals(listOf(ApprovalInput("joana.gerente", "1234", "Cortesia")), conn.grants)
            assertEquals(listOf(null, "token-1"), source.checkInRuns.map { it.token })
            assertEquals(1, keysOf(source), "a repetição trocou a Idempotency-Key")
            assertTrue(source.dayPageCalls.size > pagesBefore, "o painel não foi atualizado")
            assertTrue(source.reservationCalls.size > reads, "o detalhe não foi relido")
        }
    }

    @Test
    fun cancelledApprovalLeavesDialogUsableWithoutDoneAndKeepsKeyOnRetry() {
        val source = shSource().apply { checkInRequiresApproval = shRequired }
        shell(ShConn(source)) {
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithText("Cancelar").performClick()
            waitForIdle()

            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            onNodeWithText("Check-in feito na UH 101.").assertDoesNotExist()
            onNodeWithTag("check-in-confirm").assertIsEnabled()
            onNodeWithTag("check-in-close").assertIsEnabled()
            assertEquals(listOf<String?>(null), source.checkInRuns.map { it.token })

            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithText("Aprovação de superior").assertExists()
            assertEquals(1, keysOf(source), "cancelar a aprovação trocou a chave")
        }
    }

    @Test
    fun outsideTheShellCheckInRunsWithoutCoordinator() =
        runComposeUiTest {
            val source = shSource()
            val conn = ShConn(source, SessionState.Active("u-1", "s-1", false))
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, conn, "s-1", emptySet()) } }
            waitForIdle()
            onNodeWithTag("row-$shResId").performClick()
            waitForIdle()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()

            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            onNodeWithText("Check-in feito na UH 101.").assertExists()
            assertEquals(listOf<String?>(null), source.checkInRuns.map { it.token })
        }

    @Test
    fun sessionSwitchWithDialogOpenLeavesNoStaleDialog() {
        val source = shSource()
        val conn = ShConn(source)
        shell(conn) {
            onNodeWithTag("check-in-dialog").assertExists()
            val quotes = source.quoteCalls.size

            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            onNodeWithTag("check-in-dialog").assertDoesNotExist()
            assertEquals(quotes, source.quoteCalls.size, "a sessão nova reabriu o diálogo")
        }
    }

    @Test
    fun sessionSwitchDuringHeldCheckInDoesNotRefreshTheOldPanelAfterRelease() {
        val source = shSource().apply { holdCheckIn = true }
        val conn = ShConn(source)
        shell(conn) {
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()
            val pagesAfterSwitch = source.dayPageCalls.size
            source.heldCheckIns.forEach { it.complete(Unit) }
            waitForIdle()

            assertEquals(pagesAfterSwitch, source.dayPageCalls.size, "o check-in da sessão velha atualizou o painel")
            onNodeWithText("Check-in feito na UH 101.").assertDoesNotExist()
        }
    }

    @Test
    fun reopeningTheDialogCreatesFreshStateWithoutPreviousError() {
        val source = shSource()
        source.checkInOutcomes += ApiError.Problem(422, "ROOM_NOT_READY", null, emptyList())
        shell(ShConn(source)) {
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithTag("check-in-error").assertExists()
            val quotes = source.quoteCalls.size

            onNodeWithTag("check-in-close").performClick()
            waitForIdle()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()

            onNodeWithTag("check-in-error").assertDoesNotExist()
            onNodeWithTag("check-in-confirm").assertIsEnabled()
            assertEquals(quotes + 1, source.quoteCalls.size, "o diálogo novo não buscou a cotação")
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            assertEquals(2, source.checkInRuns.size)
            assertNotEquals(source.checkInRuns[0].key, source.checkInRuns[1].key, "chave reaproveitada em estado novo")
        }
    }

    @Test
    fun cancelledApprovalShowsNoErrorInDialog() {
        val source = shSource().apply { checkInRequiresApproval = shRequired }
        shell(ShConn(source)) {
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithText("Cancelar").performClick()
            waitForIdle()

            onNodeWithTag("check-in-error").assertDoesNotExist()
            onNodeWithText("Aprovação de superior").assertDoesNotExist()
        }
    }
}
