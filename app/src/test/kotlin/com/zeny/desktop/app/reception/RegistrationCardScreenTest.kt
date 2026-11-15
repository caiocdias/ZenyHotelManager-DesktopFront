package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
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
import com.zeny.desktop.sdk.model.StayRoomResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val rcResId = Uuid.parse("20000000-0000-0000-0000-0000000000e1")
private val rcOtherId = Uuid.parse("20000000-0000-0000-0000-0000000000e2")
private val rcHolderId = Uuid.parse("30000000-0000-0000-0000-0000000000e1")
private val rcPlanId = Uuid.parse("40000000-0000-0000-0000-0000000000e1")
private val rcRoomId = Uuid.parse("50000000-0000-0000-0000-0000000000e1")
private const val RC_CHECKIN = "frontdesk.stays.checkin"
private val rcAll =
    setOf(
        "reservations.reservations.read",
        RC_CHECKIN,
        "reservations.assignments.manage",
        "guests.profiles.read",
        "rooms.status.read",
    )

/** Um PDF mínimo de uma página em branco; o PDFBox reconstrói a tabela de referências. */
private val RC_PDF: ByteArray =
    (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n" +
            "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)

private fun rcListItem(
    id: Uuid,
    code: String,
) = ReservationListItemResponse(
    adults = 2,
    arrival = "2026-11-13",
    arrivalOverdue = false,
    bookingCode = "B-E1",
    bookingId = Uuid.random(),
    channel = "DIRECT",
    children = 0,
    code = code,
    dayUse = false,
    departure = "2026-11-15",
    departureOverdue = false,
    folioId = Uuid.random(),
    holderGuestId = rcHolderId,
    id = id,
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = rcPlanId,
    roomAssignment = Option.Undefined,
    roomTypeId = Uuid.parse(typeId(1)),
    segment = "LEISURE",
    status = "CONFIRMED",
    total = "500.00",
    version = 1,
)

private fun rcDetail(
    status: String,
    id: Uuid = rcResId,
    code: String = "R-E1",
) = ReservationDetailResponse(
    bookingCode = "B-E1",
    holderGuestId = rcHolderId,
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
            code = code,
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = "2026-11-15",
            folioId = Uuid.random(),
            guaranteeTerms =
                GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
            guests = emptyList(),
            id = id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = rcPlanId,
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
                        reservationId = id,
                        roomId = rcRoomId,
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

private fun rcQuote() =
    EarlyLateQuoteResponse(
        arrival = "2026-11-13",
        checkInTime = "14:00",
        checkOutTime = "12:00",
        departure = "2026-11-15",
        earlyCheckIn = EarlyLateOfferResponse(offered = false),
        lateCheckOut = EarlyLateOfferResponse(offered = false),
        reservationId = rcResId,
        timezone = "America/Sao_Paulo",
    )

private fun rcCheckInResponse() =
    CheckInResponse(
        reservation =
            StayReservationResponse(
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                children = 0,
                code = "R-E1",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guests = emptyList(),
                id = rcResId,
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
                roomId = rcRoomId,
                version = 3,
            ),
    )

private class RcCards : RegistrationCards {
    var result: suspend () -> PdfDocument = { PdfDocument(RC_PDF, "ficha.pdf") }
    val calls = mutableListOf<String>()

    override suspend fun registrationCard(reservationId: String): PdfDocument {
        calls += reservationId
        return result()
    }
}

@OptIn(ExperimentalTestApi::class)
class RegistrationCardScreenTest {
    private val cards = RcCards()
    private val documents = mutableListOf<PdfDocumentState>()
    private val titles = mutableListOf<String>()

    private fun source(
        permissions: Set<String> = rcAll,
        status: String = "CONFIRMED",
    ) = FakeReceptionSource().apply {
        this.permissions = permissions
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        pages[DayList.ARRIVALS to null] =
            Page(listOf(rcListItem(rcResId, "R-E1"), rcListItem(rcOtherId, "R-E2")), null)
        ratePlans[rcPlanId.toString()] = "Tarifa Balcão"
        reservations[rcResId.toString()] = rcDetail(status)
        reservations[rcOtherId.toString()] = rcDetail(status, rcOtherId, "R-E2")
        holders[rcHolderId.toString()] = HolderSummary("Ana Titular", "CPF ***.111.222-**")
        quotes[rcResId.toString()] = rcQuote()
        checkInResponse = rcCheckInResponse()
        suggestions[rcResId.toString()] =
            listOf(
                RoomSuggestionResponse(
                    current = true,
                    featureIds = emptyList(),
                    floor = Option.Some("1"),
                    matchedFeatureIds = emptyList(),
                    matchesFloorPreference = false,
                    number = "101",
                    outOfService = false,
                    roomId = rcRoomId,
                ),
            )
        roomStatusList += roomStatus(rcRoomId.toString(), "101").copy(readyForCheckIn = true)
    }

    private fun cardFactory(scope: kotlinx.coroutines.CoroutineScope): (String, String) -> RegistrationCardState =
        { id, code ->
            RegistrationCardState(
                cards,
                scope,
                id,
                code,
                newDocument = { bytes, s -> PdfDocumentState(bytes, s).also { documents += it } },
                newActions = { bytes, name ->
                    PdfActions(
                        bytes,
                        name,
                        { null },
                        { PrintOutcome.CANCELLED },
                        UnconfinedTestDispatcher(),
                        UnconfinedTestDispatcher(),
                    )
                },
            ).also { titles += it.title }
        }

    private fun ComposeUiTest.screen(
        source: FakeReceptionSource,
        withCards: Boolean = true,
        session: () -> Int = { 0 },
        visible: () -> Boolean = { true },
        panelAndDetail: (DayPanelState, ReservationDetailState) -> Unit = { _, _ -> },
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val key = session()
            val panel = remember(key) { DayPanelState(source, scope) }
            val detail = remember(key) { ReservationDetailState(source, scope) }
            panelAndDetail(panel, detail)
            val checkIn: (CheckInSeed) -> CheckInState = { seed ->
                CheckInState(
                    source,
                    scope,
                    seed,
                    panel.permissions,
                    { it.run(null) },
                    { Instant.parse("2026-11-13T13:00:00Z") },
                ) {
                    panel.refresh()
                    detail.reload()
                }
            }
            ZenyTheme {
                if (visible()) {
                    ReceptionScreen(
                        panel,
                        detail,
                        checkIn = checkIn,
                        registrationCard = if (withCards) cardFactory(scope) else null,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openReservation(id: Uuid = rcResId) {
        onNodeWithTag("row-$id").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.awaitViewer() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("pdf-viewer").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.openCard() {
        onNodeWithTag("open-registration-card").performClick()
        awaitViewer()
    }

    private fun isClosed(document: PdfDocumentState) = !runBlocking { document.open() }

    @Test
    fun buttonOnlyForAllowedStatusesAndPermission() {
        listOf("CONFIRMED", "IN_HOUSE", "CHECKED_OUT").forEach { status ->
            runComposeUiTest {
                screen(source(status = status))
                openReservation()
                onNodeWithTag("open-registration-card").assertExists()
            }
        }
        listOf("CANCELLED", "NO_SHOW").forEach { status ->
            runComposeUiTest {
                screen(source(status = status))
                openReservation()
                onNodeWithTag("reservation-detail").assertExists()
                onNodeWithTag("open-registration-card").assertDoesNotExist()
            }
        }
        runComposeUiTest {
            screen(source(permissions = rcAll - RC_CHECKIN))
            openReservation()
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-registration-card").assertDoesNotExist()
        }
        runComposeUiTest {
            screen(source(), withCards = false)
            openReservation()
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-registration-card").assertDoesNotExist()
        }
    }

    @Test
    fun openingShowsViewer() =
        runComposeUiTest {
            screen(source())
            openReservation()
            openCard()
            assertEquals(listOf(rcResId.toString()), cards.calls)
            assertEquals(listOf("Ficha de registro — R-E1"), titles)
            onNodeWithText("Ficha de registro — R-E1").assertExists()
            onNodeWithTag("pdf-save").assertExists()
            onNodeWithTag("pdf-print").assertExists()
        }

    @Test
    fun serverRefusalShowsMessageNotViewer() =
        runComposeUiTest {
            cards.result = { throw ApiError.Problem(409, "REGISTRATION_CARD_UNAVAILABLE", "detalhe", emptyList()) }
            screen(source())
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            onNodeWithText("A ficha só existe para reservas confirmadas, hospedadas ou encerradas.").assertExists()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            onNodeWithTag("registration-card-close").performClick()
            waitForIdle()
            onNodeWithTag("registration-card-dialog").assertDoesNotExist()
        }

    @Test
    fun checkInDoneOffersCard() =
        runComposeUiTest {
            val source = source()
            screen(source)
            openReservation()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()
            onNodeWithTag("check-in-open-card").assertDoesNotExist()
            source.reservations[rcResId.toString()] = rcDetail("IN_HOUSE")
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithText("Check-in feito na UH 101.").assertExists()
            onNodeWithTag("check-in-open-card").performClick()
            awaitViewer()
            assertEquals(listOf(rcResId.toString()), cards.calls)
            assertEquals(listOf("Ficha de registro — R-E1"), titles)
        }

    @Test
    fun checkInDoneWithoutCardFactoryHasNoButton() =
        runComposeUiTest {
            screen(source(), withCards = false)
            openReservation()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithText("Check-in feito na UH 101.").assertExists()
            onNodeWithTag("check-in-open-card").assertDoesNotExist()
        }

    @Test
    fun closingViewerClosesState() =
        runComposeUiTest {
            screen(source())
            openReservation()
            openCard()
            val document = documents.single()
            assertFalse(isClosed(document))
            onNodeWithTag("pdf-close").performClick()
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            assertTrue(isClosed(document))
        }

    @Test
    fun closingDetailClosesCard() =
        runComposeUiTest {
            lateinit var detail: ReservationDetailState
            screen(source(), panelAndDetail = { _, d -> detail = d })
            openReservation()
            openCard()
            detail.close()
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            assertTrue(isClosed(documents.single()))
        }

    @Test
    fun switchingReservationClosesCard() =
        runComposeUiTest {
            lateinit var detail: ReservationDetailState
            screen(source(), panelAndDetail = { _, d -> detail = d })
            openReservation()
            openCard()
            detail.open(rcOtherId.toString())
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            assertTrue(isClosed(documents.single()))
            // Voltar à primeira reserva não traz a ficha de volta.
            detail.open(rcResId.toString())
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
        }

    @Test
    fun leavingAreaClosesCard() =
        runComposeUiTest {
            var shown by mutableStateOf(true)
            screen(source(), visible = { shown })
            openReservation()
            openCard()
            shown = false
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            assertTrue(isClosed(documents.single()))
        }

    @Test
    fun sessionChangeClosesCard() =
        runComposeUiTest {
            var session by mutableStateOf(1)
            screen(source(), session = { session })
            openReservation()
            openCard()
            session = 2
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            assertTrue(isClosed(documents.single()))
        }
}
