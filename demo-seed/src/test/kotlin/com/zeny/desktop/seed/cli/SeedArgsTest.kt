package com.zeny.desktop.seed.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeedArgsTest {
    private val pw = "senha-longa-123"
    private val full =
        mapOf(
            "server" to "http://localhost:8080",
            "admin-login" to "admin",
            "admin-password" to "$pw-a",
            "manager-password" to "$pw-m",
            "receptionist-password" to "$pw-r",
            "housekeeper-password" to "$pw-h",
            "manager-pin" to "1234",
        )

    private fun asArgs(map: Map<String, String>) = map.flatMap { listOf("--${it.key}", it.value) }

    private fun asEnv(map: Map<String, String>) = map.mapKeys { "ZENY_SEED_" + it.key.uppercase().replace('-', '_') }

    private fun ok(result: ParseResult): SeedConfig = assertIs<ParseResult.Ok>(result).config

    private fun invalid(result: ParseResult): String = assertIs<ParseResult.Invalid>(result).message

    @Test
    fun parsesAllFromArgs() {
        val c = ok(SeedArgs.parse(asArgs(full), emptyMap()))
        assertEquals("http://localhost:8080", c.server.value)
        assertEquals("admin", c.adminLogin)
        assertEquals("$pw-a", c.adminPassword.value)
        assertEquals(null, c.adminNewPassword)
        assertEquals("$pw-m", c.managerPassword.value)
        assertEquals("$pw-r", c.receptionistPassword.value)
        assertEquals("$pw-h", c.housekeeperPassword.value)
        assertEquals("1234", c.managerPin.value)
    }

    @Test
    fun parsesAllFromEnv() {
        val c = ok(SeedArgs.parse(emptyList(), asEnv(full + ("admin-new-password" to "$pw-n"))))
        assertEquals("admin", c.adminLogin)
        assertEquals("$pw-n", c.adminNewPassword?.value)
        assertEquals("$pw-h", c.housekeeperPassword.value)
    }

    @Test
    fun argWinsOverEnv() {
        val c = ok(SeedArgs.parse(listOf("--admin-login", "arg"), asEnv(full)))
        assertEquals("arg", c.adminLogin)
    }

    @Test
    fun equalsFormWorks() {
        val args = full.map { "--${it.key}=${it.value}" }
        assertEquals("admin", ok(SeedArgs.parse(args, emptyMap())).adminLogin)
    }

    @Test
    fun helpWins() {
        assertEquals(ParseResult.Help, SeedArgs.parse(listOf("--admin-login", "x", "--help"), emptyMap()))
        assertEquals(ParseResult.Help, SeedArgs.parse(listOf("-h"), emptyMap()))
    }

    @Test
    fun missingRequiredNamesArgAndEnv() {
        for (key in full.keys) {
            val message = invalid(SeedArgs.parse(asArgs(full - key), emptyMap()))
            val env = "ZENY_SEED_" + key.uppercase().replace('-', '_')
            assertEquals("Falta --$key (ou $env).", message)
        }
    }

    @Test
    fun adminNewPasswordOptional() {
        assertEquals(null, ok(SeedArgs.parse(asArgs(full), emptyMap())).adminNewPassword)
        val c = ok(SeedArgs.parse(asArgs(full) + listOf("--admin-new-password", "$pw-n"), emptyMap()))
        assertEquals("$pw-n", c.adminNewPassword?.value)
    }

    @Test
    fun shortPasswordRejected() {
        val message = invalid(SeedArgs.parse(asArgs(full + ("manager-password" to "a".repeat(11))), emptyMap()))
        assertTrue("--manager-password" in message)
        assertIs<ParseResult.Ok>(SeedArgs.parse(asArgs(full + ("manager-password" to "a".repeat(12))), emptyMap()))
    }

    @Test
    fun longPasswordRejected() {
        val tooLong = SeedArgs.parse(asArgs(full + ("manager-password" to "a".repeat(129))), emptyMap())
        assertIs<ParseResult.Invalid>(tooLong)
        assertIs<ParseResult.Ok>(SeedArgs.parse(asArgs(full + ("manager-password" to "a".repeat(128))), emptyMap()))
    }

    @Test
    fun pinMustBe4to8Digits() {
        for (bad in listOf("123", "123456789", "12a4")) {
            val message = invalid(SeedArgs.parse(asArgs(full + ("manager-pin" to bad)), emptyMap()))
            assertTrue("--manager-pin" in message)
        }
        assertIs<ParseResult.Ok>(SeedArgs.parse(asArgs(full + ("manager-pin" to "12345678")), emptyMap()))
    }

    @Test
    fun serverWithPathRejected() {
        val message = invalid(SeedArgs.parse(asArgs(full + ("server" to "http://localhost:8080/api")), emptyMap()))
        assertEquals("Informe só o endereço e a porta, sem caminho.", message)
        assertIs<ParseResult.Invalid>(SeedArgs.parse(asArgs(full + ("server" to "ftp://x")), emptyMap()))
    }

    @Test
    fun unknownOptionRejected() {
        assertTrue("--nada" in invalid(SeedArgs.parse(asArgs(full) + "--nada", emptyMap())))
        assertTrue("--server" in invalid(SeedArgs.parse(listOf("--server"), emptyMap())))
    }

    @Test
    fun invalidMessagesNeverEchoSecrets() {
        val secret = "SENHA-SECRETA-123"
        val cases =
            listOf(
                "admin-password" to secret.take(11),
                "admin-new-password" to secret.take(11),
                "manager-password" to secret.take(11),
                "receptionist-password" to secret.take(11),
                "housekeeper-password" to secret.take(11),
                "manager-password" to secret.repeat(10),
                "manager-pin" to secret,
            )
        for ((key, value) in cases) {
            val planted = full + (key to value)
            val fromArgs = SeedArgs.parse(asArgs(planted), emptyMap())
            val fromEnv = SeedArgs.parse(emptyList(), asEnv(planted))
            for (r in listOf(fromArgs, fromEnv)) {
                val message = invalid(r)
                assertFalse(value in message, "eco de $key")
                assertFalse("SENHA-SECRETA" in message, "eco de $key")
            }
        }
        val unknown = invalid(SeedArgs.parse(listOf("--$secret=x"), emptyMap()))
        assertTrue(unknown.isNotBlank())
    }

    @Test
    fun secretToStringMasks() {
        assertEquals("***", Secret("x").toString())
        val c = ok(SeedArgs.parse(asArgs(full + ("admin-new-password" to "$pw-n")), emptyMap()))
        val text = c.toString()
        for (v in listOf("$pw-a", "$pw-m", "$pw-r", "$pw-h", "$pw-n", "1234")) assertFalse(v in text)
    }
}
