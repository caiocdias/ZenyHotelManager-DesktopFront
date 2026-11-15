package com.zeny.desktop.design.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Documento PDF aberto só em memória (ADR 0009), com uma página renderizada por vez.
 *
 * Todo acesso ao `PDDocument` (abrir, renderizar, fechar) passa por [documentDispatcher], que precisa ser
 * confinado (uma thread por vez). Os comandos ([next], [zoomIn], [close]...) vêm da thread de UI; o resultado de
 * uma renderização que chega depois de [close], ou depois de um pedido mais novo, é descartado. Os bytes, as
 * páginas e o texto nunca saem daqui em log, mensagem ou [toString].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfDocumentState(
    private val bytes: ByteArray,
    private val scope: CoroutineScope,
    private val documentDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    var pageCount: Int by mutableIntStateOf(0)
        private set
    var page: Int by mutableIntStateOf(0)
        private set
    var zoom: Int by mutableIntStateOf(DEFAULT_ZOOM)
        private set
    var image: ImageBitmap? by mutableStateOf(null)
        private set
    var rendering: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    // Protege closed, generation e a publicação do resultado: o pedido e o descarte não se atropelam.
    private val lock = Any()
    private var closed = false
    private var generation = 0

    // Só tocada na thread do documentDispatcher.
    private var session: Session? = null

    @Volatile
    internal var documentClosed = false
        private set

    @Volatile
    internal var renderedPage = -1
        private set

    @Volatile
    internal var renderCount = 0
        private set

    /** Abre o PDF e pede a primeira página. Falso, com [error], se o PDFBox não abre; falso se já fechou. */
    suspend fun open(): Boolean {
        val count = withContext(documentDispatcher) { attach() }
        synchronized(lock) {
            if (closed) return false
            if (count == 0) error = OPEN_ERROR else pageCount = count
        }
        if (count > 0) requestRender()
        return count > 0
    }

    fun next() = show(page + 1, zoom)

    fun previous() = show(page - 1, zoom)

    fun zoomIn() = show(page, zoom + ZOOM_STEP)

    fun zoomOut() = show(page, zoom - ZOOM_STEP)

    /**
     * Idempotente. Solta a imagem já, e fecha o documento no executor confinado, depois da renderização em voo
     * e mesmo que o [scope] já tenha sido cancelado.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            image = null
            rendering = false
        }
        CoroutineScope(documentDispatcher).launch {
            runCatching { session?.document?.close() }
            session = null
            documentClosed = true
        }
    }

    override fun toString() = "PdfDocumentState(pages=$pageCount, page=$page)"

    /** Carrega os bytes em memória. Devolve o número de páginas, ou 0 se não abriu (ou se já fechou). */
    private fun attach(): Int {
        PdfBoxSetup.install()
        val document =
            try {
                Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return 0
            }
        val usable = document.numberOfPages > 0 && synchronized(lock) { !closed }
        if (usable) session = Session(document) else document.close().also { documentClosed = true }
        return if (usable) document.numberOfPages else 0
    }

    private fun show(
        targetPage: Int,
        targetZoom: Int,
    ) {
        val newZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (targetPage !in 0 until pageCount || (targetPage == page && newZoom == zoom)) return
        page = targetPage
        zoom = newZoom
        requestRender()
    }

    private fun requestRender() {
        val ticket: Int
        synchronized(lock) {
            if (closed || pageCount == 0) return
            ticket = ++generation
            rendering = true
        }
        val targetPage = page
        val targetZoom = zoom
        scope.launch(documentDispatcher) { render(ticket, targetPage, targetZoom) }
    }

    private fun render(
        ticket: Int,
        targetPage: Int,
        targetZoom: Int,
    ) {
        if (synchronized(lock) { generation != ticket }) return
        val bitmap =
            try {
                session?.render(targetPage, targetZoom)
            } catch (_: Exception) {
                null
            }
        synchronized(lock) {
            if (generation != ticket) return
            rendering = false
            if (bitmap == null) {
                error = OPEN_ERROR
            } else {
                image = bitmap
                renderedPage = targetPage
                renderCount++
            }
        }
    }

    private class Session(
        val document: PDDocument,
    ) {
        private val renderer = PDFRenderer(document)

        fun render(
            page: Int,
            zoom: Int,
        ): ImageBitmap = renderer.renderImageWithDPI(page, BASE_DPI * zoom / PERCENT).toComposeImageBitmap()
    }

    private companion object {
        const val OPEN_ERROR = "Não foi possível abrir a ficha."
        const val DEFAULT_ZOOM = 100
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 200
        const val ZOOM_STEP = 25
        const val BASE_DPI = 96f
        const val PERCENT = 100f
    }
}
