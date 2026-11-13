package com.zeny.desktop.app.rooms

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.app.shell.ShellScreen
import com.zeny.desktop.app.shell.ShellState
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.RoomStatusResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOMS = "rooms.status.read"
private const val GUESTS = "guests.profiles.read"
private const val RESERVATIONS = "reservations.reservations.read"
private const val NO_SCREENS_YET = "As telas do seu perfil chegam nas próximas versões."
private const val PICK_AN_AREA = "Selecione uma área no menu."
private const val UNAVAILABLE = "Mapa indisponível nesta conexão."

/**
 * Stream de mentira com uma fila por assinatura: o evento posto chega a todas as assinaturas vivas, então duas vivas
 * ao mesmo tempo apareceriam como leituras dobradas. Conta as assinaturas, as vivas, o pico de vivas, as canceladas
 * e as leituras de `statuses()` (que podem ser presas por portão para simular resposta atrasada).
 */
private class BroadcastRoomsSource(
    val fake: FakeRoomsSource = FakeRoomsSource().withRooms101And102(),
) : RoomsSource by fake {
    private val live = mutableListOf<Channel<RealtimeEvent>>()
    var subscriptions = 0
    var active = 0
    var maxActive = 0
    var cancelled = 0
    var statusesStarted = 0
    var statusesCancelled = 0
    var holdStatuses = false
    val heldStatuses = mutableListOf<CompletableDeferred<Unit>>()

    fun emit(event: RealtimeEvent) {
        live.toList().forEach { it.trySend(event) }
    }

    override fun events(topics: Set<String>): Flow<RealtimeEvent> =
        flow {
            val queue = Channel<RealtimeEvent>(Channel.UNLIMITED)
            live += queue
            subscriptions++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                for (event in queue) emit(event)
            } catch (e: CancellationException) {
                cancelled++
                throw e
            } finally {
                live -= queue
                active--
            }
        }

    override suspend fun statuses(): List<RoomStatusResponse> {
        statusesStarted++
        if (holdStatuses) {
            val gate = CompletableDeferred<Unit>().also { heldStatuses += it }
            try {
                gate.await()
            } catch (e: CancellationException) {
                statusesCancelled++
                throw e
            }
        }
        return fake.statuses()
    }
}

