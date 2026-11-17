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
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
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

// O teto do `limit` do servidor nas listas por cursor é 200 (`PageParams.MAX_LIMIT`, `@Max`); acima, 400.
private const val PAGE_LIMIT = 200
private const val MIN_PAGES = 2
private const val MAX_CAUSES = 20
private const val SMALL_PAGE = 3
private const val IN_HOUSE = "IN_HOUSE"
private const val IN_HOUSE_LABEL = "Hospedada"
private const val G1_HOLDER = "Hóspede Demonstração Um"
private const val G1_CPF_BASE = "202600101"
private const val CPF_FIRST_WEIGHT = 10
private const val CPF_MODULUS = 11
private const val NEED_SEED =
    "pré-condição do ambiente: o seed pós-fechamento não deixou hospedados suficientes no dia operacional"
private const val G12_CPF_BASE = "202600112"
private const val CANCELLED = "CANCELLED"
private const val CANCELLED_FROM_DAYS = 28L
private const val CANCELLED_TO_DAYS = 30L
private const val NEED_DEMO_SEED = "NEED_SEED: rode o demo-seed desta etapa"

// O `externalId` das reservas do seed do Hóspede Doze e a situação que o roteiro (passo 19.2) mostra após o seed.
private val SEED_STATUS_LABELS =
    mapOf(
        "DEMO-PEND-P1" to "Pré-reserva",
        "DEMO-PEND-R1" to "Confirmada",
        "DEMO-FUT-F1" to "Confirmada",
        "DEMO-FUT-F2" to "Confirmada",
        "DEMO-FUT-C1" to "Cancelada",
    )
