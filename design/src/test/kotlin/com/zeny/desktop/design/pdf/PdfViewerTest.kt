package com.zeny.desktop.design.pdf

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PdfViewerTest {
    private val docExecutor = Executors.newSingleThreadExecutor { Thread(it, "viewer-doc") }
    private val uiExecutor = Executors.newSingleThreadExecutor { Thread(it, "viewer-ui") }
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "viewer-io") }
    private val scope = CoroutineScope(SupervisorJob())
    private val bytes = SamplePdf.threePages()
    private val folder: Path = Files.createTempDirectory("pdf-viewer-test")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        docExecutor.shutdownNow()
        uiExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    private fun state() = PdfDocumentState(bytes, scope, docExecutor.asCoroutineDispatcher())

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

    private fun ComposeUiTest.show(
        document: PdfDocumentState,
        actions: PdfActions = actions(),
        onClose: () -> Unit = {},
    ) {
        setContent { ZenyTheme { PdfViewer("Ficha de registro", document, actions, onClose) } }
        waitUntil(timeoutMillis = TIMEOUT_MS) { document.image != null }
        waitForIdle()
    }

    @Test
    fun showsFirstPageAndCounter() =
        runComposeUiTest {
            show(state())
            onNodeWithTag("pdf-viewer").assertIsDisplayed()
            onNodeWithText("Ficha de registro").assertIsDisplayed()
            onNodeWithText("Página 1 de ${SamplePdf.PAGE_COUNT}").assertIsDisplayed()
            onNodeWithText("100%").assertIsDisplayed()
            onNodeWithTag("pdf-page").assertExists()
        }

    @Test
    fun navigationButtonsRespectBounds() =
        runComposeUiTest {
            val document = state()
            show(document)
            onNodeWithTag("pdf-prev").assertIsNotEnabled()
            onNodeWithTag("pdf-next").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.page == 1 && document.image != null }
            onNodeWithText("Página 2 de ${SamplePdf.PAGE_COUNT}").assertExists()
            onNodeWithTag("pdf-prev").assertIsEnabled()
            onNodeWithTag("pdf-next").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.page == 2 && document.image != null }
            onNodeWithTag("pdf-next").assertIsNotEnabled()
            onNodeWithTag("pdf-prev").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.page == 1 }
            onNodeWithText("Página 2 de ${SamplePdf.PAGE_COUNT}").assertExists()
        }

    @Test
    fun zoomButtons() =
        runComposeUiTest {
            val document = state()
            show(document)
            onNodeWithTag("pdf-zoom-in").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.zoom == 125 }
            onNodeWithText("125%").assertExists()
            onNodeWithTag("pdf-zoom-out").performClick()
            onNodeWithTag("pdf-zoom-out").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { document.zoom == 75 }
            onNodeWithText("75%").assertExists()
        }

    @Test
    fun saveShowsStatus() =
        runComposeUiTest {
            show(state())
            onNodeWithTag("pdf-save").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { onAllStatus("Arquivo salvo.") }
            onNodeWithTag("pdf-status").assertIsDisplayed()
            assertEquals(1, Files.list(folder).use { it.count() })
        }

    @Test
    fun saveFailureShowsMessage() =
        runComposeUiTest {
            show(state(), actions(chooser = { folder.resolve("nao/existe/ficha.pdf") }))
            onNodeWithTag("pdf-save").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { onAllStatus("Não foi possível salvar o arquivo.") }
        }

    @Test
    fun cancelledSaveShowsNothing() =
        runComposeUiTest {
            val calls = AtomicInteger()
            show(state(), actions(chooser = { calls.incrementAndGet().let { null } }))
            onNodeWithTag("pdf-save").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { calls.get() == 1 }
            waitUntil(timeoutMillis = TIMEOUT_MS) { onNodeWithTag("pdf-save").isEnabledNow() }
            onNodeWithTag("pdf-status").assertDoesNotExist()
        }

    @Test
    fun printShowsStatus() =
        runComposeUiTest {
            show(state())
            onNodeWithTag("pdf-print").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { onAllStatus("Enviado para a impressora.") }
        }

    @Test
    fun actionsDisabledWhileRunning() =
        runComposeUiTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val chooser =
                SaveLocationChooser {
                    calls.incrementAndGet()
                    entered.countDown()
                    release.await()
                    folder.resolve(it)
                }
            show(state(), actions(chooser = chooser))
            onNodeWithTag("pdf-save").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { entered.count == 0L }
            onNodeWithTag("pdf-save").assertIsNotEnabled()
            onNodeWithTag("pdf-print").assertIsNotEnabled()
            release.countDown()
            waitUntil(timeoutMillis = TIMEOUT_MS) { onAllStatus("Arquivo salvo.") }
            onNodeWithTag("pdf-save").assertIsEnabled()
            onNodeWithTag("pdf-print").assertIsEnabled()
            assertEquals(1, calls.get())
        }

    @Test
    fun closeCallsOnClose() =
        runComposeUiTest {
            var closed = 0
            show(state(), onClose = { closed++ })
            onNodeWithTag("pdf-close").performClick()
            assertEquals(1, closed)
        }

    @Test
    fun documentErrorReplacesThePage() =
        runComposeUiTest {
            val broken = PdfDocumentState(byteArrayOf(1, 2, 3), scope, docExecutor.asCoroutineDispatcher())
            setContent {
                ZenyTheme { PdfViewer("Ficha de registro", broken, actions(), {}) }
            }
            waitUntil(timeoutMillis = TIMEOUT_MS) { broken.error != null }
            onNodeWithText("Não foi possível abrir a ficha.").assertIsDisplayed()
            onNodeWithTag("pdf-page").assertDoesNotExist()
        }

    private fun ComposeUiTest.onAllStatus(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isEnabledNow() =
        runCatching { assertIsEnabled() }.isSuccess

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
