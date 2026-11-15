package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.app.traces.TraceFormState
import com.zeny.desktop.app.traces.TraceRow
import com.zeny.desktop.app.traces.TraceTarget
import com.zeny.desktop.app.traces.TracesListState
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.TraceStatusFilter
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val DEPARTMENT = "Governança"
private const val RESOLVED_STATUS = "RESOLVED"
private const val ID_PREFIX_LENGTH = 8

/**
 * Verificação dos recados contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real e pelos estados reais. **Escreve** no servidor (cria, edita e resolve recados), por isso tem
 * opt-in próprio: pulada sem `ZENY_LIVE_TRACES=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e
 * `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Cada recado leva um texto único da rodada e é
 * resolvido no fim, para não acumular recados abertos. Grava `build/live/traces.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_TRACES=1 ./gradlew :app:test --tests '*LiveTracesCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveTracesCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private class Screen(
        val source: com.zeny.desktop.app.traces.TracesSource,
    ) {
        lateinit var panel: DayPanelState
        lateinit var detail: ReservationDetailState
        lateinit var scope: CoroutineScope
        lateinit var traces: ReceptionTraces
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_TRACES") == "1", "sem ZENY_LIVE_TRACES=1: verificação real pulada")
    }

    @Test
    fun createdTraceAppearsInListAndDetail() =
        withScreen { screen ->
            val row = firstInHouse(screen)
            val text = uniqueText("criado")
            val id = create(screen, row, text)
            try {
                val listed = findRow(screen.traces.list, text)
                assertTrue(listed.open, "o recado novo não está aberto")
                assertEquals(DEPARTMENT, listed.department)
                assertTrue(listed.link.contains(row.code), "o vínculo da lista não cita a reserva ${row.code}")

                // a seção do detalhe da reserva (a aba fechada): o texto só pode vir dela
                runOnUiThread { screen.detail.open(row.id) }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.detail.view?.code == row.code }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                    onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                runOnUiThread { screen.detail.close() }
                waitForIdle()

                // a aba "Recados", só com o recado da rodada
                onNodeWithText("Recados").performClick()
                runOnUiThread { screen.traces.list.table.filter = text }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                    onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                capture("traces")
                println(
                    "LiveTracesCheck: recado criado na reserva ${row.code}, setor ${listed.department}, " +
                        "vencimento ${listed.due}; na aba (abertos) e na seção do detalhe",
                )
            } finally {
                resolveQuietly(screen, id)
            }
        }

    @Test
    fun resolvingUpdatesPendingList() =
        withScreen { screen ->
            val row = firstInHouse(screen)
            val text = uniqueText("resolvido")
            val id = create(screen, row, text)
            val list = screen.traces.list
            val before = findRow(list, text)
            assertTrue(before.open)
            runOnUiThread { list.resolve(id) }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !list.busy }
            assertNull(list.error, "resolver falhou: ${list.error?.let { it::class.simpleName }}")
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !list.table.loading }
            assertTrue(list.table.rows.none { it.id == id }, "o recado resolvido continua nos abertos")

            runOnUiThread { list.setStatus(TraceStatusFilter.RESOLVED) }
            val resolved = findRow(list, text)
            assertFalse(resolved.open, "o recado não aparece resolvido")
            assertEquals(RESOLVED_STATUS, runBlocking { screen.source.trace(id) }.trace.status)
            println("LiveTracesCheck: recado ${row.code} resolvido: saiu dos abertos e apareceu em Resolvidos")
        }

    @Test
    @Suppress("LongMethod") // um roteiro só: dois formulários, o primeiro salva, o segundo relê e salva
    fun concurrentEditShowsReloadAndNotice() =
        withScreen { screen ->
            val row = firstInHouse(screen)
            val original = uniqueText("original")
            val id = create(screen, row, original)
            val first = newForm(screen, TraceTarget.Existing(id))
            val second = newForm(screen, TraceTarget.Existing(id))
            try {
                runOnUiThread {
                    first.start()
                    second.start()
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { first.canSave && second.canSave }
                assertEquals(original, second.text)
                val firstText = uniqueText("do-primeiro")
                val secondText = uniqueText("do-segundo")
                runOnUiThread {
                    first.text = firstText
                    first.save()
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { first.saved || first.error != null }
                assertNull(first.error, "o primeiro salvamento falhou: ${first.error}")
                assertTrue(first.saved)

                runOnUiThread {
                    second.text = secondText
                    second.save()
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                    !second.busy && (second.notice != null || second.saved)
                }
                assertFalse(second.saved, "o segundo salvou sem o 412")
                val notice = assertNotNull(second.notice, "o segundo não mostrou o aviso")
                assertEquals("Alguém alterou este recado. Confira e salve de novo.", notice)
                assertEquals(firstText, second.text, "o segundo não releu o recado do primeiro")

                runOnUiThread {
                    second.text = secondText
                    second.save()
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { second.saved || second.error != null }
                assertNull(second.error, "a segunda confirmação falhou: ${second.error}")
                assertTrue(second.saved, "o segundo não salvou na segunda confirmação")
                assertEquals(secondText, runBlocking { screen.source.trace(id) }.trace.text)
                println("LiveTracesCheck: edição concorrente: 412 com aviso e releitura; segunda confirmação salvou")
            } finally {
                first.close()
                second.close()
                resolveQuietly(screen, id)
            }
        }

    private fun uniqueText(kind: String): String =
        "Verificação ao vivo $kind ${UUID.randomUUID().toString().take(ID_PREFIX_LENGTH)}"

    private fun DesktopComposeUiTest.firstInHouse(screen: Screen): ReservationRow =
        screen.panel.tabs
            .getValue(DayList.IN_HOUSE)
            .rows
            .minByOrNull { it.code } ?: fail("sem reserva hospedada na recepção")

    /** Cria o recado da reserva pelo formulário real e devolve o id (achado na lista pelo texto único). */
    private fun DesktopComposeUiTest.create(
        screen: Screen,
        row: ReservationRow,
        text: String,
    ): String {
        val list = screen.traces.list
        val department =
            list.departments.firstOrNull { it.name == DEPARTMENT }
                ?: fail("o setor $DEPARTMENT não está entre os ${list.departments.size} setores legíveis")
        val form = newForm(screen, TraceTarget.ForReservation(row.id, row.code))
        try {
            runOnUiThread { form.start() }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { form.canSave }
            runOnUiThread {
                form.departmentId = department.id
                form.text = text
            }
            runOnUiThread { form.save() }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { form.saved || form.error != null }
            assertNull(form.error, "criar o recado falhou: ${form.error}")
            assertTrue(form.saved, "o recado não foi criado")
        } finally {
            form.close()
        }
        runOnUiThread { list.refresh() }
        return findRow(list, text).id
    }

    private fun DesktopComposeUiTest.findRow(
        list: TracesListState,
        text: String,
    ): TraceRow {
        runOnUiThread { list.refresh() }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            (!list.table.loading && list.table.rows.isNotEmpty()) || list.table.error != null
        }
        var found: TraceRow? = null
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            found = list.table.rows.firstOrNull { it.text == text }
            if (found == null && list.table.hasMore && !list.table.loading) runBlocking { list.table.loadMore() }
            found != null || (!list.table.hasMore && !list.table.loading)
        }
        return found ?: fail(
            "o recado da rodada não está na lista (${list.table.rows.size} linhas, filtro ${list.statusFilter})",
        )
    }

    private fun newForm(
        screen: Screen,
        target: TraceTarget,
    ): TraceFormState {
        val list = screen.traces.list
        return TraceFormState(screen.source, screen.scope, target, list.departments, list.departmentsDenied) {
            list.refresh()
        }
    }

    private fun resolveQuietly(
        screen: Screen,
        id: String,
    ) {
        runCatching { runBlocking { screen.source.resolve(id) } }
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen) -> Unit) =
        withViewer { connection ->
            val receptionSource = connection.receptionSource()
            val tracesSource = checkNotNull(connection.tracesSource()) { "a conexão não tem recados" }
            val screen = Screen(tracesSource)
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.panel = remember { DayPanelState(receptionSource, screen.scope) }
                    screen.detail = remember { ReservationDetailState(receptionSource, screen.scope) }
                    screen.traces =
                        remember {
                            val list = TracesListState(tracesSource, screen.scope)
                            ReceptionTraces(
                                list = list,
                                forReservation = { id -> TracesListState(tracesSource, screen.scope, id) },
                                form = { target ->
                                    TraceFormState(
                                        tracesSource,
                                        screen.scope,
                                        target,
                                        list.departments,
                                        list.departmentsDenied,
                                    ) {}
                                },
                            )
                        }
                    ZenyTheme { ReceptionScreen(screen.panel, screen.detail, traces = screen.traces) }
                }
                val list = screen.traces.list
                try {
                    runOnUiThread { list.start() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        screen.panel.day != null &&
                            screen.panel.tabs.values
                                .all { !it.loading && !it.hasMore }
                    }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        list.canCreate || list.departmentsDenied || list.departmentsError != null
                    }
                    assertTrue(
                        list.canCreate,
                        "a Recepcionista não pode criar recados: ${list.permissions.size} permissões",
                    )
                    block(screen)
                } finally {
                    screen.traces.list.close()
                    screen.detail.close()
                    screen.panel.close()
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
