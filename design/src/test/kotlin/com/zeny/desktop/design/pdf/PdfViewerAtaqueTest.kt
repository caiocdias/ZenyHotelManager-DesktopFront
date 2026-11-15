package com.zeny.desktop.design.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.awt.print.PrinterException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Testes de ataque ao [PdfViewer]: descarte, lado da impressão, carregando, janela estreita e semântica. */
@OptIn(ExperimentalTestApi::class)
class PdfViewerAtaqueTest {
    private val docExecutor = Executors.newSingleThreadExecutor { Thread(it, "ataque-doc") }
    private val uiExecutor = Executors.newSingleThreadExecutor { Thread(it, "ataque-ui") }
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "ataque-io") }
    private val scope = CoroutineScope(SupervisorJob())
    private val bytes = SamplePdf.threePages()
    private val folder: Path = Files.createTempDirectory("pdf-viewer-ataque")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        docExecutor.shutdownNow()
        uiExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    private fun state(dispatcher: CoroutineDispatcher = docExecutor.asCoroutineDispatcher()) =
        PdfDocumentState(bytes, scope, dispatcher)

    private fun actions(
        chooser: SaveLocationChooser = SaveLocationChooser { folder.resolve(it) },
        printer: PrintDialog = PrintDialog { PrintOutcome.PRINTED },
    ) = PdfActions(
        bytes,
        "ficha.pdf",
        chooser,
        printer,
        uiExecutor.asCoroutineDispatcher(),
        ioExecutor.asCoroutineDispatcher(),
    )

    private fun ComposeUiTest.waitForPage(document: PdfDocumentState) {
        waitUntil(timeoutMillis = TIMEOUT_MS) { document.image != null }
        waitForIdle()
    }

    private fun ComposeUiTest.hasTextNow(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteraction.enabledNow() = runCatching { assertIsEnabled() }.isSuccess

    @Test
    fun leavingCompositionClosesTheDocument() =
        runComposeUiTest {
            val document = state()
            var show by mutableStateOf(true)
            setContent {
                ZenyTheme { if (show) PdfViewer("Ficha de registro", document, actions(), {}) }
            }
            waitForPage(document)
            assertFalse(document.documentClosed)
            show = false
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.documentClosed }
            assertTrue(document.documentClosed)
            onNodeWithTag("pdf-viewer").assertDoesNotExist()
        }

    @Test
    fun cancelledPrintShowsNoStatusAndReenablesButtons() =
        runComposeUiTest {
            val calls = AtomicInteger()
            val document = state()
            setContent {
                ZenyTheme {
                    PdfViewer(
                        "Ficha de registro",
                        document,
                        actions(printer = { calls.incrementAndGet().let { PrintOutcome.CANCELLED } }),
                        {},
                    )
                }
            }
            waitForPage(document)
            onNodeWithTag("pdf-print").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { calls.get() == 1 }
            waitUntil(timeoutMillis = TIMEOUT_MS) { onNodeWithTag("pdf-print").enabledNow() }
            onNodeWithTag("pdf-status").assertDoesNotExist()
            onNodeWithTag("pdf-save").assertIsEnabled()
        }

    @Test
    fun printFailureShowsMessage() =
        runComposeUiTest {
            val document = state()
            setContent {
                ZenyTheme {
                    PdfViewer(
                        "Ficha de registro",
                        document,
                        actions(printer = { throw PrinterException("detalhe interno") }),
                        {},
                    )
                }
            }
            waitForPage(document)
            onNodeWithTag("pdf-print").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { hasTextNow("Não foi possível imprimir.") }
            assertFalse(hasTextNow("detalhe interno"))
        }

    @Test
    fun runningPrintDisablesSaveAndDoubleClickOpensOneDialog() =
        runComposeUiTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val prints = AtomicInteger()
            val saves = AtomicInteger()
            val printer =
                PrintDialog {
                    prints.incrementAndGet()
                    entered.countDown()
                    release.await()
                    PrintOutcome.PRINTED
                }
            val document = state()
            setContent {
                ZenyTheme {
                    PdfViewer(
                        "Ficha de registro",
                        document,
                        actions(chooser = { saves.incrementAndGet().let { null } }, printer = printer),
                        {},
                    )
                }
            }
            waitForPage(document)
            onNodeWithTag("pdf-print").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { entered.count == 0L }
            onNodeWithTag("pdf-print").assertIsNotEnabled()
            onNodeWithTag("pdf-save").assertIsNotEnabled()
            onNodeWithTag("pdf-print").performClick()
            onNodeWithTag("pdf-save").performClick()
            release.countDown()
            waitUntil(timeoutMillis = TIMEOUT_MS) { hasTextNow("Enviado para a impressora.") }
            assertEquals(1, prints.get())
            assertEquals(0, saves.get())
            onNodeWithTag("pdf-save").assertIsEnabled()
        }

    @Test
    fun spinnerShowsWhileFirstPageRenders() =
        runComposeUiTest {
            val release = CountDownLatch(1)
            val delegate = docExecutor.asCoroutineDispatcher()
            val dispatches = AtomicInteger()
            // O 1o despacho é o open (attach); o 2o é a renderização, que seguramos até o fim da asserção.
            val slowRender =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        if (dispatches.incrementAndGet() == 2) {
                            delegate.dispatch(context) {
                                release.await()
                                block.run()
                            }
                        } else {
                            delegate.dispatch(context, block)
                        }
                    }
                }
            val document = state(slowRender)
            setContent { ZenyTheme { PdfViewer("Ficha de registro", document, actions(), {}) } }
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.rendering }
            waitForIdle()
            onNodeWithTag("pdf-loading").assertIsDisplayed()
            onNodeWithTag("pdf-page").assertDoesNotExist()
            release.countDown()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.image != null }
            waitForIdle()
            onNodeWithTag("pdf-loading").assertDoesNotExist()
            onNodeWithTag("pdf-page").assertExists()
        }

    @Test
    fun counterBeforeOpenFinishesShowsPageOneOfZeroKnownMinor() =
        runComposeUiTest {
            val release = CountDownLatch(1)
            val delegate = docExecutor.asCoroutineDispatcher()
            val gated =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = delegate.dispatch(context) {
                        release.await()
                        block.run()
                    }
                }
            val document = state(gated)
            setContent { ZenyTheme { PdfViewer("Ficha de registro", document, actions(), {}) } }
            waitForIdle()
            val tree = onRoot(useUnmergedTree = true).printToString()
            println("CONTADOR ANTES DO OPEN: " + tree.lines().filter { it.contains("Página") })
            release.countDown()
            // Caracterização (minor adiado da Task 6): o brief não proíbe; se o contador passar a esconder o "de 0",
            // este teste deve ser atualizado, não removido.
            assertTrue(tree.contains("Página 1 de 0"), tree)
        }

    @Test
    fun narrowWidthKeepsEveryButtonDisplayedAndClickable() =
        runComposeUiTest {
            val document = state()
            var closed = 0
            setContent {
                ZenyTheme {
                    Box(Modifier.width(NARROW_DP.dp).height(600.dp)) {
                        PdfViewer("Ficha de registro", document, actions(), { closed++ })
                    }
                }
            }
            waitForPage(document)
            val tags =
                listOf("pdf-prev", "pdf-next", "pdf-zoom-out", "pdf-zoom-in", "pdf-save", "pdf-print", "pdf-close")
            tags.forEach { onNodeWithTag(it).assertIsDisplayed() }
            onNodeWithTag("pdf-zoom-in").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.zoom == 125 }
            onNodeWithTag("pdf-close").performClick()
            assertEquals(1, closed)
        }

    @Test
    fun semanticsTreeHoldsNoDocumentContent() =
        runComposeUiTest {
            val document = state()
            setContent { ZenyTheme { PdfViewer("Ficha de registro", document, actions(), {}) } }
            waitForPage(document)
            val tree = onRoot(useUnmergedTree = true).printToString(Int.MAX_VALUE)
            listOf("Hóspede", "000.000", "documento", "página 1", "Helvetica").forEach {
                assertFalse(tree.contains(it), "a árvore de semântica vaza \"$it\":\n$tree")
            }
            assertTrue(tree.contains("Ficha de registro"))
        }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val NARROW_DP = 220
    }
}
