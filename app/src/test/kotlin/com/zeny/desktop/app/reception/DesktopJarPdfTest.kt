package com.zeny.desktop.app.reception

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FONT_RESOURCE = "org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
private const val DPI = 96f

/**
 * A prova da ficha pelo `zeny-desktop.jar`: as classes vêm só do JAR (classloader isolado, sem o classpath de teste)
 * e vai até onde não toca o Skia, cujo nativo um segundo classloader não carrega: `PdfBoxSetup.install()`, a carga em
 * memória de um PDF com Helvetica e o `PDFRenderer` até um `BufferedImage` da página 1.
 */
class DesktopJarPdfTest {
    private val jarPath: String? = System.getProperty("zeny.desktopJar")

    @BeforeTest
    fun requireJar() {
        assumeTrue(jarPath != null, "-Dzeny.desktopJar não definido; rode pelo Gradle (:app:test)")
    }

    private fun jar() = File(checkNotNull(jarPath)).also { assertTrue(it.isFile, "JAR ausente: $jarPath") }

    private fun isolated() = URLClassLoader(arrayOf(jar().toURI().toURL()), ClassLoader.getPlatformClassLoader())

    @Test
    fun jarRendersFirstPage() {
        isolated().use { loader ->
            val setup = loader.loadClass("com.zeny.desktop.design.pdf.PdfBoxSetup")
            val instance = setup.getField("INSTANCE").get(null)
            setup.getMethod("install").invoke(instance)

            val mapper =
                loader
                    .loadClass("org.apache.pdfbox.pdmodel.font.FontMappers")
                    .getMethod("instance")
                    .invoke(null)
            assertEquals("com.zeny.desktop.design.pdf.BundledFontMapper", mapper.javaClass.name)
            assertEquals(loader, mapper.javaClass.classLoader, "o mapper veio do JAR")

            val font = loader.getResource(FONT_RESOURCE)
            assertNotNull(font, "a fonte do PDFBox não está no JAR")
            assertTrue(font.toString().startsWith("jar:file:${jar().absolutePath}!"), "a fonte não vem do JAR: $font")

            val loaderClass = loader.loadClass("org.apache.pdfbox.Loader")
            val document = loaderClass.getMethod("loadPDF", ByteArray::class.java).invoke(null, helveticaPdf())
            try {
                val renderer =
                    loader
                        .loadClass("org.apache.pdfbox.rendering.PDFRenderer")
                        .getConstructor(loader.loadClass("org.apache.pdfbox.pdmodel.PDDocument"))
                        .newInstance(document)
                val image =
                    renderer.javaClass
                        .getMethod("renderImageWithDPI", Int::class.java, Float::class.java)
                        .invoke(renderer, 0, DPI) as BufferedImage
                assertTrue(image.width > 0 && image.height > 0, "página 1 vazia")
                val ink =
                    (0 until image.width).any { x ->
                        (0 until image.height).any { y -> image.getRGB(x, y) != -1 }
                    }
                assertTrue(ink, "a página 1 saiu toda em branco")
            } finally {
                document.javaClass.getMethod("close").invoke(document)
            }
        }
    }

    @Test
    fun jarKeepsPdfBoxNotices() {
        ZipFile(jar()).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            listOf("pdfbox", "pdfbox-io", "fontbox", "commons-logging").forEach { artifact ->
                val prefix = Regex("META-INF/third-party-notices/${Regex.escape(artifact)}-\\d[^/]*/")
                assertTrue(
                    names.any { prefix.containsMatchIn(it) && it.contains("/NOTICE") },
                    "NOTICE de $artifact ausente do JAR",
                )
                assertTrue(
                    names.any { prefix.containsMatchIn(it) && it.contains("/LICENSE") },
                    "LICENSE de $artifact ausente do JAR",
                )
            }
        }
    }

    /** Uma página A4 com texto em Helvetica (fonte Standard 14, não embutida), com a tabela xref calculada. */
    private fun helveticaPdf(): ByteArray {
        val stream = "BT /F1 18 Tf 72 760 Td (Ficha de registro) Tj ET"
        val objects =
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R " +
                    "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets =
            objects.mapIndexed { i, body ->
                out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") }
            }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
