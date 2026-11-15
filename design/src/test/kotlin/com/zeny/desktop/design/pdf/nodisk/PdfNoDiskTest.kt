package com.zeny.desktop.design.pdf.nodisk

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zeny.desktop.design.pdf.SamplePdf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.rendering.PDFRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Roda só na tarefa `noDiskTest` do design: JVM própria, com `java.io.tmpdir` e `user.home` em pastas novas do
 * build. A tarefa `test` exclui este pacote.
 */
class PdfNoDiskTest {
    private val zooms = listOf(1.0f, 2.0f)
    private val baseDpi = 96f

    @Test
    fun pdfLifecycleCreatesNoFile() = NoDiskProbe.assertNoFileCreatedBy(::pdfLifecycle)

    /** Gera o PDF (depois do install()), carrega só em memória, renderiza as 3 páginas em 2 zooms e fecha. */
    private fun pdfLifecycle() {
        val bytes = SamplePdf.threePages()
        Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache()).use { doc ->
            assertEquals(SamplePdf.PAGE_COUNT, doc.numberOfPages)
            val renderer = PDFRenderer(doc)
            for (zoom in zooms) {
                for (page in 0 until doc.numberOfPages) {
                    renderer.renderImageWithDPI(page, baseDpi * zoom).toComposeImageBitmap()
                }
            }
        }
    }
}
