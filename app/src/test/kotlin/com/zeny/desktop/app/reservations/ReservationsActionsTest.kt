package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationGuaranteeResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val MANAGE = "reservations.reservations.manage"
private const val READ = "reservations.reservations.read"
private const val AVAILABILITY_READ = "reservations.availability.read"
private const val GUESTS_READ = "guests.profiles.read"
private const val TAB = "Disponibilidade e cotação"
private const val CHANGE_BUTTON = "open-reservation-change"
private const val CONFIRM_BUTTON = "open-reservation-confirm"
private const val PDF_BUTTON = "open-reservation-confirmation"
private val arType = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val arPlan = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val arGuest = GuestPick("70000000-0000-0000-0000-000000000001", HolderSummary("Ana Teste", "CPF ***.***-01"))
private val arCreatedId = Uuid.parse("30000000-0000-0000-0000-000000000009")

/** Uma reserva do servidor com a situação, o day use e a garantia que os testes variam. */
@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
private fun arReservation(
    item: ReservationListItemResponse,
    dayUse: Boolean = false,
    guaranteeType: String = "NONE",
    guaranteeMethod: String? = null,
    id: Uuid = item.id,
    code: String = item.code,
) = ReservationResponse(
    additionalPackagesTotal = "0.00",
    adults = 2,
    arrival = item.arrival,
    bookingId = item.bookingId,
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
    dayUse = dayUse,
    departure = item.departure,
    folioId = item.folioId,
    guarantee =
        guaranteeMethod
            ?.let { Option.Some(ReservationGuaranteeResponse(confirmedAt = "2026-11-11T12:00:00Z", method = it)) }
            ?: Option.Undefined,
    guaranteeTerms =
        GuaranteeTermsResponse(name = "Política", policyId = Uuid.random(), type = guaranteeType, version = 1),
    guests = emptyList(),
    id = id,
    nightlyRates = emptyList(),
    penaltiesCharged = "0.00",
    ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
    ratePlanId = item.ratePlanId,
    roomTotal = "300.00",
    roomTypeId = arType,
    segment = "LEISURE",
    status = item.status,
    total = "300.00",
    version = 1,
)

private fun arDetail(
    item: ReservationListItemResponse,
    dayUse: Boolean = false,
    guaranteeType: String = "NONE",
    guaranteeMethod: String? = null,
) = ReservationDetailResponse(
    bookingCode = item.bookingCode,
    holderGuestId = item.holderGuestId,
    reservation = arReservation(item, dayUse, guaranteeType, guaranteeMethod),
    restrictionAlerts = emptyList(),
)

private fun arOffer() =
    QuoteOfferResponse(
        accommodationTotal = "655.37",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = arPlan,
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = arPlan,
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "FLEX",
        ratePlanId = arPlan,
        ratePlanName = "Flexível",
        roomTotal = "655.37",
        roomTypeCode = "STD",
        roomTypeId = arType,
        roomTypeName = "Standard",
        total = "655.37",
    )

private fun arBooking(): BookingResponse {
    val seed = searchItemOf(code = "R-90")
    return BookingResponse(
        channel = "DIRECT",
        code = "B-12",
        createdAt = "2026-11-12T10:00:00Z",
        holderGuestId = Uuid.parse(arGuest.id),
        id = Uuid.random(),
        origin = "FRONT_DESK",
        reservations = listOf(arReservation(seed, id = arCreatedId)),
        restrictionAlerts = emptyList(),
        version = 1,
    )
}

/** A conexão de mentira da área Reservas: cada porta é opcional, e as nulas não existem na conexão. */
@Suppress("LongParameterList") // uma porta opcional por parâmetro
private class ActionsConnection(
    val reception: FakeReceptionSource?,
    val search: FakeReservationSearchSource,
    val availability: FakeAvailabilitySource? = null,
    val newReservation: NewReservationSource? = null,
    val change: ReservationChangeSource? = null,
    val confirm: ReservationConfirmSource? = null,
    val confirmations: ReservationConfirmations? = null,
) : ServerConnection {
    override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)

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

    override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", setOf(READ, "frontdesk.stays.checkin"))

    override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

    override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 13))

    override suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String = error("não usado")

    override fun receptionSource(): ReceptionSource? = reception

    override fun reservationSearchSource(): ReservationSearchSource = search

    override fun availabilitySource(): AvailabilitySource? = availability

    override fun newReservationSource(): NewReservationSource? = newReservation

    override fun reservationChangeSource(): ReservationChangeSource? = change

    override fun reservationConfirmSource(): ReservationConfirmSource? = confirm

    override fun reservationConfirmations(): ReservationConfirmations? = confirmations

    override fun close() = Unit
}

