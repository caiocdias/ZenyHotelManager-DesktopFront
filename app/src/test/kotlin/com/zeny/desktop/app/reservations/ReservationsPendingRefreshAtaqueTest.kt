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
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.facade.ReservationVersion
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
private val WITH_AVAILABILITY = setOf(MANAGE, READ, AVAILABILITY_READ)
private const val SEARCH_TAB = "Busca"
private const val PENDING_TAB = "Pendências"
private const val CANCEL_BUTTON = "open-reservation-cancel"
private const val REACTIVATE_BUTTON = "open-reservation-reactivate"

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
private class RaConnection(
    val reception: FakeReceptionSource?,
    val search: FakeReservationSearchSource,
    val availability: FakeAvailabilitySource? = null,
    val cancel: ReservationCancelSource? = null,
    val reactivate: ReservationReactivateSource? = null,
    val pending: PendingSource? = null,
    val change: ReservationChangeSource? = null,
    val confirm: ReservationConfirmSource? = null,
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

    // O conjunto que a porta da Recepção devolvia vai no `me`, que a casca lê e entrega à área (ADR 0013).
    override suspend fun me(): Me =
        Me("u-1", "ana", "Ana Souza", setOf(READ, "frontdesk.stays.checkin") + reception?.permissions.orEmpty())

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

    override fun reservationChangeSource(): ReservationChangeSource? = change

    override fun reservationConfirmSource(): ReservationConfirmSource? = confirm

    override fun close() = Unit
}

private class RaSettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-1"
}

/** As leituras de cada lista de pendências, na ordem do seletor. */
private fun FakePendingSource.reads() =
    listOf(guaranteeCalls.size, depositCalls, noShowCalls.size, reallocationCalls.size)

/**
 * Ataque à fiação do `refreshAll()` na área Reservas (M3 E06, Task 7): cada ação, pela aba "Pendências" ou pelo
 * detalhe da busca, relê as quatro listas uma vez (nunca oito), a reativação e a alteração entram na regra, e a
 * aba que ainda não foi aberta nunca é lida por causa de uma ação.
 */