/** Ataques ao mapa de UHs ligado à casca (Task 9): ciclo de vida, permissão, slot e recomposição. */
@OptIn(ExperimentalTestApi::class)
class RoomMapShellAtaqueTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private data class User(
        val id: String,
        val name: String,
        val permissions: Set<String>,
    )

    /** Cada login abre uma sessão nova (`s-1`, `s-2`, …) do usuário do login digitado; `me()` é o da sessão vigente. */
    private class Fake(
        private val users: Map<String, User> = mapOf("ana" to User("u-ana", "Ana Souza", setOf(ROOMS))),
        private val rooms: RoomsSource? = BroadcastRoomsSource(),
        initial: SessionState = SessionState.LoggedOut,
        val hotelGate: CompletableDeferred<Unit>? = null,
    ) : ServerConnection {
        override val state = MutableStateFlow(initial)
        private var sessions = 0
        var closed = false

        override suspend fun login(
            login: String,
            password: String,
        ) {
            val user = users.getValue(login)
            sessions++
            state.value = SessionState.Active(user.id, "s-$sessions", false)
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
            val userId = (state.value as SessionState.Active).userId
            val user = users.values.single { it.id == userId }
            return Me(user.id, user.id.removePrefix("u-"), user.name, user.permissions)
        }

        override suspend fun hotel(): HotelInfo {
            hotelGate?.await()
            return HotelInfo("Hotel Mar Azul", configured = true)
        }

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun roomsSource(): RoomsSource? = rooms

        override fun close() {
            closed = true
        }
    }

    private fun ComposeUiTest.logIn(user: String = "ana") {
        onNodeWithTag("login-user").performTextClearance()
        onNodeWithTag("login-user").performTextInput(user)
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
    }

    /** A raiz como no `main`: o controlador fecha quando a janela ([open]) sai de cena. */
    private fun shell(
        fake: Fake,
        devMode: Boolean = false,
        open: () -> Boolean = { true },
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            if (open()) {
                val scope = rememberCoroutineScope()
                val controller = remember { AppController(Settings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
                DisposableEffect(controller) { onDispose { controller.close() } }
                ZenyTheme { AppRoot(controller, devMode) }
            }
        }
        logIn()
        after()
    }

    private fun ComposeUiTest.openHousekeeping() {
        onNodeWithText("Governança").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.emit(
        rooms: BroadcastRoomsSource,
        event: RealtimeEvent,
    ) {
        rooms.emit(event)
        waitForIdle()
    }

    private fun ComposeUiTest.tile(n: Int) = onNodeWithTag("room-tile-${roomId(n)}")

    // Permissão e menu

    @Test
    fun releaseWithoutRoomsPermissionHidesHousekeepingAndSaysNoScreensYet() {
        val rooms = BroadcastRoomsSource()
        val users = mapOf("ana" to User("u-ana", "Ana Souza", setOf(GUESTS, RESERVATIONS)))
        shell(Fake(users, rooms)) {
            onNodeWithText("Ana Souza").assertExists()
            onNodeWithText("Governança", substring = true).assertDoesNotExist()
            onNodeWithText("Hóspedes", substring = true).assertDoesNotExist()
            onNodeWithText(NO_SCREENS_YET).assertExists()
            assertEquals(0, rooms.subscriptions)
        }
    }

    @Test
    fun devModeKeepsOtherAreasComingSoonAndNotSelectable() {
        val rooms = BroadcastRoomsSource()
        val users = mapOf("ana" to User("u-ana", "Ana Souza", setOf(ROOMS, GUESTS, RESERVATIONS)))
        shell(Fake(users, rooms), devMode = true) {
            onNodeWithText("Governança").assertExists()
            onNodeWithText("Governança (em breve)").assertDoesNotExist()
            onNodeWithText("Hóspedes (em breve)").assertExists()
            onNodeWithText("Reservas (em breve)").assertExists()
            onNodeWithText(PICK_AN_AREA).assertExists()

            onNodeWithText("Hóspedes (em breve)").performClick()
            waitForIdle()

            onNodeWithText(PICK_AN_AREA).assertExists()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.subscriptions, "área \"(em breve)\" abriu o mapa")

            openHousekeeping()

            onNodeWithText("Mapa de UHs").assertExists()
            onNodeWithText(PICK_AN_AREA).assertDoesNotExist()
            assertEquals(1, rooms.active)
        }
    }

    @Test
    fun defaultSlotShowsSelectedAreaNameAndOpensNoMap() =
        runComposeUiTest {
            val rooms = BroadcastRoomsSource()
            val fake = Fake(rooms = rooms, initial = SessionState.Active("u-ana", "s-1", false))
            setContent {
                val scope = rememberCoroutineScope()
                val state = remember { ShellState(fake, false, scope) }
                ZenyTheme { ShellScreen(state, onLogout = {}) }
            }
            waitForIdle()
            onNodeWithText(PICK_AN_AREA).assertExists()

            onNodeWithText("Governança").performClick()
            waitForIdle()

            // O menu e o conteúdo: sem slot, a área escolhida mostra só o nome, como antes da Task 9.
            onAllNodesWithText("Governança").assertCountEquals(2)
            onNodeWithText(PICK_AN_AREA).assertDoesNotExist()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.subscriptions)
        }

    // Ciclo de vida: área

    @Test
    fun returningToHousekeepingStartsFreshMapWithOneLiveSubscription() =
        runComposeUiTest {
            val rooms = BroadcastRoomsSource()
            val fake = Fake(rooms = rooms, initial = SessionState.Active("u-ana", "s-1", false))
            var area by mutableStateOf(Area.HOUSEKEEPING)
            setContent { ZenyTheme { AreaContent(area, fake) } }
            waitForIdle()
            emit(rooms, RealtimeEvent.Ready)
            tile(1).assertExists()

            area = Area.GUESTS
            waitForIdle()
            assertEquals(0, rooms.active, "a assinatura seguiu viva fora da área")
            assertEquals(1, rooms.cancelled)

            area = Area.HOUSEKEEPING
            waitForIdle()

            onNodeWithText("Mapa de UHs").assertExists()
            tile(1).assertDoesNotExist()
            assertEquals(2, rooms.subscriptions)
            assertEquals(1, rooms.active)

            emit(rooms, RealtimeEvent.Reset)

            assertEquals(2, rooms.statusesStarted, "mais de um mapa vivo respondeu ao mesmo evento")
            tile(1).assertExists()
            assertEquals(1, rooms.maxActive)
        }

    @Test
    fun mapLeavingCompositionClosesSubscriptionAndIgnoresLaterEvents() =
        runComposeUiTest {
            val rooms = BroadcastRoomsSource()
            val fake = Fake(rooms = rooms, initial = SessionState.Active("u-ana", "s-1", false))
            var shown by mutableStateOf(true)
            setContent { ZenyTheme { if (shown) AreaContent(Area.HOUSEKEEPING, fake) } }
            waitForIdle()
            emit(rooms, RealtimeEvent.Ready)
            assertEquals(1, rooms.statusesStarted)

            shown = false
            waitForIdle()
            emit(rooms, RealtimeEvent.Reset)
            rooms.fake.message(roomId(1), 9)
            waitForIdle()

            assertEquals(0, rooms.active)
            assertEquals(1, rooms.cancelled)
            assertEquals(1, rooms.statusesStarted, "o mapa fora de cena ainda recarregou")
            assertTrue(rooms.fake.statusCalls.isEmpty())
        }

    // Ciclo de vida: sessão

    @Test
    fun sessionWithoutActiveStateOpensNoMapAndEndingClosesIt() =
        runComposeUiTest {
            val rooms = BroadcastRoomsSource()
            val fake = Fake(rooms = rooms, initial = SessionState.Ended("Sua sessão foi encerrada. Entre novamente."))
            setContent { ZenyTheme { AreaContent(Area.HOUSEKEEPING, fake) } }
            waitForIdle()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.subscriptions)

            fake.state.value = SessionState.Active("u-ana", "s-1", false)
            waitForIdle()
            onNodeWithText("Mapa de UHs").assertExists()
            assertEquals(1, rooms.active)

            fake.state.value = SessionState.Ended("Sua sessão foi encerrada. Entre novamente.")
            waitForIdle()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.active, "a assinatura sobreviveu ao fim da sessão")

            fake.state.value = SessionState.Active("u-ana", "s-2", false)
            waitForIdle()
            assertEquals(2, rooms.subscriptions)
            assertEquals(1, rooms.active)
        }

    @Test
    fun logoutStopsApplyingStreamEvents() {
        val rooms = BroadcastRoomsSource()
        shell(Fake(rooms = rooms)) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            tile(1).assertExists()
            assertEquals(1, rooms.statusesStarted)

            onNodeWithText("Sair").performClick()
            waitForIdle()
            onNodeWithText("Entrar").assertExists()

            emit(rooms, RealtimeEvent.Reset)
            rooms.fake.message(roomId(1), 9)
            waitForIdle()

            assertEquals(0, rooms.active)
            assertEquals(1, rooms.cancelled)
            assertEquals(1, rooms.statusesStarted, "evento aplicado depois do logout")
            assertTrue(rooms.fake.statusCalls.isEmpty())
        }
    }

    @Test
    fun sessionEndedInsideShellClosesMap() {
        val rooms = BroadcastRoomsSource()
        val fake = Fake(rooms = rooms)
        shell(fake) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            assertEquals(1, rooms.active)

            fake.state.value = SessionState.Ended("Sua sessão foi encerrada. Entre novamente.")
            waitForIdle()
            onNodeWithText("Entrar").assertExists()
            emit(rooms, RealtimeEvent.Reset)

            assertEquals(0, rooms.active)
            assertEquals(1, rooms.statusesStarted, "evento aplicado depois do fim da sessão")
        }
    }

    @Test
    fun sessionSwitchShowsNoRoomsFromPreviousSessionAndOnlyNewMapListens() {
        val rooms = BroadcastRoomsSource()
        val fake = Fake(rooms = rooms)
        shell(fake) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            tile(1).assertExists()

            fake.state.value = SessionState.Active("u-ana", "s-9", false)
            waitForIdle()

            onNodeWithText("Mapa de UHs").assertExists()
            tile(1).assertDoesNotExist()
            assertEquals(1, rooms.cancelled, "a assinatura da sessão anterior não foi cancelada")
            assertEquals(1, rooms.active)

            emit(rooms, RealtimeEvent.Ready)

            assertEquals(2, rooms.statusesStarted, "o mapa da sessão anterior também respondeu")
            tile(1).assertExists()
        }
    }

    @Test
    fun reloadInFlightFromPreviousSessionIsCancelledOnSwitch() {
        val rooms = BroadcastRoomsSource().apply { holdStatuses = true }
        val fake = Fake(rooms = rooms)
        shell(fake) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            assertEquals(1, rooms.heldStatuses.size)

            fake.state.value = SessionState.Active("u-ana", "s-9", false)
            waitForIdle()
            assertEquals(1, rooms.statusesCancelled, "a recarga da sessão anterior seguiu em voo")

            rooms.heldStatuses.single().complete(Unit)
            waitForIdle()

            tile(1).assertDoesNotExist()
            assertEquals(1, rooms.statusesStarted)
        }
    }

    // Recomposição

    @Test
    fun sameSessionUpdateKeepsMapAndSubscription() {
        val rooms = BroadcastRoomsSource()
        val fake = Fake(rooms = rooms)
        shell(fake) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            val active = fake.state.value as SessionState.Active

            fake.state.value = active.copy(passwordChangeAwaitingRefresh = true)
            waitForIdle()
            fake.state.value = active
            waitForIdle()

            tile(1).assertExists()
            assertEquals(1, rooms.subscriptions, "a mesma sessão reassinou")
            assertEquals(0, rooms.cancelled)
            assertEquals(1, rooms.statusesStarted)
        }
    }

    @Test
    fun shellStateChangesDoNotRecreateMap() {
        val rooms = BroadcastRoomsSource()
        val gate = CompletableDeferred<Unit>()
        shell(Fake(rooms = rooms, hotelGate = gate)) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            tile(1).assertExists()

            gate.complete(Unit)
            waitForIdle()
            onNodeWithText("Hotel Mar Azul").assertExists()
            onNodeWithText("Governança").performClick()
            waitForIdle()

            tile(1).assertExists()
            assertEquals(1, rooms.subscriptions, "a recomposição da casca reassinou")
            assertEquals(0, rooms.cancelled)
            assertEquals(1, rooms.statusesStarted)
        }
    }

    // Saída da casca e troca de usuário

    @Test
    fun closingTheWindowClosesMap() {
        val rooms = BroadcastRoomsSource()
        val fake = Fake(rooms = rooms)
        var open by mutableStateOf(true)
        shell(fake, open = { open }) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)
            assertEquals(1, rooms.active)

            open = false
            waitForIdle()
            emit(rooms, RealtimeEvent.Reset)

            assertEquals(0, rooms.active, "a assinatura sobreviveu à janela")
            assertEquals(1, rooms.statusesStarted)
            assertTrue(fake.closed)
        }
    }

    @Test
    fun loginAsAnotherUserAfterLogoutShowsOnlyNewUserShell() {
        val rooms = BroadcastRoomsSource()
        val users =
            mapOf(
                "ana" to User("u-ana", "Ana Souza", setOf(ROOMS)),
                "bia" to User("u-bia", "Bia Lima", setOf(GUESTS)),
            )
        shell(Fake(users, rooms)) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)

            onNodeWithText("Sair").performClick()
            waitForIdle()
            logIn("bia")

            onNodeWithText("Bia Lima").assertExists()
            onNodeWithText("Ana Souza").assertDoesNotExist()
            onNodeWithText("Governança", substring = true).assertDoesNotExist()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            onNodeWithText(NO_SCREENS_YET).assertExists()
            assertEquals(0, rooms.active)
            assertEquals(1, rooms.subscriptions)
        }
    }

    @Test
    fun reloginStartsWithNoAreaOpenAndNoSubscription() {
        val rooms = BroadcastRoomsSource()
        shell(Fake(rooms = rooms)) {
            openHousekeeping()
            emit(rooms, RealtimeEvent.Ready)

            onNodeWithText("Sair").performClick()
            waitForIdle()
            logIn()

            onNodeWithText(PICK_AN_AREA).assertExists()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.active, "o mapa reabriu sozinho no login seguinte")

            openHousekeeping()

            tile(1).assertDoesNotExist()
            assertEquals(2, rooms.subscriptions)
            assertEquals(1, rooms.active)
        }
    }

    // Conexão sem o mapa

    @Test
    fun connectionWithoutRoomsKeepsShellUsable() {
        val fake = Fake(rooms = null)
        shell(fake) {
            openHousekeeping()
            onNodeWithText(UNAVAILABLE).assertExists()

            fake.state.value = SessionState.Active("u-ana", "s-9", false)
            waitForIdle()
            onNodeWithText(UNAVAILABLE).assertExists()
            onNodeWithText("Governança").performClick()
            waitForIdle()
            onNodeWithText(UNAVAILABLE).assertExists()

            onNodeWithText("Sair").performClick()
            waitForIdle()
            onNodeWithText("Entrar").assertExists()
        }
    }
}
