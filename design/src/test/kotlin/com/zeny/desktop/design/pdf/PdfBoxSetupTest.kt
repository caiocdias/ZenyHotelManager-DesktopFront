package com.zeny.desktop.design.pdf

import org.apache.commons.logging.LogFactory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PdfBoxSetupTest {
    // Nome PostScript da LiberationSans-Regular.ttf que o JAR do PDFBox traz em org/apache/pdfbox/resources/ttf.
    private val bundledName = "LiberationSans"

    @Test
    fun installIsIdempotent() {
        PdfBoxSetup.install()
        val mapper = FontMappers.instance()
        PdfBoxSetup.install()
        assertSame(mapper, FontMappers.instance(), "o segundo install() mantém o mesmo mapper")
        assertFalse(ImageIO.getUseCache(), "o segundo install() mantém o cache do ImageIO desligado")
    }

    @Test
    fun fontMapperIsOurs() {
        PdfBoxSetup.install()
        assertIs<BundledFontMapper>(FontMappers.instance())
    }

    @Test
    fun imageIoCacheIsOff() {
        PdfBoxSetup.install()
        assertFalse(ImageIO.getUseCache(), "com o cache, o decodificador de JPEG gravaria imageio*.tmp")
    }

    @Test
    fun standardFontsResolveWithoutSystemScan() {
        PdfBoxSetup.install()
        val fonts =
            listOf(FontName.HELVETICA, FontName.HELVETICA_BOLD, FontName.TIMES_ROMAN, FontName.COURIER)
                .associateWith { PDType1Font(it).fontBoxFont }
        fonts.forEach { (name, font) -> assertEquals(bundledName, font.name, "${name.name} usa a fonte do JAR") }
        // Uma varredura do sistema daria a Times e a Courier fontes próprias; aqui todas são a mesma do JAR.
        val distinct = fonts.values.distinctBy { System.identityHashCode(it) }
        assertEquals(1, distinct.size, "uma única fonte, a do JAR")
    }

    @Test
    fun everyMappingUsesTheBundledFont() {
        PdfBoxSetup.install()
        val mapper = FontMappers.instance()
        assertEquals(bundledName, mapper.getTrueTypeFont("Arial", null).font.name)
        assertEquals(bundledName, mapper.getFontBoxFont("NoSuchFont-Bold", null).font.name)
        val cid = mapper.getCIDFont("MS-Gothic", null, null)
        assertEquals(bundledName, cid.trueTypeFont.name)
        assertTrue(cid.isFallback, "a fonte do JAR é sempre substituta")
    }

    @Test
    fun pdfBoxLogIsQuiet() {
        PdfBoxSetup.install()
        for (name in listOf("org.apache.pdfbox", "org.apache.fontbox")) {
            assertEquals(Level.OFF, Logger.getLogger(name).level, "$name desligado no java.util.logging")
        }
        assertFalse(LogFactory.getLog(PDType1Font::class.java).isWarnEnabled, "aviso de glifo não sai no log")
    }

    @Test
    fun samplePdfHasThreePagesInMemory() {
        val bytes = SamplePdf.threePages()
        assertEquals("%PDF-", bytes.copyOf(5).decodeToString())
        Loader.loadPDF(bytes).use { assertEquals(SamplePdf.PAGE_COUNT, it.numberOfPages) }
    }
}
