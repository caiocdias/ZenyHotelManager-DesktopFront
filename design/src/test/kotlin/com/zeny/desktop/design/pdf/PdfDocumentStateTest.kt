package com.zeny.desktop.design.pdf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfDocumentStateTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun newState(bytes: ByteArray = SamplePdf.threePages()) = PdfDocumentState(bytes, scope, dispatcher)

    /** Espera as renderizações lançadas até agora, sem dormir. */
    private fun awaitRenders() =
        runBlocking {
            scope.coroutineContext.job.children
                .toList()
                .joinAll()
        }

    /** Espera tudo o que já está na fila do executor confinado (inclusive o fechamento). */
    private fun awaitExecutor() = runBlocking { withContext(dispatcher) {} }

    /** Segura o executor confinado até [release]. */
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

    private fun assertWidthNear(
        expected: Int,
        actual: Int,
    ) = assertTrue(kotlin.math.abs(expected - actual) <= 2, "largura $actual, esperado perto de $expected")

    private fun openedState(): PdfDocumentState {
        val state = newState()
        assertTrue(runBlocking { state.open() })
        awaitRenders()
        return state
    }

    @Test
    fun opensAndRendersFirstPage() {
        val state = newState()
        assertTrue(runBlocking { state.open() })
        awaitRenders()
        assertEquals(SamplePdf.PAGE_COUNT, state.pageCount)
        assertEquals(0, state.page)
        assertEquals(100, state.zoom)
        assertNotNull(state.image)
        assertFalse(state.rendering)
        assertNull(state.error)
    }

    @Test
    fun navigatesWithinBounds() {
        val state = openedState()
        state.previous()
        assertEquals(0, state.page)
        repeat(SamplePdf.PAGE_COUNT + 2) { state.next() }
        awaitRenders()
        assertEquals(SamplePdf.PAGE_COUNT - 1, state.page)
        assertEquals(SamplePdf.PAGE_COUNT - 1, state.renderedPage)
        state.previous()
        awaitRenders()
        assertEquals(SamplePdf.PAGE_COUNT - 2, state.page)
        assertEquals(SamplePdf.PAGE_COUNT - 2, state.renderedPage)
    }

    @Test
    fun zoomStepsAndLimits() {
        val state = openedState()
        val widthAt100 = state.image!!.width
        state.zoomIn()
        assertEquals(125, state.zoom)
        repeat(10) { state.zoomIn() }
        assertEquals(200, state.zoom)
        awaitRenders()
        assertWidthNear(widthAt100 * 2, state.image!!.width)
        repeat(10) { state.zoomOut() }
        assertEquals(50, state.zoom)
        state.zoomOut()
        assertEquals(50, state.zoom)
        awaitRenders()
        assertWidthNear(widthAt100 / 2, state.image!!.width)
    }

    @Test
    fun invalidBytesShowError() {
        val random = Random(7).nextBytes(512)
        val sample = SamplePdf.threePages()
        for (bytes in listOf(random, sample.copyOf(sample.size / 3), ByteArray(0))) {
            val state = newState(bytes)
            assertFalse(runBlocking { state.open() })
            awaitRenders()
            assertEquals("Não foi possível abrir a ficha.", state.error)
            assertNull(state.image)
            assertFalse(state.rendering)
            assertEquals(0, state.pageCount)
        }
    }

    @Test
    fun closeDuringRenderDiscardsResult() {
        val state = openedState()
        val release = blockExecutor()
        state.next()
        assertTrue(state.rendering)
        state.close()
        assertNull(state.image)
        assertFalse(state.rendering)
        release()
        awaitRenders()
        awaitExecutor()
        assertNull(state.image)
        assertFalse(state.rendering)
        assertTrue(state.documentClosed)
    }

    @Test
    fun closeIsIdempotent() {
        val state = openedState()
        state.close()
        state.close()
        awaitExecutor()
        state.close()
        awaitExecutor()
        assertTrue(state.documentClosed)
        assertNull(state.image)
        state.next()
        state.zoomIn()
        assertNull(state.image)
        assertFalse(runBlocking { state.open() })
    }

    @Test
    fun closeStillClosesDocumentWhenScopeIsCancelled() {
        val state = openedState()
        scope.cancel()
        state.close()
        awaitExecutor()
        assertTrue(state.documentClosed)
    }

    @Test
    fun rapidNavigationRendersLastRequestedPage() {
        val state = openedState()
        val rendersBefore = state.renderCount
        val release = blockExecutor()
        state.next()
        state.next()
        assertEquals(2, state.page)
        release()
        awaitRenders()
        assertEquals(2, state.renderedPage)
        assertEquals(rendersBefore + 1, state.renderCount)
        assertNotNull(state.image)
        assertFalse(state.rendering)
    }

    @Test
    fun toStringHasNoContent() {
        val state = openedState()
        state.next()
        assertEquals("PdfDocumentState(pages=${SamplePdf.PAGE_COUNT}, page=1)", state.toString())
    }
}
