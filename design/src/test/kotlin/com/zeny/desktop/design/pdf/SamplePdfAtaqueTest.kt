package com.zeny.desktop.design.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ataque à Task 3: o PDF de amostra exercita de fato as fontes e o caminho JPEG/ImageIO que o setup protege. */
class SamplePdfAtaqueTest {
    @Test
    fun samplePdfUsesHelveticaAndHelveticaBoldOnEveryPage() {
        Loader.loadPDF(SamplePdf.threePages()).use { doc ->
            for (page in doc.pages) {
                val names =
                    page.resources.fontNames
                        .map { page.resources.getFont(it).name }
                        .toSet()
                assertTrue("Helvetica" in names, "Helvetica na página: $names")
                assertTrue("Helvetica-Bold" in names, "Helvetica-Bold na página: $names")
            }
        }
    }

    @Test
    fun samplePdfHasAJpegImageWithDctDecodeThatDecodes() {
        Loader.loadPDF(SamplePdf.threePages()).use { doc ->
            val resources = doc.getPage(0).resources
            val images =
                resources.xObjectNames
                    .map { resources.getXObject(it) }
                    .filterIsInstance<PDImageXObject>()
            assertEquals(1, images.size, "uma imagem na página 1")
            val filters = images.single().cosObject.getDictionaryObject(COSName.FILTER)
            assertTrue(filters.toString().contains("DCTDecode"), "filtro da imagem: $filters")
            // Decodifica pelo ImageIO (DCTFilter): é o caminho que gravaria imageio*.tmp com o cache ligado.
            val decoded = images.single().image
            assertTrue(decoded.width > 0 && decoded.height > 0)
        }
    }
}
