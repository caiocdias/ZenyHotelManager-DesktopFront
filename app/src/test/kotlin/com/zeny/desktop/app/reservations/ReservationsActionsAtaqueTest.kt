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
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val SA_MANAGE = "reservations.reservations.manage"
private const val SA_READ = "reservations.reservations.read"
private const val SA_CHANGE_BUTTON = "open-reservation-change"

private fun saDetail(item: ReservationListItemResponse) =
    ReservationDetailResponse(
        bookingCode = item.bookingCode,
        holderGuestId = item.holderGuestId,
        reservation =
            ReservationResponse(
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
                code = item.code,
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = item.departure,
                folioId = item.folioId,
                guaranteeTerms =
                    GuaranteeTermsResponse(name = "Política", policyId = Uuid.random(), type = "NONE", version = 1),
                guests = emptyList(),
                id = item.id,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = item.ratePlanId,
                roomTotal = "300.00",
                roomTypeId = Uuid.parse("10000000-0000-0000-0000-0000000000e1"),
                segment = "LEISURE",
                status = item.status,
                total = "300.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

/**
 * A conexão de mentira da área Reservas, com a sessão trocável no lugar (sem passar pelo login). O `me` devolve o menu
 * e o conjunto programado em `reception.permissions` (o que a porta da Recepção devolvia), lido a cada chamada.
 */
private class SessionConnection(
    val reception: FakeReceptionSource,
    val search: FakeReservationSearchSource,
    val change: ReservationChangeSource,
) : ServerConnection {
    override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    var meCalls = 0

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

    override suspend fun me(): Me {
        meCalls++
        return Me("u-1", "ana", "Ana Souza", setOf(SA_READ, "frontdesk.stays.checkin") + reception.permissions)
    }

    override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

    override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 13))

    override suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String = error("não usado")

    override fun receptionSource(): ReceptionSource = reception

    override fun reservationSearchSource(): ReservationSearchSource = search

    override fun reservationChangeSource(): ReservationChangeSource = change

    override fun close() = Unit
}

private class SaSettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = "device-ataque"
}

/**
 * Ataques à fiação das ações de Reservas (M3 E03) pela raiz do app: a troca de sessão no lugar fecha o diálogo aberto
 * e não deixa a permissão da sessão anterior valer enquanto a nova não foi lida; fechar a alteração depois de uma
 * recusa definitiva relê o detalhe.
 */
@OptIn(ExperimentalTestApi::class)
class ReservationsActionsAtaqueTest {
    private val item = searchItemOf(code = "R-1", status = "CONFIRMED")
    private val fake =
        FakeReceptionSource().apply {
            reservations[item.id.toString()] = saDetail(item)
            holders[item.holderGuestId.toString()] = HolderSummary("Hóspede R-1", null)
            histories[item.id.toString()] = emptyList()
            ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
            permissions = setOf(SA_MANAGE, SA_READ)
        }
    private val search =
        FakeReservationSearchSource().apply {
            reservations += item
            holders[item.holderGuestId.toString()] = HolderSummary("Hóspede R-1", null)
        }
    private val change =
        FakeReservationChangeSource().apply {
            versions[item.id.toString()] = ReservationVersion(saDetail(item), "\"v1\"")
            changeResponse = saDetail(item)
        }

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun shell(
        connection: SessionConnection,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember { AppController(SaSettings(), { connection }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        onNodeWithText("Reservas").performClick()
        waitForIdle()
        onNodeWithText("R-1").performClick()
        waitForIdle()
        after()
    }

    @Test
    fun inPlaceSessionSwitchClosesTheOpenChangeDialog() {
        val connection = SessionConnection(fake, search, change)
        shell(connection) {
            onNodeWithTag(SA_CHANGE_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag("change-dialog").assertExists()

            connection.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            onNodeWithTag("change-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun newSessionWithoutManageHidesChange() {
        val connection = SessionConnection(fake, search, change)
        shell(connection) {
            assertTrue(hasTag(SA_CHANGE_BUTTON))
            fake.permissions = setOf(SA_READ)

            connection.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()
            // A área Reservas segue aberta na casca nova; o detalhe é outro, e a reserva é aberta de novo.
            onNodeWithText("R-1").performClick()
            waitForIdle()

            assertTrue(hasTag("reservation-detail"))
            assertFalse(hasTag(SA_CHANGE_BUTTON))
            assertEquals(2, connection.meCalls)
        }
    }

    @Test
    fun closingTheChangeDialogAfterADefinitiveRefusalReloadsTheDetail() {
        change.changeOutcomes += ApiError.Problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED", "d", emptyList())
        val connection = SessionConnection(fake, search, change)
        shell(connection) {
            onNodeWithTag(SA_CHANGE_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag("change-requests").performTextInput("Andar alto")
            onNodeWithTag("change-submit").performClick()
            waitForIdle()
            onNodeWithTag("change-error").assertExists()
            onNodeWithText("RESERVATION_TRANSITION_NOT_ALLOWED", substring = true).assertDoesNotExist()
            val reads = fake.reservationCalls.size
            val searches = search.searchCalls.size

            onNodeWithTag("change-close").performClick()
            waitForIdle()

            onNodeWithTag("change-dialog").assertDoesNotExist()
            assertEquals(reads + 1, fake.reservationCalls.size, "fechar depois da recusa não releu o detalhe")
            assertEquals(searches, search.searchCalls.size, "a recusa não muda a tabela")
            assertEquals(1, change.changeCalls.size)
        }
    }
}
