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
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Page
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
import kotlinx.serialization.json.Json
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val sdResId = Uuid.parse("20000000-0000-0000-0000-000000000092")
private val sdHolderId = Uuid.parse("30000000-0000-0000-0000-000000000092")
private val sdPlanId = Uuid.parse("40000000-0000-0000-0000-000000000092")
private val sdRoom101 = Uuid.parse("50000000-0000-0000-0000-0000000000d1")
private val sdRoom102 = Uuid.parse("50000000-0000-0000-0000-0000000000d2")
private val sdRoom103 = Uuid.parse("50000000-0000-0000-0000-0000000000d3")
private val sdUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val sdRawCodes = listOf("ROOM_NOT_READY", "IN_HOUSE", "GUEST_REQUEST", "KEEP", "RECALCULATE", "CONFIRMED")
private const val SD_ETAG = "\"v1\""
private val sdMovePermissions = setOf("frontdesk.stays.move", "rooms.status.read", "reservations.reservations.read")
private val sdUndoPermissions = setOf("frontdesk.stays.checkin", "reservations.reservations.read")
private val sdAll = sdMovePermissions + sdUndoPermissions

private fun sdListItem() =
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
        holderGuestId = sdHolderId,
        id = sdResId,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = sdPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "IN_HOUSE",
        total = "500.00",
        version = 1,
    )

private fun sdCancellation() =
    CancellationTermsResponse(
        24,
        "Flexível",
        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
        policyId = Uuid.random(),
        version = 1,
    )

