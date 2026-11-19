package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val FOLIO_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val FOLIO_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"

/** Ataque ao devido do check-out (`409 FOLIO_BALANCE_NOT_ZERO`): as extensões malformadas fora dos testes da fatia. */
class CheckOutFacadeAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings(), MockEngine { handler(it) })

    private fun balanceProblem(extensions: String) =
        """{"code":"FOLIO_BALANCE_NOT_ZERO","detail":"saldo","status":409$extensions}"""

    private val folioA = """{"folioId":"$FOLIO_A","number":"F-1","status":"OPEN","balance":"150.00"}"""

    private suspend fun problemFor(extensions: String): ApiError.Problem =
        core { json(balanceProblem(extensions), HttpStatusCode.Conflict) }.use { core ->
            assertFailsWith<ApiError.Problem>(extensions) { core.checkOut.checkOut(RES, null, Intent()) }
        }

    @Test
    fun oneMalformedFolioAmongValidOnesInvalidatesTheWholeDue() =
        runTest {
            val bad = """{"folioId":"$FOLIO_B","number":"F-2","status":"OPEN","balance":"-20,00"}"""

            val error = problemFor(""","folios":[$folioA,$bad],"total":"130.00"""")

            assertEquals("FOLIO_BALANCE_NOT_ZERO", error.code, "nenhuma conta omitida em silêncio")
        }

    @Test
    fun nonObjectOrNullFolioItemsFallBackToTheProblem() =
        runTest {
            for (items in listOf("[1]", "[null]", """["F-1"]""", "[$folioA,[]]", "null", "\"F-1\"")) {
                assertEquals("FOLIO_BALANCE_NOT_ZERO", problemFor(""","folios":$items,"total":"150.00"""").code)
            }
        }

    @Test
    fun amountsOutsideTheDecimalStringFormatFallBackToTheProblem() =
        runTest {
            for (balance in listOf("150", "150.001", "1e2", "\"\"", "\"R$ 150,00\"", "null", "\"150.00 \"")) {
                val item = """{"folioId":"$FOLIO_A","number":"F-1","status":"OPEN","balance":$balance}"""
                assertEquals(
                    "FOLIO_BALANCE_NOT_ZERO",
                    problemFor(""","folios":[$item],"total":"150.00"""").code,
                    balance,
                )
            }
            for (total in listOf("null", "\"\"", "\"150.001\"", "{}", "[]")) {
                assertEquals("FOLIO_BALANCE_NOT_ZERO", problemFor(""","folios":[$folioA],"total":$total""").code, total)
            }
        }

    @Test
    fun blankOrMissingNumberFallsBackToTheProblem() =
        runTest {
            for (number in listOf("\"\"", "\"   \"", "null", "1")) {
                val item = """{"folioId":"$FOLIO_A","number":$number,"status":"OPEN","balance":"150.00"}"""
                assertEquals("FOLIO_BALANCE_NOT_ZERO", problemFor(""","folios":[$item],"total":"150.00"""").code)
            }
        }

    @Test
    fun theFallbackProblemCarriesNoExtensionValueInMessageNorToString() =
        runTest {
            val item = """{"folioId":"$FOLIO_A","number":"F-777","balance":"987,65"}"""
            val error = problemFor(""","folios":[$item],"total":"987.65"""")

            val text = error.toString() + " " + error.message + " " + error.userMessage
            for (leak in listOf("987", "F-777", FOLIO_A)) assertFalse(leak in text, leak)
        }

    @Test
    fun validDueKeepsTheServerOrderAndSigns() =
        runTest {
            val credit = """{"folioId":"$FOLIO_B","number":"F-2","status":"OPEN","balance":"-20.00"}"""
            val extensions = ""","folios":[$credit,$folioA],"total":"130.00","futuro":{"x":1}"""
            val result =
                core { json(balanceProblem(extensions), HttpStatusCode.Conflict) }.use { core ->
                    core.checkOut.checkOut(RES, null, Intent())
                }

            val due = assertIs<CheckOutResult.BalanceDue>(result)
            assertEquals(listOf("F-2", "F-1"), due.folios.map { it.number })
            assertEquals(listOf("-20.00", "150.00"), due.folios.map { it.balance.toApi() })
            assertEquals("130.00", due.total.toApi(), "o total do servidor, sem recalcular")
        }
}
