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
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.approval.ApprovalCoordinator
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
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
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
private const val IN_HOUSE = "IN_HOUSE"
private const val CONFIRMED = "CONFIRMED"
private const val MOVE_TITLE = "Troca de UH"
private const val APPROVAL_REASON = "Verificação ao vivo do desfazer check-in"
private const val DIGITS = 10

/**
 * Verificação da troca de UH e do desfazer check-in contra um servidor real (o ambiente `zeny-front`), com a
 * Recepcionista, pelo `CoreConnection` real, pelos estados reais e pelo `ApprovalCoordinator` real. As duas
 * verificações **escrevem** no servidor, por isso têm opt-in próprio: puladas sem `ZENY_LIVE_STAY=1`; com ele,
 * exigem `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`, `ZENY_LIVE_APPROVER_LOGIN` e
 * `ZENY_LIVE_APPROVER_PIN` (só do ambiente, nunca impressas). Grava `build/live/room-move.png` e
 * `build/live/undo-check-in.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_STAY=1 ./gradlew :app:test --tests '*LiveStayActionsCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveStayActionsCheck {
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
        lateinit var approvals: ApprovalCoordinator
        var roomMove by mutableStateOf<RoomMoveState?>(null)
        var undo by mutableStateOf<UndoCheckInState?>(null)
        var undoCode by mutableStateOf("")
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_STAY") == "1", "sem ZENY_LIVE_STAY=1: verificação real pulada")
    }

    @Test
    @Suppress("LongMethod") // um roteiro só: escolha, troca, mapa, histórico e captura
    fun roomMoveChangesBothRoomsAndHistory() =
        withViewer { connection ->
            val source = connection.receptionSource()
            withScreen(connection) { screen ->
                val rows =
                    screen.panel.tabs
                        .getValue(DayList.IN_HOUSE)
                        .rows
                        .sortedBy { it.code }
                var chosen: ReservationRow? = null
                var state: RoomMoveState? = null
                val roomsPerStay = mutableListOf<Int>()
                for (row in rows) {
                    val candidate = newRoomMove(screen, source, row.id)
                    runOnUiThread { candidate.start() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !candidate.loading }
                    roomsPerStay += candidate.rooms.size
                    if (candidate.error == null && candidate.rooms.isNotEmpty()) {
                        chosen = row
                        state = candidate
                        break
                    }
                    candidate.close()
                }
                val row =
                    chosen ?: fail(
                        "sem hospedada com noite restante e UH pronta do mesmo tipo: hospedadas=${rows.size}, " +
                            "UHs candidatas por hospedada=$roomsPerStay",
                    )
                val move = checkNotNull(state)
                try {
                    val origin = checkNotNull(move.currentRoomNumber) { "a hospedada ${row.code} não tem UH atual" }
                    val target = move.rooms.first()
                    assertTrue(
                        tile(screen.map, origin).status == RoomDisplayStatus.OCCUPIED,
                        "a origem não estava ocupada",
                    )
                    assertTrue(
                        tile(screen.map, target.number).status != RoomDisplayStatus.OCCUPIED,
                        "o destino já estava ocupado",
                    )
                    runOnUiThread {
                        screen.roomMove = move
                        screen.detail.open(row.id)
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.detail.view?.code == row.code }
                    // o diálogo relê a reserva ao abrir: espera a recarga dele antes de escolher a UH
                    waitForIdle()
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !move.loading }
                    val movesBefore = moveLines(screen)
                    runOnUiThread {
                        move.room = target
                        move.reason = "GUEST_REQUEST"
                        move.tariff = "KEEP"
                    }
                    assertTrue(move.canConfirm, "a troca não pode ser confirmada: ${move.error}")
                    runOnUiThread { move.confirm() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { move.done != null || move.error != null }
                    assertNull(move.error, "a troca falhou: ${move.error}")
                    val summary = checkNotNull(move.done)
                    assertTrue(summary.contains(target.number), "o resumo não cita a UH ${target.number}")

                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        tileOrNull(screen.map, target.number)?.status == RoomDisplayStatus.OCCUPIED &&
                            tileOrNull(screen.map, origin)?.status == RoomDisplayStatus.DIRTY
                    }
                    val originAfter = tile(screen.map, origin)
                    assertEquals(RoomDisplayStatus.DIRTY, originAfter.status, "a origem devia ficar vaga e suja")
                    runOnUiThread { screen.detail.reload() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { moveLines(screen) > movesBefore }
                    assertTrue(moveLines(screen) > movesBefore, "o histórico não ganhou \"$MOVE_TITLE\"")
                    println(
                        "LiveStayActionsCheck: troca da reserva ${row.code} de UH $origin para UH ${target.number}; " +
                            "hospedadas ${rows.size}, UHs candidatas por hospedada $roomsPerStay; " +
                            "histórico \"$MOVE_TITLE\" $movesBefore -> ${moveLines(screen)}",
                    )
                    waitForIdle()
                    capture("room-move")
                } finally {
                    move.close()
                }
            }
        }

    @Test
    @Suppress("LongMethod") // um roteiro só: escolha, PIN errado, PIN certo, reserva, mapa e captura
    fun undoCheckInNeedsManagerApproval() =
        withViewer { connection ->
            val source = connection.receptionSource()
            val approverLogin = env("ZENY_LIVE_APPROVER_LOGIN")
            val approverPin = env("ZENY_LIVE_APPROVER_PIN")
            require(approverPin.all(Char::isDigit)) { "ZENY_LIVE_APPROVER_PIN deve ser numérico" }
            val wrongPin = approverPin.map { ((it - '0' + 1) % DIGITS).digitToChar() }.joinToString("")
            assertTrue(wrongPin != approverPin && wrongPin.length == approverPin.length, "PIN errado inválido")
            withScreen(connection) { screen ->
                val today = runBlocking { source.businessDay().date }
                val rows =
                    screen.panel.tabs
                        .getValue(DayList.IN_HOUSE)
                        .rows
                        .sortedBy { it.code }
                val seeds =
                    rows.mapNotNull { row ->
                        val reservation = runBlocking { source.reservation(row.id) }.reservation
                        val checkedInOn = (reservation.checkIn as? Option.Some)?.value?.businessDate
                        val roomNumber = (reservation.roomAssignment as? Option.Some)?.value?.roomNumber
                        if (reservation.status == IN_HOUSE && checkedInOn == today.value.toString()) {
                            Triple(row, (roomNumber as? Option.Some)?.value, checkedInOn)
                        } else {
                            null
                        }
                    }
                val (row, roomNumber, _) =
                    seeds.firstOrNull()
                        ?: fail(
                            "sem hospedada com check-in em ${today.value}: hospedadas=${rows.size}, " +
                                "com check-in hoje=${seeds.size}",
                        )
                val room = checkNotNull(roomNumber) { "a hospedada ${row.code} não tem UH atual" }
                assertEquals(RoomDisplayStatus.OCCUPIED, tile(screen.map, room).status, "a UH não estava ocupada")
                val approvals = screen.approvals
                val state = newUndo(screen, source, row.id)
                try {
                    runOnUiThread {
                        screen.undoCode = row.code
                        screen.undo = state
                        screen.detail.open(row.id)
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.detail.view?.code == row.code }
                    runOnUiThread { state.confirm() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { approvals.pending != null }
                    assertTrue(approvals.pending != null, "o servidor não pediu aprovação")

                    // PIN errado: uma tentativa só, cada uma conta para o bloqueio do aprovador
                    runOnUiThread {
                        approvals.login = approverLogin
                        approvals.pin = wrongPin
                        approvals.reason = APPROVAL_REASON
                        approvals.confirm()
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { approvals.dialog?.message != null }
                    val refusal = checkNotNull(approvals.dialog?.message)
                    assertTrue(refusal.isNotBlank(), "o coordenador não mostrou mensagem")
                    assertFalse(refusal.contains(wrongPin) || refusal.contains(approverPin), "a mensagem vazou um PIN")
                    assertFalse(state.done, "o desfazer andou com o PIN errado")
                    assertEquals(IN_HOUSE, runBlocking { source.reservation(row.id) }.reservation.status)
                    println("LiveStayActionsCheck: PIN errado recusado com a mensagem \"$refusal\"")

                    // PIN certo
                    runOnUiThread {
                        approvals.login = approverLogin
                        approvals.pin = approverPin
                        approvals.reason = APPROVAL_REASON
                        approvals.confirm()
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.done || state.error != null }
                    assertNull(state.error, "o desfazer falhou: ${state.error}")
                    assertTrue(state.done, "o desfazer não concluiu")
                    assertEquals(CONFIRMED, runBlocking { source.reservation(row.id) }.reservation.status)
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        tileOrNull(screen.map, room)?.status != RoomDisplayStatus.OCCUPIED
                    }
                    val after = tile(screen.map, room)
                    println(
                        "LiveStayActionsCheck: desfazer da reserva ${row.code} (UH $room): IN_HOUSE -> CONFIRMED; " +
                            "UH no mapa: ${after.status.label}; hospedadas ${rows.size}, " +
                            "com check-in hoje ${seeds.size}",
                    )
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        screen.detail.view
                            ?.status
                            ?.label != "Hospedada"
                    }
                    waitForIdle()
                    capture("undo-check-in")
                } finally {
                    state.close()
                }
            }
        }

    private fun moveLines(screen: Screen): Int =
        screen.detail.view
            ?.history
            ?.count { it.title == MOVE_TITLE } ?: 0

    private fun newRoomMove(
        screen: Screen,
        source: ReceptionSource,
        id: String,
    ): RoomMoveState {
        val approve: suspend (ApprovableAction<RoomMoveResponse>) -> ApiResponse<RoomMoveResponse>? =
            { screen.approvals.run(it) }
        return RoomMoveState(source, screen.scope, id, approve) {
            screen.panel.refresh()
            screen.detail.reload()
        }
    }

    private fun newUndo(
        screen: Screen,
        source: ReceptionSource,
        id: String,
    ): UndoCheckInState {
        val approve: suspend (ApprovableAction<UndoCheckInResponse>) -> ApiResponse<UndoCheckInResponse>? =
            { screen.approvals.run(it) }
        return UndoCheckInState(source, screen.scope, id, approve) {
            screen.panel.refresh()
            screen.detail.reload()
        }
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        // com o diálogo aberto há duas raízes (janela e diálogo): captura a última
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
                screen.approvals = remember { ApprovalCoordinator(connection::grant, emptyFlow(), screen.scope) }
                ZenyTheme {
                    ReceptionScreen(screen.panel, screen.detail)
                    screen.roomMove?.let { RoomMoveDialog(it) {} }
                    screen.undo?.let { UndoCheckInDialog(it, screen.undoCode) {} }
                    ApprovalHost(screen.approvals)
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
                screen.approvals.close()
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
