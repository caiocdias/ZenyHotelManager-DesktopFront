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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.EarlyLateOfferResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRestrictionAlertResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import de.quati.kotlin.util.Option
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val dlgResId = Uuid.parse("20000000-0000-0000-0000-000000000091")
private val dlgHolderId = Uuid.parse("30000000-0000-0000-0000-000000000091")
private val dlgPlanId = Uuid.parse("40000000-0000-0000-0000-000000000091")
private val dlgRoomA = Uuid.parse("50000000-0000-0000-0000-0000000000c1")
private val dlgRoomB = Uuid.parse("50000000-0000-0000-0000-0000000000c2")
private val dlgUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private const val DLG_RAW_CODE = "ROOM_NOT_READY"
private const val DLG_NOT_READY =
    "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança."
private val dlgAll =
    setOf(
        "reservations.reservations.read",
        "frontdesk.stays.checkin",
        "reservations.assignments.manage",
        "guests.profiles.read",
        "guests.profiles.manage",
        "rooms.status.read",
    )

private fun dlgListItem() =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-91",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "R-91",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = dlgHolderId,
        id = dlgResId,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = dlgPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

private fun dlgAssignment() =
    RoomAssignmentResponse(
        active = true,
        assignedAt = "2026-11-13T15:00:00Z",
        dayUse = false,
        doNotMove = false,
        endsOn = "2026-11-15",
        id = Uuid.random(),
        reason = "INITIAL",
        reservationId = dlgResId,
        roomId = dlgRoomA,
        roomNumber = Option.Some("101"),
        startsOn = "2026-11-13",
    )

private fun dlgDetail(
    status: String = "CONFIRMED",
    assigned: Boolean = true,
) = ReservationDetailResponse(
    bookingCode = "B-91",
    holderGuestId = dlgHolderId,
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
            code = "R-91",
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
            id = dlgResId,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = dlgPlanId,
            roomAssignment = if (assigned) Option.Some(dlgAssignment()) else Option.Undefined,
            roomTotal = "500.00",
            roomTypeId = Uuid.parse(typeId(1)),
            segment = "LEISURE",
            status = status,
            total = "500.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun dlgSuggestion(
    room: Uuid,
    number: String,
    outOfService: Boolean = false,
    preferred: Boolean = false,
) = RoomSuggestionResponse(
    current = false,
    featureIds = emptyList(),
    floor = Option.Some("1"),
    matchedFeatureIds = emptyList(),
    matchesFloorPreference = preferred,
    number = number,
    outOfService = outOfService,
    roomId = room,
)

private fun dlgQuote() =
    EarlyLateQuoteResponse(
        arrival = "2026-11-13",
        checkInTime = "14:00",
        checkOutTime = "12:00",
        departure = "2026-11-15",
        earlyCheckIn =
            EarlyLateOfferResponse(
                offered = true,
                fee = Option.Some("80.00"),
                limitTime = Option.Some("10:00"),
                from = Option.Some("2026-11-13T13:00:00Z"),
                until = Option.Some("2026-11-13T15:00:00Z"),
            ),
        lateCheckOut = EarlyLateOfferResponse(offered = false),
        reservationId = dlgResId,
        timezone = "America/Sao_Paulo",
    )

private fun dlgCheckInResponse() =
    CheckInResponse(
        reservation =
            StayReservationResponse(
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                children = 0,
                code = "R-91",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guests = emptyList(),
                id = dlgResId,
                roomTypeId = Uuid.random(),
                status = "CHECKED_IN",
                version = 2,
            ),
        restrictionAlerts =
            listOf(StayRestrictionAlertResponse(guestId = dlgHolderId, reason = "Fumante", since = "2026-11-01")),
        room =
            StayRoomResponse(
                housekeepingStatus = StayRoomResponse.HousekeepingStatus.INSPECTED,
                inspectionPending = false,
                number = "101",
                occupancy = StayRoomResponse.Occupancy.OCCUPIED,
                roomId = dlgRoomA,
                version = 3,
            ),
    )

