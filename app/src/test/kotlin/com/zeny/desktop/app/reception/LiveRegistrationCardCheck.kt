package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import com.zeny.desktop.design.pdf.SaveLocationChooser
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.text.PDFTextStripper
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
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1400
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val CANCELLED_LABEL = "Cancelada"
private const val OPEN_CARD = "open-registration-card"
private val ELEVEN_DIGITS = Regex("\\d{11}")
private val FORMATTED_CPF = Regex("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")
private val FORMATTED_CNPJ = Regex("\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}")
private val MASKED_DOCUMENT = Regex("\\*{3}\\d{4}")

/**
 * Verificação da ficha de registro contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real e pelos estados reais. Só LÊ do servidor (baixa a ficha). Pulada sem `ZENY_LIVE_CARD=1`; com
 * ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca
 * impressas). O texto da ficha é extraído com o `PDFTextStripper` só aqui, em memória, e nunca vai a log. Grava
 * `build/live/registration-card.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_CARD=1 ./gradlew :app:test --tests '*LiveRegistrationCardCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveRegistrationCardCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private class Screen {
        lateinit var panel: DayPanelState
        lateinit var detail: ReservationDetailState
        lateinit var scope: CoroutineScope

        /** Os bytes da última ficha baixada, vistos só pelo teste. */
        @Volatile var bytes: ByteArray? = null
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_CARD") == "1", "sem ZENY_LIVE_CARD=1: verificação real pulada")
    }

    @Test
    fun cardOfInHouseOpens() =
        withScreen { screen ->
            val row = firstInHouse(screen)
            openCard(screen, row)
            val bytes = assertNotNull(screen.bytes, "a ficha não foi baixada")
            val view = assertNotNull(screen.detail.view, "o detalhe não carregou")

            val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), " ")
            assertTrue(text.contains(row.code), "o texto da ficha não traz o código da reserva ${row.code}")
            assertTrue(row.room.isNotBlank() && text.contains(row.room), "o texto da ficha não traz a UH alocada")
            assertTrue(view.holder.isNotBlank(), "o detalhe não traz o nome do titular")
            assertTrue(text.contains(view.holder, ignoreCase = true), "o texto da ficha não traz o nome do titular")
            assertFalse(ELEVEN_DIGITS.containsMatchIn(text), "o documento aparece sem máscara (11 dígitos seguidos)")
            assertFalse(FORMATTED_CPF.containsMatchIn(text), "um CPF formatado aparece sem máscara")
            // O CNPJ do estabelecimento (cabeçalho) é público; o do hóspede estaria depois do nome dele.
            val guestPart = text.substringAfter(view.holder, missingDelimiterValue = text)
            assertFalse(FORMATTED_CNPJ.containsMatchIn(guestPart), "um CNPJ formatado aparece sem máscara")
            assertTrue(MASKED_DOCUMENT.containsMatchIn(text), "o documento mascarado (***NNNN) não aparece na ficha")
            capture("registration-card")
            println(
                "LiveRegistrationCardCheck: ficha da reserva ${row.code} (UH ${row.room}) abriu: " +
                    "${bytes.size} bytes, ${text.length} caracteres de texto, código, UH e titular presentes, " +
                    "documento mascarado",
            )
        }

    @Test
    fun cancelledReservationHasNoButton() =
        withScreen { screen ->
            val row =
                screen.panel.tabs.values
                    .flatMap { it.rows }
                    .firstOrNull { it.status == CANCELLED_LABEL }
            if (row == null) {
                println("LiveRegistrationCardCheck: sem reserva cancelada nas listas do dia; caso cancelada pulado")
                return@withScreen
            }
            runOnUiThread { screen.detail.open(row.id) }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.detail.view?.code == row.code }
            waitForIdle()
            assertTrue(onAllNodesWithTag(OPEN_CARD).fetchSemanticsNodes().isEmpty(), "reserva cancelada com o botão")
            println("LiveRegistrationCardCheck: a reserva cancelada ${row.code} não mostra o botão da ficha")
        }

    @Test
    fun pdfBoxSetupIsActive() =
        withScreen { screen ->
            openCard(screen, firstInHouse(screen))
            assertEquals(
                "BundledFontMapper",
                FontMappers.instance().javaClass.simpleName,
                "o FontMapper não é o próprio",
            )
            assertFalse(ImageIO.getUseCache(), "o cache do ImageIO está ligado")
            println("LiveRegistrationCardCheck: FontMapper próprio e cache do ImageIO desligado depois da ficha real")
        }

    private fun DesktopComposeUiTest.firstInHouse(screen: Screen): ReservationRow =
        screen.panel.tabs
            .getValue(DayList.IN_HOUSE)
            .rows
            .minByOrNull { it.code } ?: fail("sem reserva hospedada na recepção")

    /** Abre o detalhe, clica em "Ficha de registro" e espera a página do visualizador. */
    private fun DesktopComposeUiTest.openCard(
        screen: Screen,
        row: ReservationRow,
    ) {
        runOnUiThread { screen.detail.open(row.id) }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { screen.detail.view?.code == row.code }
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            onAllNodesWithTag(OPEN_CARD).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(OPEN_CARD).performClick()
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
            onAllNodesWithTag("pdf-page").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithTag("registration-card-error").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithTag("registration-card-error").fetchSemanticsNodes().isEmpty(),
            "a ficha mostrou erro em vez do visualizador",
        )
        waitForIdle()
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onNodeWithTag("registration-card-dialog").captureToImage().toAwtImage(), "png", file)
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen) -> Unit) =
        withViewer { connection ->
            val receptionSource = connection.receptionSource()
            val real = checkNotNull(connection.registrationCards()) { "a conexão não tem fichas" }
            val screen = Screen()

            @Suppress("InjectDispatcher") // as ações (salvar, imprimir) ficam inertes aqui; nada roda em outra thread
            val inline = Dispatchers.Unconfined
            val cards = RegistrationCards { id -> real.registrationCard(id).also { screen.bytes = it.bytes } }
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.panel = remember { DayPanelState(receptionSource, screen.scope) }
                    screen.detail = remember { ReservationDetailState(receptionSource, screen.scope) }
                    ZenyTheme {
                        ReceptionScreen(
                            screen.panel,
                            screen.detail,
                            registrationCard = { id, code ->
                                RegistrationCardState(
                                    source = cards,
                                    scope = screen.scope,
                                    reservationId = id,
                                    reservationCode = code,
                                    newDocument = { bytes, scope -> PdfDocumentState(bytes, scope) },
                                    newActions = { bytes, name ->
                                        PdfActions(
                                            bytes,
                                            name,
                                            SaveLocationChooser { null },
                                            { PrintOutcome.CANCELLED },
                                            inline,
                                            inline,
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
                try {
                    waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) {
                        screen.panel.day != null &&
                            screen.panel.tabs.values
                                .all { !it.loading && !it.hasMore }
                    }
                    block(screen)
                } finally {
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
