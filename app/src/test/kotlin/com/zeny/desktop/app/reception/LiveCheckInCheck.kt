package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.rooms.RoomDisplayStatus
import com.zeny.desktop.app.rooms.RoomMapScreen
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
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val PAGE_LIMIT = 200
private const val CPF_BASE_DIGITS = 9
private const val CPF_MODULUS = 11
private const val CPF_FIRST_WEIGHT = 10
private const val CPF_SECOND_WEIGHT = 11
private const val CONFIRMED = "CONFIRMED"
private const val NOT_READY_TEXT =
    "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança."
private val ELEVEN_DIGITS = Regex("""\d{11}""")

/** Uma chegada `CONFIRMED` sem UH e o que o servidor sugere para ela. */
@Suppress("LongParameterList") // o que o servidor oferece, em contagens e UHs
private class Candidate(
    val seed: CheckInSeed,
    val code: String,
    val suggestions: Int,
    val readyRoom: RoomOption?,
    val notReadyVacantRoom: RoomOption?,
    val readyCount: Int,
    val notReadyVacantCount: Int,
)

/**
 * Verificação do check-in contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real e pelos estados reais da tela. **Escreve** no servidor (check-in de uma chegada do seed e um
 * hóspede fictício), por isso tem opt-in próprio: pulada sem `ZENY_LIVE_CHECKIN=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/checkin.png` e `build/live/checkin-map.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_CHECKIN=1 ./gradlew :app:test --tests '*LiveCheckInCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
@Suppress("LargeClass", "TooManyFunctions")
class LiveCheckInCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** A tela viva: painel, detalhe e mapa de UHs reais, com o que a tela alterna entre recepção e mapa. */
    private class Screen {
        lateinit var panel: DayPanelState
        lateinit var detail: ReservationDetailState
        lateinit var map: RoomMapState
        lateinit var scope: CoroutineScope
        var showMap by mutableStateOf(false)
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_CHECKIN") == "1", "sem ZENY_LIVE_CHECKIN=1: verificação real pulada")
    }

    @Test
    @Suppress("LongMethod") // um roteiro só: preparação, fluxo e as quatro afirmações
    fun checkInMovesArrivalAndRoom() =
        withViewer { connection ->
            val source = connection.receptionSource()
            val pick =
                candidates(source).filter { it.readyRoom != null }.minByOrNull { it.seed.reservationId }
                    ?: fail("sem chegada CONFIRMED sem UH com UH pronta: ${describe(candidates(source))}")
            val ready = checkNotNull(pick.readyRoom)
            val id = pick.seed.reservationId
            withScreen(connection) { screen ->
                val panel = screen.panel
                val arrivalsBefore =
                    panel.tabs
                        .getValue(DayList.ARRIVALS)
                        .rows.size
                val inHouseBefore =
                    panel.tabs
                        .getValue(DayList.IN_HOUSE)
                        .rows.size
                val tileBefore = tile(screen.map, ready.number)
                assertTrue(tileBefore.status != RoomDisplayStatus.OCCUPIED, "a UH escolhida já estava ocupada")
                val name = "Hóspede Teste Verificação ${Random.nextInt(MAX_SUFFIX)}"
                val cpf = validCpf()
                val checkIn = newCheckIn(screen, source, pick.seed)
                try {
                    runOnUiThread {
                        screen.detail.open(id)
                        checkIn.start()
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { checkIn.allocation.options.isNotEmpty() }
                    assertTrue(checkIn.allocation.options.any { it.roomId == ready.roomId }, "a UH pronta sumiu")
                    runOnUiThread { checkIn.allocation.assign(ready.roomId) }
                    awaitAllocation(checkIn)
                    assertNull(checkIn.allocation.error, "a alocação falhou: ${checkIn.allocation.error}")
                    assertEquals(ready.roomId, checkIn.allocation.currentRoomId)

                    createGuest(checkIn, name, cpf)
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        val created = checkIn.primary?.id != pick.seed.holderGuestId
                        !checkIn.picker.creating && (created || checkIn.picker.createError != null)
                    }
                    assertNull(checkIn.picker.createError, "o cadastro falhou: ${checkIn.picker.createError}")
                    assertTrue(checkIn.primary?.id != pick.seed.holderGuestId, "o hóspede criado não virou o principal")
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { checkIn.canConfirm }
                    runOnUiThread { checkIn.confirm() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { checkIn.done != null || checkIn.error != null }
                    assertNull(checkIn.error, "o check-in falhou: ${checkIn.error}")
                    assertEquals(ready.number, checkNotNull(checkIn.done).roomNumber)

                    // Painel: sai de "Chegadas" como confirmada e entra em "Hospedados".
                    runOnUiThread { panel.refresh() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        panel.tabs
                            .getValue(DayList.IN_HOUSE)
                            .rows
                            .any { it.id == id }
                    }
                    val arrivalRow =
                        panel.tabs
                            .getValue(DayList.ARRIVALS)
                            .rows
                            .firstOrNull { it.id == id }
                    println(
                        "LiveCheckInCheck: reserva ${pick.code} UH ${ready.number}; chegadas $arrivalsBefore -> " +
                            "${panel.tabs.getValue(DayList.ARRIVALS).rows.size}, hospedados $inHouseBefore -> " +
                            "${panel.tabs.getValue(DayList.IN_HOUSE).rows.size}; na lista de chegadas depois: " +
                            "${arrivalRow?.status ?: "ausente"}",
                    )
                    assertTrue(arrivalRow == null || arrivalRow.status != "Confirmada", "ainda aparece como confirmada")

                    // Mapa: a UH fica ocupada pelo evento de `rooms`, sem recarregar a tela.
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        tileOrNull(screen.map, ready.number)?.status == RoomDisplayStatus.OCCUPIED
                    }

                    // Detalhe: o hóspede criado em "Hóspedes da estadia", com o documento mascarado.
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        screen.detail.view
                            ?.stayGuests
                            ?.any { it.name == name } == true
                    }
                    assertStayGuest(checkNotNull(screen.detail.view), name, cpf)

                    capture("checkin")
                    runOnUiThread { screen.showMap = true }
                    waitForIdle()
                    capture("checkin-map")
                } finally {
                    checkIn.close()
                }
            }
        }

    @Test
    fun notReadyRoomLeavesNoEffect() =
        withViewer { connection ->
            val source = connection.receptionSource()
            val found = candidates(source)
            val pick =
                found.filter { it.notReadyVacantRoom != null }.maxByOrNull { it.seed.reservationId }
                    ?: fail("sem UH vaga e não pronta nas sugestões: ${describe(found)}")
            val room = checkNotNull(pick.notReadyVacantRoom)
            val id = pick.seed.reservationId
            try {
                withScreen(connection) { screen ->
                    val checkIn = newCheckIn(screen, source, pick.seed)
                    try {
                        runOnUiThread { checkIn.start() }
                        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { checkIn.allocation.options.isNotEmpty() }
                        runOnUiThread { checkIn.allocation.assign(room.roomId) }
                        awaitAllocation(checkIn)
                        assertNull(checkIn.allocation.error, "a alocação falhou: ${checkIn.allocation.error}")
                        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { checkIn.canConfirm }
                        runOnUiThread { checkIn.confirm() }
                        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { checkIn.done != null || checkIn.error != null }
                        assertNull(checkIn.done, "o check-in passou com UH não pronta")
                        assertEquals(NOT_READY_TEXT, checkIn.error)
                    } finally {
                        checkIn.close()
                    }
                }
                val after = runBlocking { source.reservation(id) }
                assertEquals(CONFIRMED, after.reservation.status, "a reserva saiu de CONFIRMED")
                val status = runBlocking { source.roomStatuses() }.first { it.roomId.toString() == room.roomId }
                assertEquals(Occupancy.VACANT, status.occupancy, "a UH deixou de estar vaga")
            } finally {
                runBlocking { source.unassignRoom(id) }
            }
            val restored = runBlocking { source.reservation(id) }.toCheckInSeed()
            assertNull(restored.roomId, "a reserva continua com UH")
        }

    private fun assertStayGuest(
        view: ReservationDetailView,
        name: String,
        cpf: String,
    ) {
        val line = view.stayGuests.first { it.name == name }
        assertTrue(line.primary, "o hóspede criado não é o principal")
        val document = assertNotNull(line.document, "o hóspede da estadia veio sem documento")
        assertTrue(document.startsWith("CPF "), "documento fora do padrão")
        assertTrue(ELEVEN_DIGITS.find(document) == null, "documento sem máscara")
        assertTrue(!document.contains(cpf), "documento sem máscara")
        assertEquals("Hospedada", view.status.label)
    }

    private fun DesktopComposeUiTest.awaitAllocation(checkIn: CheckInState) {
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            val allocation = checkIn.allocation
            !allocation.busy && (allocation.currentRoomId != null || allocation.error != null)
        }
    }

    private fun createGuest(
        checkIn: CheckInState,
        name: String,
        cpf: String,
    ) {
        val form = checkIn.picker.form

        fun set(
            field: String,
            value: String,
        ) = form.fields.first { it.name == field }.onChange(value)
        set("fullName", name)
        set("documentType", "CPF")
        set("documentNumber", cpf)
        set("nationality", "BR")
        set("birthDate", "15/06/1990")
        checkIn.picker.create()
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun tileOrNull(
        map: RoomMapState,
        number: String,
    ): RoomTile? = map.floors.flatMap { it.second }.firstOrNull { it.number == number }

    private fun tile(
        map: RoomMapState,
        number: String,
    ): RoomTile = tileOrNull(map, number) ?: fail("a UH $number não está no mapa")

    private fun newCheckIn(
        screen: Screen,
        source: ReceptionSource,
        seed: CheckInSeed,
    ): CheckInState {
        val approve: suspend (ApprovableAction<CheckInResponse>) -> ApiResponse<CheckInResponse>? = { it.run(null) }
        return CheckInState(source, screen.scope, seed, screen.panel.permissions, approve, Clock.systemUTC()::instant) {
            screen.panel.refresh()
            screen.detail.reload()
        }
    }

    /** As chegadas `CONFIRMED` sem UH, com as sugestões já separadas em prontas e vagas-não-prontas. */
    private fun candidates(source: ReceptionSource): List<Candidate> =
        runBlocking {
            val date = source.businessDay().date
            val statuses = source.roomStatuses().associateBy { it.roomId.toString() }
            val items = mutableListOf<ReservationListItemResponse>()
            var cursor: String? = null
            do {
                val page = source.dayPage(DayList.ARRIVALS, date, cursor, PAGE_LIMIT)
                items += page.items
                cursor = page.nextCursor
            } while (cursor != null)
            items.mapNotNull { item ->
                val seed = source.reservation(item.id.toString()).toCheckInSeed()
                if (seed.status != CONFIRMED || seed.roomId != null) return@mapNotNull null
                val suggestions = source.roomSuggestions(seed.reservationId)
                val options =
                    suggestions.map {
                        val status = statuses[it.roomId.toString()]
                        Triple(it, status?.readyForCheckIn == true, status?.occupancy == Occupancy.VACANT)
                    }

                fun option(entry: Triple<RoomSuggestionResponse, Boolean, Boolean>?) =
                    entry?.let { (suggestion, ready, _) ->
                        RoomOption(
                            roomId = suggestion.roomId.toString(),
                            number = suggestion.number,
                            floor = null,
                            current = suggestion.current,
                            outOfService = suggestion.outOfService,
                            matchesFloorPreference = suggestion.matchesFloorPreference,
                            readiness = ChipModel("", StatusTone.NEUTRAL),
                            ready = ready,
                        )
                    }
                val ready = options.filter { it.second && !it.first.outOfService }
                val notReady = options.filter { !it.second && it.third && !it.first.outOfService }
                Candidate(
                    seed = seed,
                    code = item.code,
                    suggestions = suggestions.size,
                    readyRoom = option(ready.firstOrNull()),
                    notReadyVacantRoom = option(notReady.firstOrNull()),
                    readyCount = ready.size,
                    notReadyVacantCount = notReady.size,
                )
            }
        }

    private fun describe(found: List<Candidate>): String =
        "chegadas CONFIRMED sem UH=${found.size}, sugestões=${found.map { it.suggestions }}, " +
            "prontas=${found.map { it.readyCount }}, vagas não prontas=${found.map { it.notReadyVacantCount }}"

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
        val permissions = runBlocking { connection.me().permissions }
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            setContent {
                screen.scope = rememberCoroutineScope()
                screen.panel = remember { DayPanelState(source, screen.scope, permissions) }
                screen.detail = remember { ReservationDetailState(source, screen.scope) }
                screen.map = remember { RoomMapState(rooms, screen.scope) }
                ZenyTheme {
                    if (screen.showMap) RoomMapScreen(screen.map) else ReceptionScreen(screen.panel, screen.detail)
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

    private companion object {
        const val DIGITS = 10
        const val MAX_SUFFIX = 100_000
    }
}
