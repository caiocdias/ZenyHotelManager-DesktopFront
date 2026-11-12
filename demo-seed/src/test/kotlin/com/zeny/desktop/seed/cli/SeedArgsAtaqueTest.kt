package com.zeny.desktop.seed.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeedArgsAtaqueTest {
    private val secret = "SEGREDO-ULTRA-987"
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

    private fun parse(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ) = SeedArgs.parse(args, env)

    private fun ok(r: ParseResult) = assertIs<ParseResult.Ok>(r).config

    private fun message(r: ParseResult) = assertIs<ParseResult.Invalid>(r).message

    @Test
    fun typoedOptionNameWithGluedValueDoesNotEchoValue() {
        val m = message(parse(asArgs(full - "admin-password") + "--admin-pasword=$secret"))
        assertFalse(secret in m, m)
        assertTrue("--admin-pasword" in m, m)
    }

    @Test
    fun typoedOptionNameWithSeparateValueDoesNotEchoValue() {
        val m = message(parse(listOf("--admin-pasword", secret) + asArgs(full - "admin-password")))
        assertFalse(secret in m, m)
    }

    @Test
    fun passwordWithUnquotedSpacesDoesNotLeakItsWordsInMessage() {
        // Operador esquece as aspas: "--admin-password minha senha SEGREDO" vira argumentos soltos.
        val m = message(parse(listOf("--admin-password", "minha", secret, "outra") + asArgs(full - "admin-password")))
        assertFalse(secret in m, m)
        assertFalse("outra" in m, m)
    }

    @Test
    fun positionalArgumentIsNotEchoedInMessage() {
        val m = message(parse(asArgs(full) + secret))
        assertFalse(secret in m, m)
    }

    @Test
    fun optionNameCarryingEqualsAndSecretIsCutAtEquals() {
        val m = message(parse(listOf("--x=$secret")))
        assertFalse(secret in m, m)
    }

    @Test
    fun invalidMessageForEveryBadFieldNeverContainsAnyPlantedSecret() {
        val bad = mapOf("server" to "http://u:$secret@h:1", "manager-pin" to "$secret")
        for ((k, v) in bad) {
            val m = message(parse(asArgs(full + (k to v))))
            assertFalse(secret in m, "eco em $k: $m")
        }
        for (k in listOf("admin-password", "manager-password", "receptionist-password", "housekeeper-password")) {
            val tooLong = secret.repeat(10)
            val m = message(parse(asArgs(full + (k to tooLong))))
            assertFalse(secret in m, "eco em $k: $m")
        }
    }

    @Test
    fun optionValueThatLooksLikeAnotherOptionIsNotSwallowedAsPassword() {
        // "--manager-password --manager-pin" (PIN vindo do ambiente): "--manager-pin" (13 chars) virava a senha.
        val args = asArgs(full - "manager-password" - "manager-pin") + listOf("--manager-password", "--manager-pin")
        val r = parse(args, mapOf("ZENY_SEED_MANAGER_PIN" to "1234"))
        assertIs<ParseResult.Invalid>(r, "aceitou '--manager-pin' como senha do gerente: $r")
    }

    @Test
    fun valueMissingBeforeAnotherOptionIsReportedAsMissingValue() {
        val m = message(parse(listOf("--server", "--admin-login", "admin")))
        assertTrue("--server" in m, m)
    }

    @Test
    fun emptyEqualsValueForRequiredLoginIsRejected() {
        val r = parse(asArgs(full - "admin-login") + "--admin-login=")
        assertIs<ParseResult.Invalid>(r, "aceitou login vazio")
    }

    @Test
    fun emptyEnvLoginIsRejected() {
        val r = parse(emptyList(), asEnv(full + ("admin-login" to "")))
        assertIs<ParseResult.Invalid>(r, "aceitou login vazio do ambiente")
    }

    @Test
    fun blankLoginIsRejected() {
        assertIs<ParseResult.Invalid>(parse(asArgs(full + ("admin-login" to "   "))), "aceitou login só com espaços")
    }

    @Test
    fun emptyEqualsServerIsInvalidAndEmptyPasswordIsInvalid() {
        assertIs<ParseResult.Invalid>(parse(asArgs(full - "server") + "--server="))
        assertIs<ParseResult.Invalid>(parse(asArgs(full - "manager-password") + "--manager-password="))
        assertIs<ParseResult.Invalid>(parse(asArgs(full - "manager-pin") + "--manager-pin="))
    }

    @Test
    fun emptyArgDoesNotFallBackToEnv() {
        val r = parse(listOf("--manager-password="), asEnv(full))
        assertIs<ParseResult.Invalid>(r)
    }

    @Test
    fun argumentWinsOverEnvForEverySecretAndForServer() {
        val envMap = asEnv(full + ("admin-new-password" to "$pw-envn"))
        val argsMap =
            full.mapValues { if (it.key == "manager-pin") "5678" else "${it.value}-arg" } +
                ("admin-new-password" to "$pw-argn")
        val c = ok(parse(asArgs(argsMap - "server") + "--server=http://arg:9", envMap))
        assertEquals("http://arg:9", c.server.value)
        assertEquals("$pw-a-arg", c.adminPassword.value)
        assertEquals("$pw-argn", c.adminNewPassword?.value)
        assertEquals("$pw-m-arg", c.managerPassword.value)
        assertEquals("$pw-r-arg", c.receptionistPassword.value)
        assertEquals("$pw-h-arg", c.housekeeperPassword.value)
        assertEquals("5678", c.managerPin.value)
    }

    @Test
    fun invalidArgIsNotRescuedByValidEnv() {
        val r = parse(listOf("--manager-password", "curta"), asEnv(full))
        assertIs<ParseResult.Invalid>(r)
    }

    @Test
    fun passwordBoundariesAreExactlyTwelveAndOneHundredTwentyEight() {
        for ((len, expectOk) in listOf(0 to false, 11 to false, 12 to true, 128 to true, 129 to false)) {
            val r = parse(asArgs(full + ("admin-new-password" to "n".repeat(len))))
            assertEquals(expectOk, r is ParseResult.Ok, "admin-new-password len=$len")
        }
    }

    @Test
    fun passwordLengthCountsCharactersNotBytes() {
        // 12 caracteres multibyte (36 bytes em UTF-8) valem; 11 não.
        assertIs<ParseResult.Ok>(parse(asArgs(full + ("manager-password" to "ç".repeat(12)))))
        assertIs<ParseResult.Invalid>(parse(asArgs(full + ("manager-password" to "ç".repeat(11)))))
        assertIs<ParseResult.Ok>(parse(asArgs(full + ("manager-password" to "ç".repeat(128)))))
        assertIs<ParseResult.Invalid>(parse(asArgs(full + ("manager-password" to "ç".repeat(129)))))
    }

    @Test
    fun passwordKeepsSpacesAndLeadingDashesUntrimmed() {
        val value = " espaço no meio e fim "
        assertEquals(value, ok(parse(asArgs(full + ("manager-password" to value)))).managerPassword.value)
        val dashed = "--abcdefghijkl"
        val c = ok(parse(listOf("--manager-password=$dashed") + asArgs(full - "manager-password")))
        assertEquals(dashed, c.managerPassword.value)
    }

    @Test
    fun passwordContainingEqualsSignIsKeptWhole() {
        val value = "abc=def=ghi=jkl"
        val c = ok(parse(listOf("--manager-password=$value") + asArgs(full - "manager-password")))
        assertEquals(value, c.managerPassword.value)
    }

    @Test
    fun pinBoundariesAndNonAsciiDigits() {
        val cases = listOf("" to false, "123" to false, "1234" to true, "12345678" to true, "123456789" to false)
        for ((pin, expectOk) in cases) {
            assertEquals(expectOk, parse(asArgs(full + ("manager-pin" to pin))) is ParseResult.Ok, "pin='$pin'")
        }
        for (bad in listOf("١٢٣٤", "12 34", "-123", "1.23", "１２３４", "1234\n")) {
            assertIs<ParseResult.Invalid>(parse(asArgs(full + ("manager-pin" to bad))), "aceitou pin '$bad'")
        }
    }

    @Test
    fun serverAddressVariants() {
        fun server(v: String) = parse(asArgs(full + ("server" to v)))
        assertEquals("http://h:8080", ok(server("http://h:8080/")).server.value)
        assertEquals("http://h:8080", ok(server("  http://h:8080  ")).server.value)
        assertEquals("https://h", ok(server("https://h")).server.value)
        assertEquals("Informe só o endereço e a porta, sem caminho.", message(server("http://h:8080/api/v1")))
        assertEquals("Informe só o endereço e a porta, sem caminho.", message(server("http://h:8080/x/")))
        val bads =
            listOf(
                "http://h:8080?x=1",
                "http://h:8080/?x=1",
                "http://h:8080#f",
                "http://u:p@h:8080",
                "ftp://h",
                "h:8080",
                "localhost",
                "http://",
                "http://h ost:80",
                "http://h:99999",
                "file:///etc",
            )
        for (bad in bads) {
            assertIs<ParseResult.Invalid>(server(bad), "aceitou servidor '$bad'")
        }
    }

    @Test
    fun serverWithUserinfoDoesNotEchoCredentials() {
        val m = message(parse(asArgs(full + ("server" to "http://admin:$secret@h:8080"))))
        assertFalse(secret in m, m)
        assertFalse("admin:" in m, m)
    }

    @Test
    fun repeatedOptionDoesNotLeakAndYieldsAValidDecision() {
        val r = parse(asArgs(full) + listOf("--manager-password", "curta"))
        assertIs<ParseResult.Invalid>(r, "última ocorrência (inválida) deve valer ou ser recusada")
        val r2 = parse(listOf("--manager-password", "curta") + asArgs(full))
        assertIs<ParseResult.Ok>(r2)
        assertEquals("$pw-m", ok(r2).managerPassword.value)
    }

    @Test
    fun helpInAnyPositionWins() {
        assertEquals(ParseResult.Help, parse(listOf("--help") + asArgs(full)))
        assertEquals(ParseResult.Help, parse(asArgs(full) + "-h"))
        assertEquals(ParseResult.Help, parse(listOf("--nada", "--help")))
        assertEquals(ParseResult.Help, parse(listOf("--server", "--help")))
        assertEquals(ParseResult.Help, parse(listOf("--manager-pin", "1", "-h"), asEnv(full)))
        assertEquals(ParseResult.Help, parse(emptyList<String>() + "--help", emptyMap()))
    }

    @Test
    fun helpIsNotTriggeredByLookalikes() {
        assertIs<ParseResult.Invalid>(parse(listOf("--help=1")))
        assertIs<ParseResult.Invalid>(parse(listOf("--HELP")))
        assertIs<ParseResult.Invalid>(parse(listOf("-help")))
    }

    @Test
    fun noArgumentsAndNoEnvIsInvalidNamingTheFirstMissing() {
        assertEquals("Falta --server (ou ZENY_SEED_SERVER).", message(parse(emptyList())))
    }

    @Test
    fun unrelatedEnvVariablesAreIgnored() {
        val env = mapOf("ZENY_SEED_NADA" to "x", "ZENY_LIVE_URL" to "http://x", "zeny_seed_server" to "http://y")
        val r = parse(asArgs(full), env)
        assertEquals("http://localhost:8080", ok(r).server.value)
    }

    @Test
    fun usageRecommendsEnvironmentVariablesForSecrets() {
        val u = SeedArgs.usage
        val needed =
            listOf(
                "ZENY_SEED_SERVER",
                "ZENY_SEED_ADMIN_PASSWORD",
                "ZENY_SEED_ADMIN_NEW_PASSWORD",
                "ZENY_SEED_MANAGER_PIN",
            )
        for (v in needed + "--help") {
            assertTrue(v in u, v)
        }
        assertTrue("variáveis de ambiente" in u)
    }

    @Test
    fun configAndOkToStringNeverExposeAnySecret() {
        val args = asArgs(full + ("admin-new-password" to "$pw-n"))
        val r = parse(args)
        val text = r.toString() + ok(r).toString() + ok(r).managerPin + "${ok(r).adminNewPassword}"
        for (v in listOf("$pw-a", "$pw-m", "$pw-r", "$pw-h", "$pw-n", "1234")) assertFalse(v in text, v)
    }

    @Test
    fun secretEqualityIsByValueAndNeverEqualToPlainString() {
        assertEquals(Secret("abc"), Secret("abc"))
        assertEquals(Secret("abc").hashCode(), Secret("abc").hashCode())
        assertFalse(Secret("abc") == Secret("abd"))
        assertFalse(Secret("abc").equals("abc"))
        assertEquals("***", Secret("").toString())
        assertEquals("***", "${Secret(secret)}")
    }
}
