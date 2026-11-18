package com.zeny.desktop.app.cashier

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.app.shell.ShellState
import com.zeny.desktop.app.shell.ShiftIndicator
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1280
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val ID_PREFIX_LENGTH = 8
private const val OPENING_FLOAT = "100,00"

/**
 * Verificação do turno de caixa contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real e pelos estados reais (`ShellState` e `CashierState`). Abre o turno da Recepcionista (ou
 * reaproveita o aberto) e confere o indicador do topo; a reabertura por uma tela que ainda não viu o turno mostra a
 * mensagem de `CASHIER_SHIFT_ALREADY_OPEN`; uma sangria e um suprimento, cada um repetido com a mesma chave depois de
 * uma resposta perdida, não duplicam; o fechamento com o dinheiro declarado zero mostra o relatório com a diferença
 * em BRL. Grava `build/live/cashier.png`.
 *
 * **Escreve na demo e não desfaz:** registra os dois movimentos e **fecha o turno da Recepcionista**, que a H2 e a
 * E05 esperam aberto (abra outro depois, pela tela ou pelo seed). Por isso tem opt-in próprio: pulada sem
 * `ZENY_LIVE_CASHIER=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só
 * do ambiente, nunca impressas). Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_CASHIER=1 ./gradlew :app:test --tests '*LiveCashierCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveCashierCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /**
     * A porta real com dois desvios de teste: a primeira leitura do turno corrente pode fingir que não há turno (uma
     * tela que ainda não viu o turno aberto), e o próximo movimento pode chegar ao servidor e perder a resposta.
     */
    private class ProbeSource(
        private val real: CashierSource,
    ) : CashierSource by real {
        var hideNextShift = false
        var loseNextMovementAnswer = false

        override suspend fun currentShift(): CashierShiftResponse? {
            val shift = real.currentShift()
            if (!hideNextShift) return shift
            hideNextShift = false
            return null
        }

        override suspend fun recordMovement(
            shiftId: String,
            type: CashMovementType,
            amount: Money,
            reason: String,
            intent: Intent,
        ): CashMovementResponse {
            val recorded = real.recordMovement(shiftId, type, amount, reason, intent)
            if (!loseNextMovementAnswer) return recorded
            loseNextMovementAnswer = false
            throw ApiError.Network(IOException("resposta perdida pela verificação"))
        }
    }

    private class Screen(
        val source: ProbeSource,
    ) {
        lateinit var scope: CoroutineScope
        lateinit var shell: ShellState
        var shown: CashierState? = null
        val cashier: CashierState get() = checkNotNull(shown) { "a área de caixa não foi montada" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_CASHIER") == "1", "sem ZENY_LIVE_CASHIER=1: verificação real pulada")
    }

    @Test
    fun shiftOpensMovesAndClosesWithDifference() =
        withScreen { screen ->
            val number = openOrReuse(screen)
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                (screen.shell.shift as? ShiftIndicator.Open)?.number == number
            }
            println("LiveCashierCheck: turno $number aberto; o topo mostra o turno")

            reopenShowsAlreadyOpen(screen)
            val tag = UUID.randomUUID().toString().take(ID_PREFIX_LENGTH)
            recordTwice(screen, CashMovementType.WITHDRAWAL, "10,00", "Verificação ao vivo sangria $tag")
            recordTwice(screen, CashMovementType.SUPPLY, "20,00", "Verificação ao vivo suprimento $tag")
            val shift = assertNotNull(screen.cashier.shift)
            assertEquals(1, shift.movements.count { it.reason.endsWith("sangria $tag") }, "a sangria duplicou")
            assertEquals(1, shift.movements.count { it.reason.endsWith("suprimento $tag") }, "o suprimento duplicou")
            println("LiveCashierCheck: sangria e suprimento repetidos com a mesma chave: um movimento de cada")

            closeWithDifference(screen)
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.shell.shift == ShiftIndicator.None }
            capture("cashier")
        }

    /** O turno corrente da Recepcionista, ou um aberto agora no primeiro ponto ativo com um fundo conhecido. */
    private fun DesktopComposeUiTest.openOrReuse(screen: Screen): String {
        val cashier = screen.cashier
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { cashier.shift != null || cashier.showsOpenForm }
        cashier.shift?.let { return it.number }
        val point = cashier.cashPoints.firstOrNull() ?: fail("sem ponto de caixa ativo para abrir o turno")
        runOnUiThread {
            cashier.cashPointId = point.id
            cashier.openingFloat = OPENING_FLOAT
            cashier.open()
        }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            !cashier.busy && (cashier.shift != null || cashier.message != null)
        }
        return assertNotNull(cashier.shift, "abrir o turno falhou: ${cashier.message}").number
    }

    /** Uma segunda tela, que ainda não viu o turno, tenta abrir outro: a mensagem e a releitura mostram o aberto. */
    private fun DesktopComposeUiTest.reopenShowsAlreadyOpen(screen: Screen) {
        screen.source.hideNextShift = true
        val other = CashierState(screen.source, screen.scope, screen.shell.permissions.orEmpty()) {}
        try {
            runOnUiThread { other.start() }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { other.showsOpenForm }
            val point = other.cashPoints.firstOrNull() ?: fail("sem ponto de caixa ativo")
            runOnUiThread {
                other.cashPointId = point.id
                other.openingFloat = OPENING_FLOAT
                other.open()
            }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !other.busy && other.message != null }
            assertEquals("Já existe um turno de caixa aberto.", other.message)
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { other.shift != null }
            assertEquals(screen.cashier.shift?.number, other.shift?.number, "a releitura não mostrou o turno aberto")
            println("LiveCashierCheck: reabrir mostrou \"${other.message}\" e releu o turno aberto")
        } finally {
            other.close()
        }
    }

    /** O movimento chega ao servidor e a resposta se perde; a repetição leva a mesma chave e o turno é relido. */
    private fun DesktopComposeUiTest.recordTwice(
        screen: Screen,
        type: CashMovementType,
        amount: String,
        reason: String,
    ) {
        val cashier = screen.cashier
        screen.source.loseNextMovementAnswer = true
        runOnUiThread { cashier.startMovement(type) }
        val form = assertNotNull(cashier.movement, "o diálogo de ${type.name} não abriu")
        runOnUiThread {
            form.amount = amount
            form.reason = reason
            form.confirm()
        }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !form.busy && form.message != null }
        assertTrue(form.unconfirmed, "a resposta perdida não deixou o movimento sem confirmação: ${form.message}")
        runOnUiThread { form.confirm() }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { cashier.movement == null || (!form.busy && !form.unconfirmed) }
        assertNull(cashier.movement, "a repetição com a mesma chave falhou: ${form.message}")
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            !cashier.loading && cashier.shift?.movements?.any { it.reason == reason } == true
        }
    }

    /** Fecha com o dinheiro declarado zero (o turno tem ao menos o suprimento líquido): o relatório tem diferença. */
    private fun DesktopComposeUiTest.closeWithDifference(screen: Screen) {
        val cashier = screen.cashier
        val number = assertNotNull(cashier.shift).number
        runOnUiThread { cashier.startClose() }
        val form = assertNotNull(cashier.closing, "o fechamento não abriu")
        runOnUiThread {
            form.setCount(PaymentMethod.CASH, "0,00")
            form.confirm()
        }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            cashier.report != null || (!form.busy && form.message != null)
        }
        val report = assertNotNull(cashier.report, "o fechamento falhou: ${form.message}")
        assertEquals(number, report.number)
        assertEquals(true, report.hasDifference, "o fechamento não teve diferença")
        val different = report.counts.filter { it.difference != "R$ 0,00" }
        assertTrue(different.isNotEmpty(), "nenhuma forma com diferença no relatório")
        different.forEach { line ->
            assertTrue(line.difference.contains("R$ "), "a diferença de ${line.method} não está em BRL")
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                onAllNodesWithText("Diferença: ${line.difference}").fetchSemanticsNodes().isNotEmpty()
            }
        }
        println(
            "LiveCashierCheck: turno $number fechado com diferença; " +
                different.joinToString { "${it.method} ${it.difference}" },
        )
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen) -> Unit) =
        withViewer { connection ->
            val source = checkNotNull(connection.cashierSource()) { "a conexão não tem caixa" }
            val screen = Screen(ProbeSource(source))
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.shell = remember { ShellState(connection, devMode = false, scope = screen.scope) }
                    ZenyTheme {
                        val permissions = screen.shell.permissions
                        if (permissions != null) {
                            screen.shown =
                                remember(permissions) {
                                    CashierState(screen.source, screen.scope, permissions, screen.shell::refreshShift)
                                        .also { it.start() }
                                }
                            CashierScreen(screen.cashier)
                        }
                    }
                }
                try {
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.shell.permissions != null }
                    waitForIdle()
                    assertTrue(screen.cashier.canOperate, "a Recepcionista não opera o caixa")
                    block(screen)
                } finally {
                    screen.shown?.close()
                    screen.shell.close()
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