@OptIn(ExperimentalTestApi::class)
class CheckInDialogTest {
    private var changes = 0
    private val created = mutableListOf<CheckInState>()

    private fun source(
        permissions: Set<String> = dlgAll,
        detail: ReservationDetailResponse = dlgDetail(),
    ) = FakeReceptionSource().apply {
        this.permissions = permissions
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        pages[DayList.ARRIVALS to null] = Page(listOf(dlgListItem()), null)
        ratePlans[dlgPlanId.toString()] = "Tarifa Balcão"
        reservations[dlgResId.toString()] = detail
        holders[dlgHolderId.toString()] = HolderSummary("Ana Titular", "CPF ***.111.222-**")
        quotes[dlgResId.toString()] = dlgQuote()
        checkInResponse = dlgCheckInResponse()
        suggestions[dlgResId.toString()] =
            listOf(
                dlgSuggestion(dlgRoomA, "101", preferred = true),
                dlgSuggestion(dlgRoomB, "102", outOfService = true),
            )
        roomStatusList +=
            roomStatus(dlgRoomA.toString(), "101").copy(readyForCheckIn = true)
        roomStatusList += roomStatus(dlgRoomB.toString(), "102")
    }

    private fun ComposeUiTest.screen(
        source: FakeReceptionSource,
        withCheckIn: Boolean = true,
        approve: suspend (ApprovableAction<CheckInResponse>) -> ApiResponse<CheckInResponse>? = { it.run(null) },
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope, source.permissions) }
            val detail = remember { ReservationDetailState(source, scope) }
            val factory: ((CheckInSeed) -> CheckInState)? =
                if (withCheckIn) {
                    { seed ->
                        CheckInState(
                            source,
                            scope,
                            seed,
                            panel.permissions,
                            approve,
                            { Instant.parse("2026-11-13T13:00:00Z") },
                        ) {
                            changes++
                            panel.refresh()
                            detail.reload()
                        }.also { created += it }
                    }
                } else {
                    null
                }
            ZenyTheme {
                if (factory == null) ReceptionScreen(panel, detail) else ReceptionScreen(panel, detail, factory)
            }
        }
        waitForIdle()
        onNodeWithTag("row-$dlgResId").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openDialog() {
        onNodeWithTag("open-check-in").performClick()
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
    fun checkInButtonOnlyForConfirmedWithPermission() =
        runComposeUiTest {
            screen(source())
            onNodeWithTag("open-check-in").assertExists()
        }

    @Test
    fun buttonHiddenWhenNotConfirmed() =
        runComposeUiTest {
            screen(source(detail = dlgDetail(status = "IN_HOUSE")))
            onNodeWithTag("open-check-in").assertDoesNotExist()
        }

    @Test
    fun buttonHiddenWithoutCheckInPermission() =
        runComposeUiTest {
            screen(source(permissions = dlgAll - "frontdesk.stays.checkin"))
            onNodeWithTag("open-check-in").assertDoesNotExist()
        }

    @Test
    fun existingCallsWithoutCheckInHideButton() =
        runComposeUiTest {
            screen(source(), withCheckIn = false)
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-check-in").assertDoesNotExist()
        }

    @Test
    fun dialogShowsSuggestionsWithReadiness() =
        runComposeUiTest {
            val source = source()
            screen(source)
            openDialog()

            val texts = allTexts()
            listOf(
                "UH",
                "UH atual: 101",
                "101",
                "102",
                "Pronta",
                "Fora de serviço",
                "Andar preferido",
                "Alocar",
                "Soltar UH",
                "Não mover",
                "Early check-in",
                "Cobrar early check-in",
                "Hóspede principal",
                "Ana Titular",
                "Trocar hóspede",
            ).forEach { assertTrue(it in texts || texts.any { t -> t.contains(it) }, "faltou \"$it\" em $texts") }
            assertTrue(texts.any { it.startsWith("Entrada a partir de 10:00") }, "faltou a oferta do early em $texts")
            assertEquals(1, source.quoteCalls.size)
            onNodeWithTag("check-in-confirm").assertIsEnabled()
        }

    @Test
    fun assignFromDialog() =
        runComposeUiTest {
            val source = source()
            screen(source)
            openDialog()
            val before = changes

            onNodeWithTag("assign-1").performClick()
            waitForIdle()

            assertEquals(listOf(AssignCall(dlgResId.toString(), dlgRoomB.toString(), null)), source.assignCalls)
            assertTrue(changes > before)
        }

    @Test
    fun allocationHiddenWithoutPermission() =
        runComposeUiTest {
            screen(source(permissions = dlgAll - "reservations.assignments.manage"))
            openDialog()

            onNodeWithText("Alocar").assertDoesNotExist()
            onNodeWithText("Soltar UH").assertDoesNotExist()
            onNodeWithText("Não mover").assertDoesNotExist()
            onNodeWithText("UH atual: 101").assertExists()
        }

    @Test
    fun withoutRoomShowsNoRoomAndDisablesConfirm() =
        runComposeUiTest {
            screen(source(detail = dlgDetail(assigned = false)))
            openDialog()

            onNode(hasText("Sem UH") and hasAnyAncestor(hasTestTag("check-in-dialog"))).assertExists()
            onNodeWithTag("check-in-confirm").assertIsNotEnabled()
        }

    @Test
    fun confirmShowsDoneAndRefreshes() =
        runComposeUiTest {
            val source = source()
            screen(source)
            openDialog()
            val reservationReads = source.reservationCalls.size
            val before = changes
            source.reservations[dlgResId.toString()] = dlgDetail(status = "IN_HOUSE")

            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            onNodeWithText("Check-in feito na UH 101.").assertExists()
            assertTrue(allTexts().any { it.contains("Fumante") })
            assertEquals(before + 1, changes)
            assertTrue(source.reservationCalls.size > reservationReads)
            onNodeWithTag("check-in-close").assertIsEnabled()
            onNodeWithTag("check-in-confirm").assertDoesNotExist()

            onNodeWithTag("check-in-close").performClick()
            waitForIdle()
            onNodeWithText("Check-in feito na UH 101.").assertDoesNotExist()
        }

    @Test
    fun closeDisabledWhileBusy() =
        runComposeUiTest {
            val source = source().apply { holdCheckIn = true }
            screen(source)
            openDialog()

            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            assertTrue(created.single().busy)
            onNodeWithTag("check-in-close").assertIsNotEnabled()
            onNodeWithTag("charge-early").assertIsNotEnabled()
            onNodeWithTag("swap-guest").assertIsNotEnabled()

            source.heldCheckIns.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithText("Check-in feito na UH 101.").assertExists()
        }

    @Test
    fun allocationAndGuestChangesDisabledWhileCheckInInFlight() =
        runComposeUiTest {
            val source = source().apply { holdCheckIn = true }
            screen(source)
            openDialog()
            onNodeWithTag("swap-guest").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("assign-1").assertIsEnabled()
            onNodeWithTag("unassign").assertIsEnabled()
            onNodeWithTag("do-not-move").assertIsEnabled()
            onNodeWithTag("guest-query").assertExists()

            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            assertTrue(created.single().busy)
            onNodeWithTag("assign-1").assertIsNotEnabled()
            onNodeWithTag("unassign").assertIsNotEnabled()
            onNodeWithTag("do-not-move").assertIsNotEnabled()
            onNodeWithTag("guest-query").assertDoesNotExist()
            source.heldCheckIns.forEach { it.complete(Unit) }
            waitForIdle()
        }

    @Test
    fun closeDisabledWhileAllocationInFlight() =
        runComposeUiTest {
            val source = source().apply { holdAssign = true }
            screen(source)
            openDialog()

            onNodeWithTag("assign-1").performClick()
            waitForIdle()

            assertTrue(created.single().allocation.busy)
            onNodeWithTag("check-in-close").assertIsNotEnabled()
            source.heldAssigns.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("check-in-close").assertIsEnabled()
        }

    @Test
    fun closingDialogReloadsDetail() =
        runComposeUiTest {
            val source = source()
            screen(source)
            openDialog()
            val before = source.reservationCalls.size

            onNodeWithTag("check-in-close").performClick()
            waitForIdle()

            assertTrue(source.reservationCalls.size > before)
        }

    @Test
    fun guestPanelStaysOpenWhenHolderArrivesLate() =
        runComposeUiTest {
            val source = source()
            screen(source)
            source.holdHolder = true
            openDialog()
            onNodeWithTag("swap-guest").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("guest-query").assertExists()

            source.heldHolders.forEach { it.second.complete(Unit) }
            waitForIdle()

            assertTrue("Ana Titular" in allTexts())
            onNodeWithTag("guest-query").assertExists()
        }

    @Test
    fun ruleErrorShowsNextStep() =
        runComposeUiTest {
            val source = source()
            source.checkInOutcomes += ApiError.Problem(422, DLG_RAW_CODE, null, emptyList())
            screen(source)
            openDialog()

            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            onNodeWithText(DLG_NOT_READY).assertExists()
            assertFalse(allTexts().any { it.contains(DLG_RAW_CODE) })
            onNodeWithTag("check-in-confirm").assertIsEnabled()
        }

    @Test
    fun pickingGuestClosesPickerAndShowsNewPrimary() =
        runComposeUiTest {
            val source = source()
            val carla =
                GuestPick("30000000-0000-0000-0000-000000000092", HolderSummary("Carla Teste", "CPF ***.333.444-**"))
            source.guestsByName["Carla"] = listOf(carla)
            screen(source)
            openDialog()

            onNodeWithTag("swap-guest").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("guest-query").performTextInput("Carla")
            onNodeWithTag("guest-search").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("guest-result-0").assertExists()

            onNodeWithTag("guest-pick-0").performScrollTo().performClick()
            waitForIdle()

            onNodeWithTag("guest-result-0").assertDoesNotExist()
            onNodeWithTag("guest-query").assertDoesNotExist()
            assertTrue("Carla Teste" in allTexts())
        }

    @Test
    fun dialogStartsOnceAndCloseClosesState() =
        runComposeUiTest {
            val source = source()
            screen(source)
            openDialog()
            assertEquals(1, source.quoteCalls.size)
            assertEquals(1, source.suggestionCalls.size)

            onNodeWithTag("check-in-close").performClick()
            waitForIdle()

            onNodeWithTag("check-in-close").assertDoesNotExist()
            created.single().start()
            waitForIdle()
            assertEquals(1, source.quoteCalls.size)
        }

    @Test
    fun noUuidInDialog() =
        runComposeUiTest {
            screen(source())
            openDialog()
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            val offenders = allTexts().filter { dlgUuid.containsMatchIn(it) }
            assertTrue(offenders.isEmpty(), "UUID na tela: $offenders")
        }

    @Test
    fun noRawCodeInDialog() =
        runComposeUiTest {
            val source = source()
            val raw = "{\"type\":\"urn:zeny\"}"
            source.checkInOutcomes += ApiError.Problem(422, "SOME_UNMAPPED_CODE", raw, emptyList())
            screen(source)
            openDialog()
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()

            val texts = allTexts()
            assertFalse(texts.any { it.contains("SOME_UNMAPPED_CODE") || it.contains("urn:zeny") }, "$texts")
            assertFalse(texts.any { Regex("\\b[A-Z]+(_[A-Z]+)+\\b").containsMatchIn(it) }, "$texts")
        }
}
