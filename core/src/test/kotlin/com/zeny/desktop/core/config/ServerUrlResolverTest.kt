package com.zeny.desktop.core.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerUrlResolverTest {
    private class FakeSettings(
        override var serverUrl: String? = null,
    ) : LocalSettings {
        override var lastLogin: String? = null
        override val deviceId: String = "00000000-0000-0000-0000-000000000000"
    }

    private val dir: Path = Files.createTempDirectory("zeny-env")

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

    private fun writeEnv(bytes: ByteArray) = Files.write(dir.resolve(".env"), bytes)

    private fun writeEnv(text: String) = writeEnv(text.toByteArray())

    @Test
    fun savedWins() {
        writeEnv("ZENY_SERVER_URL=http://dotenv:1")
        assertEquals("http://saved:1", resolve(saved = "http://saved:1", env = "http://env:1"))
    }

    @Test
    fun envSecond() {
        writeEnv("ZENY_SERVER_URL=http://dotenv:1")
        assertEquals("http://env:1", resolve(env = "http://env:1"))
    }

    @Test
    fun dotEnvThird() {
        writeEnv("ZENY_SERVER_URL=http://dotenv:1")
        assertEquals("http://dotenv:1", resolve())
    }

    @Test
    fun defaultLast() {
        assertEquals("http://localhost:8088", resolve())
    }

    @Test
    fun dotEnvWithCrlfBomAndQuotes() {
        val text = "# comentario\r\nOUTRA=x\r\nexport ZENY_SERVER_URL = \"http://10.0.0.5:8088/\"\r\n"
        writeEnv(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())
        assertEquals("http://10.0.0.5:8088", resolve())
    }

    @Test
    fun dotEnvSingleQuotesAndComments() {
        writeEnv("#ZENY_SERVER_URL=http://comentado:1\nZENY_SERVER_URL='http://single:2'\n")
        assertEquals("http://single:2", resolve())
    }

    @Test
    fun blankOrInvalidSourceFallsThrough() {
        writeEnv("ZENY_SERVER_URL=http://dotenv:1")
        assertEquals("http://dotenv:1", resolve(saved = "  ", env = "nao-e-url"))
    }

    @Test
    fun missingDotEnvIsIgnored() {
        assertEquals("http://localhost:8088", resolve(envDir = dir))
        assertEquals("http://localhost:8088", resolve(envDir = null))
    }

    @Test
    fun unreadableDotEnvIsIgnored() {
        Files.createDirectory(dir.resolve(".env"))
        assertEquals("http://localhost:8088", resolve())
    }

    @Test
    fun jarDirectoryIsNullWhenRunningFromClasses() {
        assertNull(jarDirectory(ServerUrlResolverTest::class.java))
    }

    @Test
    fun jarDirectoryOfLibraryJar() {
        assertNotNull(jarDirectory(Unit::class.java))
    }
}
