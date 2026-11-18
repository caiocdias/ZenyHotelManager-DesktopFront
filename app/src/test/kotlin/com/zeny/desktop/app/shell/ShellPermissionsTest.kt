package com.zeny.desktop.app.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reservations.AvailabilitySource
import com.zeny.desktop.app.reservations.FakeAvailabilitySource
import com.zeny.desktop.app.reservations.FakeReservationChangeSource
import com.zeny.desktop.app.reservations.FakeReservationSearchSource
import com.zeny.desktop.app.reservations.ReservationChangeSource
import com.zeny.desktop.app.reservations.ReservationSearchSource
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val READ = "reservations.reservations.read"
private const val MANAGE = "reservations.reservations.manage"
private const val AVAILABILITY_READ = "reservations.availability.read"
private const val TAB = "Disponibilidade e cotação"
private const val CHANGE = "open-reservation-change"
private const val HOLDER = "30000000-0000-0000-0000-000000000003"
private const val CHECKIN = "frontdesk.stays.checkin"
private const val ROOMS_STATUS_READ = "rooms.status.read"
private const val PANEL_DATE = "Data operacional: 13/11/2026"

/**
 * A casca é a fonte única das permissões das áreas (ADR 0013): sem um `me` bom desta sessão, nenhuma área é
 * desenhada, e o conjunto de uma sessão nunca chega à área de outra.
 */
