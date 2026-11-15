package com.zeny.desktop.design.pdf.nodisk

import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.SamplePdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Só na `noDiskTest`: o ciclo do próprio [PdfDocumentState] (abrir, renderizar, navegar, dar zoom, fechar). */
class PdfDocumentStateNoDiskTest {
    @Test
    fun documentStateLifecycleCreatesNoFile() = NoDiskProbe.assertNoFileCreatedBy(::stateLifecycle)

    private fun stateLifecycle() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val state = PdfDocumentState(SamplePdf.threePages(), scope, dispatcher)

            fun awaitRenders() =
                runBlocking {
                    scope.coroutineContext.job.children
                        .toList()
                        .joinAll()
                }
            assertTrue(runBlocking { state.open() })
            awaitRenders()
            assertNotNull(state.image)
            repeat(SamplePdf.PAGE_COUNT) {
                state.next()
                awaitRenders()
            }
            repeat(2) {
                state.zoomIn()
                awaitRenders()
            }
            state.zoomOut()
            state.previous()
            awaitRenders()
            assertNotNull(state.image)
            assertEquals(1, state.page)
            state.close()
            runBlocking { withContext(dispatcher) {} }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }
}
