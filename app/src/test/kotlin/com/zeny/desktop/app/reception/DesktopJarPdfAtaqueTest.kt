package com.zeny.desktop.app.reception

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ataque à prova pelo JAR: de onde as classes vêm de fato e se cada aviso é o da biblioteca certa. */
class DesktopJarPdfAtaqueTest {
    private val jarPath: String? = System.getProperty("zeny.desktopJar")

    @BeforeTest
    fun requireJar() {
        assumeTrue(jarPath != null, "-Dzeny.desktopJar não definido; rode pelo Gradle (:app:test)")
    }

    private fun jar() = File(checkNotNull(jarPath))

    private fun isolated() = URLClassLoader(arrayOf(jar().toURI().toURL()), ClassLoader.getPlatformClassLoader())

    private fun locationOf(clazz: Class<*>): File =
        File(
            clazz.protectionDomain.codeSource.location
                .toURI(),
        )

    @Test
    fun classesComeFromTheJarAndTheTestClasspathIsUnreachable() {
        isolated().use { loader ->
            listOf(
                "org.apache.pdfbox.pdmodel.PDDocument",
                "org.apache.pdfbox.io.RandomAccessRead",
                "org.apache.fontbox.ttf.TTFParser",
                "com.zeny.desktop.design.pdf.PdfBoxSetup",
                "com.zeny.desktop.design.pdf.BundledFontMapper",
            ).forEach { name ->
                val clazz = loader.loadClass(name)
                assertEquals(loader, clazz.classLoader, "$name não veio do classloader do JAR")
                assertEquals(jar().canonicalFile, locationOf(clazz).canonicalFile, "$name não tem o JAR como origem")
            }
            // O que só existe no classpath de teste não pode ser visto pelo loader isolado.
            assertFailsWith<ClassNotFoundException> { loader.loadClass(DesktopJarPdfAtaqueTest::class.java.name) }
            assertFailsWith<ClassNotFoundException> { loader.loadClass("kotlin.test.Test") }
        }
    }

    private val noticeName = Regex("META-INF/(NOTICE|LICENSE)[^/]*", RegexOption.IGNORE_CASE)

    /** Confere os avisos de [artifact] no JAR contra os da biblioteca de origem e devolve os bytes do NOTICE. */
    private fun checkNotices(
        bundle: ZipFile,
        artifact: String,
        source: File,
    ): ByteArray {
        var notice: ByteArray? = null
        ZipFile(source).use { lib ->
            val own =
                lib
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && noticeName.matches(it.name) }
                    .toList()
            assertTrue(own.any { "NOTICE" in it.name } && own.any { "LICENSE" in it.name }, "$artifact sem avisos")
            val version = source.nameWithoutExtension.removePrefix("$artifact-")
            for (e in own) {
                val target = "META-INF/third-party-notices/$artifact-$version/${e.name.substringAfterLast('/')}"
                val copy = assertNotNull(bundle.getEntry(target), "ausente do JAR: $target")
                val expected = lib.getInputStream(e).readBytes()
                assertContentEquals(expected, bundle.getInputStream(copy).readBytes(), "$target difere do original")
                if ("NOTICE" in e.name) notice = expected
            }
        }
        return assertNotNull(notice)
    }

    @Test
    fun eachNoticeIsTheOneOfItsOwnLibrary() {
        val sources =
            mapOf(
                "pdfbox" to "org.apache.pdfbox.pdmodel.PDDocument",
                "pdfbox-io" to "org.apache.pdfbox.io.RandomAccessRead",
                "fontbox" to "org.apache.fontbox.ttf.TTFParser",
                "commons-logging" to "org.apache.commons.logging.LogFactory",
            )
        val notices =
            ZipFile(jar()).use { bundle ->
                sources.map { (artifact, className) ->
                    val source = locationOf(Class.forName(className))
                    assertTrue(source.isFile && source.name.startsWith("$artifact-"), "origem inesperada: $source")
                    checkNotices(bundle, artifact, source).toList()
                }
            }
        assertEquals(sources.size, notices.toSet().size, "há NOTICE repetido entre bibliotecas diferentes")
    }
}
