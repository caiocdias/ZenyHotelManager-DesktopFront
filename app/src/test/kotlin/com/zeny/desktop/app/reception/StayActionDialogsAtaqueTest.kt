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
import androidx.compose.ui.test.hasTestTag
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
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val adResId = Uuid.parse("20000000-0000-0000-0000-000000000092")
private val adHolderId = Uuid.parse("30000000-0000-0000-0000-000000000092")
private val adPlanId = Uuid.parse("40000000-0000-0000-0000-000000000092")
private val adRoom101 = Uuid.parse("50000000-0000-0000-0000-0000000000d1")
private val adRoom102 = Uuid.parse("50000000-0000-0000-0000-0000000000d2")
private val adRoom103 = Uuid.parse("50000000-0000-0000-0000-0000000000d3")
private val adUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val adRawCodes = listOf("ROOM_NOT_READY", "IN_HOUSE", "GUEST_REQUEST", "KEEP", "RECALCULATE", "CONFIRMED")
private const val AD_ETAG = "\"v1\""
private val adMovePermissions = setOf("frontdesk.stays.move", "rooms.status.read", "reservations.reservations.read")
private val adUndoPermissions = setOf("frontdesk.stays.checkin", "reservations.reservations.read")
private val adAll = adMovePermissions + adUndoPermissions

private fun adListItem() =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-92",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "R-92",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = adHolderId,
        id = adResId,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = adPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "IN_HOUSE",
        total = "500.00",
        version = 1,
    )

private fun adCancellation() =
    CancellationTermsResponse(
        24,
        "Flexível",
        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
        policyId = Uuid.random(),
        version = 1,
    )

