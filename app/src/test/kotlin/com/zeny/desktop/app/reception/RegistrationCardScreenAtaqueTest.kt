package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import com.zeny.desktop.design.pdf.SaveLocationChooser
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val raResId = Uuid.parse("20000000-0000-0000-0000-0000000000e1")
private val raOtherId = Uuid.parse("20000000-0000-0000-0000-0000000000e2")
private val raHolderId = Uuid.parse("30000000-0000-0000-0000-0000000000e1")
private val raPlanId = Uuid.parse("40000000-0000-0000-0000-0000000000e1")
private val raRoomId = Uuid.parse("50000000-0000-0000-0000-0000000000e1")
private const val RA_CHECKIN = "frontdesk.stays.checkin"
private val raAll =
    setOf(
        "reservations.reservations.read",
        RA_CHECKIN,
        "reservations.assignments.manage",
        "guests.profiles.read",
        "rooms.status.read",
    )

/** Um PDF mínimo de uma página em branco; o PDFBox reconstrói a tabela de referências. */
private val RA_PDF: ByteArray =
    (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n" +
            "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)

private fun raListItem(
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
    holderGuestId = raHolderId,
    id = id,
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = raPlanId,
    roomAssignment = Option.Undefined,
    roomTypeId = Uuid.parse(typeId(1)),
    segment = "LEISURE",
    status = "CONFIRMED",
    total = "500.00",
    version = 1,
)

private fun raDetail(
    status: String,
    id: Uuid = raResId,
    code: String = "R-E1",
) = ReservationDetailResponse(
    bookingCode = "B-E1",
    holderGuestId = raHolderId,
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
            ratePlanId = raPlanId,
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
                        roomId = raRoomId,
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

private fun raQuote() =
    EarlyLateQuoteResponse(
        arrival = "2026-11-13",
        checkInTime = "14:00",
        checkOutTime = "12:00",
        departure = "2026-11-15",
        earlyCheckIn = EarlyLateOfferResponse(offered = false),
        lateCheckOut = EarlyLateOfferResponse(offered = false),
        reservationId = raResId,
        timezone = "America/Sao_Paulo",
    )

private fun raCheckInResponse() =
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
                id = raResId,
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
                roomId = raRoomId,
                version = 3,
            ),
    )

private class RaCards : RegistrationCards {
    var result: suspend () -> PdfDocument = { PdfDocument(RA_PDF, "ficha.pdf") }
    val calls = mutableListOf<String>()

    override suspend fun registrationCard(reservationId: String): PdfDocument {
        calls += reservationId
        return result()
    }
}

private val RA_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val RA_RAW =
    listOf("REGISTRATION_CARD_UNAVAILABLE", "RESERVATION_NOT_FOUND", "FORBIDDEN", "CONFIRMED", "IN_HOUSE", "409", "404")

