package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ATK_FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f11"
private const val ATK_COMPANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c11"
private const val ATK_ETAG = "\"3\""
private const val ATK_CNPJ = "12345678000199"
private const val ATK_CNPJ_MASKED = "12.345.678/0001-99"

/**
 * Ataque do testador de etapa (M4 E12) às fachadas do pagador e do limite: ids que não são UUID antes da rede, limite
 * zero aceito e enviado como zero, o CNPJ (cru ou com máscara) fora da `CompanyChoice` por construção, o nome fantasia
 * em branco e o cursor em branco.
 */
class FolioAccountFacadeAttackTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    private fun folio(limit: String = "0") =
        """{"balance":"0.00","creditLimitExceeded":false,"id":"$ATK_FOLIO","number":"F-1",
            "openedAt":"2026-11-18T10:00:00Z","openedOn":"2026-11-18",
            "owner":{"id":"$ATK_FOLIO","type":"RESERVATION"},"status":"OPEN",
            "updatedAt":"2026-11-18T10:00:00Z","creditLimit":"$limit"}"""

    private fun company(
        cnpj: String,
        trade: String?,
    ): String {
        val tradeField = trade?.let { ""","tradeName":"$it"""" }.orEmpty()
        return """{"active":true,"cnpj":"$cnpj","createdAt":"2026-11-01T10:00:00Z","id":"$ATK_COMPANY",
            "legalName":"Razão Social Ltda","updatedAt":"2026-11-01T10:00:00Z"$tradeField}"""
    }

    @Test
    fun companyWithAnIdThatIsNotUuidFailsBeforeTheNetwork() =
        runTest {
            core { json(company(ATK_CNPJ, "Acme"), HttpStatusCode.OK) }.use { core ->
                for (id in listOf("não-é-uuid", "", " ", "../folios")) {
                    assertFailsWith<IllegalArgumentException>("id \"$id\"") { core.companies.company(id) }
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun updateWithAPayerThatIsNotUuidFailsBeforeTheNetwork() =
        runTest {
            core { json(folio(), HttpStatusCode.OK, "ETag" to ATK_ETAG) }.use { core ->
                assertFailsWith<IllegalArgumentException> {
                    core.folios.updateFolio(ATK_FOLIO, ATK_ETAG, "não-é-uuid", null)
                }
                assertFailsWith<IllegalArgumentException> {
                    core.folios.updateFolio("não-é-uuid", ATK_ETAG, null, null)
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun zeroLimitIsAcceptedAndSentAsZero() =
        runTest {
            core { json(folio("0.00"), HttpStatusCode.OK, "ETag" to "\"4\"") }.use { core ->
                val version = core.folios.updateFolio(ATK_FOLIO, ATK_ETAG, null, Money.parse("0.00"))
                assertEquals("\"4\"", version.etag)
                val body =
                    Json
                        .parseToJsonElement(
                            requests
                                .single()
                                .body
                                .toByteArray()
                                .decodeToString(),
                        ).jsonObject
                assertEquals("0.00", body.getValue("creditLimit").jsonPrimitive.content)
            }
        }

    @Test
    fun unknownCompanyRisesAsTheProblem() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "UNKNOWN_COMPANY") }.use { core ->
                val failure =
                    assertFailsWith<ApiError.Problem> {
                        core.folios.updateFolio(ATK_FOLIO, ATK_ETAG, ATK_COMPANY, null)
                    }
                assertEquals("UNKNOWN_COMPANY", failure.code)
                assertEquals("A empresa informada não existe.", failure.userMessage)
            }
        }

    /** A garantia de que o CNPJ não chega ao `app` é estrutural: a `CompanyChoice` só tem o id e o nome. */
    @Test
    fun companyChoiceHoldsOnlyTheIdAndTheName() {
        val fields =
            CompanyChoice::class.java.declaredFields
                .filterNot {
                    java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                }.map { it.name }
                .toSet()
        assertEquals(setOf("id", "name"), fields)
    }

    @Test
    fun maskedCnpjFromTheServerNeverReachesTheChoice() =
        runTest {
            core { json(company(ATK_CNPJ_MASKED, "Acme"), HttpStatusCode.OK) }.use { core ->
                val choice = core.companies.company(ATK_COMPANY)
                assertEquals(CompanyChoice(ATK_COMPANY, "Acme"), choice)
                assertFalse(ATK_CNPJ_MASKED in choice.toString())
                assertFalse("0001" in choice.toString())
            }
        }

    @Test
    fun blankTradeNameFallsBackToTheLegalName() =
        runTest {
            core { json(company(ATK_CNPJ, "  "), HttpStatusCode.OK) }.use { core ->
                assertEquals("Razão Social Ltda", core.companies.company(ATK_COMPANY).name)
            }
        }

    @Test
    fun blankNextCursorEndsTheList() =
        runTest {
            val page = """{"items":[${company(ATK_CNPJ, "Acme")}],"nextCursor":"  "}"""
            core { json(page, HttpStatusCode.OK) }.use { core ->
                assertNull(core.companies.activeCompanyPage("Ac", null).nextCursor)
            }
        }
}
