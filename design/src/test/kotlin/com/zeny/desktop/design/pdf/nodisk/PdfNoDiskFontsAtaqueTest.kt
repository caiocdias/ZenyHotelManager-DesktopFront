package com.zeny.desktop.design.pdf.nodisk

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zeny.desktop.design.pdf.PdfBoxSetup
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDFontFactory
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ataque à Task 3, só na `noDiskTest`: fontes NÃO embutidas além das Standard 14 (TrueType "Arial", Type1 com
 * nome livre, Times, Courier e uma fonte CID) resolvem sem varrer o sistema e sem criar arquivo.
 */
class PdfNoDiskFontsAtaqueTest {
    @Test
    fun nonEmbeddedFontsOfEveryKindCreateNoFile() {
        PdfBoxSetup.install()
        val roots = listOf(System.getProperty("java.io.tmpdir"), System.getProperty("user.home")).map { Path.of(it) }
        assertTrue(roots.all { it.toAbsolutePath().toString().contains("no-disk-test") }, "use :design:noDiskTest")
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        java.awt.image.BufferedImage(8, 8, 1).apply {
            createGraphics().apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                drawString("x", 0, 8)
                dispose()
            }
            toComposeImageBitmap()
        }
        FileSystems.getDefault().newWatchService().use { watcher ->
            val dirs = roots.flatMap { r -> Files.walk(r).use { s -> s.filter { it.isDirectory() }.toList() } }
            val keys = dirs.associateBy { it.register(watcher, ENTRY_CREATE) }
            val before = snapshot(roots)

            val bytes = buildPdf()
            Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache()).use { doc ->
                val fontNames =
                    doc
                        .getPage(0)
                        .resources.fontNames
                        .toList()
                assertEquals(6, fontNames.size)
                fontNames.forEach { name ->
                    val font = doc.getPage(0).resources.getFont(name)
                    assertTrue(font.isDamaged.not(), "fonte ${font.name} resolvida sem dano")
                }
                PDFRenderer(doc).renderImageWithDPI(0, 96f).toComposeImageBitmap()
            }

            assertEquals(before, snapshot(roots), "fontes não embutidas não deixam arquivo")
            val sentinels = roots.map { Files.createFile(it.resolve("sentinel-${UUID.randomUUID()}")) }.toSet()
            val created = mutableSetOf<Path>()
            while (!created.containsAll(sentinels)) {
                val key = watcher.poll(30, TimeUnit.SECONDS) ?: fail("sentinela não chegou")
                val dir = keys.getValue(key)
                for (e in key.pollEvents()) {
                    if (e.kind() == OVERFLOW) fail("OVERFLOW")
                    created.add(dir.resolve(e.context() as Path))
                }
                key.reset()
            }
            assertEquals(emptySet(), created - sentinels, "nenhum arquivo criado, nem apagado depois")
        }
    }

    private fun buildPdf(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val resources = PDResources()
            page.resources = resources
            val fonts =
                listOf(
                    type1("Times-Roman"),
                    type1("Courier"),
                    type1("Garamond-Unembedded"),
                    trueType("Arial"),
                    trueType("Verdana-Bold"),
                    cidType0("MS-Mincho"),
                )
            val ops = StringBuilder("BT 12 Tf 72 700 Td\n")
            fonts.forEachIndexed { i, f ->
                resources.put(COSName.getPDFName("F$i"), f)
                ops.append("/F$i 12 Tf (Texto $i) Tj 0 -20 Td\n")
            }
            ops.append("ET")
            val stream =
                org.apache.pdfbox.pdmodel
                    .PDPageContentStream(doc, page)
            stream.close()
            page.cosObject.setItem(
                COSName.CONTENTS,
                org.apache.pdfbox.cos.COSStream().also { s ->
                    s.createOutputStream().use { it.write(ops.toString().toByteArray()) }
                },
            )
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun type1(base: String): PDFont = font("Type1", base)

    private fun trueType(base: String): PDFont = font("TrueType", base)

    private fun font(
        subtype: String,
        base: String,
    ): PDFont {
        val d = COSDictionary()
        d.setItem(COSName.TYPE, COSName.FONT)
        d.setItem(COSName.SUBTYPE, COSName.getPDFName(subtype))
        d.setName(COSName.BASE_FONT, base)
        d.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING)
        return PDFontFactory.createFont(d)
    }

    private fun cidType0(base: String): PDFont {
        val cid = COSDictionary()
        cid.setItem(COSName.TYPE, COSName.FONT)
        cid.setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType2"))
        cid.setName(COSName.BASE_FONT, base)
        val info = COSDictionary()
        info.setString(COSName.REGISTRY, "Adobe")
        info.setString(COSName.ORDERING, "Japan1")
        info.setInt(COSName.SUPPLEMENT, 2)
        cid.setItem(COSName.CIDSYSTEMINFO, info)
        val type0 = COSDictionary()
        type0.setItem(COSName.TYPE, COSName.FONT)
        type0.setItem(COSName.SUBTYPE, COSName.getPDFName("Type0"))
        type0.setName(COSName.BASE_FONT, base)
        type0.setItem(COSName.ENCODING, COSName.IDENTITY_H)
        type0.setItem(COSName.DESCENDANT_FONTS, COSArray().also { it.add(cid) })
        return PDFontFactory.createFont(type0)
    }

    private fun snapshot(roots: List<Path>): Map<Path, String> =
        roots
            .flatMap { root -> Files.walk(root).use { it.toList() } }
            .associateWith { if (it.isDirectory()) "dir" else "${Files.size(it)}@${Files.getLastModifiedTime(it)}" }
}