private fun sdDetail(status: String = "IN_HOUSE") =
    ReservationDetailResponse(
        bookingCode = "B-92",
        holderGuestId = sdHolderId,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                cancellationTerms = sdCancellation(),
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
                id = sdResId,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = sdPlanId,
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
                            reservationId = sdResId,
                            roomId = sdRoom101,
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

private fun sdMoved(): RoomMoveResponse {
    fun room(
        id: Uuid,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
        "roomId":"$id","version":5}"""
    val any = Uuid.random()
    val json =
        """{"difference":"0.00","remainingAfter":"500.00","remainingBefore":"500.00","tariff":"KEEP",
        "fromRoom":${room(sdRoom101, "101")},"toRoom":${room(sdRoom102, "102")},
        "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$any","children":0,"code":"R-92",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$sdResId",
        "roomTypeId":"$any","status":"IN_HOUSE","version":4}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
}

private fun sdUndone(): UndoCheckInResponse {
    val any = Uuid.random()
    val json =
        """{"reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$any","children":0,"code":"R-92",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$sdResId",
        "roomId":"$sdRoom101","roomTypeId":"$any","status":"CONFIRMED","version":3},
        "room":{"housekeepingStatus":"CLEAN","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$sdRoom101","version":5}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(UndoCheckInResponse.serializer(), json)
}

@OptIn(ExperimentalTestApi::class)
class StayActionDialogsTest {
    private var changes = 0
    private var cancelUndo = false

    private fun source(
        permissions: Set<String> = sdAll,
        status: String = "IN_HOUSE",
    ) = FakeReceptionSource().apply {
        this.permissions = permissions
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        pages[DayList.ARRIVALS to null] = Page(listOf(sdListItem()), null)
        ratePlans[sdPlanId.toString()] = "Tarifa Balcão"
        val detail = sdDetail(status)
        reservations[sdResId.toString()] = detail
        versions[sdResId.toString()] = ReservationVersion(detail, SD_ETAG)
        holders[sdHolderId.toString()] = HolderSummary("Ana Titular", "CPF ***.111.222-**")
        roomStatusList +=
            listOf(
                roomStatus(sdRoom101.toString(), "101", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                roomStatus(sdRoom103.toString(), "103").copy(readyForCheckIn = true),
                roomStatus(sdRoom102.toString(), "102").copy(readyForCheckIn = true),
            )
        moveResponse = sdMoved()
        undoResponse = sdUndone()
    }

    private fun ComposeUiTest.screen(
        source: FakeReceptionSource,
        withActions: Boolean = true,
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope, source.permissions) }
            val detail = remember { ReservationDetailState(source, scope) }
            val move: ((String) -> RoomMoveState)? =
                if (withActions) {
                    { id ->
                        val approve: suspend (ApprovableAction<RoomMoveResponse>) -> ApiResponse<RoomMoveResponse>? =
                            { it.run(null) }
                        RoomMoveState(source, scope, id, approve) {
                            changes++
                            panel.refresh()
                            detail.reload()
                        }
                    }
                } else {
                    null
                }
            val undo: ((String) -> UndoCheckInState)? =
                if (withActions) {
                    { id ->
                        val approve: suspend (
                            ApprovableAction<UndoCheckInResponse>,
                        ) -> ApiResponse<UndoCheckInResponse>? =
                            { if (cancelUndo) null else it.run(null) }
                        UndoCheckInState(source, scope, id, approve) {
                            changes++
                            panel.refresh()
                            detail.reload()
                        }
                    }
                } else {
                    null
                }
            ZenyTheme {
                if (move == null && undo == null) {
                    ReceptionScreen(panel, detail)
                } else {
                    ReceptionScreen(panel, detail, roomMove = move, undoCheckIn = undo)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("row-$sdResId").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.clickFooter(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
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
    fun buttonsOnlyForInHouseWithPermissions() {
        runComposeUiTest {
            screen(source())
            onNodeWithTag("open-room-move").assertExists()
            onNodeWithTag("open-undo-check-in").assertExists()
        }
        runComposeUiTest {
            screen(source(status = "CONFIRMED"))
            onNodeWithTag("open-room-move").assertDoesNotExist()
            onNodeWithTag("open-undo-check-in").assertDoesNotExist()
        }
        sdMovePermissions.forEach { missing ->
            runComposeUiTest {
                screen(source(sdAll - missing))
                onNodeWithTag("open-room-move").assertDoesNotExist()
                onNodeWithTag("open-undo-check-in").assertExists()
            }
        }
        runComposeUiTest {
            screen(source(sdAll - "frontdesk.stays.checkin"))
            onNodeWithTag("open-room-move").assertExists()
            onNodeWithTag("open-undo-check-in").assertDoesNotExist()
        }
    }

    @Test
    fun existingCallsHaveNoStayButtons() =
        runComposeUiTest {
            screen(source(), withActions = false)
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-room-move").assertDoesNotExist()
            onNodeWithTag("open-undo-check-in").assertDoesNotExist()
        }

    @Test
    fun moveDialogListsRoomsAndConfirms() =
        runComposeUiTest {
            val source = source()
            screen(source)
            clickFooter("open-room-move")
            val texts = allTexts()
            assertTrue("UH atual: 101" in texts)
            assertTrue("102" in texts && "103" in texts)
            listOf("GUEST_REQUEST", "OUT_OF_ORDER", "ROOM_ISSUE", "OPERATIONAL").forEach {
                assertTrue(ReceptionText.assignmentReason(it) in texts, it)
            }
            listOf("KEEP", "RECALCULATE").forEach { assertTrue(ActionText.tariff(it) in texts, it) }
            assertTrue("Motivo" in texts && "Tarifa das noites restantes" in texts)
            onNodeWithTag("room-move-confirm").assertIsNotEnabled()
            click("room-move-choose-0")
            click("room-move-reason-OPERATIONAL")
            click("room-move-tariff-RECALCULATE")
            onNodeWithTag("room-move-confirm").assertIsEnabled()
            clickFooter("room-move-confirm")
            val call = source.moveCalls.single()
            assertEquals("OPERATIONAL", call.input.reason)
            assertEquals("RECALCULATE", call.input.tariff)
            assertEquals(sdRoom102.toString(), call.input.roomId)
            assertEquals(SD_ETAG, call.etag)
            onNodeWithText(ActionText.moveSummary(sdMoved())).assertExists()
            assertEquals(1, changes)
            clickFooter("room-move-close")
            onNodeWithTag("room-move-dialog").assertDoesNotExist()
        }

    @Test
    fun moveDialogShowsPreconditionText() =
        runComposeUiTest {
            val source = source()
            source.moveOutcomes += ApiError.Problem(409, "ROOM_NOT_READY", "detalhe", emptyList())
            screen(source)
            clickFooter("open-room-move")
            click("room-move-choose-0")
            clickFooter("room-move-confirm")
            val expected = ActionText.moveError(ApiError.Problem(409, "ROOM_NOT_READY", null, emptyList()))
            onNodeWithTag("room-move-error").assertExists()
            onNodeWithText(expected).assertExists()
            assertFalse(allTexts().any { "ROOM_NOT_READY" in it })
        }

    @Test
    fun undoDialogConfirms() =
        runComposeUiTest {
            val source = source()
            screen(source)
            clickFooter("open-undo-check-in")
            onNodeWithText("Desfazer o check-in da reserva R-92? A reserva volta a confirmada e a UH fica vaga.")
                .assertExists()
            onNodeWithText(
                "A caução e o early lançados ficam na conta: libere e estorne pela conta da reserva.",
            ).assertExists()
            clickFooter("undo-confirm")
            assertEquals(1, source.undoRuns.size)
            assertEquals(listOf(sdResId.toString()), source.undoCalls)
            onNodeWithText("Check-in desfeito.").assertExists()
            assertEquals(1, changes)
            clickFooter("undo-close")
            onNodeWithTag("undo-dialog").assertDoesNotExist()
        }

    @Test
    fun undoApprovalCancelledShowsNothing() =
        runComposeUiTest {
            cancelUndo = true
            val source = source()
            screen(source)
            clickFooter("open-undo-check-in")
            clickFooter("undo-confirm")
            onNodeWithText("Check-in desfeito.").assertDoesNotExist()
            onNodeWithTag("undo-error").assertDoesNotExist()
            onNodeWithTag("undo-confirm").assertIsEnabled()
            assertEquals(0, changes)
            assertTrue(source.undoRuns.isEmpty())
        }

    @Test
    fun controlsDisabledWhileBusy() {
        runComposeUiTest {
            val source = source().apply { holdUndo = true }
            screen(source)
            clickFooter("open-undo-check-in")
            clickFooter("undo-confirm")
            onNodeWithTag("undo-confirm").assertIsNotEnabled()
            onNodeWithTag("undo-close").assertIsNotEnabled()
            source.heldUndos.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("undo-close").assertIsEnabled()
        }
        runComposeUiTest {
            val source = source().apply { holdMove = true }
            screen(source)
            clickFooter("open-room-move")
            click("room-move-choose-0")
            clickFooter("room-move-confirm")
            onNodeWithTag("room-move-confirm").assertIsNotEnabled()
            onNodeWithTag("room-move-close").assertIsNotEnabled()
            onNodeWithTag("room-move-choose-1").assertIsNotEnabled()
            onNodeWithTag("room-move-reason-OPERATIONAL").assertIsNotEnabled()
            onNodeWithTag("room-move-tariff-RECALCULATE").assertIsNotEnabled()
            source.heldMoves.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("room-move-close").assertIsEnabled()
        }
    }

    @Test
    fun noUuidOrRawCode() =
        runComposeUiTest {
            val source = source()
            source.moveOutcomes += ApiError.Problem(409, "ROOM_NOT_READY", "detalhe", emptyList())
            screen(source)
            clickFooter("open-room-move")
            click("room-move-choose-0")
            clickFooter("room-move-confirm")
            val moveTexts = allTexts()
            assertFalse(moveTexts.any { sdUuid.containsMatchIn(it) })
            assertFalse(moveTexts.any { text -> sdRawCodes.any { it in text } })
            clickFooter("room-move-close")
            clickFooter("open-undo-check-in")
            val undoTexts = allTexts()
            assertFalse(undoTexts.any { sdUuid.containsMatchIn(it) })
        }
}
