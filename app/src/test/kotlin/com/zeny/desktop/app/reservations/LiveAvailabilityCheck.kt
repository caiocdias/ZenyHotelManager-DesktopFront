package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
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
private const val HEIGHT = 1400
private const val LOAD_TIMEOUT_MILLIS = 60_000L

// A H2 do roadmap: o calendário carrega em até 10 s (o `LOAD_TIMEOUT` da E01 dividido por 6); acima, por semana.
private const val H2_LIMIT_MILLIS = 10_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val WINDOW_DAYS = 30
private const val ARRIVAL_OFFSET_DAYS = 7L
private const val DEPARTURE_OFFSET_DAYS = 9L
private const val TWO_ADULTS = "2"
private const val TOO_MANY_ADULTS = "4"
private val ROOM_TYPE_NAMES = setOf("Standard", "Luxo", "Suíte")

/**
 * Verificação da disponibilidade e da cotação contra um servidor real (o ambiente `zeny-front`), com a Recepcionista,
 * pelo `CoreConnection` real; só lê. Pulada sem o opt-in `ZENY_LIVE_AVAILABILITY=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/availability.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_AVAILABILITY=1 ./gradlew :app:test --tests '*LiveAvailabilityCheck*'
 * --rerun --max-workers=2)`
 *
 * As leituras e comparações ficam em `LiveAvailabilityReaders.kt`, provadas sem rede pela
 * `LiveAvailabilityReadersTest`.
 * As chamadas da tela ao servidor são medidas na fonte (`RecordingSource`), não pelo que a tela mostra.
 */
