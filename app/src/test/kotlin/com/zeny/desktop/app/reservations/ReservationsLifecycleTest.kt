package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationPenaltyResponse
import com.zeny.desktop.sdk.model.CancellationResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MANAGE = "reservations.reservations.manage"
private const val READ = "reservations.reservations.read"
private const val AVAILABILITY_READ = "reservations.availability.read"
private const val SEARCH_TAB = "Busca"
private const val AVAILABILITY_TAB = "Disponibilidade e cotação"
private const val PENDING_TAB = "Pendências"
private const val CANCEL_BUTTON = "open-reservation-cancel"
private const val REACTIVATE_BUTTON = "open-reservation-reactivate"
private const val DETAIL = "reservation-detail"
private val STATUSES = listOf("PROVISIONAL", "CONFIRMED", "IN_HOUSE", "CHECKED_OUT", "CANCELLED", "NO_SHOW")
private val CANCELLABLE = setOf("PROVISIONAL", "CONFIRMED")
private val REACTIVABLE = setOf("CANCELLED", "NO_SHOW")

/** A reserva do servidor para a linha dada, com a situação e, se houver, o cancelamento que ela guarda. */
private fun lcDetail(
    item: ReservationListItemResponse,
    status: String = item.status,
    cancellation: CancellationResponse? = null,
) = ReservationDetailResponse(
    bookingCode = item.bookingCode,
    holderGuestId = item.holderGuestId,
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = item.arrival,
            bookingId = item.bookingId,
            cancellation = if (cancellation != null) Option.Some(cancellation) else Option.Undefined,
            cancellationTerms =
                CancellationTermsResponse(
                    deadlineHours = 24,
                    name = "Flexível",
                    penaltyNights = Option.Some(1),
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = kotlin.uuid.Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = item.code,
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = item.departure,
            folioId = item.folioId,
            guaranteeTerms =
                GuaranteeTermsResponse(
                    name = "Sem garantia",
                    policyId = kotlin.uuid.Uuid.random(),
                    type = "NONE",
                    version = 1,
                ),
            guests = emptyList(),
            id = item.id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = item.ratePlanId,
            roomTotal = "300.00",
            roomTypeId = kotlin.uuid.Uuid.random(),
            segment = "LEISURE",
            status = status,
            total = "300.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun lcCancelled(item: ReservationListItemResponse) =
    lcDetail(
        item,
        status = "CANCELLED",
        cancellation =
            CancellationResponse(
                cancelledAt = "2026-11-16T12:00:00Z",
                penalty =
                    CancellationPenaltyResponse(amount = "150.00", deadline = "2026-11-15T17:00:00Z", waived = false),
                previousStatus = "CONFIRMED",
                reason = "GUEST_REQUEST",
            ),
    )

/** A conexão de mentira da área Reservas com as portas deste ciclo; cada porta é opcional. */
@Suppress("LongParameterList") // uma porta opcional por parâmetro
private class LifecycleConnection(
    val reception: FakeReceptionSource?,
    val search: FakeReservationSearchSource,
    val availability: FakeAvailabilitySource? = null,
    val cancel: ReservationCancelSource? = null,
    val reactivate: ReservationReactivateSource? = null,
    val pending: PendingSource? = null,
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

    override fun reservationCancelSource(): ReservationCancelSource? = cancel

    override fun reservationReactivateSource(): ReservationReactivateSource? = reactivate

    override fun pendingSource(): PendingSource? = pending

    override fun close() = Unit
}

private class LifecycleSettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-1"
}

/** Cancelar, reativar e as pendências na área Reservas, ligados pela área inteira. */
@OptIn(ExperimentalTestApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class ReservationsLifecycleTest {
    private class Rig(
        val search: FakeReservationSearchSource,
        val reception: FakeReceptionSource,
        val pending: FakePendingSource,
        val cancel: FakeReservationCancelSource,
        val reactivate: FakeReservationReactivateSource,
        val availability: FakeAvailabilitySource,
    )

    private fun rig(
        vararg items: ReservationListItemResponse,
        permissions: Set<String> = setOf(MANAGE, READ),
    ): Rig {
        val rig =
            Rig(
                FakeReservationSearchSource(),
                FakeReceptionSource(),
                FakePendingSource(),
                FakeReservationCancelSource(),
                FakeReservationReactivateSource(),
                FakeAvailabilitySource().apply { this.permissions = setOf(AVAILABILITY_READ) },
            )
        rig.reception.permissions = permissions
        items.forEach { item ->
            val holder = HolderSummary("Hóspede ${item.code}", null)
            val id = item.id.toString()
            rig.search.reservations += item
            rig.search.holders[item.holderGuestId.toString()] = holder
            rig.reception.reservations[id] = lcDetail(item)
            rig.reception.holders[item.holderGuestId.toString()] = holder
            rig.reception.histories[id] = emptyList()
            rig.reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
            rig.pending.holders[item.holderGuestId.toString()] = holder
            rig.cancel.reservations[id] = lcDetail(item)
            rig.cancel.response = lcCancelled(item)
            rig.reactivate.response = lcDetail(item, status = "CONFIRMED")
        }
        return rig
    }

    private fun Rig.connection(
        reception: Boolean = true,
        availability: Boolean = false,
        cancel: Boolean = true,
        reactivate: Boolean = true,
        pending: Boolean = false,
    ) = LifecycleConnection(
        reception.takeIf { reception }?.let { this.reception },
        search,
        this.availability.takeIf { availability },
        this.cancel.takeIf { cancel },
        this.reactivate.takeIf { reactivate },
        this.pending.takeIf { pending },
    )

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.open(label: String) {
        onNodeWithText(label).performClick()
        waitForIdle()
    }

    private fun shell(
        connection: LifecycleConnection,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember { AppController(LifecycleSettings(), { connection }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        after()
    }

    /** Abre a reserva na busca e devolve quais dos dois botões novos o detalhe mostra. */
    private fun buttonsFor(
        status: String,
        permissions: Set<String> = setOf(MANAGE, READ),
    ): Set<String> {
        val item = searchItemOf(code = "R-1", status = status)
        val rig = rig(item, permissions = permissions)
        var shown = emptySet<String>()
        shell(rig.connection()) {
            open("Reservas")
            open("R-1")
            assertTrue(hasTag(DETAIL))
            shown = listOf(CANCEL_BUTTON, REACTIVATE_BUTTON).filterTo(mutableSetOf()) { hasTag(it) }
        }
        return shown
    }

    @Test
    fun cancelOnlyForProvisionalOrConfirmedWithManage() {
        STATUSES.forEach { status ->
            assertEquals(status in CANCELLABLE, CANCEL_BUTTON in buttonsFor(status), "Cancelar reserva em $status")
        }
        assertFalse(CANCEL_BUTTON in buttonsFor("CONFIRMED", setOf(READ)), "sem manage")
    }

    @Test
    fun reactivateOnlyForCancelledOrNoShowWithManage() {
        STATUSES.forEach { status ->
            assertEquals(status in REACTIVABLE, REACTIVATE_BUTTON in buttonsFor(status), "Reativar em $status")
        }
        assertFalse(REACTIVATE_BUTTON in buttonsFor("CANCELLED", setOf(READ)), "sem manage")
    }

    @Test
    fun noButtonsWithoutThePorts() {
        val item = searchItemOf(code = "R-1", status = "CONFIRMED")
        val cancelled = searchItemOf(code = "R-2", status = "CANCELLED")
        val rig = rig(item, cancelled)
        shell(rig.connection(cancel = false, reactivate = false)) {
            open("Reservas")
            open("R-1")
            assertFalse(hasTag(CANCEL_BUTTON))
            open("R-2")
            assertFalse(hasTag(REACTIVATE_BUTTON))
        }
    }

    @Test
    fun cancelDoneReloadsDetailTableAndCalendar() {
        val item = searchItemOf(code = "R-1", status = "CONFIRMED")
        val rig = rig(item)
        shell(rig.connection(availability = true, pending = true)) {
            open("Reservas")
            open("R-1")
            click(CANCEL_BUTTON)
            onNodeWithTag("cancel-dialog").assertExists()
            val detailReads = rig.reception.reservationCalls.size
            val searches = rig.search.searchCalls.size
            val calendars = rig.availability.availabilityCalls.size
            val pendingReads = rig.pending.guaranteeCalls.size

            click("cancel-submit")

            onNodeWithTag("cancel-done").assertExists()
            assertEquals(1, rig.cancel.calls.size)
            assertEquals(detailReads + 1, rig.reception.reservationCalls.size)
            assertEquals(searches + 1, rig.search.searchCalls.size)
            assertEquals(calendars + 1, rig.availability.availabilityCalls.size)
            assertEquals(pendingReads + 1, rig.pending.guaranteeCalls.size)
            click("cancel-close")
            onNodeWithTag("cancel-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun reactivateDoneReloadsDetailTableAndCalendar() {
        val item = searchItemOf(code = "R-1", status = "CANCELLED")
        val rig = rig(item)
        shell(rig.connection(availability = true)) {
            open("Reservas")
            open("R-1")
            click(REACTIVATE_BUTTON)
            onNodeWithTag("reactivate-dialog").assertExists()
            val detailReads = rig.reception.reservationCalls.size
            val searches = rig.search.searchCalls.size
            val calendars = rig.availability.availabilityCalls.size

            click("reactivate-submit")

            onNodeWithTag("reactivate-done").assertExists()
            assertEquals(1, rig.reactivate.calls.size)
            assertEquals(listOf(item.id.toString()), rig.reactivate.calls.map { it.id })
            assertEquals(detailReads + 1, rig.reception.reservationCalls.size)
            assertEquals(searches + 1, rig.search.searchCalls.size)
            assertEquals(calendars + 1, rig.availability.availabilityCalls.size)
        }
    }

    @Test
    fun pendingTabNeedsOnlyThePort() {
        val item = searchItemOf(code = "G-1", status = "CONFIRMED")
        val rig = rig(item)
        rig.pending.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = item.holderGuestId.toString())
        shell(rig.connection(reception = false, cancel = false, reactivate = false, pending = true)) {
            open("Reservas")
            assertTrue(hasText(PENDING_TAB))
            open(PENDING_TAB)
            onNodeWithText("G-1").assertExists()
            open("G-1")
            assertFalse(hasTag(DETAIL))
        }
    }

    @Test
    fun pendingTabShowsCancelForConfirmedRow() {
        val item = searchItemOf(code = "G-1", status = "CONFIRMED")
        val rig = rig(item)
        rig.pending.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = item.holderGuestId.toString())
        rig.pending.guarantees[0] = rig.pending.guarantees[0].copy(id = item.id)
        shell(rig.connection(pending = true)) {
            open("Reservas")
            open(PENDING_TAB)
            open("G-1")
            assertTrue(hasTag(DETAIL))
            assertTrue(hasTag(CANCEL_BUTTON))
            assertFalse(hasTag(REACTIVATE_BUTTON))

            // Cancelar pela aba recarrega a lista selecionada, sem sair dela.
            click(CANCEL_BUTTON)
            val reads = rig.pending.guaranteeCalls.size
            click("cancel-submit")
            onNodeWithTag("cancel-done").assertExists()
            assertEquals(reads + 1, rig.pending.guaranteeCalls.size)
        }
    }

    @Test
    fun pendingTabStartsOnlyOnEntry() {
        val rig = rig(searchItemOf(code = "R-1", status = "CONFIRMED"))
        shell(rig.connection(pending = true)) {
            open("Reservas")
            assertEquals(0, rig.pending.guaranteeCalls.size, "só ao entrar na aba")
            open(PENDING_TAB)
            assertEquals(1, rig.pending.guaranteeCalls.size)
            assertEquals(1, rig.pending.depositCalls)
        }
    }

    @Test
    fun tabsWithoutAvailability() {
        val rig = rig(searchItemOf(code = "R-1"))
        rig.availability.permissions = emptySet()
        shell(rig.connection(availability = true, pending = true)) {
            open("Reservas")
            assertTrue(hasText(SEARCH_TAB))
            assertTrue(hasText(PENDING_TAB))
            assertFalse(hasText(AVAILABILITY_TAB))
        }
    }

    @Test
    fun tabsAreSearchAvailabilityAndPendingInOrder() {
        val rig = rig(searchItemOf(code = "R-1"))
        shell(rig.connection(availability = true, pending = true)) {
            open("Reservas")
            val left =
                listOf(SEARCH_TAB, AVAILABILITY_TAB, PENDING_TAB).map {
                    onNodeWithText(it).fetchSemanticsNode().positionInRoot.x
                }
            assertEquals(left.sorted(), left)
            assertEquals(3, left.toSet().size)
        }
    }

    @Test
    fun noPendingTabWithoutThePort() {
        val rig = rig(searchItemOf(code = "R-1"))
        shell(rig.connection(availability = true)) {
            open("Reservas")
            assertTrue(hasText(AVAILABILITY_TAB))
            assertFalse(hasText(PENDING_TAB))
        }
        shell(rig.connection()) {
            open("Reservas")
            assertFalse(hasText(PENDING_TAB))
            assertFalse(hasText(SEARCH_TAB))
            assertFalse(hasText(AVAILABILITY_TAB))
        }
    }

    @Test
    fun dialogClosesWhenReservationChanges() =
        runComposeUiTest {
            val first = searchItemOf(code = "R-1", status = "CONFIRMED")
            val second = searchItemOf(code = "R-2", status = "CONFIRMED")
            val rig = rig(first, second)
            lateinit var detail: ReservationDetailState
            lateinit var made: ReservationCancelState
            setContent {
                val scope = rememberCoroutineScope()
                val search = remember { ReservationSearchState(rig.search, scope, pageSize = 2) }
                detail = remember { ReservationDetailState(rig.reception, scope) }
                val actions =
                    ReservationsActions(
                        setOf(MANAGE, READ),
                        change = null,
                        confirm = null,
                        confirmation = null,
                        cancel = { id ->
                            ReservationCancelState(rig.cancel, scope, id, { null }, {}).also { made = it }
                        },
                    )
                ZenyTheme { ReservationsScreen(search, detail, actions) }
                remember(search) { search.start() }
            }
            waitForIdle()
            onNodeWithText("R-1").performClick()
            waitForIdle()
            click(CANCEL_BUTTON)
            onNodeWithTag("cancel-dialog").assertExists()
            assertTrue(made.canConfirm)

            detail.open(second.id.toString())
            waitForIdle()

            onNodeWithTag("cancel-dialog").assertDoesNotExist()
            assertFalse(made.canConfirm)
            // Voltar à primeira reserva não traz o diálogo velho de volta.
            detail.open(first.id.toString())
            waitForIdle()
            onNodeWithTag("cancel-dialog").assertDoesNotExist()
        }
}
