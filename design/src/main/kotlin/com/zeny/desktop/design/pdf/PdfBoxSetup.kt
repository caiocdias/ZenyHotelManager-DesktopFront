package com.zeny.desktop.design.pdf

import org.apache.fontbox.FontBoxFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.font.CIDFontMapping
import org.apache.pdfbox.pdmodel.font.FontMapper
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.FontMapping
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/**
 * Configura o PDFBox para não tocar o disco (ADR 0009). Chame [install] antes de qualquer classe de fonte do
 * PDFBox: o construtor de uma fonte Standard 14 já consulta o `FontMappers`, cujo mapper padrão varre as fontes
 * do sistema e grava `.pdfbox.cache` em `user.home`.
 */
object PdfBoxSetup {
    // Referências fortes: o java.util.logging esquece o nível de um logger que o coletor de lixo levou.
    private val pdfBoxLoggers = listOf("org.apache.pdfbox", "org.apache.fontbox").map { Logger.getLogger(it) }
    private val fontMapper by lazy { BundledFontMapper() }

    /** Idempotente: cada chamada reaplica o mesmo estado, com o mesmo mapper. */
    @Synchronized
    fun install() {
        FontMappers.set(fontMapper)
        // O leitor e o gravador de JPEG do ImageIO usariam arquivos imageio*.tmp na pasta temporária.
        ImageIO.setUseCache(false)
        // Avisos de glifo citam o texto do documento. Vale quando o commons-logging cai no java.util.logging;
        // com o slf4j-api sem provedor (o classpath de hoje), o PDFBox já não registra nada.
        pdfBoxLoggers.forEach { it.level = Level.OFF }
    }
}

/**
 * Mapper de fontes só com a LiberationSans-Regular que o JAR do PDFBox traz, lida em memória. Nunca instancia o
 * `FontMapperImpl` nem o `FileSystemFontProvider`. Toda fonte não embutida vira essa substituta.
 */
internal class BundledFontMapper : FontMapper {
    private val sans: TrueTypeFont by lazy {
        val stream = FontMapper::class.java.getResourceAsStream(BUNDLED_SANS)
        requireNotNull(stream) { "Fonte do PDFBox ausente: $BUNDLED_SANS" }
        TTFParser().parse(stream.use { RandomAccessReadBuffer.createBufferFromStream(it) })
    }

    override fun getTrueTypeFont(
        baseFont: String?,
        fontDescriptor: PDFontDescriptor?,
    ): FontMapping<TrueTypeFont> = FontMapping(sans, true)

    override fun getFontBoxFont(
        baseFont: String?,
        fontDescriptor: PDFontDescriptor?,
    ): FontMapping<FontBoxFont> = FontMapping<FontBoxFont>(sans, true)

    override fun getCIDFont(
        baseFont: String?,
        fontDescriptor: PDFontDescriptor?,
        cidSystemInfo: PDCIDSystemInfo?,
    ): CIDFontMapping = CIDFontMapping(null, sans, true)

    private companion object {
        const val BUNDLED_SANS = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    }
}
