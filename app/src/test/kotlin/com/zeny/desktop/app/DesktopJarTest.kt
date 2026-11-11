package com.zeny.desktop.app

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Inspeciona o app/build/libs/zeny-desktop.jar já gerado (não chama o Gradle).
 * Sem o JAR, os testes são pulados: rode `./gradlew :app:desktopJar` antes.
 */
class DesktopJarTest {
    // O Gradle passa o caminho absoluto do JAR que ele gerou. Sem a propriedade (IDE), pula;
    // com a propriedade e sem o arquivo, falha.
    private val jarPath: String? = System.getProperty("zeny.desktopJar")

    private fun <T> withJar(block: (ZipFile) -> T): T {
        assumeTrue(jarPath != null, "-Dzeny.desktopJar não definido; rode pelo Gradle (:app:test)")
        val file = File(jarPath!!)
        assertTrue(file.isFile, "JAR indicado por zeny.desktopJar não existe: $jarPath")
        return ZipFile(file).use(block)
    }

    private fun lines(text: String) = text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    private fun classpathJars() =
        System
            .getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isFile && it.name.endsWith(".jar") }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private fun nativeIsIdentical(
        zip: ZipFile,
        artifactPrefix: String,
        lib: String,
    ) {
        val artifact = classpathJars().firstOrNull { it.name.startsWith(artifactPrefix) }
        assertNotNull(artifact, "$artifactPrefix fora do classpath de teste")
        val expected = ZipFile(artifact).use { a -> sha(a.getInputStream(a.getEntry(lib)).readBytes()) }
        val entry = assertNotNull(zip.getEntry(lib), "$lib ausente do JAR")
        assertEquals(expected, sha(zip.getInputStream(entry).readBytes()), "$lib diverge do artefato $artifactPrefix")
    }

    @Test
    fun hasNoDuplicateEntries() =
        withJar { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            val dups =
                names
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            assertTrue(dups.isEmpty(), "entradas duplicadas: ${dups.take(10)}")
        }

    @Test
    fun excludesModuleInfoAndThirdPartySignatures() =
        withJar { zip ->
            val sig = Regex("META-INF/[^/]+\\.(SF|DSA|RSA|EC)", RegexOption.IGNORE_CASE)
            val bad =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.endsWith("module-info.class") || sig.matches(it) }
                    .toList()
            assertTrue(bad.isEmpty(), "entradas proibidas: $bad")
        }

    @Test
    fun manifestDeclaresMainClassAndNativeAccess() =
        withJar { zip ->
            val mf = Manifest(zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")))
            assertEquals("com.zeny.desktop.app.MainKt", mf.mainAttributes.getValue("Main-Class"))
            assertEquals("ALL-UNNAMED", mf.mainAttributes.getValue("Enable-Native-Access"))
        }

    @Test
    fun mainClassIsInsideTheJar() =
        withJar { zip ->
            assertTrue(zip.getEntry("com/zeny/desktop/app/MainKt.class") != null, "MainKt.class ausente do JAR")
        }

    @Test
    fun windowsDllIsByteIdenticalToTheSkikoWindowsArtifact() =
        withJar { zip ->
            nativeIsIdentical(zip, "skiko-awt-runtime-windows-x64", "skiko-windows-x64.dll")
        }

    @Test
    fun linuxSoIsByteIdenticalToTheSkikoLinuxArtifact() =
        withJar { zip ->
            nativeIsIdentical(zip, "skiko-awt-runtime-linux-x64", "libskiko-linux-x64.so")
        }

    @Test
    fun everyServiceInTheJarKeepsAllProvidersOfItsClasspathJars() =
        withJar { zip ->
            val expected = sortedMapOf<String, MutableSet<String>>()
            classpathJars().forEach { dep ->
                ZipFile(dep).use { z ->
                    z
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { e ->
                            expected
                                .getOrPut(e.name) { linkedSetOf() }
                                .addAll(lines(z.getInputStream(e).bufferedReader().readText()))
                        }
                }
            }
            val inJar =
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .map { it.name }
                    .toList()
            assertTrue(inJar.isNotEmpty(), "nenhum serviço no JAR")
            inJar.forEach { name ->
                val actual = lines(zip.getInputStream(zip.getEntry(name)).bufferedReader().readText())
                assertEquals(actual.toSet().size, actual.size, "linhas repetidas em $name")
                val missing = expected[name].orEmpty() - actual.toSet()
                assertTrue(missing.isEmpty(), "$name perdeu: $missing")
            }
        }
}
