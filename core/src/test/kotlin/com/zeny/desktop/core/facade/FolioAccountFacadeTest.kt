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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val COMPANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val COMPANY_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ETAG = "\"9\""
private const val CNPJ = "12345678000199"

/** A leitura da conta com o `ETag`, o `PUT` do pagador e do limite e a leitura das empresas. */
class FolioAccountFacadeTest {
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

    private fun folio(
        payer: String? = COMPANY,
        limit: String? = "500.00",
    ): String {
        val payerField = payer?.let { ""","payerCompanyId":"$it"""" }.orEmpty()
        val limitField = limit?.let { ""","creditLimit":"$it"""" }.orEmpty()
        return """{"balance":"0.00","creditLimitExceeded":false,"id":"$FOLIO","number":"F-1",
            "openedAt":"2026-11-18T10:00:00Z","openedOn":"2026-11-18",
            "owner":{"id":"$FOLIO","type":"RESERVATION"},"status":"OPEN",
            "updatedAt":"2026-11-18T10:00:00Z"$payerField$limitField}"""
    }

    private fun company(
        id: String = COMPANY,
        legal: String = "Acme Comércio Ltda",
        trade: String? = "Acme",
    ): String {
        val tradeField = trade?.let { ""","tradeName":"$it"""" }.orEmpty()
        return """{"active":true,"cnpj":"$CNPJ","createdAt":"2026-11-01T10:00:00Z","id":"$id","legalName":"$legal",
            "updatedAt":"2026-11-01T10:00:00Z"$tradeField}"""
    }

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    @Test
    fun versionedFolioKeepsTheEtag() =
        runTest {
            core { json(folio(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                val version = core.folios.versionedFolio(FOLIO)
                assertEquals(ETAG, version.etag)
                assertEquals(FOLIO, version.folio.id.toString())
            }
        }

    @Test
    fun missingEtagIsIncompatible() =
        runTest {
            core { json(folio(), HttpStatusCode.OK) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.folios.versionedFolio(FOLIO) }
            }
        }

    @Test
    fun updateSendsIfMatchBothFieldsAndNoIdempotencyKey() =
        runTest {
            core { json(folio(COMPANY_2, "10.50"), HttpStatusCode.OK, "ETag" to "\"10\"") }.use { core ->
                val version = core.folios.updateFolio(FOLIO, ETAG, COMPANY_2, Money.parse("10.50"))
                assertEquals("\"10\"", version.etag)
                val request = requests.single()
                assertEquals(HttpMethod.Put, request.method)
                assertEquals("/api/v1/folios/$FOLIO", request.url.encodedPath)
                assertEquals(ETAG, request.headers["If-Match"])
                assertNull(request.headers["Idempotency-Key"])
                val body = bodyOf(request)
                assertEquals(COMPANY_2, body.getValue("payerCompanyId").jsonPrimitive.content)
                assertEquals("10.50", body.getValue("creditLimit").jsonPrimitive.content)
            }
        }

    @Test
    fun nullLimitAndNullPayerAreSentAsNull() =
        runTest {
            core { json(folio(null, null), HttpStatusCode.OK, "ETag" to "\"10\"") }.use { core ->
                core.folios.updateFolio(FOLIO, ETAG, null, null)
                val body = bodyOf(requests.single())
                assertEquals(JsonNull, body.getValue("payerCompanyId"))
                assertEquals(JsonNull, body.getValue("creditLimit"))
            }
        }

    @Test
    fun preconditionFailedRises() =
        runTest {
            core { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED") }.use { core ->
                assertFailsWith<ApiError.PreconditionFailed> { core.folios.updateFolio(FOLIO, ETAG, null, null) }
            }
        }

    @Test
    fun blankEtagOrNegativeLimitFailBeforeTheNetwork() =
        runTest {
            core { json(folio(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                assertFailsWith<IllegalArgumentException> { core.folios.updateFolio(FOLIO, " ", null, null) }
                assertFailsWith<IllegalArgumentException> {
                    core.folios.updateFolio(FOLIO, ETAG, null, Money.parse("-1.00"))
                }
                assertTrue(requests.isEmpty())
            }
        }

    private fun page(vararg items: String) = """{"items":[${items.joinToString(",")}],"nextCursor":"c-2"}"""

    @Test
    fun activeCompaniesSendActiveTrueAndName() =
        runTest {
            core { json(page(company()), HttpStatusCode.OK) }.use { core ->
                val result = core.companies.activeCompanyPage("Ac", "c-1", 20)
                assertEquals("c-2", result.nextCursor)
                val query = requests.single().url.parameters
                assertEquals("true", query["active"])
                assertEquals("Ac", query["name"])
                assertEquals("c-1", query["cursor"])
                assertEquals("20", query["limit"])
            }
        }

    @Test
    fun shortNameIsNotSent() =
        runTest {
            core { json(page(company()), HttpStatusCode.OK) }.use { core ->
                core.companies.activeCompanyPage("A", null)
                val query = requests.single().url.parameters
                assertFalse(query.contains("name"))
            }
        }

    @Test
    fun companyChoiceUsesTradeNameThenLegalNameAndNoCnpj() =
        runTest {
            val body = page(company(COMPANY, trade = "Acme"), company(COMPANY_2, "Beta Ltda", null))
            core { json(body, HttpStatusCode.OK) }.use { core ->
                val page = core.companies.activeCompanyPage(null, null)
                val items = page.items
                assertEquals(listOf("Acme", "Beta Ltda"), items.map { it.name })
                assertFalse(items.any { CNPJ in it.toString() })
            }
        }

    @Test
    fun companyByIdGivesTheChoice() =
        runTest {
            core { json(company(), HttpStatusCode.OK) }.use { core ->
                val choice = core.companies.company(COMPANY)
                assertEquals(CompanyChoice(COMPANY, "Acme"), choice)
                assertEquals("/api/v1/companies/$COMPANY", requests.single().url.encodedPath)
            }
        }
}
