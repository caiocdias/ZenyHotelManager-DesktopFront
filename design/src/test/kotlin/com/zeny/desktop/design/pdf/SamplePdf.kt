package com.zeny.desktop.design.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * PDF de teste gerado só em memória, sempre depois do [PdfBoxSetup.install]: [PAGE_COUNT] páginas A4 com
 * Helvetica e Helvetica-Bold, e uma imagem JPEG (DCTDecode) na primeira.
 */
internal object SamplePdf {
    const val PAGE_COUNT = 3

    private const val MARGIN = 72f
    private const val TOP = 760f
    private const val TITLE_SIZE = 18f
    private const val BODY_SIZE = 12f
    private const val LINE = -24f
    private const val PHOTO_Y = 560f
    private const val PHOTO_WIDTH = 160f
    private const val PHOTO_HEIGHT = 120f
    private const val IMAGE_WIDTH = 64
    private const val IMAGE_HEIGHT = 48

    fun threePages(): ByteArray {
        PdfBoxSetup.install()
        PDDocument().use { doc ->
            val regular = PDType1Font(FontName.HELVETICA)
            val bold = PDType1Font(FontName.HELVETICA_BOLD)
            val photo = JPEGFactory.createFromImage(doc, gradient())
            repeat(PAGE_COUNT) { index ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setFont(bold, TITLE_SIZE)
                    content.newLineAtOffset(MARGIN, TOP)
                    content.showText("Ficha de registro - página ${index + 1}")
                    content.setFont(regular, BODY_SIZE)
                    content.newLineAtOffset(0f, LINE)
                    content.showText("Hóspede de teste, documento 000.000.000-00")
                    content.endText()
                    if (index == 0) content.drawImage(photo, MARGIN, PHOTO_Y, PHOTO_WIDTH, PHOTO_HEIGHT)
                }
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun gradient(): BufferedImage {
        val image = BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until IMAGE_WIDTH) {
            for (y in 0 until IMAGE_HEIGHT) {
                image.setRGB(x, y, Color(x * 255 / IMAGE_WIDTH, y * 255 / IMAGE_HEIGHT, 128).rgb)
            }
        }
        return image
    }
}
