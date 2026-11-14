package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val PAGE_LIMIT = 200
private val DOCUMENT_PATTERN =
    Regex("""^(CPF|RG|CNH|RNM|Passaporte|Documento estrangeiro|Documento) .*""")
private val ELEVEN_DIGITS = Regex("""\d{11}""")
private val UUID_PATTERN = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

/**
 * Verificação do painel do dia contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real. Pulada sem o opt-in `ZENY_LIVE_RECEPTION=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/reception.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_RECEPTION=1 ./gradlew :app:test --tests '*LiveReceptionCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveReceptionCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_RECEPTION") == "1", "sem ZENY_LIVE_RECEPTION=1: verificação real pulada")
    }

    @Test
    fun panelMatchesServer() =
        withViewer { core, connection ->
            val source = connection.receptionSource()
            val date = runBlocking { core.hotel.businessDay() }.date
            val expected =
                DayList.entries.associateWith { list ->
                    runBlocking {
                        var cursor: String? = null
                        var total = 0
                        do {
                            val page = core.reservations.dayPage(list, date, cursor, PAGE_LIMIT)
                            total += page.items.size
                            cursor = page.nextCursor
                        } while (cursor != null)
                        total
                    }
                }
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                lateinit var panel: DayPanelState
                setContent {
                    val scope = rememberCoroutineScope()
                    panel = remember { DayPanelState(source, scope) }
                    ZenyTheme { ReceptionScreen(panel, ReservationDetailState(source, scope)) }
                }
                try {
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        panel.day != null && panel.tabs.values.all { !it.loading && !it.hasMore }
                    }
                    assertNull(panel.error, "o painel falhou: ${panel.error?.userMessage}")
                    val shown = DayList.entries.associateWith { rowCount(panel, it) }
                    val (arrivals, departures, inHouse) = DayList.entries.map { shown.getValue(it) }
                    println("LiveReceptionCheck: chegadas=$arrivals saídas=$departures hospedados=$inHouse")
                    assertEquals(expected, shown, "as contagens do painel não batem com a leitura direta")
                    assertTrue(shown.getValue(DayList.ARRIVALS) > 0, "sem chegadas hoje")
                    assertTrue(shown.getValue(DayList.IN_HOUSE) > 0, "sem hospedados")
                } finally {
                    panel.close()
                }
            }
        }

    @Test
    fun detailOfInHouseReservation() =
        withViewer { _, connection ->
            val source = connection.receptionSource()
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                lateinit var panel: DayPanelState
                lateinit var detail: ReservationDetailState
                setContent {
                    val scope = rememberCoroutineScope()
                    panel = remember { DayPanelState(source, scope) }
                    detail = remember { ReservationDetailState(source, scope) }
                    ZenyTheme { ReceptionScreen(panel, detail) }
                }
                try {
                    val inHouse = panel.tabs.getValue(DayList.IN_HOUSE)
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { panel.day != null && inHouse.rows.isNotEmpty() }
                    val id = inHouse.rows.first().id
                    runOnUiThread { detail.open(id) }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { detail.view != null || detail.error != null }
                    assertNull(detail.error, "o detalhe falhou: ${detail.error?.userMessage}")
                    waitForIdle()
                    val view = checkNotNull(detail.view)
                    assertEquals("Hospedada", view.status.label)
                    assertTrue(Regex("""^UH \S+""").matches(view.room), "UH inesperada: ${view.room}")
                    assertNull(view.historyError, "histórico falhou")
                    val titles = view.history.map { it.title }
                    val created = titles.indexOf("Reserva criada")
                    val checkIn = titles.indexOf("Check-in")
                    assertTrue(created >= 0 && checkIn > created, "ordem do histórico: $titles")
                    val document = view.document
                    assertTrue(document != null && DOCUMENT_PATTERN.matches(document), "documento fora do padrão")
                    assertTrue(ELEVEN_DIGITS.find(document) == null, "documento sem máscara")
                    val fields = listOf(view.code, view.holder, document, view.period, view.roomType, view.room)
                    val history = view.history.flatMap { listOf(it.at, it.title, it.detail) }
                    val assignments = view.assignments.flatMap { listOf(it.room, it.period, it.reason, it.state) }
                    val texts = fields + view.guests + view.plan + view.total + view.alerts + history + assignments
                    assertTrue(texts.none { UUID_PATTERN.containsMatchIn(it) }, "há UUID na tela")

                    val file = File("build/live/reception.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                } finally {
                    detail.close()
                    panel.close()
                }
            }
        }

    private fun rowCount(
        panel: DayPanelState,
        list: DayList,
    ): Int =
        panel.tabs
            .getValue(list)
            .rows.size

    private fun withViewer(block: (ZenyCore, CoreConnection) -> Unit) {
        val url = ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
        val core = ZenyCore(url, MemorySettings())
        val connection = CoreConnection(core)
        try {
            runBlocking { connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD")) }
            block(core, connection)
            assertIs<SessionState.Active>(connection.state.value, "a sessão da Recepcionista caiu")
        } finally {
            runCatching { runBlocking { connection.logout() } }
            connection.close()
        }
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")
}