private class Settings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-1"
}

/** As ações de Reservas no detalhe da busca e a nova reserva na cotação, pela área inteira ou só pela tela. */
@OptIn(ExperimentalTestApi::class)
class ReservationsActionsTest {
    private class Rig(
        val search: FakeReservationSearchSource,
        val reception: FakeReceptionSource,
    )

    private fun rig(
        vararg items: ReservationListItemResponse,
        details: (ReservationListItemResponse) -> ReservationDetailResponse = ::arDetail,
    ): Rig {
        val search = FakeReservationSearchSource()
        val reception = FakeReceptionSource()
        items.forEach { item ->
            search.reservations += item
            search.holders[item.holderGuestId.toString()] = HolderSummary("Hóspede ${item.code}", null)
            reception.reservations[item.id.toString()] = details(item)
            reception.holders[item.holderGuestId.toString()] = HolderSummary("Hóspede ${item.code}", null)
            reception.histories[item.id.toString()] = emptyList()
            reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        }
        return Rig(search, reception)
    }

    private fun item(
        status: String = "CONFIRMED",
        code: String = "R-1",
    ) = searchItemOf(code = code, status = status)

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** Abre a reserva na tela da busca (sem a casca) e devolve os botões de ação que o detalhe mostra. */
    private fun visibleActions(
        reservation: ReservationListItemResponse,
        detail: ReservationDetailResponse,
        permissions: Set<String>,
    ): Set<String> {
        var shown = emptySet<String>()
        runComposeUiTest {
            val rig = rig(reservation) { detail }
            setContent {
                val scope = rememberCoroutineScope()
                val search = remember { ReservationSearchState(rig.search, scope, pageSize = 2) }
                val state = remember { ReservationDetailState(rig.reception, scope) }
                val actions =
                    ReservationsActions(
                        permissions,
                        change = { error("não usado") },
                        confirm = { error("não usado") },
                        confirmation = { _, _ -> error("não usado") },
                    )
                ZenyTheme { ReservationsScreen(search, state, actions) }
                remember(search) { search.start() }
            }
            waitForIdle()
            onNodeWithText(reservation.code).performClick()
            waitForIdle()
            shown = listOf(CHANGE_BUTTON, CONFIRM_BUTTON, PDF_BUTTON).filterTo(mutableSetOf()) { hasTag(it) }
        }
        return shown
    }

    private fun shown(
        status: String,
        permissions: Set<String> = setOf(MANAGE, READ),
        dayUse: Boolean = false,
        guaranteeType: String = "NONE",
        guaranteeMethod: String? = null,
    ): Set<String> {
        val reservation = item(status)
        return visibleActions(reservation, arDetail(reservation, dayUse, guaranteeType, guaranteeMethod), permissions)
    }

    @Test
    fun changeOnlyForOpenStatusesWithManage() {
        val open = setOf("PROVISIONAL", "CONFIRMED", "IN_HOUSE")
        listOf("PROVISIONAL", "CONFIRMED", "IN_HOUSE", "CHECKED_OUT", "CANCELLED", "NO_SHOW").forEach { status ->
            assertEquals(status in open, CHANGE_BUTTON in shown(status), "Alterar em $status")
        }
        assertFalse(CHANGE_BUTTON in shown("CONFIRMED", permissions = setOf(READ)), "sem manage")
        assertFalse(CHANGE_BUTTON in shown("CONFIRMED", dayUse = true), "day use")
    }

    @Test
    fun confirmOnlyForProvisionalOrMissingGuarantee() {
        assertTrue(CONFIRM_BUTTON in shown("PROVISIONAL"))
        assertTrue(CONFIRM_BUTTON in shown("PROVISIONAL", guaranteeType = "NONE"))
        assertTrue(CONFIRM_BUTTON in shown("CONFIRMED", guaranteeType = "CARD"))
        assertTrue(CONFIRM_BUTTON in shown("CONFIRMED", guaranteeType = "DEPOSIT"))
        // A garantia registrada de outro meio não vale para o exigido.
        assertTrue(CONFIRM_BUTTON in shown("CONFIRMED", guaranteeType = "DEPOSIT", guaranteeMethod = "CARD"))
        assertFalse(CONFIRM_BUTTON in shown("CONFIRMED", guaranteeType = "DEPOSIT", guaranteeMethod = "DEPOSIT"))
        assertFalse(CONFIRM_BUTTON in shown("CONFIRMED", guaranteeType = "CARD", guaranteeMethod = "CARD"))
        assertFalse(CONFIRM_BUTTON in shown("CONFIRMED", guaranteeType = "NONE"))
        assertFalse(CONFIRM_BUTTON in shown("IN_HOUSE", guaranteeType = "CARD"))
        assertFalse(CONFIRM_BUTTON in shown("CANCELLED", guaranteeType = "CARD"))
        assertFalse(CONFIRM_BUTTON in shown("PROVISIONAL", permissions = setOf(READ)), "sem manage")
    }

