package com.zeny.desktop.seed.sdk

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ataque da Task 2 (E05): a verificação ao vivo é pulada sem os dois opt-ins e nunca imprime a credencial.
 * Lê o fonte, porque rodá-la contra o servidor está fora do escopo (ela só pode ser pulada aqui).
 */
class LiveSeedReservationsCheckAtaqueTest {
    private val source: String =
        listOf(
            "src/test/kotlin/com/zeny/desktop/seed/sdk/LiveSeedReservationsCheck.kt",
            "demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/LiveSeedReservationsCheck.kt",
        ).map(::File).first { it.exists() }.readText()

    private val testBodies =
        source.split("@Test").drop(1).map { it.substringAfter("{").substringBefore("\n    @").trim() }

    @Test
    fun everyTestChecksTheOptInBeforeAnyWork() {
        assertEquals(3, testBodies.size)
        testBodies.forEach { assertTrue(it.startsWith("optedIn()"), it.take(80)) }
    }

    @Test
    fun optInNeedsBothVariables() {
        val optIn = source.substringAfter("private fun optedIn()").substringBefore("private fun config")
        assertTrue("ZENY_SEED_SERVER\")!=null" in optIn.replace(" ", ""), optIn)
        assertTrue("&&" in optIn, optIn)
        assertTrue("ZENY_SEED_LIVE_RESERVATIONS\")==\"1\"" in optIn.replace(" ", ""), optIn)
    }

    @Test
    fun noPrintingAndNoCredentialInMessages() {
        listOf(
            "println",
            "print(",
            "System.out",
            "System.err",
            "adminPassword",
            "adminNewPassword",
            "managerPin",
            "Secret(",
        ).forEach { assertFalse(it in source, "o fonte da verificação ao vivo usa $it") }
        assertFalse(
            Regex("""(assert\w*|fail)\([^)]*config""").containsMatchIn(source),
            "config em mensagem de asserção",
        )
    }

    @Test
    fun checkOnlyReads() {
        listOf("createBooking", "cancelReservation", "assignReservationRoom", "create", "update", "delete").forEach {
            assertFalse(Regex("""\.$it\w*\(""").containsMatchIn(source), "escrita no servidor: $it")
        }
    }
}