@OptIn(ExperimentalTestApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class ReservationsPendingRefreshAtaqueTest {
    @Suppress("LongParameterList") // uma porta falsa por parâmetro
    private class Rig(
        val search: FakeReservationSearchSource = FakeReservationSearchSource(),
        val reception: FakeReceptionSource = FakeReceptionSource(),
        val pending: FakePendingSource = FakePendingSource(),
        val cancel: FakeReservationCancelSource = FakeReservationCancelSource(),
        val reactivate: FakeReservationReactivateSource = FakeReservationReactivateSource(),
        val availability: FakeAvailabilitySource = FakeAvailabilitySource(),
        val change: FakeReservationChangeSource = FakeReservationChangeSource(),
    )

    /** Uma reserva que está na busca e, pelo [inSearch], só nas listas de pendências ou também na busca. */
    private fun rig(
        vararg items: ReservationListItemResponse,
        inSearch: Boolean = true,
    ): Rig {
        val rig = Rig()
        rig.reception.permissions = WITH_AVAILABILITY
        items.forEach { item ->
            val holder = HolderSummary("Hóspede ${item.code}", null)
            val id = item.id.toString()
            if (inSearch) rig.search.reservations += item
            rig.search.holders[item.holderGuestId.toString()] = holder
            rig.reception.reservations[id] = lcDetail(item)
            rig.reception.holders[item.holderGuestId.toString()] = holder
            rig.reception.histories[id] = emptyList()
            rig.reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
            rig.pending.holders[item.holderGuestId.toString()] = holder
            rig.cancel.reservations[id] = lcDetail(item)
            rig.cancel.response = lcCancelled(item)
            rig.reactivate.response = lcDetail(item, status = "CONFIRMED")
            rig.change.versions[id] = ReservationVersion(lcDetail(item), "\"v1\"")
            rig.change.changeResponse = lcDetail(item)
        }
        return rig
    }

    private fun Rig.connection() =
        RaConnection(reception, search, availability, cancel, reactivate, pending, change, null)

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.open(label: String) {
        onNodeWithText(label).performClick()
        waitForIdle()
    }

    private fun shell(
        connection: RaConnection,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember { AppController(RaSettings(), { connection }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        after()
    }

    private fun ComposeUiTest.cancelOpenDetail() {
        click(CANCEL_BUTTON)
        click("cancel-submit")
        onNodeWithTag("cancel-done").assertExists()
        click("cancel-close")
    }

    @Test
    fun reactivateFromThePendingTabReloadsTheFourListsOnceAndTheReservationLeavesBoth() {
        val item = searchItemOf(code = "N-1", status = "NO_SHOW")
        val rig = rig(item, inSearch = false)
        rig.pending.noShows += item
        rig.pending.reallocations += reallocationOf(code = "N-1").copy(reservationId = item.id)
        val row = "row-${item.id}"
        shell(rig.connection()) {
            open("Reservas")
            open(PENDING_TAB)
            click("pending-list-REALLOCATION")
            assertTrue(hasTag(row), "a reserva está na realocação")
            click("pending-list-NO_SHOWS")
            open("N-1")
            click(REACTIVATE_BUTTON)
            val reads = rig.pending.reads()
            rig.pending.noShows.clear()
            rig.pending.reallocations.clear()

            click("reactivate-submit")

            onNodeWithTag("reactivate-done").assertExists()
            assertEquals(reads.map { it + 1 }, rig.pending.reads(), "quatro listas, uma leitura cada")
            click("reactivate-close")
            assertFalse(hasTag(row), "reativada: some de no-shows")
            click("pending-list-REALLOCATION")
            assertFalse(hasTag(row), "reativada: some da realocação")
        }
    }

    @Test
    fun changeFromThePendingTabReloadsTheFourListsOnce() {
        val item = searchItemOf(code = "G-1", status = "CONFIRMED")
        val rig = rig(item, inSearch = false)
        rig.pending.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = item.holderGuestId.toString())
        rig.pending.guarantees[0] = rig.pending.guarantees[0].copy(id = item.id)
        shell(rig.connection()) {
            open("Reservas")
            open(PENDING_TAB)
            open("G-1")
            click("open-reservation-change")
            onNodeWithTag("change-dialog").assertExists()
            onNodeWithTag("change-requests").performTextInput("Andar alto")
            val reads = rig.pending.reads()

            click("change-submit")

            onNodeWithTag("change-done").assertExists()
            assertEquals(1, rig.change.changeCalls.size)
            assertEquals(reads.map { it + 1 }, rig.pending.reads(), "quatro listas, uma leitura cada")
        }
    }

    @Test
    fun cancelFromTheSearchDetailAfterThePendingTabWasOpenedReloadsTheFourListsOnce() {
        val item = searchItemOf(code = "S-1", status = "CONFIRMED")
        val rig = rig(item)
        shell(rig.connection()) {
            open("Reservas")
            open(PENDING_TAB)
            assertEquals(listOf(1, 1, 1, 1), rig.pending.reads(), "a entrada na aba lê cada lista uma vez")
            open(SEARCH_TAB)
            open("S-1")
            click(CANCEL_BUTTON)
            click("cancel-submit")
            onNodeWithTag("cancel-done").assertExists()

            assertEquals(listOf(2, 2, 2, 2), rig.pending.reads(), "uma releitura por lista, não duas")
        }
    }

    @Test
    fun twoActionsInARowReloadTheFourListsTwiceNotMore() {
        val item = searchItemOf(code = "S-1", status = "CONFIRMED")
        val rig = rig(item)
        val id = item.id.toString()
        shell(rig.connection()) {
            open("Reservas")
            open(PENDING_TAB)
            open(SEARCH_TAB)
            open("S-1")
            click(CANCEL_BUTTON)
            rig.reception.reservations[id] = lcDetail(item, status = "CANCELLED")
            click("cancel-submit")
            onNodeWithTag("cancel-done").assertExists()
            click("cancel-close")
            assertEquals(listOf(2, 2, 2, 2), rig.pending.reads())

            click(REACTIVATE_BUTTON)
            rig.reception.reservations[id] = lcDetail(item, status = "CONFIRMED")
            click("reactivate-submit")
            onNodeWithTag("reactivate-done").assertExists()

            assertEquals(listOf(3, 3, 3, 3), rig.pending.reads(), "uma leitura por lista e por ação")
        }
    }

    @Test
    fun theTabOpenedAfterAnActionReadsEachListOnceNotTwice() {
        val item = searchItemOf(code = "S-1", status = "CONFIRMED")
        val rig = rig(item)
        shell(rig.connection()) {
            open("Reservas")
            open("S-1")
            cancelOpenDetail()
            assertEquals(listOf(0, 0, 0, 0), rig.pending.reads())

            open(PENDING_TAB)

            assertEquals(listOf(1, 1, 1, 1), rig.pending.reads())
        }
    }
}