    @Test
    fun confirmationHiddenForCancelled() {
        assertFalse(PDF_BUTTON in shown("CANCELLED"))
        assertFalse(PDF_BUTTON in shown("NO_SHOW"))
        assertTrue(PDF_BUTTON in shown("CONFIRMED"))
        assertFalse(PDF_BUTTON in shown("CONFIRMED", permissions = setOf(MANAGE)), "sem read")
    }

    @Test
    fun noActionsWithoutPorts() {
        val reservation = item("PROVISIONAL")
        var shown = emptySet<String>()
        runComposeUiTest {
            val rig = rig(reservation)
            setContent {
                val scope = rememberCoroutineScope()
                val search = remember { ReservationSearchState(rig.search, scope, pageSize = 2) }
                val state = remember { ReservationDetailState(rig.reception, scope) }
                ZenyTheme { ReservationsScreen(search, state, actions = null) }
                remember(search) { search.start() }
            }
            waitForIdle()
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            shown = listOf(CHANGE_BUTTON, CONFIRM_BUTTON, PDF_BUTTON).filterTo(mutableSetOf()) { hasTag(it) }
        }
        assertEquals(emptySet(), shown)
    }

    @Test
    fun noActionsWhenAConnectionPortIsMissing() {
        val reservation = item("PROVISIONAL")
        val rig = rig(reservation)
        rig.reception.permissions = setOf(MANAGE, READ)
        // Só o PDF tem porta: as outras duas ações não aparecem.
        shell(ActionsConnection(rig.reception, rig.search, confirmations = { PdfDocument(ByteArray(0), null) })) {
            openReservation("R-1")
            assertFalse(hasTag(CHANGE_BUTTON))
            assertFalse(hasTag(CONFIRM_BUTTON))
            assertTrue(hasTag(PDF_BUTTON))
        }
        // Sem a porta da Recepção não há detalhe nem ação.
        shell(ActionsConnection(null, rig.search, change = FakeReservationChangeSource())) {
            open("Reservas")
            onNodeWithText("R-1").performClick()
            waitForIdle()
            assertFalse(hasTag("reservation-detail"))
            assertFalse(hasTag(CHANGE_BUTTON))
        }
    }

    @Test
    fun noActionsWhenPermissionsCannotBeRead() {
        val reservation = item("PROVISIONAL")
        val rig = rig(reservation)
        rig.reception.permissionsError = IOException("fora do ar")
        val connection = ActionsConnection(rig.reception, rig.search, change = FakeReservationChangeSource())
        shell(connection) {
            openReservation("R-1")
            assertFalse(hasTag(CHANGE_BUTTON))
        }
    }

    @Test
    fun permissionsAreReadOncePerSession() {
        val first = item("CONFIRMED", "R-1")
        val second = item("CONFIRMED", "R-2")
        val rig = rig(first, second)
        rig.reception.permissions = setOf(MANAGE, READ)
        shell(ActionsConnection(rig.reception, rig.search, change = FakeReservationChangeSource())) {
            openReservation("R-1")
            assertTrue(hasTag(CHANGE_BUTTON))
            onNodeWithText("R-2").performClick()
            waitForIdle()
            assertEquals(1, rig.reception.permissionsCalls)
        }
    }