private const val CONTROL_MARKER = "zeny-live-control-7f3a"

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
        private val oldLevel = root.level
        private val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records.append(record.message).append(' ').append(record.parameters?.joinToString(" ").orEmpty())
                    // A cadeia inteira de causas: a mensagem e o `toString()` de cada uma.
                    generateSequence(record.thrown) { it.cause }.take(MAX_CAUSES).forEach {
                        records
                            .append(' ')
                            .append(it.message)
                            .append(' ')
                            .append(it.toString())
                    }
                    records.append('\n')
                }

                override fun flush() = Unit

                override fun close() = Unit
            }

        fun start() {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            root.level = Level.ALL
            root.addHandler(handler)
        }

        fun stop(): String {
            root.removeHandler(handler)
            root.level = oldLevel
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
            assertTrue(expected.isNotEmpty(), "$NEED_SEED: a lista do dia veio sem hospedados")
            val shown = filteredRows(connection, pageSize = PAGE_LIMIT).rows
            println("LiveReservationSearchCheck: filtro por período e situação: ${shown.size} linhas")
            assertTrue(shown.isNotEmpty(), "a busca por período e situação não achou linha, mas a lista do dia tem")
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
            assertTrue(
                expected.size > SMALL_PAGE,
                "$NEED_SEED: precisa de mais de $SMALL_PAGE (para 2 páginas), a lista do dia tem ${expected.size}",
            )
            val loaded = filteredRows(connection, pageSize = SMALL_PAGE)
            val ids = loaded.rows.map { it.id }
            println("LiveReservationSearchCheck: páginas de $SMALL_PAGE: ${ids.size} linhas em ${loaded.pages} páginas")
            assertTrue(loaded.pages >= MIN_PAGES, "só ${loaded.pages} página carregada: a paginação não foi exercida")
            assertTrue(
                loaded.calls.all { it.limit == SMALL_PAGE },
                "há chamada com limit diferente de $SMALL_PAGE: ${loaded.calls.map { it.limit }}",
            )
            assertNull(loaded.calls.first().cursor, "a 1ª chamada da busca veio com cursor")
            assertTrue(
                loaded.calls.drop(1).all { it.cursor != null },
                "há chamada depois da 1ª sem cursor: a paginação não seguiu o cursor",
            )
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
                    assertTrue(search.table.rows.isNotEmpty(), "busca pelo documento do G1 não achou reservas")
                    assertTrue(search.table.rows.any { it.holder == G1_HOLDER }, "nenhuma reserva do titular G1")
                    assertEquals(masked, search.searchedDocument, "máscara inesperada")
                    val texts = screenTexts()
                    assertTrue(texts.any { it.contains(masked) }, "a tela não mostra $masked")
                    assertTrue(texts.none { it.contains(cpf) }, "há nó com os 11 dígitos do CPF do G1")
                    val file = File("build/live/reservation-search.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                    println("LiveReservationSearchCheck: o documento do G1 achou ${search.table.rows.size} reserva(s)")
                } finally {
                    search.close()
                }
            }
        }

    /**
     * Passo 19.2 do roteiro; **só lê**. Vale **logo depois do seed**: depois da demo (o walk-in do passo 14, a
     * reativação da `DEMO-FUT-C1`, a confirmação da `DEMO-PEND-P1`) as situações mudam e ela reprova de propósito.
     * Linhas a mais do mesmo titular (o walk-in) não reprovam.
     */
    @Test
    fun documentSearchFindsSeedReservationsOfGuestTwelve() =
        withViewer { _, connection ->
            val source = connection.reservationSearchSource()
            val codes = SEED_STATUS_LABELS.keys.associateWith { seedCode(source, it) }
            val cpf = fictitiousCpf(G12_CPF_BASE)
            val masked = "CPF ***${cpf.takeLast(4)}"
            withSearchScreen(connection, PAGE_LIMIT) { search ->
                searchByDocument(search, cpf)
                waitForIdle()
                assertNull(search.table.error, "a busca falhou: ${search.table.error?.userMessage}")
                assertEquals(masked, search.searchedDocument, "máscara inesperada")
                val texts = screenTexts()
                assertTrue(texts.any { it.contains(masked) }, "a tela não mostra $masked")
                assertTrue(texts.none { it.contains(cpf) }, "há nó com os 11 dígitos do CPF do Hóspede Doze")
                val statusByCode = search.table.rows.associate { it.code to it.status }
                SEED_STATUS_LABELS.forEach { (externalId, label) ->
                    val code = codes.getValue(externalId)
                    assertEquals(
                        label,
                        statusByCode[code],
                        "$externalId ($code) na busca por documento: esperava \"$label\", a tela mostra " +
                            "${statusByCode[code]?.let { "\"$it\"" } ?: "nenhuma linha"} " +
                            "(${search.table.rows.size} linha(s) no resultado)",
                    )
                }
                println(
                    "LiveReservationSearchCheck: o documento do Hóspede Doze achou ${search.table.rows.size} " +
                        "reserva(s), com as ${codes.size} do seed nas situações do roteiro",
                )
            }
        }

    /** Passo 25 do roteiro; **só lê**: o filtro "Cancelada", no período d+28 a d+30, acha a `DEMO-FUT-C1`. */
    @Test
    fun cancelledFilterFindsSeedCancelled() =
        withViewer { _, connection ->
            val source = connection.reservationSearchSource()
            val externalId = "DEMO-FUT-C1"
            val seed = seedReservation(source, externalId)
            if (seed.status != CANCELLED) {
                fail("$NEED_DEMO_SEED: a $externalId (${seed.code}) está ${seed.status}, esperava $CANCELLED")
            }
            val cancelledLabel = checkNotNull(SEED_STATUS_LABELS[externalId])
            withSearchScreen(connection, PAGE_LIMIT) { search ->
                runOnUiThread { search.start() }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { search.day != null && !search.table.loading }
                runOnUiThread {
                    val day = checkNotNull(search.day).date.value
                    search.number = ""
                    search.name = ""
                    search.fromText = DateInput.format(BusinessDate(day.plusDays(CANCELLED_FROM_DAYS)))
                    search.toText = DateInput.format(BusinessDate(day.plusDays(CANCELLED_TO_DAYS)))
                    search.statuses = setOf(CANCELLED)
                    search.search()
                }
                waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !search.table.loading }
                waitForIdle()
                assertNull(search.table.error, "a busca falhou: ${search.table.error?.userMessage}")
                assertNull(search.fieldError, "a busca foi barrada no cliente: ${search.fieldError}")
                val rows = search.table.rows
                assertTrue(
                    rows.any { it.code == seed.code },
                    "a ${seed.code} ($externalId) não está no filtro \"$cancelledLabel\" de d+$CANCELLED_FROM_DAYS " +
                        "a d+$CANCELLED_TO_DAYS: ${rows.size} linha(s), códigos ${rows.map { it.code }}",
                )
                val others = rows.filter { it.status != cancelledLabel }
                assertTrue(
                    others.isEmpty(),
                    "há linha que não mostra \"$cancelledLabel\": ${others.map { "${it.code} ${it.status}" }}",
                )
                println(
                    "LiveReservationSearchCheck: o filtro Cancelada achou a ${seed.code} entre ${rows.size} linha(s)",
                )
            }
        }

    @Test
    fun termNotInLogs() =
        withViewer { _, connection ->
            val cpf = fictitiousCpf()
            val source = connection.reservationSearchSource()
            val captured = Captured()
            // Tudo, inclusive o `start()` e o controle, dentro do `runCatching`: o `stop()` abaixo sempre roda
            // e devolve as streams e o nível do JUL raiz.
            val ran =
                runCatching {
                    captured.start()
                    // Controle positivo: uma linha conhecida por cada caminho capturado.
                    Logger.getLogger("zeny.live.control").warning("jul $CONTROL_MARKER")
                    println("stdout $CONTROL_MARKER")
                    System.err.println("stderr $CONTROL_MARKER")
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
                            // O período padrão do `start()` (hoje a +30 dias) pode não alcançar o G1: sem período,
                            // a busca por nome acha o G1 em qualquer data.
                            runOnUiThread {
                                search.fromText = ""
                                search.toText = ""
                                search.name = G1_HOLDER
                                search.search()
                            }
                            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !search.table.loading }
                            assertNull(
                                search.table.error,
                                "a busca por nome falhou: ${search.table.error?.userMessage}",
                            )
                            assertTrue(search.table.rows.isNotEmpty(), "busca por nome do G1 não achou reservas")
                            searchByDocument(search, cpf)
                            waitForIdle()
                        } finally {
                            search.close()
                        }
                    }
                }
            val text = captured.stop()
            ran.getOrThrow()
            for (path in listOf("jul", "stdout", "stderr")) {
                assertTrue(text.contains("$path $CONTROL_MARKER"), "a captura não pegou a linha de controle de $path")
            }
            val encoded = URLEncoder.encode(G1_HOLDER, Charsets.UTF_8)
            val nameForms = listOf(G1_HOLDER, encoded, encoded.replace("+", "%20"))
            val formatted = "${cpf.take(3)}.${cpf.substring(3, 6)}.${cpf.substring(6, 9)}-${cpf.takeLast(2)}"
            val cpfForms = listOf(cpf, formatted)
            assertTrue(
                nameForms.none { text.contains(it, ignoreCase = true) },
                "o nome buscado apareceu em log ou na saída (puro ou codificado para URL)",
            )
            assertTrue(
                cpfForms.none { text.contains(it) },
                "o CPF buscado apareceu em log ou na saída (puro ou formatado)",
            )
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
        // Sem exigir linhas: um resultado vazio termina a espera e o teste falha com a mensagem certa, não no timeout.
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            search.searchedDocument != null && !search.table.loading
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

    /** Abre a tela de Reservas com o estado real, entrega a `search` ao [block] e a fecha no fim. */
    private fun withSearchScreen(
        connection: CoreConnection,
        pageSize: Int,
        block: ComposeUiTest.(ReservationSearchState) -> Unit,
    ) {
        val source = connection.reservationSearchSource()
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            lateinit var search: ReservationSearchState
            setContent {
                val scope = rememberCoroutineScope()
                search = remember { ReservationSearchState(source, scope, pageSize) }
                val detail = remember { ReservationDetailState(connection.receptionSource(), scope) }
                ZenyTheme { ReservationsScreen(search, detail) }
            }
            try {
                block(search)
            } finally {
                search.close()
            }
        }
    }

    /** A reserva do seed de `externalId` dado, achada pela busca; sem ela, ou duplicada, `NEED_SEED`. */
    private fun seedReservation(
        source: ReservationSearchSource,
        externalId: String,
    ): ReservationListItemResponse {
        val found =
            runBlocking {
                val rows = mutableListOf<ReservationListItemResponse>()
                var cursor: String? = null
                do {
                    val page = source.search(ReservationFilter(externalId = externalId), cursor, PAGE_LIMIT)
                    rows += page.items
                    cursor = page.nextCursor
                } while (cursor != null)
                rows
            }
        if (found.size != 1) {
            fail("$NEED_DEMO_SEED: a busca pelo externalId $externalId achou ${found.size} reserva(s), esperava 1")
        }
        return found.single()
    }

    private fun seedCode(
        source: ReservationSearchSource,
        externalId: String,
    ): String = seedReservation(source, externalId).code

    /** O CPF fictício de um hóspede do seed (por padrão o G1): a base mais os dígitos, como `FictitiousDocuments`. */
    private fun fictitiousCpf(baseDigits: String = G1_CPF_BASE): String {
        fun digit(
            base: String,
            firstWeight: Int,
        ): Int {
            val sum = base.mapIndexed { i, c -> (c - '0') * (firstWeight - i) }.sum()
            return (sum % CPF_MODULUS).let { if (it < 2) 0 else CPF_MODULUS - it }
        }
        val d1 = digit(baseDigits, CPF_FIRST_WEIGHT)
        val d2 = digit(baseDigits + d1, CPF_FIRST_WEIGHT + 1)
        return "$baseDigits$d1$d2"
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
    ): Loaded {
        // O `DataTable` pede a página seguinte sozinho, antes da 1ª observação estável: as páginas se contam nas
        // chamadas à fonte, não nos tamanhos da tabela.
        val source = RecordingSource(connection.reservationSearchSource())
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
                    // O `start()` já buscou o período padrão: só contam as chamadas da busca filtrada.
                    source.calls.clear()
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
        val pages = source.calls.filter { it.rows > 0 }
        return Loaded(rows, pages.size, pages)
    }

    private class Loaded(
        val rows: List<ReservationRow>,
        val pages: Int,
        val calls: List<SearchCall>,
    )

    /** Uma chamada a `search`: o cursor e o `limit` recebidos e as linhas devolvidas. */
    private class SearchCall(
        val cursor: String?,
        val limit: Int,
        val rows: Int,
    )

    /** Delega à fonte real e registra cada `search`; o resto passa direto. */
    private class RecordingSource(
        private val inner: ReservationSearchSource,
    ) : ReservationSearchSource by inner {
        val calls: MutableList<SearchCall> = CopyOnWriteArrayList()

        override suspend fun search(
            filter: ReservationFilter,
            cursor: String?,
            limit: Int,
        ): Page<ReservationListItemResponse> =
            inner.search(filter, cursor, limit).also { calls += SearchCall(cursor, limit, it.items.size) }
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
