package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.rooms.RoomDisplayStatus
import com.zeny.desktop.app.rooms.RoomMapState
import com.zeny.desktop.app.rooms.RoomTile
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val NIGHTS = 2L
private const val CROWD = 40
private const val CPF_BASE_DIGITS = 9
private const val CPF_MODULUS = 11
private const val CPF_FIRST_WEIGHT = 10
private const val CPF_SECOND_WEIGHT = 11
private const val DIGITS = 10
private const val MAX_SUFFIX = 100_000

/**
 * Verificação do walk-in contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo `CoreConnection`
 * real e pelos estados reais da tela. `twoNightWalkInGoesInHouse` **escreve** no servidor (cria um walk-in e um hóspede
 * fictício), por isso tem opt-in próprio: pulada sem `ZENY_LIVE_WALKIN=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/walkin.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_WALKIN=1 ./gradlew :app:test --tests '*LiveWalkInCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveWalkInCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private class Screen {
        lateinit var panel: DayPanelState
        lateinit var detail: ReservationDetailState
        lateinit var map: RoomMapState
        lateinit var scope: CoroutineScope
        var walkIn by mutableStateOf<WalkInState?>(null)
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_WALKIN") == "1", "sem ZENY_LIVE_WALKIN=1: verificação real pulada")
    }

    @Test
    @Suppress("LongMethod") // um roteiro só: cotação, UH, hóspede, conclusão e as afirmações
    fun twoNightWalkInGoesInHouse() =
        withViewer { connection ->
            val source = connection.receptionSource()
            withScreen(connection) { screen ->
                val panel = screen.panel
                val inHouseBefore =
                    panel.tabs
                        .getValue(DayList.IN_HOUSE)
                        .rows.size
                val state = newWalkIn(screen, source)
                try {
                    runOnUiThread { screen.walkIn = state }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.arrival != null }
                    val arrival = checkNotNull(state.arrival)
                    runOnUiThread {
                        state.departureText = DateInput.format(BusinessDate(arrival.value.plusDays(NIGHTS)))
                        state.adults = 1
                        state.quote()
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        !state.quoting && (state.offers.isNotEmpty() || state.noOffers || state.error != null)
                    }
                    assertNull(state.error, "a cotação falhou: ${state.error}")
                    assertTrue(state.offers.isNotEmpty(), "a cotação não trouxe oferta")
                    val roomsPerOffer = mutableListOf<Int>()
                    var chosen: WalkInRoom? = null
                    for (offer in state.offers) {
                        runOnUiThread { state.chooseOffer(offer) }
                        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !state.loadingRooms }
                        roomsPerOffer += state.rooms.size
                        chosen = state.rooms.firstOrNull()
                        if (chosen != null) break
                    }
                    val room = chosen ?: fail("nenhuma oferta com UH: ofertas=${state.offers.size}, UHs=$roomsPerOffer")
                    val tileBefore = tile(screen.map, room.number)
                    assertTrue(tileBefore.status != RoomDisplayStatus.OCCUPIED, "a UH escolhida já estava ocupada")
                    runOnUiThread { state.chooseRoom(room) }
                    assertTrue(state.stepGuestEnabled, "o passo do hóspede não abriu")

                    createGuest(state, "Hóspede Teste Verificação ${Random.nextInt(MAX_SUFFIX)}", validCpf())
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        !state.picker.creating && (state.primary != null || state.picker.createError != null)
                    }
                    assertNull(state.picker.createError, "o cadastro falhou: ${state.picker.createError}")
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.canConfirm }
                    runOnUiThread { state.confirm() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.done != null || state.error != null }
                    assertNull(state.error, "o walk-in falhou: ${state.error}")
                    val done = checkNotNull(state.done)
                    assertEquals(room.number, done.roomNumber)

                    runOnUiThread { panel.refresh() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        panel.tabs
                            .getValue(DayList.IN_HOUSE)
                            .rows
                            .any { it.code == done.reservationCode }
                    }
                    val row =
                        panel.tabs
                            .getValue(DayList.IN_HOUSE)
                            .rows
                            .first { it.code == done.reservationCode }
                    assertEquals("Hospedada", row.status)
                    println(
                        "LiveWalkInCheck: reserva ${done.reservationCode} UH ${room.number}; ofertas " +
                            "${state.offers.size}, UHs por oferta $roomsPerOffer; hospedados $inHouseBefore -> " +
                            "${panel.tabs.getValue(DayList.IN_HOUSE).rows.size}; ${done.period}, total ${done.total}",
                    )

                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        tileOrNull(screen.map, room.number)?.status == RoomDisplayStatus.OCCUPIED
                    }
                    waitForIdle()
                    capture("walkin")
                } finally {
                    state.close()
                }
            }
        }

    @Test
    fun noOfferShowsMessage() =
        withViewer { connection ->
            val source = connection.receptionSource()
            withScreen(connection) { screen ->
                val state = newWalkIn(screen, source)
                try {
                    runOnUiThread { state.start() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.arrival != null }
                    val arrival = checkNotNull(state.arrival)
                    runOnUiThread {
                        state.departureText = DateInput.format(BusinessDate(arrival.value.plusDays(NIGHTS)))
                        state.adults = CROWD
                        state.quote()
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        !state.quoting && (state.offers.isNotEmpty() || state.noOffers || state.error != null)
                    }
                    assertNull(state.error, "a cotação falhou: ${state.error}")
                    assertTrue(state.noOffers, "veio oferta para $CROWD adultos: ${state.offers.size}")
                    assertEquals(
                        "Não há UH disponível para essa estadia. Mude a saída ou o número de hóspedes.",
                        ActionText.noOffers(),
                    )
                    assertFalse(state.stepGuestEnabled)
                } finally {
                    state.close()
                }
            }
        }

    @Test
    fun roomListLeavesOutRoomsAssignedToArrivals() =
        withViewer { connection ->
            val source = connection.receptionSource()
            withScreen(connection) { screen ->
                // só leitura: cota e lista UHs, não cadastra nem conclui
                val assigned =
                    screen.panel.tabs
                        .getValue(DayList.ARRIVALS)
                        .rows
                        .map { it.room }
                        .filter { it.isNotBlank() && it != "—" && it != "-" }
                        .toSet()
                assertTrue(assigned.isNotEmpty(), "nenhuma chegada com UH alocada: a verificação ficaria vazia")
                val state = newWalkIn(screen, source)
                try {
                    runOnUiThread { state.start() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.arrival != null }
                    val arrival = checkNotNull(state.arrival)
                    runOnUiThread {
                        state.departureText = DateInput.format(BusinessDate(arrival.value.plusDays(NIGHTS)))
                        state.adults = 1
                        state.quote()
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        !state.quoting && (state.offers.isNotEmpty() || state.noOffers || state.error != null)
                    }
                    assertNull(state.error, "a cotação falhou: ${state.error}")
                    assertTrue(state.offers.isNotEmpty(), "a cotação não trouxe oferta")
                    val listed = mutableListOf<Int>()
                    for (offer in state.offers) {
                        runOnUiThread { state.chooseOffer(offer) }
                        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !state.loadingRooms }
                        listed += state.rooms.size
                        val leaked = state.rooms.map { it.number }.filter { it in assigned }
                        assertTrue(leaked.isEmpty(), "UHs de chegadas na lista: $leaked (alocadas: $assigned)")
                    }
                    println("LiveWalkInCheck: alocadas às chegadas ${assigned.size}; UHs por oferta $listed")
                } finally {
                    state.close()
                }
            }
        }

    private fun newWalkIn(
        screen: Screen,
        source: ReceptionSource,
    ): WalkInState {
        val permissions = runBlocking { source.permissions() }
        val approve: suspend (ApprovableAction<WalkInResponse>) -> ApiResponse<WalkInResponse>? = { it.run(null) }
        return WalkInState(source, screen.scope, permissions, approve) { screen.panel.refresh() }
    }

    private fun createGuest(
        state: WalkInState,
        name: String,
        cpf: String,
    ) {
        val form = state.picker.form

        fun set(
            field: String,
            value: String,
        ) = form.fields.first { it.name == field }.onChange(value)
        set("fullName", name)
        set("documentType", "CPF")
        set("documentNumber", cpf)
        set("nationality", "BR")
        set("birthDate", "15/06/1990")
        state.picker.create()
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        // com o diálogo aberto há duas raízes (janela e diálogo): captura a última, o diálogo concluído
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    private fun tileOrNull(
        map: RoomMapState,
        number: String,
    ): RoomTile? = map.floors.flatMap { it.second }.firstOrNull { it.number == number }

    private fun tile(
        map: RoomMapState,
        number: String,
    ): RoomTile = tileOrNull(map, number) ?: fail("a UH $number não está no mapa")

    private fun validCpf(): String {
        while (true) {
            val base = List(CPF_BASE_DIGITS) { Random.nextInt(DIGITS) }
            if (base.distinct().size == 1) continue
            val first = checkDigit(base, CPF_FIRST_WEIGHT)
            val second = checkDigit(base + first, CPF_SECOND_WEIGHT)
            return (base + first + second).joinToString("")
        }
    }

    private fun checkDigit(
        digits: List<Int>,
        firstWeight: Int,
    ): Int {
        val sum = digits.mapIndexed { index, digit -> digit * (firstWeight - index) }.sum()
        return (sum * DIGITS % CPF_MODULUS) % DIGITS
    }

    private fun withScreen(
        connection: CoreConnection,
        block: DesktopComposeUiTest.(Screen) -> Unit,
    ) {
        val screen = Screen()
        val source = connection.receptionSource()
        val rooms = checkNotNull(connection.roomsSource())
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            setContent {
                screen.scope = rememberCoroutineScope()
                screen.panel = remember { DayPanelState(source, screen.scope) }
                screen.detail = remember { ReservationDetailState(source, screen.scope) }
                screen.map = remember { RoomMapState(rooms, screen.scope) }
                ZenyTheme {
                    ReceptionScreen(screen.panel, screen.detail)
                    screen.walkIn?.let { WalkInDialog(it) {} }
                }
            }
            try {
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                    screen.panel.day != null &&
                        screen.panel.tabs.values
                            .all { !it.loading && !it.hasMore }
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !screen.map.loading && screen.map.floors.isNotEmpty() }
                assertNull(screen.map.error, "o mapa falhou: ${screen.map.error?.userMessage}")
                block(screen)
            } finally {
                screen.detail.close()
                screen.panel.close()
                screen.map.close()
            }
        }
    }

    private fun withViewer(block: (CoreConnection) -> Unit) {
        val url = ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
        val core = ZenyCore(url, MemorySettings())
        val connection = CoreConnection(core)
        try {
            runBlocking { connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD")) }
            block(connection)
            assertIs<SessionState.Active>(connection.state.value, "a sessão da Recepcionista caiu")
        } finally {
            runCatching { runBlocking { connection.logout() } }
            connection.close()
        }
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")
}