private fun raPdf(pages: Int): ByteArray {
    val kids = (0 until pages).joinToString(" ") { "${3 + it} 0 R" }
    val objs =
        (0 until pages).joinToString("") {
            "${3 + it} 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n"
        }
    return (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[$kids]/Count $pages>>endobj\n$objs" +
            "trailer<</Root 1 0 R/Size ${3 + pages}>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)
}

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RegistrationCardScreenAtaqueTest {
    private val cards = RaCards()
    private val documents = mutableListOf<PdfDocumentState>()
    private val titles = mutableListOf<String>()
    private var saveCalls = 0
    private var printCalls = 0

    private fun source(
        permissions: Set<String> = raAll,
        status: String = "CONFIRMED",
    ) = FakeReceptionSource().apply {
        this.permissions = permissions
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        pages[DayList.ARRIVALS to null] =
            Page(listOf(raListItem(raResId, "R-E1"), raListItem(raOtherId, "R-E2")), null)
        ratePlans[raPlanId.toString()] = "Tarifa Balcão"
        reservations[raResId.toString()] = raDetail(status)
        reservations[raOtherId.toString()] = raDetail(status, raOtherId, "R-E2")
        holders[raHolderId.toString()] = HolderSummary("Ana Titular", "CPF ***.111.222-**")
        quotes[raResId.toString()] = raQuote()
        checkInResponse = raCheckInResponse()
        suggestions[raResId.toString()] =
            listOf(
                RoomSuggestionResponse(
                    current = true,
                    featureIds = emptyList(),
                    floor = Option.Some("1"),
                    matchedFeatureIds = emptyList(),
                    matchesFloorPreference = false,
                    number = "101",
                    outOfService = false,
                    roomId = raRoomId,
                ),
            )
        roomStatusList += roomStatus(raRoomId.toString(), "101").copy(readyForCheckIn = true)
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
                        SaveLocationChooser {
                            saveCalls++
                            null as Path?
                        },
                        {
                            printCalls++
                            PrintOutcome.CANCELLED
                        },
                        UnconfinedTestDispatcher(),
                        UnconfinedTestDispatcher(),
                    )
                },
            ).also { titles += it.title }
        }

    private fun ComposeUiTest.screen(
        source: FakeReceptionSource,
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
                    ReceptionScreen(panel, detail, checkIn = checkIn, registrationCard = cardFactory(scope))
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openReservation(id: Uuid = raResId) {
        onNodeWithTag("row-$id").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.awaitViewer() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("pdf-viewer").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun isClosed(document: PdfDocumentState) = !runBlocking { document.open() }

    /** O download só termina quando o teste libera; ignora o cancelamento para exercitar o descarte tardio. */
    private fun hold(bytes: ByteArray = raPdf(1)): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        cards.result = {
            withContext(NonCancellable) { gate.await() }
            PdfDocument(bytes, "ficha.pdf")
        }
        return gate
    }

    private fun ComposeUiTest.visibleTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                val config = node.config
                val text = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                val desc = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                text + desc
            }

    private fun ComposeUiTest.assertNoLeaks() {
        visibleTexts().forEach { t ->
            assertTrue(!RA_UUID.containsMatchIn(t), "UUID na interface: $t")
        }
    }

    // ---- botão por status e permissão, com clique real ----

    @Test
    fun buttonClickOpensViewerForEachAllowedStatus() {
        listOf("CONFIRMED", "IN_HOUSE", "CHECKED_OUT").forEach { status ->
            runComposeUiTest {
                screen(source(status = status))
                openReservation()
                onNodeWithTag("open-registration-card").assertIsDisplayed()
                onNodeWithTag("open-registration-card").performClick()
                awaitViewer()
                onNodeWithText("Ficha de registro — R-E1").assertExists()
            }
        }
    }

    @Test
    fun buttonHiddenForUnknownAndProvisionalStatuses() {
        listOf("PROVISIONAL", "CANCELLED", "NO_SHOW", "SOMETHING_NEW", "", "confirmed").forEach { status ->
            runComposeUiTest {
                screen(source(status = status))
                openReservation()
                onNodeWithTag("reservation-detail").assertExists()
                onNodeWithTag("open-registration-card").assertDoesNotExist()
                assertEquals(emptyList(), cards.calls)
            }
        }
    }

    @Test
    fun buttonHiddenWithOnlyOtherPermissionsAndNoCheckIn() {
        runComposeUiTest {
            screen(source(permissions = raAll - RA_CHECKIN + "frontdesk.stays.checkout"))
            openReservation()
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-registration-card").assertDoesNotExist()
        }
    }

    // ---- abertura repetida ----

    @Test
    fun doubleClickOpensOneStateAndOneDownload() =
        runComposeUiTest {
            val gate = hold()
            screen(source())
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            onNodeWithTag("open-registration-card").performClick()
            gate.complete(Unit)
            awaitViewer()
            waitForIdle()
            assertEquals(1, cards.calls.size, "um clique duplo baixou ${cards.calls.size} vezes")
            assertEquals(1, titles.size)
            assertEquals(1, documents.size)
            assertFalse(isClosed(documents.single()))
        }

    @Test
    fun reopeningAfterCloseCreatesFreshState() =
        runComposeUiTest {
            screen(source())
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            awaitViewer()
            val first = documents.single()
            onNodeWithTag("pdf-close").performClick()
            waitForIdle()
            assertTrue(isClosed(first))
            onNodeWithTag("open-registration-card").performClick()
            awaitViewer()
            assertEquals(2, cards.calls.size)
            assertEquals(2, documents.size)
            assertFalse(isClosed(documents[1]))
        }

    // ---- troca de reserva, fechar o detalhe, sair, sessão: durante o carregamento ----

    @Test
    fun switchingReservationWhileLoadingDiscardsLateDownload() =
        runComposeUiTest {
            val gate = hold()
            lateinit var detail: ReservationDetailState
            screen(source(), panelAndDetail = { _, d -> detail = d })
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            assertEquals(1, cards.calls.size)
            detail.open(raOtherId.toString())
            waitForIdle()
            onNodeWithTag("registration-card-dialog").assertDoesNotExist()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            documents.forEach { assertTrue(isClosed(it), "documento tardio ficou aberto") }
        }

    @Test
    fun switchingReservationWithClickWhileLoadingNeverLeaksAnOpenDocument() =
        runComposeUiTest {
            val gate = hold()
            screen(source())
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            // O diálogo é modal: o clique na outra linha ou é bloqueado ou troca a reserva (e então fecha a ficha).
            runCatching { onNodeWithTag("row-$raOtherId").performClick() }
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()
            val switched = onAllNodesWithTag("registration-card-dialog").fetchSemanticsNodes().isEmpty()
            if (switched) {
                documents.forEach { assertTrue(isClosed(it)) }
            } else {
                awaitViewer()
            }
        }

    @Test
    fun switchingReservationWithViewerOpenClosesPreviousAndNewOpenIsFresh() =
        runComposeUiTest {
            lateinit var detail: ReservationDetailState
            screen(source(), panelAndDetail = { _, d -> detail = d })
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            awaitViewer()
            val first = documents.single()
            detail.open(raOtherId.toString())
            waitForIdle()
            assertTrue(isClosed(first))
            onNodeWithTag("open-registration-card").performClick()
            awaitViewer()
            assertEquals(listOf(raResId.toString(), raOtherId.toString()), cards.calls)
            assertEquals(listOf("Ficha de registro — R-E1", "Ficha de registro — R-E2"), titles)
            onNodeWithText("Ficha de registro — R-E2").assertExists()
        }

    @Test
    fun closingDetailWhileLoadingDiscardsLateDownload() =
        runComposeUiTest {
            val gate = hold()
            lateinit var detail: ReservationDetailState
            screen(source(), panelAndDetail = { _, d -> detail = d })
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            detail.close()
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("registration-card-dialog").assertDoesNotExist()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            documents.forEach { assertTrue(isClosed(it)) }
        }

    @Test
    fun leavingAreaWhileLoadingDiscardsLateDownload() =
        runComposeUiTest {
            val gate = hold()
            var shown by mutableStateOf(true)
            screen(source(), visible = { shown })
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            shown = false
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            documents.forEach { assertTrue(isClosed(it)) }
        }

    @Test
    fun sessionChangeWhileLoadingDiscardsLateDownload() =
        runComposeUiTest {
            val gate = hold()
            var session by mutableStateOf(1)
            screen(source(), session = { session })
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            session = 2
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            onNodeWithTag("registration-card-dialog").assertDoesNotExist()
            documents.forEach { assertTrue(isClosed(it)) }
        }

    // ---- atalho depois do check-in ----

    @Test
    fun checkInShortcutOpensCardOfCheckedInReservationEvenIfDetailSwitched() =
        runComposeUiTest {
            val source = source()
            lateinit var detail: ReservationDetailState
            screen(source, panelAndDetail = { _, d -> detail = d })
            openReservation()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()
            source.reservations[raResId.toString()] = raDetail("IN_HOUSE")
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithText("Check-in feito na UH 101.").assertExists()
            detail.open(raOtherId.toString())
            waitForIdle()
            onNodeWithTag("check-in-open-card").performClick()
            awaitViewer()
            assertEquals(listOf(raResId.toString()), cards.calls)
            assertEquals(listOf("Ficha de registro — R-E1"), titles)
            onNodeWithTag("check-in-done").assertDoesNotExist()
        }

    @Test
    fun checkInShortcutCardClosesWhenDetailThenSwitches() =
        runComposeUiTest {
            val source = source()
            lateinit var detail: ReservationDetailState
            screen(source, panelAndDetail = { _, d -> detail = d })
            openReservation()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()
            source.reservations[raResId.toString()] = raDetail("IN_HOUSE")
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithTag("check-in-open-card").performClick()
            awaitViewer()
            val doc = documents.single()
            detail.close()
            waitForIdle()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            assertTrue(isClosed(doc))
        }

    @Test
    fun checkInShortcutRefusalShowsMessageAndRetryWorks() =
        runComposeUiTest {
            val source = source()
            cards.result = { throw ApiError.Problem(409, "REGISTRATION_CARD_UNAVAILABLE", null, emptyList()) }
            screen(source)
            openReservation()
            onNodeWithTag("open-check-in").performClick()
            waitForIdle()
            source.reservations[raResId.toString()] = raDetail("IN_HOUSE")
            onNodeWithTag("check-in-confirm").performClick()
            waitForIdle()
            onNodeWithTag("check-in-open-card").performClick()
            waitForIdle()
            onNodeWithText("A ficha só existe para reservas confirmadas, hospedadas ou encerradas.").assertExists()
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
            cards.result = { PdfDocument(raPdf(1), "ficha.pdf") }
            onNodeWithTag("registration-card-retry").performClick()
            awaitViewer()
            assertEquals(2, cards.calls.size)
        }

    // ---- recusas do servidor ----

    private fun refusal(
        failure: Exception,
        expected: String,
    ) = runComposeUiTest {
        cards.result = { throw failure }
        screen(source())
        openReservation()
        onNodeWithTag("open-registration-card").performClick()
        waitForIdle()
        onNodeWithTag("registration-card-error").assertIsDisplayed()
        onNodeWithText(expected).assertExists()
        onNodeWithTag("pdf-viewer").assertDoesNotExist()
        assertEquals(emptyList(), documents)
        assertNoLeaks()
        visibleTexts().forEach { t -> RA_RAW.forEach { raw -> assertFalse(t.contains(raw), "texto cru: $t") } }
        // "Tentar de novo" funciona
        cards.result = { PdfDocument(raPdf(1), "ficha.pdf") }
        onNodeWithTag("registration-card-retry").performClick()
        awaitViewer()
        onNodeWithTag("registration-card-error").assertDoesNotExist()
        assertEquals(2, cards.calls.size)
        assertEquals(1, documents.size)
    }

    @Test
    fun conflictRefusalShowsMessageAndRetryWorks() =
        refusal(
            ApiError.Problem(409, "REGISTRATION_CARD_UNAVAILABLE", "x", emptyList()),
            "A ficha só existe para reservas confirmadas, hospedadas ou encerradas.",
        )

    @Test
    fun notFoundRefusalShowsMessageAndRetryWorks() =
        refusal(
            ApiError.Problem(404, "RESERVATION_NOT_FOUND", "x", emptyList()),
            "A reserva não foi encontrada. Atualize o painel.",
        )

    @Test
    fun forbiddenRefusalShowsCommonPermissionText() {
        val failure = ApiError.Problem(403, "FORBIDDEN", "Falta frontdesk.stays.checkin", emptyList())
        refusal(failure, errorMessage(failure))
    }

    @Test
    fun networkRefusalShowsCommonNetworkText() =
        refusal(
            java.io.IOException("connection refused to 10.0.0.1"),
            "Não foi possível falar com o servidor. Confira o endereço e a rede.",
        )

    @Test
    fun refusalCloseButtonClosesDialogAndNextOpenStartsClean() =
        runComposeUiTest {
            cards.result = { throw ApiError.Problem(409, "REGISTRATION_CARD_UNAVAILABLE", null, emptyList()) }
            screen(source())
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            onNodeWithTag("registration-card-close").performClick()
            waitForIdle()
            onNodeWithTag("registration-card-dialog").assertDoesNotExist()
            cards.result = { PdfDocument(raPdf(1), "ficha.pdf") }
            onNodeWithTag("open-registration-card").performClick()
            awaitViewer()
            onNodeWithTag("registration-card-error").assertDoesNotExist()
        }

    // ---- botões do visualizador dentro do diálogo, em várias janelas ----

    private fun ComposeUiTest.assertViewerUsable(
        width: Int,
        height: Int,
    ) {
        cards.result = { PdfDocument(raPdf(2), "ficha.pdf") }
        screen(source())
        openReservation()
        onNodeWithTag("open-registration-card").performClick()
        awaitViewer()
        val window = DpRect(0.dp, 0.dp, with(density) { width.toDp() }, with(density) { height.toDp() })
        val tags = listOf("pdf-prev", "pdf-next", "pdf-zoom-out", "pdf-zoom-in", "pdf-save", "pdf-print", "pdf-close")
        tags.forEach { tag ->
            val node = onNodeWithTag(tag)
            node.assertExists()
            val b = node.getBoundsInRoot()
            assertTrue(b.width > 0.dp && b.height > 0.dp, "$tag com área vazia: $b (janela ${width}x$height)")
            assertTrue(
                b.left >= window.left && b.top >= window.top && b.right <= window.right && b.bottom <= window.bottom,
                "$tag fora da janela ${width}x$height: $b",
            )
            node.assertIsDisplayed()
        }
        onNodeWithText("Página 1 de 2").assertExists()
        onNodeWithTag("pdf-next").performClick()
        waitForIdle()
        onNodeWithText("Página 2 de 2").assertExists()
        onNodeWithTag("pdf-prev").performClick()
        waitForIdle()
        onNodeWithText("Página 1 de 2").assertExists()
        onNodeWithTag("pdf-zoom-in").performClick()
        waitForIdle()
        onNodeWithText("125%").assertExists()
        onNodeWithTag("pdf-zoom-out").performClick()
        onNodeWithTag("pdf-zoom-out").performClick()
        waitForIdle()
        onNodeWithText("75%").assertExists()
        onNodeWithTag("pdf-save").performClick()
        waitForIdle()
        assertEquals(1, saveCalls)
        onNodeWithTag("pdf-print").performClick()
        waitForIdle()
        assertEquals(1, printCalls)
        assertNoLeaks()
        val doc = documents.single()
        onNodeWithTag("pdf-close").performClick()
        waitForIdle()
        onNodeWithTag("pdf-viewer").assertDoesNotExist()
        assertTrue(isClosed(doc))
    }

    @Test
    fun viewerButtonsUsableAtDefaultWindow() = runComposeUiTest { assertViewerUsable(1024, 768) }

    @Test
    fun viewerButtonsUsableAtSmallWindow() = runDesktopComposeUiTest(800, 600) { assertViewerUsable(800, 600) }

    @Test
    fun viewerButtonsUsableAtTinyWindow() = runDesktopComposeUiTest(480, 360) { assertViewerUsable(480, 360) }

    @Test
    fun viewerButtonsUsableAtLargeWindow() = runDesktopComposeUiTest(1920, 1080) { assertViewerUsable(1920, 1080) }

    // ---- nada cru na interface ----

    @Test
    fun noUuidOrRawCodeInTreeWhileLoadingOrViewing() =
        runComposeUiTest {
            val gate = hold()
            screen(source())
            openReservation()
            onNodeWithTag("open-registration-card").performClick()
            waitForIdle()
            assertNoLeaks()
            gate.complete(Unit)
            awaitViewer()
            assertNoLeaks()
            visibleTexts().forEach { t -> assertFalse(t.contains("REGISTRATION"), t) }
            assertNotNull(documents.singleOrNull())
        }
}