@OptIn(ExperimentalTestApi::class)
class ShellPermissionsTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    /** A conexão da área Reservas com o `me` programável; o R-1 da busca tem detalhe na Recepção falsa. */
    private class Conn(
        var me: suspend () -> Me,
        initial: SessionState = SessionState.LoggedOut,
    ) : ServerConnection {
        override val state = MutableStateFlow(initial)
        val search = FakeReservationSearchSource()
        val reception = FakeReceptionSource()
        val availability = FakeAvailabilitySource()
        private val change = FakeReservationChangeSource()

        init {
            val item = searchItemOf(code = "R-1", holderGuestId = HOLDER)
            val detail = confirmDetail(item.id, status = "CONFIRMED")
            search.reservations += item
            search.holders[HOLDER] = HolderSummary("Ana Fictícia", null)
            reception.reservations[item.id.toString()] = detail
            reception.holders[detail.holderGuestId.toString()] = HolderSummary("Ana Fictícia", null)
            reception.histories[item.id.toString()] = emptyList()
            reception.ratePlans[detail.reservation.ratePlanId.toString()] = "Tarifa Balcão"
        }

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

        override suspend fun me(): Me = me.invoke()

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 13))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource = reception

        override fun reservationSearchSource(): ReservationSearchSource = search

        override fun availabilitySource(): AvailabilitySource = availability

        override fun reservationChangeSource(): ReservationChangeSource = change

        override fun close() = Unit
    }

    private fun me(vararg permissions: String) = Me("u-1", "ana", "Ana Souza", setOf(*permissions))

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.click(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shell(conn: Conn) {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(Settings(), { conn }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        click("Entrar")
    }

    @Test
    fun failedMeDrawsNoAreaAndRetryGrants() =
        runComposeUiTest {
            val conn = Conn(me = { throw ApiError.Network(RuntimeException("sem rede")) })
            shell(conn)

            onNodeWithText("Tentar de novo").assertExists()
            assertFalse(hasText("R-1"), "área desenhada sem leitura boa")
            assertFalse(hasText("Reservas"), "menu sem leitura boa")
            assertEquals(0, conn.search.businessDayCalls, "a área leu sem leitura boa do me")
            assertEquals(0, conn.search.searchCalls.size)

            conn.me = { me(READ, MANAGE) }
            click("Tentar de novo")
            click("Reservas")
            click("R-1")

            onNodeWithTag("reservation-detail").assertExists()
            assertTrue(hasTag(CHANGE), "o retry com o me bom não concedeu as ações")
        }

    @Test
    fun failedMeDrawsNoReceptionAndRetryGrants() =
        runComposeUiTest {
            val conn = Conn(me = { throw ApiError.Network(RuntimeException("sem rede")) })
            shell(conn)

            onNodeWithText("Tentar de novo").assertExists()
            assertFalse(hasText("Recepção"), "menu sem leitura boa")
            assertFalse(hasTag("tab-ARRIVALS"), "painel desenhado sem leitura boa")
            assertEquals(0, conn.reception.businessDayCalls, "a Recepção leu sem leitura boa do me")
            assertEquals(0, conn.reception.subscriptions, "o painel assinou sem leitura boa do me")

            conn.me = { me(CHECKIN, ROOMS_STATUS_READ) }
            click("Tentar de novo")
            click("Recepção")

            onNodeWithTag("tab-ARRIVALS").assertExists()
            assertEquals(listOf(setOf("rooms")), conn.reception.subscribedTopics, "o retry não deu o conjunto lido")
        }

    @Test
    fun newSessionAreaNeverSeesThePreviousSet() =
        runComposeUiTest {
            val conn = Conn(me = { me(READ, AVAILABILITY_READ) })
            shell(conn)
            click("Reservas")
            assertTrue(hasText(TAB), "a sessão A tem a aba")

            val gate = CompletableDeferred<Me>()
            conn.me = { gate.await() }
            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            assertFalse(hasText(TAB), "a área da sessão B viu o conjunto da A")
            assertFalse(hasText("R-1"), "área desenhada com o me da sessão B em voo")
            val searches = conn.search.searchCalls.size

            gate.complete(me(READ))
            waitForIdle()

            onNodeWithText("R-1").assertExists()
            assertFalse(hasText(TAB), "a aba da sessão A sobreviveu na B")
            assertTrue(conn.search.searchCalls.size > searches, "a área da sessão B não foi desenhada")
        }

    @Test
    fun receivedSetDecidesTabAndActions() {
        listOf(
            setOf(READ, AVAILABILITY_READ) to (true to false),
            setOf(READ, MANAGE) to (false to true),
        ).forEach { (permissions, expected) ->
            runComposeUiTest {
                val conn = Conn(me = { error("a área não lê o me") }, SessionState.Active("u-1", "s-1", false))
                setContent { ZenyTheme { AreaContent(Area.RESERVATIONS, conn, "s-1", permissions) } }
                waitForIdle()
                click("R-1")

                onNodeWithTag("reservation-detail").assertExists()
                assertEquals(expected.first, hasText(TAB), "aba com $permissions")
                assertEquals(expected.second, hasTag(CHANGE), "ação com $permissions")
            }
        }
    }

    @Test
    fun areaWithForeignSessionDrawsNothing() =
        runComposeUiTest {
            val conn = Conn(me = { error("a área não lê o me") }, SessionState.Active("u-1", "B", false))
            var sessionId by mutableStateOf("A")
            setContent {
                ZenyTheme { AreaContent(Area.RESERVATIONS, conn, sessionId, setOf(READ, MANAGE, AVAILABILITY_READ)) }
            }
            waitForIdle()

            assertFalse(hasText("R-1"), "área desenhada com o conjunto de outra sessão")
            assertFalse(hasText(TAB))
            assertEquals(0, conn.search.businessDayCalls, "a porta de busca foi chamada")
            assertEquals(0, conn.search.searchCalls.size)
            assertEquals(0, conn.availability.businessDayCalls)

            sessionId = "B"
            waitForIdle()

            onNodeWithText("R-1").assertExists()
            assertTrue(hasText(TAB))
        }

    @Test
    fun receptionWithForeignSessionDrawsNothing() =
        runComposeUiTest {
            val conn = Conn(me = { error("a área não lê o me") }, SessionState.Active("u-1", "B", false))
            var sessionId by mutableStateOf("A")
            setContent {
                ZenyTheme { AreaContent(Area.RECEPTION, conn, sessionId, setOf(CHECKIN, ROOMS_STATUS_READ)) }
            }
            waitForIdle()

            assertFalse(hasText(PANEL_DATE), "a Recepção desenhou com o conjunto de outra sessão")
            assertEquals(0, conn.reception.subscriptions, "events chamado com o conjunto de outra sessão")
            assertEquals(0, conn.reception.businessDayCalls, "a porta da Recepção foi chamada")
            assertEquals(0, conn.reception.dayPageCalls.size)

            sessionId = "B"
            waitForIdle()

            onNodeWithText(PANEL_DATE).assertExists()
            assertEquals(listOf(setOf("rooms")), conn.reception.subscribedTopics)
            assertEquals(1, conn.reception.activeSubscriptions)
        }
}
