package com.zeny.desktop.design.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataque de concorrência e ciclo de vida ao [PdfDocumentState]. Sem dormir; o executor é uma thread só. */
class PdfDocumentStateAtaqueTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val uncaught = ConcurrentLinkedQueue<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught.add(e) })

    @AfterTest
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    /** Dispatcher que avisa cada vez que algo é enfileirado nele. */
    private class Signaling(
        private val delegate: CoroutineDispatcher,
        private val onDispatch: () -> Unit,
    ) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
            onDispatch()
        }
    }

    private fun newState(
        bytes: ByteArray = SamplePdf.threePages(),
        on: CoroutineDispatcher = dispatcher,
    ) = PdfDocumentState(bytes, scope, on)

    private fun awaitRenders() =
        runBlocking {
            scope.coroutineContext.job.children
                .toList()
                .joinAll()
        }

    private fun awaitExecutor() = runBlocking { withContext(dispatcher) {} }

    private fun blockExecutor(): () -> Unit {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        executor.execute {
            started.countDown()
            gate.await()
        }
        started.await()
        return { gate.countDown() }
    }

    private fun openedState(
        bytes: ByteArray = SamplePdf.threePages(),
        on: CoroutineDispatcher = dispatcher,
    ): PdfDocumentState {
        val state = newState(bytes, on)
        assertTrue(runBlocking { state.open() })
        awaitRenders()
        return state
    }

    private fun pdfOf(build: (PDDocument) -> Unit): ByteArray {
        PdfBoxSetup.install()
        PDDocument().use { doc ->
            build(doc)
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun simplePages(
        doc: PDDocument,
        count: Int,
        brokenIndex: Int = -1,
    ) {
        val font = PDType1Font(FontName.HELVETICA)
        repeat(count) { index ->
            // Página "quebrada": a caixa é grande demais para a imagem, e o renderizador lança na hora de alocar.
            val page = PDPage(if (index == brokenIndex) PDRectangle(HUGE_BOX, HUGE_BOX) else PDRectangle.A6)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                if (index == brokenIndex) {
                    content.addRect(1f, 1f, 5f, 5f)
                    content.fill()
                } else {
                    content.beginText()
                    content.setFont(font, 12f)
                    content.newLineAtOffset(20f, 300f)
                    content.showText("Segredo do hospede $index")
                    content.endText()
                }
            }
        }
    }

    @Test
    fun nextNextPreviousBurstRendersOnlyTheLastRequestedPage() {
        val state = openedState()
        val before = state.renderCount
        val release = blockExecutor()
        state.next()
        state.next()
        state.previous()
        assertEquals(1, state.page)
        release()
        awaitRenders()
        assertEquals(1, state.renderedPage)
        assertEquals(before + 1, state.renderCount)
        assertFalse(state.rendering)
    }

    @Test
    fun zoomBurstRendersOnlyTheLastRequestedZoom() {
        val state = openedState()
        val width100 = state.image!!.width
        val before = state.renderCount
        val release = blockExecutor()
        state.zoomIn()
        state.zoomIn()
        state.zoomIn()
        state.zoomOut()
        assertEquals(150, state.zoom)
        release()
        awaitRenders()
        assertEquals(before + 1, state.renderCount)
        val expected = width100 * 3 / 2
        val actual = state.image!!.width
        assertTrue(kotlin.math.abs(expected - actual) <= 2, "largura $actual esperado $expected")
    }

    @Test
    fun noStaleImageAppearsWhileANewerRequestIsPending() {
        val state = openedState()
        val release = blockExecutor()
        state.next()
        state.next()
        assertTrue(state.rendering)
        release()
        awaitRenders()
        // Nenhum resultado da página 1 pode ter sido publicado: só a 0 (antes) e a 2 (depois).
        assertEquals(2, state.renderedPage)
    }

    @Test
    fun everyDocumentTaskIsDispatchedThroughTheConfinedDispatcher() {
        val counting = Signaling(dispatcher) {}
        val state = openedState(on = counting)
        state.next()
        awaitRenders()
        val beforeClose = counting.dispatches.get()
        state.close()
        awaitExecutor()
        assertTrue(beforeClose >= 3, "open, render 0 e render 1 pelo dispatcher; foram $beforeClose")
        assertTrue(counting.dispatches.get() > beforeClose, "close tem de passar pelo dispatcher")
        assertTrue(state.documentClosed)
    }

    @Test
    fun closeWhileOpenIsStillQueuedNeverReopensNorRenders() {
        val queued = CountDownLatch(1)
        val counting = Signaling(dispatcher) { queued.countDown() }
        val state = newState(on = counting)
        val release = blockExecutor()
        var opened: Boolean? = null
        val opener = thread { opened = runBlocking { state.open() } }
        queued.await()
        state.close()
        release()
        opener.join()
        awaitRenders()
        awaitExecutor()
        assertEquals(false, opened)
        assertNull(state.image)
        assertFalse(state.rendering)
        assertEquals(0, state.renderCount)
        assertTrue(state.documentClosed, "o documento carregado depois do close tem de ser fechado")
        assertTrue(uncaught.isEmpty(), "exceções: $uncaught")
    }

    @Test
    fun closeBeforeOpenMakesOpenANoOpThatLeavesNoDocument() {
        val state = newState()
        state.close()
        assertFalse(runBlocking { state.open() })
        awaitRenders()
        awaitExecutor()
        assertNull(state.image)
        assertNull(state.error)
        assertEquals(0, state.pageCount)
        assertEquals(0, state.renderCount)
    }

    @Test
    fun closeFromManyThreadsAtOnceIsSafeAndClosesOnce() {
        val state = openedState()
        val release = blockExecutor()
        state.next()
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads =
            List(8) {
                thread {
                    start.await()
                    runCatching { state.close() }.onFailure { failures.add(it) }
                }
            }
        start.countDown()
        threads.forEach { it.join() }
        release()
        awaitRenders()
        awaitExecutor()
        assertTrue(failures.isEmpty(), "close lançou: $failures")
        assertTrue(state.documentClosed)
        assertNull(state.image)
        assertFalse(state.rendering)
    }

    @Test
    fun closeRacingWithNavigationBurstsAlwaysEndsReleased() {
        repeat(RACE_ROUNDS) {
            val state = openedState()
            val start = CountDownLatch(1)
            val navigator =
                thread {
                    start.await()
                    repeat(BURST) {
                        state.next()
                        state.zoomIn()
                        state.previous()
                        state.zoomOut()
                    }
                }
            val closer =
                thread {
                    start.await()
                    state.close()
                }
            start.countDown()
            navigator.join()
            closer.join()
            awaitRenders()
            awaitExecutor()
            assertNull(state.image, "imagem tem de estar solta depois do close")
            assertFalse(state.rendering)
            assertTrue(state.documentClosed)
        }
        assertTrue(uncaught.isEmpty(), "exceções: $uncaught")
    }

    @Test
    fun callsAfterCloseAreNoOps() {
        val state = openedState()
        state.next()
        awaitRenders()
        val pageAtClose = state.page
        val zoomAtClose = state.zoom
        val rendersAtClose = state.renderCount
        state.close()
        awaitExecutor()
        state.next()
        assertEquals(pageAtClose, state.page, "next depois do close mudou a página")
        state.previous()
        assertEquals(pageAtClose, state.page, "previous depois do close mudou a página")
        state.zoomIn()
        assertEquals(zoomAtClose, state.zoom, "zoomIn depois do close mudou o zoom")
        state.zoomOut()
        assertEquals(zoomAtClose, state.zoom, "zoomOut depois do close mudou o zoom")
        assertFalse(runBlocking { state.open() })
        awaitRenders()
        awaitExecutor()
        assertEquals(rendersAtClose, state.renderCount)
        assertNull(state.image)
        assertFalse(state.rendering)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun brokenMiddlePageShowsErrorAndViewerStaysUsable() {
        val state = openedState(pdfOf { simplePages(it, count = 3, brokenIndex = 1) })
        assertNull(state.error)
        state.next()
        awaitRenders()
        assertEquals("Não foi possível abrir a ficha.", state.error)
        assertFalse(state.rendering)
        state.next()
        awaitRenders()
        assertEquals(2, state.renderedPage, "a página seguinte à quebrada tem de renderizar")
        assertNotNull(state.image)
        assertFalse(state.rendering)
        state.previous()
        awaitRenders()
        state.close()
        awaitExecutor()
        assertTrue(state.documentClosed)
    }

    @Test
    fun errorIsClearedAfterALaterPageRendersFine() {
        val state = openedState(pdfOf { simplePages(it, count = 3, brokenIndex = 1) })
        state.next()
        awaitRenders()
        assertNotNull(state.error)
        state.next()
        awaitRenders()
        assertNotNull(state.image)
        assertNull(state.error, "a página 3 renderizou, o erro da página 2 não pode continuar na tela")
    }

    @Test
    fun brokenFirstPageShowsErrorWithoutImageOrSpinner() {
        val state = newState(pdfOf { simplePages(it, count = 2, brokenIndex = 0) })
        runBlocking { state.open() }
        awaitRenders()
        assertEquals("Não foi possível abrir a ficha.", state.error)
        assertNull(state.image)
        assertFalse(state.rendering)
        state.next()
        awaitRenders()
        assertNotNull(state.image, "o visualizador tem de seguir usável para a página 2")
    }

    @Test
    fun zeroPagesDocumentIsRejectedWithTheError() {
        val state = newState(pdfOf { })
        assertFalse(runBlocking { state.open() })
        awaitRenders()
        assertEquals("Não foi possível abrir a ficha.", state.error)
        assertNull(state.image)
        assertFalse(state.rendering)
        assertEquals(0, state.pageCount)
        state.next()
        state.zoomIn()
        awaitRenders()
        assertEquals(0, state.page)
        state.close()
        awaitExecutor()
        assertTrue(state.documentClosed)
    }

    @Test
    fun hugePageCountClampsNavigationAndRendersOnlyTheLastPage() {
        val state = openedState(pdfOf { simplePages(it, count = HUGE) })
        assertEquals(HUGE, state.pageCount)
        val before = state.renderCount
        val release = blockExecutor()
        repeat(HUGE + 50) { state.next() }
        assertEquals(HUGE - 1, state.page)
        release()
        awaitRenders()
        assertEquals(HUGE - 1, state.renderedPage)
        assertEquals(before + 1, state.renderCount)
        repeat(HUGE + 50) { state.previous() }
        awaitRenders()
        assertEquals(0, state.page)
        assertEquals(0, state.renderedPage)
    }

    @Test
    fun imageIsNullAfterCloseEvenWhenCloseComesRightAfterRender() {
        val state = openedState()
        assertNotNull(state.image)
        state.close()
        assertNull(state.image)
        awaitExecutor()
        assertNull(state.image)
    }

    @Test
    fun toStringAndErrorNeverShowDocumentContent() {
        val bytes = pdfOf { simplePages(it, count = 2) }
        val state = openedState(bytes)
        state.next()
        awaitRenders()
        val broken = newState(ByteArray(64) { 'x'.code.toByte() })
        runBlocking { broken.open() }
        for (text in listOf(state.toString(), broken.toString(), broken.error.orEmpty())) {
            assertFalse(text.contains("Segredo"), text)
            assertFalse(text.contains("hospede"), text)
            assertFalse(text.contains("%PDF"), text)
            assertFalse(text.contains("xxxx"), text)
        }
        assertEquals("PdfDocumentState(pages=2, page=1)", state.toString())
        assertEquals("PdfDocumentState(pages=0, page=0)", broken.toString())
    }

    @Test
    fun navigationBeforeOpenDoesNothing() {
        val state = newState()
        state.next()
        state.zoomIn()
        state.previous()
        awaitRenders()
        assertEquals(0, state.page)
        assertEquals(100, state.zoom)
        assertNull(state.image)
        assertFalse(state.rendering)
    }

    private companion object {
        const val HUGE_BOX = 40000f
        const val RACE_ROUNDS = 15
        const val BURST = 30
        const val HUGE = 1500
    }
}
