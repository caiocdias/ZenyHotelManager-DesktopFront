package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.reception.ReservationRow
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
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
private const val PAGE_LIMIT = 200
private const val SMALL_PAGE = 3
private const val IN_HOUSE = "IN_HOUSE"
private const val IN_HOUSE_LABEL = "Hospedada"
private const val G1_HOLDER = "Hóspede Demonstração Um"
private const val G1_CPF_BASE = "202600101"
private const val CPF_FIRST_WEIGHT = 10
private const val CPF_MODULUS = 11
private val ELEVEN_DIGITS = Regex("""\d{11}""")

/**
 * Verificação da busca de reservas contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real; só lê. Pulada sem o opt-in `ZENY_LIVE_SEARCH=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/reservation-search.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_SEARCH=1 ./gradlew :app:test --tests '*LiveReservationSearchCheck*'
 * --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveReservationSearchCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** Guarda o que o JUL raiz, `System.out` e `System.err` recebem, para procurar dado pessoal depois. */
    private class Captured {
        private val out = ByteArrayOutputStream()
        private val err = ByteArrayOutputStream()
        private val records = StringBuffer()
        private val root = Logger.getLogger("")
        private val oldOut = System.out
        private val oldErr = System.err
        private val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records.append(record.message).append(' ').append(record.parameters?.joinToString(" ").orEmpty())
                    record.thrown?.let { records.append(' ').append(it.toString()) }
                    records.append('\n')
                }

                override fun flush() = Unit

                override fun close() = Unit
            }

        fun start() {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            root.addHandler(handler)
        }

        fun stop(): String {
            root.removeHandler(handler)
            System.setOut(oldOut)
            System.setErr(oldErr)
            return out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8) + records
        }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_SEARCH") == "1", "sem ZENY_LIVE_SEARCH=1: verificação real pulada")
    }

    @Test
    fun periodAndStatusFilter() =
        withViewer { core, connection ->
            val expected = inHouseCodes(core)
            val shown = filteredRows(connection, pageSize = PAGE_LIMIT)
            println("LiveReservationSearchCheck: filtro por período e situação: ${shown.size} linhas")
            assertTrue(shown.isNotEmpty(), "sem hospedados no período")
            assertTrue(shown.all { it.status == IN_HOUSE_LABEL }, "há linha que não está Hospedada")
            assertEquals(
                expected.toSet(),
                shown.map { it.code }.toSet(),
                "os códigos não batem com os hospedados do dia",
            )
        }

    @Test
    fun pagesDoNotRepeat() =
        withViewer { core, connection ->
            val expected = inHouseCodes(core)
            val ids = filteredRows(connection, pageSize = SMALL_PAGE).map { it.id }
            println("LiveReservationSearchCheck: páginas de $SMALL_PAGE: ${ids.size} linhas")
            assertEquals(ids.size, ids.toSet().size, "há reserva repetida entre as páginas")
            assertEquals(expected.size, ids.size, "o total não bate com os hospedados do dia")
        }

    @Test
    fun documentSearchFindsHolder() =
        withViewer { _, connection ->
            val cpf = fictitiousCpf()
            val masked = "CPF ***${cpf.takeLast(4)}"
            val source = connection.reservationSearchSource()
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                lateinit var search: ReservationSearchState
                setContent {
                    val scope = rememberCoroutineScope()
                    search = remember { ReservationSearchState(source, scope) }
                    val detail = remember { ReservationDetailState(connection.receptionSource(), scope) }
                    ZenyTheme { ReservationsScreen(search, detail) }
                }
                try {
                    searchByDocument(search, cpf)
                    waitForIdle()
                    assertNull(search.table.error, "a busca falhou: ${search.table.error?.userMessage}")
                    assertTrue(search.table.rows.any { it.holder == G1_HOLDER }, "nenhuma reserva do titular G1")
                    assertEquals(masked, search.searchedDocument, "máscara inesperada")
                    val texts = screenTexts()
                    assertTrue(texts.any { it.contains(masked) }, "a tela não mostra $masked")
                    assertTrue(texts.none { ELEVEN_DIGITS.containsMatchIn(it) }, "há nó com 11 dígitos")
                    val file = File("build/live/reservation-search.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                    println("LiveReservationSearchCheck: o documento do G1 achou ${search.table.rows.size} reserva(s)")
                } finally {
                    search.close()
                }
            }
        }

    @Test
    fun termNotInLogs() =
        withViewer { _, connection ->
            val cpf = fictitiousCpf()
            val source = connection.reservationSearchSource()
            val captured = Captured()
            captured.start()
            val ran =
                runCatching {
                    runDesktopComposeUiTest(WIDTH, HEIGHT) {
                        lateinit var search: ReservationSearchState
                        setContent {
                            val scope = rememberCoroutineScope()
                            search = remember { ReservationSearchState(source, scope) }
                            ZenyTheme { ReservationsScreen(search, null) }
                        }
                        try {
                            runOnUiThread { search.start() }
                            waitUntil(
                                timeoutMillis = LOAD_TIMEOUT_MILLIS,
                            ) { search.day != null && !search.table.loading }
                            runOnUiThread {
                                search.name = G1_HOLDER
                                search.search()
                            }
                            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                                !search.table.loading && search.table.rows.isNotEmpty()
                            }
                            searchByDocument(search, cpf)
                            waitForIdle()
                        } finally {
                            search.close()
                        }
                    }
                }
            val text = captured.stop()
            ran.getOrThrow()
            assertFalse(text.contains(cpf), "o CPF buscado apareceu em log ou na saída")
            assertFalse(text.contains(G1_HOLDER, ignoreCase = true), "o nome buscado apareceu em log ou na saída")
            println("LiveReservationSearchCheck: nem o nome nem o CPF buscados aparecem no JUL, em stdout ou em stderr")
        }

    private fun ComposeUiTest.searchByDocument(
        search: ReservationSearchState,
        cpf: String,
    ) {
        runOnUiThread { search.start() }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { search.day != null && !search.table.loading }
        runOnUiThread {
            search.documentType = "CPF"
            search.documentNumber = cpf
            search.searchByDocument()
        }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            search.searchedDocument != null && !search.table.loading && search.table.rows.isNotEmpty()
        }
    }

    private fun ComposeUiTest.screenTexts(): List<String> {
        fun texts(node: SemanticsNode): List<String> =
            (
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text } +
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
            ) +
                node.children.flatMap(::texts)
        return texts(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    }

    /** O CPF fictício do G1: a base mais os dois dígitos verificadores, a mesma regra de `FictitiousDocuments`. */
    private fun fictitiousCpf(): String {
        fun digit(
            base: String,
            firstWeight: Int,
        ): Int {
            val sum = base.mapIndexed { i, c -> (c - '0') * (firstWeight - i) }.sum()
            return (sum % CPF_MODULUS).let { if (it < 2) 0 else CPF_MODULUS - it }
        }
        val d1 = digit(G1_CPF_BASE, CPF_FIRST_WEIGHT)
        val d2 = digit(G1_CPF_BASE + d1, CPF_FIRST_WEIGHT + 1)
        return "$G1_CPF_BASE$d1$d2"
    }

    /** Os códigos dos hospedados do dia sem a saída vencida: o período não os toca depois da virada das 03:00. */
    private fun inHouseCodes(core: ZenyCore): List<String> =
        runBlocking {
            val date = core.hotel.businessDay().date
            var cursor: String? = null
            val codes = mutableListOf<String>()
            do {
                val page = core.reservations.dayPage(DayList.IN_HOUSE, date, cursor, PAGE_LIMIT)
                codes += page.items.filter { !it.departureOverdue }.map { it.code }
                cursor = page.nextCursor
            } while (cursor != null)
            codes
        }

    /** Busca `IN_HOUSE` no período da data operacional (de e até) e carrega todas as páginas. */
    private fun filteredRows(
        connection: CoreConnection,
        pageSize: Int,
    ): List<ReservationRow> {
        val source = connection.reservationSearchSource()
        var rows = emptyList<ReservationRow>()
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            lateinit var search: ReservationSearchState
            lateinit var scope: CoroutineScope
            setContent {
                scope = rememberCoroutineScope()
                search = remember { ReservationSearchState(source, scope, pageSize) }
                ZenyTheme { ReservationsScreen(search, null) }
            }
            try {
                runOnUiThread { search.start() }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { search.day != null && !search.table.loading }
                runOnUiThread {
                    val day = DateInput.format(checkNotNull(search.day).date)
                    search.fromText = day
                    search.toText = day
                    search.statuses = setOf(IN_HOUSE)
                    search.search()
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                    when {
                        search.table.error != null -> {
                            true
                        }

                        search.table.loading -> {
                            false
                        }

                        !search.table.hasMore -> {
                            true
                        }

                        else -> {
                            scope.launch { search.table.loadMore() }
                            false
                        }
                    }
                }
                assertNull(search.table.error, "a busca falhou: ${search.table.error?.userMessage}")
                rows = search.table.rows
            } finally {
                search.close()
            }
        }
        return rows
    }

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