@OptIn(ExperimentalTestApi::class)
class LiveAvailabilityCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(
            System.getenv("ZENY_LIVE_AVAILABILITY") == "1",
            "sem ZENY_LIVE_AVAILABILITY=1: verificação real pulada",
        )
    }

    @Test
    fun calendarMatchesApi() =
        withViewer { core, connection ->
            val source = RecordingSource(connection.availabilitySource())
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                lateinit var calendar: AvailabilityState
                setContent {
                    val scope = rememberCoroutineScope()
                    calendar = remember { AvailabilityState(source, scope, WINDOW_DAYS) }
                    val quote = remember { QuoteState(source, scope) }
                    ZenyTheme { AvailabilityScreen(calendar, quote) }
                }
                try {
                    val today = runBlocking { core.hotel.businessDay().date }
                    val window = "${today.toApi()}..${BusinessDate(today.value.plusDays(WINDOW_DAYS - 1L)).toApi()}"
                    val started = System.nanoTime()
                    runOnUiThread { calendar.start() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !calendar.loading }
                    val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
                    println("LiveAvailabilityCheck: a carga do calendário (H2) levou $millis ms")
                    assertNull(calendar.error, "a carga do calendário falhou: ${calendar.error}")
                    // A medida na fonte: uma leitura do dia operacional e uma da janela de 30 datas, nada mais.
                    assertEquals(1, source.calls("businessDay").size, "leituras do dia operacional na carga")
                    assertEquals(listOf(window), source.calls("availability").map { it.request }, "janela da carga")
                    assertShowsApi(core, calendar)
                    runOnUiThread { calendar.reload() }
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !calendar.loading }
                    assertNull(calendar.error, "a recarga do calendário falhou: ${calendar.error}")
                    assertEquals(
                        listOf(window, window),
                        source.calls("availability").map { it.request },
                        "o reload() não foi ao servidor com a mesma janela",
                    )
                    assertShowsApi(core, calendar)
                    println("LiveAvailabilityCheck: o calendário bate com a API, na carga e depois do reload()")
                    // O teto vale por último: um estouro não esconde se o calendário bate ou não com a API.
                    assertTrue(
                        millis <= H2_LIMIT_MILLIS,
                        "H2: a carga do calendário levou $millis ms, acima de $H2_LIMIT_MILLIS ms: paginar por semana",
                    )
                } finally {
                    calendar.close()
                }
            }
        }

    @Test
    fun quoteMatchesApi() =
        withViewer { core, connection ->
            val source = RecordingSource(connection.availabilitySource())
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                lateinit var calendar: AvailabilityState
                lateinit var quote: QuoteState
                setContent {
                    val scope = rememberCoroutineScope()
                    calendar = remember { AvailabilityState(source, scope, WINDOW_DAYS) }
                    quote = remember { QuoteState(source, scope) }
                    ZenyTheme { AvailabilityScreen(calendar, quote) }
                }
                try {
                    val (arrival, departure) = stayDates(core)
                    quoteStay(calendar, quote, arrival, departure, TWO_ADULTS)
                    assertNull(quote.error, "a cotação falhou: ${quote.error}")
                    assertEquals(
                        listOf("${arrival.toApi()}..${departure.toApi()} adults=2 children=[]"),
                        source.calls("quote").map { it.request },
                        "o pedido de cotação que a tela fez",
                    )
                    val api = runBlocking { core.reservations.quoteStay(arrival, departure, 2, emptyList(), true) }
                    assertTrue(api.offers.isNotEmpty(), "pré-condição do ambiente: a API não devolveu oferta")
                    assertOffersMatch(quote.offers, api)
                    val texts = quote.offers.indices.map { nodeText("quote-offer-$it") }
                    assertTrue(texts.any { it.contains("Café da manhã") }, "nenhuma oferta mostra Café da manhã")
                    assertNoRawCode(api, emptySet())
                    println("LiveAvailabilityCheck: ${api.offers.size} oferta(s) batem com a API")
                } finally {
                    calendar.close()
                    quote.close()
                }
            }
        }

    @Test
    fun refusalInPortuguese() =
        withViewer { core, connection ->
            val source = RecordingSource(connection.availabilitySource())
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                lateinit var calendar: AvailabilityState
                lateinit var quote: QuoteState
                setContent {
                    val scope = rememberCoroutineScope()
                    calendar = remember { AvailabilityState(source, scope, WINDOW_DAYS) }
                    quote = remember { QuoteState(source, scope) }
                    ZenyTheme { AvailabilityScreen(calendar, quote) }
                }
                try {
                    val (arrival, departure) = stayDates(core)
                    quoteStay(calendar, quote, arrival, departure, TOO_MANY_ADULTS)
                    assertNull(quote.error, "a cotação falhou: ${quote.error}")
                    assertEquals(
                        listOf("${arrival.toApi()}..${departure.toApi()} adults=4 children=[]"),
                        source.calls("quote").map { it.request },
                        "o pedido de cotação que a tela fez",
                    )
                    val api = runBlocking { core.reservations.quoteStay(arrival, departure, 4, emptyList(), true) }
                    assertTrue(api.refused().isNotEmpty(), "$TOO_MANY_ADULTS adultos: a API não recusou nada")
                    assertEquals(api.refused().size, quote.unavailable.size, "o número de recusas não bate com a API")
                    val unavailable = assertRefusalsShown(api, calendar.rows.associate { it.roomTypeId to it.name })
                    for (name in listOf("Standard", "Luxo")) {
                        assertTrue(
                            unavailable.any { it.startsWith("$name ·") && it.contains("recebe até 3 adulto(s)") },
                            "$name não aparece em Indisponíveis com \"recebe até 3 adulto(s)\": $unavailable",
                        )
                    }
                    assertTrue(allTexts().any { it == "Indisponíveis" }, "a tela não mostra Indisponíveis")
                    assertNoRawCode(api, setOf("OCCUPANCY_EXCEEDS_ROOM_TYPE"))
                    val firstCell =
                        "availability-cell-${calendar.rows.first().roomTypeId}-${calendar.dates.first().toApi()}"
                    assertTrue(nodeText(firstCell).isNotEmpty(), "a captura sairia sem o calendário")
                    val file = File("build/live/availability.png")
                    file.parentFile.mkdirs()
                    val image = onRoot().captureToImage().toAwtImage()
                    assertTrue(ImageIO.write(image, "png", file), "não gravou a captura")
                    assertTrue(file.length() > 0, "a captura saiu vazia")
                    println("LiveAvailabilityCheck: ${unavailable.size} recusa(s) com $TOO_MANY_ADULTS adultos")
                } finally {
                    calendar.close()
                    quote.close()
                }
            }
        }

    /** Chegada em d+7 e saída em d+9, com d a data operacional. */
    private fun stayDates(core: ZenyCore): Pair<BusinessDate, BusinessDate> {
        val today = runBlocking { core.hotel.businessDay().date }
        return BusinessDate(today.value.plusDays(ARRIVAL_OFFSET_DAYS)) to
            BusinessDate(today.value.plusDays(DEPARTURE_OFFSET_DAYS))
    }

    /** Carrega o calendário, preenche a estadia e cota; só volta com a cotação respondida. */
    private fun ComposeUiTest.quoteStay(
        calendar: AvailabilityState,
        quote: QuoteState,
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: String,
    ) {
        runOnUiThread { calendar.start() }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { !calendar.loading }
        assertNull(calendar.error, "o calendário falhou antes da cotação: ${calendar.error}")
        assertEquals(ROOM_TYPE_NAMES, calendar.rows.map { it.name }.toSet(), "os tipos de UH do calendário")
        runOnUiThread {
            quote.arrivalText = DateInput.format(arrival)
            quote.departureText = DateInput.format(departure)
            quote.adultsText = adults
            quote.quote()
        }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            quote.quoted || quote.error != null || quote.validation != null
        }
        assertNull(quote.validation, "a estadia foi recusada antes de ir ao servidor: ${quote.validation}")
        waitForIdle()
    }

    /** Cada célula da tela mostra o `available` de uma leitura nova da API na mesma janela. */
    private fun ComposeUiTest.assertShowsApi(
        core: ZenyCore,
        calendar: AvailabilityState,
    ) {
        waitForIdle()
        val first = checkNotNull(calendar.from)
        val last = BusinessDate(first.value.plusDays(WINDOW_DAYS - 1L))
        val api = runBlocking { core.reservations.availability(first, last) }
        assertCalendarShows(calendar, api, WINDOW_DAYS, ROOM_TYPE_NAMES)
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