    @Test
    fun changeDoneReloadsDetailAndTable() {
        val reservation = item("CONFIRMED")
        val rig = rig(reservation)
        rig.reception.permissions = setOf(MANAGE, READ)
        val change =
            FakeReservationChangeSource().apply {
                versions[reservation.id.toString()] = ReservationVersion(arDetail(reservation), "\"v1\"")
                changeResponse = arDetail(reservation)
            }
        shell(ActionsConnection(rig.reception, rig.search, change = change)) {
            openReservation("R-1")
            click(CHANGE_BUTTON)
            onNodeWithTag("change-dialog").assertExists()
            onNodeWithTag("change-requests").performTextInput("Andar alto")
            val detailReads = rig.reception.reservationCalls.size
            val searches = rig.search.searchCalls.size

            click("change-submit")

            onNodeWithTag("change-done").assertExists()
            assertEquals(1, change.changeCalls.size)
            assertEquals(detailReads + 1, rig.reception.reservationCalls.size)
            assertEquals(searches + 1, rig.search.searchCalls.size)
            click("change-close")
            onNodeWithTag("change-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun closingADialogWithoutChangeReloadsTheDetail() {
        val reservation = item("PROVISIONAL")
        val rig = rig(reservation) { arDetail(it, guaranteeType = "CARD") }
        rig.reception.permissions = setOf(MANAGE, READ)
        val confirm =
            FakeReservationConfirmSource().apply {
                details[reservation.id.toString()] = confirmDetail(reservation.id)
            }
        shell(ActionsConnection(rig.reception, rig.search, confirm = confirm)) {
            openReservation("R-1")
            click(CONFIRM_BUTTON)
            onNodeWithTag("confirm-dialog").assertExists()
            val detailReads = rig.reception.reservationCalls.size

            click("confirm-close")

            onNodeWithTag("confirm-dialog").assertDoesNotExist()
            assertEquals(detailReads + 1, rig.reception.reservationCalls.size)
            assertEquals(0, confirm.confirmCalls.size)
        }
    }

    @Test
    fun confirmationOpensTheSameReservationPdf() {
        val reservation = item("CONFIRMED")
        val rig = rig(reservation)
        rig.reception.permissions = setOf(READ)
        val asked = mutableListOf<String>()
        val confirmations =
            ReservationConfirmations {
                asked += it
                throw IOException("sem rede")
            }
        shell(ActionsConnection(rig.reception, rig.search, confirmations = confirmations)) {
            openReservation("R-1")
            click(PDF_BUTTON)

            onNodeWithTag("registration-card-dialog").assertExists()
            assertEquals(listOf(reservation.id.toString()), asked)
        }
    }

    @Test
    fun confirmDialogOffersTheConfirmationPdfOfTheSameReservation() {
        val reservation = item("PROVISIONAL")
        val rig = rig(reservation) { arDetail(it, guaranteeType = "CARD") }
        rig.reception.permissions = setOf(MANAGE, READ)
        val confirm =
            FakeReservationConfirmSource().apply {
                details[reservation.id.toString()] = confirmDetail(reservation.id)
                confirmResponse = confirmDetail(reservation.id, status = "CONFIRMED")
            }
        val asked = mutableListOf<String>()
        val confirmations =
            ReservationConfirmations {
                asked += it
                throw IOException("sem rede")
            }
        shell(ActionsConnection(rig.reception, rig.search, confirm = confirm, confirmations = confirmations)) {
            openReservation("R-1")
            click(CONFIRM_BUTTON)
            click("confirm-submit")
            click("confirm-confirmation")

            onNodeWithTag("registration-card-dialog").assertExists()
            onNodeWithTag("confirm-dialog").assertDoesNotExist()
            assertEquals(listOf(reservation.id.toString()), asked)
        }
    }

    @Test
    fun dialogClosesWhenReservationChanges() =
        runComposeUiTest {
            val first = item("PROVISIONAL", "R-1")
            val second = item("CONFIRMED", "R-2")
            val rig = rig(first, second) { arDetail(it, guaranteeType = "CARD") }
            val confirm =
                FakeReservationConfirmSource().apply {
                    details[first.id.toString()] = confirmDetail(first.id)
                }
            lateinit var detail: ReservationDetailState
            lateinit var made: ReservationConfirmState
            setContent {
                val scope = rememberCoroutineScope()
                val search = remember { ReservationSearchState(rig.search, scope, pageSize = 2) }
                detail = remember { ReservationDetailState(rig.reception, scope) }
                val actions =
                    ReservationsActions(
                        setOf(MANAGE, READ),
                        change = null,
                        confirm = { id -> ReservationConfirmState(confirm, scope, id) {}.also { made = it } },
                        confirmation = null,
                    )
                ZenyTheme { ReservationsScreen(search, detail, actions) }
                remember(search) { search.start() }
            }
            waitForIdle()
            onNodeWithText("R-1").performClick()
            waitForIdle()
            click(CONFIRM_BUTTON)
            onNodeWithTag("confirm-dialog").assertExists()
            assertTrue(made.canConfirm)

            detail.open(second.id.toString())
            waitForIdle()

            onNodeWithTag("confirm-dialog").assertDoesNotExist()
            assertFalse(made.canConfirm)
            // Voltar à primeira reserva não traz o diálogo velho de volta.
            detail.open(first.id.toString())
            waitForIdle()
            onNodeWithTag("confirm-dialog").assertDoesNotExist()
        }

    private fun quoteAnswer() =
        QuoteResponse(
            adults = 2,
            arrival = "2026-11-16",
            channel = "DIRECT",
            childAges = emptyList(),
            departure = "2026-11-18",
            nights = 2,
            offers = listOf(arOffer()),
            unavailable = Option.Some(emptyList()),
        )

    private class Reserve(
        val rig: Rig,
        val availability: FakeAvailabilitySource,
        val booking: FakeNewReservationSource,
        val connection: ActionsConnection,
    )

    private fun reserve(
        permissions: Set<String>,
        withBooking: Boolean = true,
        confirmations: ReservationConfirmations? = null,
    ): Reserve {
        val rig = rig(item("CONFIRMED"))
        rig.reception.permissions = permissions
        rig.reception.guestsByName["Ana"] = listOf(arGuest)
        val availability =
            FakeAvailabilitySource().apply {
                this.permissions = setOf(AVAILABILITY_READ)
                quote = quoteAnswer()
            }
        val booking = FakeNewReservationSource().apply { response = arBooking() }
        val connection =
            ActionsConnection(
                rig.reception,
                rig.search,
                availability,
                newReservation = booking.takeIf { withBooking },
                confirmations = confirmations,
            )
        return Reserve(rig, availability, booking, connection)
    }

    private fun ComposeUiTest.quoteTheStay() {
        open(TAB)
        onNodeWithTag("quote-arrival").performTextInput("16/11/2026")
        onNodeWithTag("quote-departure").performTextInput("18/11/2026")
        click("quote-submit")
    }

    @Test
    fun reserveOpensDialogAndReloadsCalendarOnSuccess() {
        val asked = mutableListOf<String>()
        val setup =
            reserve(
                setOf(MANAGE, READ, GUESTS_READ),
                confirmations =
                    ReservationConfirmations {
                        asked += it
                        throw IOException("sem rede")
                    },
            )
        shell(setup.connection) {
            open("Reservas")
            quoteTheStay()
            click("quote-reserve-0")
            onNodeWithTag("new-reservation-dialog").assertExists()

            onNodeWithTag("guest-query").performTextInput("Ana")
            click("guest-search")
            click("guest-pick-0")
            val calendars = setup.availability.availabilityCalls.size
            val searches = setup.rig.search.searchCalls.size
            click("new-reservation-submit")

            assertEquals(1, setup.booking.calls.size)
            assertEquals(calendars + 1, setup.availability.availabilityCalls.size)
            assertEquals(searches + 1, setup.rig.search.searchCalls.size)

            click("new-reservation-confirmation")
            onNodeWithTag("new-reservation-dialog").assertDoesNotExist()
            onNodeWithTag("registration-card-dialog").assertExists()
            assertEquals(listOf(arCreatedId.toString()), asked)
        }
    }

    @Test
    fun reserveHiddenWithoutManage() {
        shell(reserve(setOf(READ, GUESTS_READ)).connection) {
            open("Reservas")
            quoteTheStay()
            assertFalse(hasTag("quote-reserve-0"))
            onNodeWithText("Standard", substring = true).assertExists()
        }
    }

    @Test
    fun reserveHiddenWithoutTheBookingPort() {
        shell(reserve(setOf(MANAGE, READ, GUESTS_READ), withBooking = false).connection) {
            open("Reservas")
            quoteTheStay()
            assertFalse(hasTag("quote-reserve-0"))
        }
    }

    @Test
    fun reserveHiddenWithoutTheReceptionPort() {
        val setup = reserve(setOf(MANAGE, READ, GUESTS_READ))
        val connection =
            ActionsConnection(null, setup.rig.search, setup.availability, newReservation = setup.booking)
        shell(connection) {
            open("Reservas")
            quoteTheStay()
            assertFalse(hasTag("quote-reserve-0"))
        }
    }

    private fun shell(
        connection: ActionsConnection,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember { AppController(Settings(), { connection }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        after()
    }

    private fun ComposeUiTest.open(label: String) {
        onNodeWithText(label).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openReservation(code: String) {
        open("Reservas")
        onNodeWithText(code).performClick()
        waitForIdle()
    }
}
