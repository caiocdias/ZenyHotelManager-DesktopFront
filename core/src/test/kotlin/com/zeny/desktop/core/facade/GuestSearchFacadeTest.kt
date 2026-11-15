package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GUEST_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val DOC_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val NAME = "Fulana Fictícia de Teste"
private const val CPF = "529.982.247-25"
private const val PASSPORT_NUMBER = "FX1234567"

/** Busca e cadastro mínimo de hóspede contra o `MockEngine`; só dados fictícios. */
class GuestSearchFacadeTest {
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

    private fun guest(status: String = "ACTIVE") =
        """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC_ID",
        "issuingCountry":"BR","maskedNumber":"***.982.247-**","type":"CPF"}],"fullName":"$NAME",
        "hasSensitiveData":false,"id":"$GUEST_ID","preferences":{},"status":"$status",
        "updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    private fun page(status: String = "ACTIVE") = """{"items":[${guest(status)}]}"""

    private fun bodyOf(request: HttpRequestData): JsonObject {
        val text =
            when (val body = request.body) {
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                else -> error("corpo inesperado: ${body::class}")
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun newGuest(
        type: String = "CPF",
        number: String = CPF,
        country: String? = null,
    ) = NewGuest(NAME, type, number, country, "BR", BusinessDate.parse("1990-05-17"))

    @Test
    fun nameSearchSendsNameAndLimit() =
        runTest {
            core { json(page()) }.use { core ->
                val found = core.guests.searchByName("  Fulana  ", 7)
                assertEquals(NAME, found.single().fullName)
            }
            val url = requests.single().url
            assertEquals(HttpMethod.Get, requests.single().method)
            assertEquals("/api/v1/guests", url.encodedPath)
            assertEquals("Fulana", url.parameters["name"])
            assertEquals("7", url.parameters["limit"])
        }

    @Test
    fun documentSearchKeepsNumberOutOfUrl() =
        runTest {
            core { json(page()) }.use { core ->
                val found = core.guests.searchByDocument("CPF", CPF, null)
                assertEquals(GUEST_ID, found.single().id.toString())
            }
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/guests/search-by-document", request.url.encodedPath)
            assertFalse(CPF in request.url.toString())
            assertFalse("529" in request.url.toString())
            val body = bodyOf(request)
            assertEquals(CPF, (body["number"] as JsonPrimitive).content)
            assertEquals("CPF", (body["type"] as JsonPrimitive).content)
        }

    @Test
    fun createSendsMinimalProfile() =
        runTest {
            core { json(guest(), HttpStatusCode.Created) }.use { core ->
                core.guests.create(newGuest())
                core.guests.create(newGuest("PASSPORT", PASSPORT_NUMBER, "AR"))
            }
            val cpf = bodyOf(requests[0])
            val profile = cpf["profile"]!!.jsonObject
            assertEquals(NAME, (profile["fullName"] as JsonPrimitive).content)
            assertEquals("false", (profile["vip"] as JsonPrimitive).content)
            assertEquals("BR", (profile["nationality"] as JsonPrimitive).content)
            assertEquals("1990-05-17", (profile["birthDate"] as JsonPrimitive).content)
            assertNull(cpf["consents"])
            assertNull(cpf["sensitiveData"])
            val cpfDoc = cpf["documents"]!!.jsonArray.single().jsonObject
            assertEquals(CPF, (cpfDoc["number"] as JsonPrimitive).content)
            assertEquals("CPF", (cpfDoc["type"] as JsonPrimitive).content)
            assertFalse("issuingCountry" in cpfDoc)

            val passportDoc = bodyOf(requests[1])["documents"]!!.jsonArray.single().jsonObject
            assertEquals("PASSPORT", (passportDoc["type"] as JsonPrimitive).content)
            assertEquals("AR", (passportDoc["issuingCountry"] as JsonPrimitive).content)
        }

    @Test
    fun createInvalidDocumentCarriesFieldError() =
        runTest {
            val body =
                """{"code":"DOCUMENT_NUMBER_INVALID","detail":"detalhe","errors":[{"field":"documents[0].number",
                "code":"DOCUMENT_NUMBER_INVALID","message":"Documento inválido."}]}"""
            core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.guests.create(newGuest()) }
                assertEquals(400, error.status)
                assertEquals("DOCUMENT_NUMBER_INVALID", error.code)
                assertEquals("documents[0].number", error.fieldErrors.single().field)
            }
        }

    @Test
    fun nameTooBroadIsProblem() =
        runTest {
            core { problem(HttpStatusCode.BadRequest, "GUEST_NAME_FILTER_TOO_BROAD") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.guests.searchByName("a", 10) }
                assertEquals(400, error.status)
                assertEquals("GUEST_NAME_FILTER_TOO_BROAD", error.code)
            }
        }

    @Test
    fun newGuestToStringHidesData() {
        val text = newGuest().toString()
        assertEquals("NewGuest(***)", text)
        assertFalse(NAME in text || CPF in text)
    }

    @Test
    fun incompatibleBodyHidesData() =
        runTest {
            core { json(page("QUARANTINE")) }.use { core ->
                val failures =
                    listOf<suspend () -> Unit>(
                        { core.guests.searchByName("Fulana", 5) },
                        { core.guests.searchByDocument("CPF", CPF, null) },
                        { core.guests.create(newGuest()) },
                    )
                for (call in failures) {
                    val error = assertFailsWith<ApiError.IncompatibleResponse> { call() }
                    val chain = generateSequence(error as Throwable) { it.cause }.toList()
                    assertTrue(
                        chain.none {
                            val text = "$it ${it.message.orEmpty()}"
                            NAME in text || CPF in text || "QUARANTINE" in text
                        },
                    )
                }
            }
            assertNotNull(requests.firstOrNull())
        }
}