private fun adDetail(status: String = "IN_HOUSE") =
    ReservationDetailResponse(
        bookingCode = "B-92",
        holderGuestId = adHolderId,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                cancellationTerms = adCancellation(),
                childAges = emptyList(),
                code = "R-92",
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
                id = adResId,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = adPlanId,
                roomAssignment =
                    Option.Some(
                        RoomAssignmentResponse(
                            active = true,
                            assignedAt = "2026-11-13T15:00:00Z",
                            dayUse = false,
                            doNotMove = false,
                            endsOn = "2026-11-15",
                            id = Uuid.random(),
                            reason = "INITIAL",
                            reservationId = adResId,
                            roomId = adRoom101,
                            roomNumber = Option.Some("101"),
                            startsOn = "2026-11-13",
                        ),
                    ),
                roomTotal = "500.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = status,
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private fun adMoved(): RoomMoveResponse {
    fun room(
        id: Uuid,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
        "roomId":"$id","version":5}"""
    val any = Uuid.random()
    val json =
        """{"difference":"0.00","remainingAfter":"500.00","remainingBefore":"500.00","tariff":"KEEP",
        "fromRoom":${room(adRoom101, "101")},"toRoom":${room(adRoom102, "102")},
        "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$any","children":0,"code":"R-92",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$adResId",
        "roomTypeId":"$any","status":"IN_HOUSE","version":4}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
}

private fun adUndone(): UndoCheckInResponse {
    val any = Uuid.random()
    val json =
        """{"reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$any","children":0,"code":"R-92",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$adResId",
        "roomId":"$adRoom101","roomTypeId":"$any","status":"CONFIRMED","version":3},
        "room":{"housekeepingStatus":"CLEAN","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$adRoom101","version":5}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(UndoCheckInResponse.serializer(), json)
}

private class AdSettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-1"
}

private class AdConn(
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

    override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", adAll)

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

private val adRequired =
    ApiError.ApprovalRequired("frontdesk.stay.undocheckin", "frontdesk.stays.approve", "reservation:92", null, null)

/** Ataque aos diálogos de troca de UH e desfazer check-in, no detalhe (componente) e na casca (ReceptionArea). */
@OptIn(ExperimentalTestApi::class)
class StayActionDialogsAtaqueTest {
    private var cancelMove = false
    private val moveStates = mutableListOf<RoomMoveState>()
    private val undoStates = mutableListOf<UndoCheckInState>()

    private fun source(
        permissions: Set<String> = adAll,
        status: String = "IN_HOUSE",
    ) = FakeReceptionSource().apply {
        this.permissions = permissions
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        pages[DayList.ARRIVALS to null] = Page(listOf(adListItem()), null)
        ratePlans[adPlanId.toString()] = "Tarifa Balcão"
        val detail = adDetail(status)
        reservations[adResId.toString()] = detail
        versions[adResId.toString()] = ReservationVersion(detail, AD_ETAG)
        holders[adHolderId.toString()] = HolderSummary("Ana Titular", "CPF ***.111.222-**")
        roomStatusList +=
            listOf(
                roomStatus(adRoom101.toString(), "101", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                roomStatus(adRoom103.toString(), "103").copy(readyForCheckIn = true),
                roomStatus(adRoom102.toString(), "102").copy(readyForCheckIn = true),
            )
        moveResponse = adMoved()
        undoResponse = adUndone()
    }

    private fun ComposeUiTest.screen(source: FakeReceptionSource) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope) }
            val detail = remember { ReservationDetailState(source, scope) }
            val move: (String) -> RoomMoveState = { id ->
                val approve: suspend (ApprovableAction<RoomMoveResponse>) -> ApiResponse<RoomMoveResponse>? =
                    { if (cancelMove) null else it.run(null) }
                RoomMoveState(source, scope, id, approve) {
                    panel.refresh()
                    detail.reload()
                }.also { moveStates += it }
            }
            val undo: (String) -> UndoCheckInState = { id ->
                val approve: suspend (ApprovableAction<UndoCheckInResponse>) -> ApiResponse<UndoCheckInResponse>? =
                    { it.run(null) }
                UndoCheckInState(source, scope, id, approve) {
                    panel.refresh()
                    detail.reload()
                }.also { undoStates += it }
            }
            ZenyTheme { ReceptionScreen(panel, detail, roomMove = move, undoCheckIn = undo) }
        }
        waitForIdle()
        onNodeWithTag("row-$adResId").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.tap(tag: String) {
        val footer = tag.endsWith("-confirm") || tag.endsWith("-close") || tag.startsWith("open-")
        if (footer) onNodeWithTag(tag).performClick() else onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    private fun collectTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap { collectTexts(it) }

    private fun ComposeUiTest.allTexts() =
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { collectTexts(it) }

    private fun assertClean(texts: List<String>) {
        assertFalse(texts.any { adUuid.containsMatchIn(it) }, "UUID na árvore: $texts")
        val raw = adRawCodes + listOf("RESERVATION_TRANSITION_NOT_ALLOWED", "UNDO_CHECK_IN_EXPIRED", "OUT_OF_ORDER")
        assertFalse(texts.any { t -> raw.any { it in t } }, "código cru na árvore: $texts")
    }

    @Test
    fun buttonsAbsentForEveryStatusOtherThanInHouse() {
        listOf("CHECKED_OUT", "CANCELLED", "NO_SHOW", "CONFIRMED", "PENDING").forEach { status ->
            runComposeUiTest {
                screen(source(status = status))
                onNodeWithTag("reservation-detail").assertExists()
                onNodeWithTag("open-room-move").assertDoesNotExist()
                onNodeWithTag("open-undo-check-in").assertDoesNotExist()
            }
        }
    }

    @Test
    fun undoButtonNeedsStaysCheckInNotUndoCheckInPermission() {
        runComposeUiTest {
            val perms = adMovePermissions + "frontdesk.stays.undocheckin"
            screen(source(perms))
            onNodeWithTag("open-room-move").assertExists()
            onNodeWithTag("open-undo-check-in").assertDoesNotExist()
        }
        runComposeUiTest {
            screen(source(adUndoPermissions))
            onNodeWithTag("open-undo-check-in").assertExists()
            onNodeWithTag("open-room-move").assertDoesNotExist()
        }
    }

    @Test
    fun undoDialogShowsErrorHighlightedAndStaysOpenWithoutDone() =
        runComposeUiTest {
            val source = source()
            source.undoOutcomes += ApiError.Problem(409, "UNDO_CHECK_IN_EXPIRED", "x", emptyList())
            screen(source)
            tap("open-undo-check-in")
            tap("undo-confirm")
            val expected = ActionText.undoError(ApiError.Problem(409, "UNDO_CHECK_IN_EXPIRED", null, emptyList()))
            onNodeWithTag("undo-error").assertExists()
            onNodeWithText(expected).assertExists()
            onNodeWithText("Check-in desfeito.").assertDoesNotExist()
            onNodeWithTag("undo-close").assertIsEnabled()
            assertClean(allTexts())
        }

    @Test
    fun undoDoneReplacesQuestionAndOffersOnlyClose() =
        runComposeUiTest {
            screen(source())
            tap("open-undo-check-in")
            tap("undo-confirm")
            onNodeWithText("Check-in desfeito.").assertExists()
            onNodeWithTag("undo-confirm").assertDoesNotExist()
            onNodeWithTag("undo-close").assertIsEnabled()
            assertFalse(allTexts().any { it.startsWith("Desfazer o check-in da reserva") })
            assertClean(allTexts())
            tap("undo-close")
            onNodeWithTag("undo-dialog").assertDoesNotExist()
        }

    @Test
    fun undoConfirmDuringBusyRunsTheActionOnce() =
        runComposeUiTest {
            val source = source().apply { holdUndo = true }
            screen(source)
            tap("open-undo-check-in")
            tap("undo-confirm")
            undoStates.single().confirm()
            waitForIdle()
            assertEquals(1, source.undoCalls.size)
            source.heldUndos.forEach { it.complete(Unit) }
            waitForIdle()
            assertEquals(1, source.undoRuns.size)
        }

    @Test
    fun undoCloseKillsTheStateAndReopeningCreatesAFreshOne() =
        runComposeUiTest {
            val source = source()
            source.undoOutcomes += ApiError.Problem(409, "UNDO_CHECK_IN_EXPIRED", null, emptyList())
            screen(source)
            tap("open-undo-check-in")
            tap("undo-confirm")
            onNodeWithTag("undo-error").assertExists()
            tap("undo-close")
            val first = undoStates.single()
            first.confirm()
            waitForIdle()
            assertEquals(1, source.undoCalls.size, "o estado fechado ainda confirma")
            tap("open-undo-check-in")
            assertEquals(2, undoStates.size)
            assertTrue(undoStates[1] !== first)
            onNodeWithTag("undo-error").assertDoesNotExist()
            onNodeWithTag("undo-confirm").assertIsEnabled()
        }

    @Test
    fun moveCloseKillsTheStateAndReopeningStartsAgain() =
        runComposeUiTest {
            val source = source()
            screen(source)
            tap("open-room-move")
            assertEquals(1, source.roomStatusesCalls)
            tap("room-move-choose-0")
            tap("room-move-close")
            onNodeWithTag("room-move-dialog").assertDoesNotExist()
            val first = moveStates.single()
            first.start()
            waitForIdle()
            assertEquals(1, source.roomStatusesCalls, "o estado fechado voltou a carregar")
            tap("open-room-move")
            assertEquals(2, moveStates.size)
            assertEquals(2, source.roomStatusesCalls, "a reabertura não chamou start de novo")
            onNodeWithTag("room-move-confirm").assertIsNotEnabled()
            onNodeWithTag("room-move-error").assertDoesNotExist()
        }

    @Test
    fun moveDoneShowsSummaryOnlyAndReopenIsFresh() =
        runComposeUiTest {
            val source = source()
            screen(source)
            tap("open-room-move")
            tap("room-move-choose-0")
            tap("room-move-confirm")
            val texts = allTexts()
            assertTrue(texts.any { it.startsWith("Troca feita para a UH 102.") }, "$texts")
            onNodeWithTag("room-move-confirm").assertDoesNotExist()
            onNodeWithTag("room-move-choose-0").assertDoesNotExist()
            onNodeWithTag("room-move-close").assertIsEnabled()
            assertClean(texts)
            tap("room-move-close")
            tap("open-room-move")
            onNodeWithTag("room-move-done").assertDoesNotExist()
            onNodeWithTag("room-move-confirm").assertIsNotEnabled()
        }

    @Test
    fun movePreconditionFailedShowsThe412Text() =
        runComposeUiTest {
            val source = source()
            source.moveOutcomes += ApiError.PreconditionFailed("etag velho")
            screen(source)
            tap("open-room-move")
            tap("room-move-choose-0")
            tap("room-move-confirm")
            onNodeWithText("Alguém alterou esta reserva. Confira e confirme de novo.").assertExists()
            onNodeWithTag("room-move-error").assertExists()
            onNodeWithTag("room-move-dialog").assertExists()
            assertClean(allTexts())
        }

    @Test
    fun moveApprovalCancelledShowsNothingNew() =
        runComposeUiTest {
            cancelMove = true
            val source = source()
            screen(source)
            tap("open-room-move")
            tap("room-move-choose-0")
            tap("room-move-confirm")
            onNodeWithTag("room-move-error").assertDoesNotExist()
            onNodeWithTag("room-move-done").assertDoesNotExist()
            onNodeWithTag("room-move-confirm").assertIsEnabled()
            onNodeWithTag("room-move-close").assertIsEnabled()
            assertTrue(source.moveRuns.isEmpty())
        }

    @Test
    fun moveControlsDisabledWhileLoadingAndConfirmOnlyWithChoice() =
        runComposeUiTest {
            val source = source().apply { holdVersion = true }
            screen(source)
            tap("open-room-move")
            onNodeWithText("Buscando UHs...").assertExists()
            onNodeWithTag("room-move-confirm").assertIsNotEnabled()
            onNodeWithTag("room-move-reason-OPERATIONAL").assertIsNotEnabled()
            onNodeWithTag("room-move-tariff-RECALCULATE").assertIsNotEnabled()
            onNodeWithTag("room-move-close").assertIsEnabled()
            source.heldVersions.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithText("Buscando UHs...").assertDoesNotExist()
            onNodeWithTag("room-move-reason-OPERATIONAL").assertIsEnabled()
            onNodeWithTag("room-move-confirm").assertIsNotEnabled()
            tap("room-move-choose-1")
            onNodeWithTag("room-move-confirm").assertIsEnabled()
            onNodeWithTag("room-move-choose-1").assertIsNotEnabled()
        }

    @Test
    fun moveDialogListsRoomsInNaturalOrderWithoutTheCurrentOne() =
        runComposeUiTest {
            screen(source())
            tap("open-room-move")
            val texts = allTexts()
            assertEquals(listOf("102", "103"), texts.filter { it == "102" || it == "103" || it == "101" })
            assertEquals(2, texts.count { it == "Escolher" })
            assertTrue("UH atual: 101" in texts)
        }

    private fun shell(
        conn: AdConn,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(AdSettings(), { conn }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        onNodeWithText("Recepção").performClick()
        waitForIdle()
        onNodeWithTag("row-$adResId").performClick()
        waitForIdle()
        after()
    }

    private fun ComposeUiTest.approve() {
        onNodeWithText("Aprovação de superior").assertExists()
        onNodeWithTag("approval-login").performTextInput("joana.gerente")
        onNodeWithTag("approval-pin").performTextInput("1234")
        onNodeWithTag("approval-reason").performTextInput("Cortesia")
        onNodeWithTag("approval-confirm").performClick()
        waitForIdle()
    }

    @Test
    fun shellUndoApprovalGrantsTokenAndRefreshesPanelAndDetail() {
        val source = source().apply { undoRequiresApproval = adRequired }
        val conn = AdConn(source)
        shell(conn) {
            onNodeWithTag("open-undo-check-in").performClick()
            waitForIdle()
            val pages = source.dayPageCalls.size
            val reads = source.reservationCalls.size
            onNodeWithTag("undo-confirm").performClick()
            waitForIdle()
            approve()
            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            onNodeWithText("Check-in desfeito.").assertExists()
            assertEquals(listOf(ApprovalInput("joana.gerente", "1234", "Cortesia")), conn.grants)
            assertEquals(listOf<String?>(null, "token-1"), source.undoRuns.map { it.token })
            assertTrue(source.dayPageCalls.size > pages, "o painel não foi atualizado")
            assertTrue(source.reservationCalls.size > reads, "o detalhe não foi relido")
        }
    }

    @Test
    fun shellUndoCancelledApprovalDoesNotRepeatNorShowError() {
        val source = source().apply { undoRequiresApproval = adRequired }
        shell(AdConn(source)) {
            onNodeWithTag("open-undo-check-in").performClick()
            waitForIdle()
            onNodeWithTag("undo-confirm").performClick()
            waitForIdle()
            onNodeWithText("Cancelar").performClick()
            waitForIdle()
            onNodeWithText("Check-in desfeito.").assertDoesNotExist()
            onNodeWithTag("undo-error").assertDoesNotExist()
            onNodeWithTag("undo-confirm").assertIsEnabled()
            assertEquals(listOf<String?>(null), source.undoRuns.map { it.token })
        }
    }

    @Test
    fun shellMoveApprovalGrantsTokenKeepsKeyAndRefreshes() {
        val required =
            ApiError.ApprovalRequired("frontdesk.stay.move", "frontdesk.stays.approve", "reservation:92", null, null)
        val source = source().apply { moveRequiresApproval = required }
        val conn = AdConn(source)
        shell(conn) {
            onNodeWithTag("open-room-move").performClick()
            waitForIdle()
            onNodeWithTag("room-move-choose-0").performClick()
            waitForIdle()
            val pages = source.dayPageCalls.size
            val reads = source.reservationCalls.size
            onNodeWithTag("room-move-confirm").performClick()
            waitForIdle()
            approve()
            assertEquals(listOf<String?>(null, "token-1"), source.moveRuns.map { it.token })
            assertEquals(
                1,
                source.moveRuns
                    .map { it.key }
                    .toSet()
                    .size,
                "a repetição trocou a chave",
            )
            assertTrue(source.dayPageCalls.size > pages, "o painel não foi atualizado")
            assertTrue(source.reservationCalls.size > reads, "o detalhe não foi relido")
            onNodeWithTag("room-move-done").assertExists()
        }
    }

    @Test
    fun outsideTheShellBothActionsRunWithoutCoordinator() =
        runComposeUiTest {
            val source = source()
            val conn = AdConn(source, SessionState.Active("u-1", "s-1", false))
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, conn) } }
            waitForIdle()
            onNodeWithTag("row-$adResId").performClick()
            waitForIdle()
            onNodeWithTag("open-undo-check-in").performClick()
            waitForIdle()
            onNodeWithTag("undo-confirm").performClick()
            waitForIdle()
            onNodeWithText("Check-in desfeito.").assertExists()
            assertEquals(listOf<String?>(null), source.undoRuns.map { it.token })
            onNodeWithTag("undo-close").performClick()
            waitForIdle()
            onNodeWithTag("open-room-move").performClick()
            waitForIdle()
            onNodeWithTag("room-move-choose-0").performClick()
            waitForIdle()
            onNodeWithTag("room-move-confirm").performClick()
            waitForIdle()
            assertEquals(listOf<String?>(null), source.moveRuns.map { it.token })
        }

    @Test
    fun openDialogSurvivesARecompositionOfTheReceptionArea() =
        runComposeUiTest {
            val source = source()
            val conn = AdConn(source, SessionState.Active("u-1", "s-1", false))
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, conn) } }
            waitForIdle()
            onNodeWithTag("row-$adResId").performClick()
            waitForIdle()
            onNodeWithTag("open-undo-check-in").performClick()
            waitForIdle()
            assertTrue(onAllNodes(hasTestTag("undo-dialog")).fetchSemanticsNodes().isNotEmpty(), "o diálogo nem abriu")

            // Mesma sessão, outro valor: a área recompõe, o painel e o detalhe continuam os mesmos.
            conn.state.value = SessionState.Active("u-1", "s-1", false, passwordChangeAwaitingRefresh = true)
            waitForIdle()

            assertTrue(
                onAllNodes(hasTestTag("undo-dialog")).fetchSemanticsNodes().isNotEmpty(),
                "a recomposição da área derrubou o diálogo aberto",
            )
        }
}
