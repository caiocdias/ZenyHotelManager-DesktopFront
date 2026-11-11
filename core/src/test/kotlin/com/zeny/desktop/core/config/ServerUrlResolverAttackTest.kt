package com.zeny.desktop.core.config

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Classe sem dependências próprias, carregada de um JAR criado pelo teste. */
class JarProbe

class ServerUrlResolverAttackTest {
    private class FakeSettings(
        override var serverUrl: String? = null,
    ) : LocalSettings {
        override var lastLogin: String? = null
        override val deviceId: String = "00000000-0000-0000-0000-000000000000"
    }

    private val dir: Path = Files.createTempDirectory("zeny-env-atk")
    private val default = "http://localhost:8088"

    private fun resolve(
        saved: String? = null,
        env: String? = null,
        envDir: Path? = dir,
    ): String =
        ServerUrlResolver(
            FakeSettings(saved),
            { name -> if (name == "ZENY_SERVER_URL") env else null },
            envDir,
        ).resolve().value

    private fun dotEnv(text: String) = Files.write(dir.resolve(".env"), text.toByteArray())

    @Test
    fun tabsAroundNameEqualsAndValue() {
        dotEnv("\tZENY_SERVER_URL\t=\thttp://tab:1\t\n")
        assertEquals("http://tab:1", resolve())
    }

    @Test
    fun bomWithCrlfOnFirstLineKey() {
        dotEnv("﻿ZENY_SERVER_URL=http://bom:1\r\nOUTRA=x\r\n")
        assertEquals("http://bom:1", resolve())
    }

    @Test
    fun similarKeysAreNotMatched() {
        dotEnv(
            "ZENY_SERVER_URL_X=http://x:1\nXZENY_SERVER_URL=http://y:1\n" +
                "exportZENY_SERVER_URL=http://z:1\nzeny_server_url=http://w:1\n",
        )
        assertEquals(default, resolve())
    }

    @Test
    fun lineWithoutEqualsIsIgnoredAndNextLineStillRead() {
        dotEnv("ZENY_SERVER_URL\nZENY_SERVER_URL=http://ok:1\n")
        assertEquals("http://ok:1", resolve())
    }

    @Test
    fun linesWithoutEqualsAloneFallToDefault() {
        dotEnv("ZENY_SERVER_URL http://nope:1\nZENY_SERVER_URL\n")
        assertEquals(default, resolve())
    }

    @Test
    fun indentedCommentIsIgnored() {
        dotEnv("   # ZENY_SERVER_URL=http://comentado:1\n")
        assertEquals(default, resolve())
    }

    @Test
    fun unbalancedOrMismatchedQuotesFallThroughWithoutException() {
        listOf("\"http://h:1", "http://h:1\"", "\"http://h:1'", "'http://h:1").forEach {
            dotEnv("ZENY_SERVER_URL=$it\n")
            assertEquals(default, resolve(), it)
        }
    }

    @Test
    fun inlineCommentAfterValueNeverProducesGarbageUrl() {
        dotEnv("ZENY_SERVER_URL=http://h:1 # servidor\n")
        val result = resolve()
        assertEquals(true, result == "http://h:1" || result == default, result)
    }

    @Test
    fun spacesInsideQuotesAreTrimmedByUrlParsing() {
        dotEnv("ZENY_SERVER_URL=' http://q:1/ '\n")
        assertEquals("http://q:1", resolve())
    }

    @Test
    fun emptyValueFallsToDefault() {
        dotEnv("ZENY_SERVER_URL=\nZENY_SERVER_URL=\"\"\n")
        assertEquals(default, resolve())
    }

    @Test
    fun emptyEnvVariableFallsThroughToDotEnv() {
        dotEnv("ZENY_SERVER_URL=http://dotenv:1\n")
        assertEquals("http://dotenv:1", resolve(env = ""))
    }

    @Test
    fun invalidSavedFallsToEnvEvenWhenDotEnvPresent() {
        dotEnv("ZENY_SERVER_URL=http://dotenv:1\n")
        assertEquals("http://env:2", resolve(saved = "ftp://x", env = "http://env:2"))
    }

    @Test
    fun dangerousSchemesInEveryLayerFallToDefault() {
        dotEnv("ZENY_SERVER_URL=file:///etc/passwd\n")
        assertEquals(default, resolve(saved = "javascript:alert(1)", env = "http://u:p@h"))
    }

    @Test
    fun binaryDotEnvDoesNotThrow() {
        Files.write(dir.resolve(".env"), Random(42).nextBytes(64 * 1024))
        assertEquals(default, resolve())
    }

    @Test
    fun invalidUtf8BeforeKeyDoesNotBreakLaterLines() {
        val bad = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '\n'.code.toByte())
        Files.write(dir.resolve(".env"), bad + "ZENY_SERVER_URL=http://ok:1\n".toByteArray())
        assertEquals("http://ok:1", resolve())
    }

    @Test
    fun hugeDotEnvStillFindsKeyAtTheEnd() {
        val filler = "# " + "x".repeat(200) + "\n"
        dotEnv(filler.repeat(25_000) + "ZENY_SERVER_URL=http://late:1\n")
        assertEquals("http://late:1", resolve())
    }

    @Test
    fun nonexistentEnvDirFallsToDefault() {
        assertEquals(default, resolve(envDir = dir.resolve("nao-existe")))
    }

    @Test
    fun ipv6InDotEnv() {
        dotEnv("ZENY_SERVER_URL=http://[::1]:8088\n")
        assertEquals("http://[::1]:8088", resolve())
    }

    @Test
    fun jarDirectoryDecodesSpacesAndAccents() {
        val jarDir = Files.createDirectory(dir.resolve("pasta com espaço e acentuação ç"))
        val jar = jarDir.resolve("app zeny.jar")
        val entry = JarProbe::class.java.name.replace('.', '/') + ".class"
        val bytes = assertNotNull(JarProbe::class.java.classLoader.getResourceAsStream(entry)).use { it.readBytes() }
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(ZipEntry(entry))
            it.write(bytes)
            it.closeEntry()
        }
        val probeName = JarProbe::class.java.name
        val loader =
            object : URLClassLoader(arrayOf<URL>(jar.toUri().toURL()), JarProbe::class.java.classLoader) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> =
                    if (name == probeName) {
                        synchronized(getClassLoadingLock(name)) { findLoadedClass(name) ?: findClass(name) }
                    } else {
                        super.loadClass(name, resolve)
                    }
            }
        loader.use {
            val clazz = it.loadClass(probeName)
            assertEquals(jarDir.toRealPath(), jarDirectory(clazz)?.toRealPath())
        }
    }
}
