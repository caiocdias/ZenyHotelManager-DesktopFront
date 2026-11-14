package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val G1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val G2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b02"
private const val D1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b11"
private const val PERSON = "Beltrano Ficticio Atacado"
private const val CPF_NUMBER = "529.982.247-25"
private const val CPF_DIGITS = "52998224725"

/** Ataques à busca e ao cadastro de hóspede: vazamento de dado pessoal, corpo, erros do contrato e tipos. */
class GuestSearchFacadeAtaqueTest {
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

    private fun guest(
        id: String,
        docId: String = D1,
    ) = """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$docId",
        "issuingCountry":"BR","maskedNumber":"***.982.247-**","type":"CPF"}],"fullName":"$PERSON",
        "hasSensitiveData":false,"id":"$id","preferences":{},"status":"ACTIVE",
        "updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun newGuest(
        type: String = "CPF",
        number: String = CPF_NUMBER,
        country: String? = null,
        birth: String = "1990-05-17",
    ) = NewGuest(PERSON, type, number, country, "BR", BusinessDate.parse(birth))

    private fun leaks(text: String) = PERSON in text || CPF_NUMBER in text || CPF_DIGITS in text

    private fun chainLeaks(error: Throwable) =
        generateSequence(error) { it.cause }.any { leaks("$it ${it.message.orEmpty()}") }

    private fun assertNothingLeaksFromRequests(allowNumberInBody: Boolean = true) {
        for (request in requests) {
            assertFalse(leaks(request.url.toString()), "URL vazou dado pessoal")
            val headers = request.headers.entries().joinToString { "${it.key}=${it.value}" }
            assertFalse(leaks(headers), "header vazou dado pessoal")
        }
        assertTrue(allowNumberInBody)
    }

    @Test
    fun nameSearchTrimsAnyWhitespaceAndEncodesSpecialCharacters() =
        runTest {
            core { json("""{"items":[]}""") }.use { core ->
                core.guests.searchByName("\t José & Ma=ria #1 \n", 3)
            }
            assertEquals("José & Ma=ria #1", requests.single().url.parameters["name"])
        }

    @Test
    fun nameSearchWithEmptyResultReturnsEmptyList() =
        runTest {
            core { json("""{"items":[]}""") }.use { core ->
                assertTrue(core.guests.searchByName("Zzzzz", 10).isEmpty())
            }
        }

    @Test
    fun nameSearchKeepsNameOutOfHeadersAndSendsNoBody() =
        runTest {
            core { json("""{"items":[${guest(G1)}]}""") }.use { core ->
                core.guests.searchByName(PERSON, 10)
            }
            val headers =
                requests
                    .single()
                    .headers
                    .entries()
                    .joinToString { "${it.key}=${it.value}" }
            assertFalse(leaks(headers))
        }

    @Test
    fun documentSearchReturnsAllDuplicateProfilesInOrder() =
        runTest {
            core { json("""{"items":[${guest(G1)},${guest(G2, "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b12")}]}""") }
                .use { core ->
                    val found = core.guests.searchByDocument("CPF", CPF_NUMBER, null)
                    assertEquals(listOf(G1, G2), found.map { it.id.toString() })
                }
        }

    @Test
    fun documentSearchWithNoMatchReturnsEmptyList() =
        runTest {
            core { json("""{"items":[]}""") }.use { core ->
                assertTrue(core.guests.searchByDocument("CPF", CPF_NUMBER, null).isEmpty())
            }
        }

    @Test
    fun documentSearchKeepsNumberOutOfHeadersAndQuery() =
        runTest {
            core { json("""{"items":[]}""") }.use { core ->
                core.guests.searchByDocument("PASSPORT", CPF_NUMBER, "AR")
            }
            val request = requests.single()
            assertTrue(request.url.parameters.isEmpty(), "busca por documento não deve ter query")
            assertNothingLeaksFromRequests()
            val body = bodyOf(request)
            assertEquals("AR", body["issuingCountry"]!!.jsonPrimitive.content)
            assertEquals("PASSPORT", body["type"]!!.jsonPrimitive.content)
        }

    @Test
    fun documentSearchWithoutCountryOmitsTheField() =
        runTest {
            core { json("""{"items":[]}""") }.use { core ->
                core.guests.searchByDocument("CPF", CPF_NUMBER, null)
            }
            assertFalse("issuingCountry" in bodyOf(requests.single()))
        }

    @Test
    fun createOmitsIssuingCountryForBrazilianTypesAndKeepsItForForeignOnes() =
        runTest {
            core { json(guest(G1), HttpStatusCode.Created) }.use { core ->
                for (type in listOf("CPF", "RG", "CNH", "RNM")) core.guests.create(newGuest(type, "123456", "BR"))
                core.guests.create(newGuest("PASSPORT", "FX123", "AR"))
                core.guests.create(newGuest("FOREIGN_ID", "FX123", "UY"))
            }
            val docs = requests.map { bodyOf(it)["documents"]!!.jsonArray.single().jsonObject }
            for (i in 0..3) assertFalse("issuingCountry" in docs[i], "tipo ${docs[i]["type"]} não leva país")
            assertEquals("AR", docs[4]["issuingCountry"]!!.jsonPrimitive.content)
            assertEquals("UY", docs[5]["issuingCountry"]!!.jsonPrimitive.content)
        }

    @Test
    fun createSendsExactlyOneDocumentAndNoExtraSections() =
        runTest {
            core { json(guest(G1), HttpStatusCode.Created) }.use { core ->
                core.guests.create(newGuest(birth = "2008-02-29"))
            }
            val request = requests.single()
            assertEquals("/api/v1/guests", request.url.encodedPath)
            assertTrue(request.url.parameters.isEmpty())
            val body = bodyOf(request)
            assertEquals(setOf("documents", "profile"), body.keys)
            assertEquals("2008-02-29", body["profile"]!!.jsonObject["birthDate"]!!.jsonPrimitive.content)
            assertNothingLeaksFromRequests()
        }

    @Test
    fun createKeepsNameAndNumberOutOfUrlAndHeaders() =
        runTest {
            core { json(guest(G1), HttpStatusCode.Created) }.use { core -> core.guests.create(newGuest()) }
            assertNothingLeaksFromRequests()
        }

    @Test
    fun unknownDocumentTypeFailsBeforeNetworkWithoutLeaking() =
        runTest {
            core { json("""{"items":[]}""") }.use { core ->
                val calls =
                    listOf<suspend () -> Unit>(
                        { core.guests.searchByDocument("TITULO_ELEITOR", CPF_NUMBER, null) },
                        { core.guests.create(newGuest("TITULO_ELEITOR")) },
                        { core.guests.searchByDocument("cpf", CPF_NUMBER, null) },
                    )
                for (call in calls) {
                    val error = assertFailsWith<IllegalArgumentException> { call() }
                    assertFalse(chainLeaks(error), "mensagem: ${error.message}")
                    assertFalse("TITULO_ELEITOR" in error.message.orEmpty())
                }
            }
            assertTrue(requests.isEmpty(), "tipo inválido não pode ir à rede")
        }

    @Test
    fun forbiddenIsProblemWithoutLeaking() =
        runTest {
            val denied = """{"code":"ACCESS_DENIED","detail":"sem permissão"}"""
            core { json(denied, HttpStatusCode.Forbidden) }.use { core ->
                val calls =
                    listOf<suspend () -> Unit>(
                        { core.guests.searchByName("Fulana", 5) },
                        { core.guests.searchByDocument("CPF", CPF_NUMBER, null) },
                        { core.guests.create(newGuest()) },
                    )
                for (call in calls) {
                    val error = assertFailsWith<ApiError.Problem> { call() }
                    assertEquals(403, error.status)
                    assertEquals("ACCESS_DENIED", error.code)
                    assertFalse(chainLeaks(error))
                }
            }
        }

    @Test
    fun serverCodesBecomeProblemWithFieldErrors() =
        runTest {
            val cases =
                mapOf(
                    "GUEST_NAME_TOO_BROAD" to "name",
                    "DOCUMENT_NUMBER_INVALID" to "number",
                    "DOCUMENT_COUNTRY_INVALID" to "issuingCountry",
                )
            for ((code, field) in cases) {
                val body =
                    """{"code":"$code","detail":"d","errors":[{"field":"$field","code":"$code","message":"m"}]}"""
                core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                    val searches =
                        listOf<suspend () -> Unit>(
                            { core.guests.searchByName("a", 5) },
                            { core.guests.searchByDocument("PASSPORT", "X", "ZZ") },
                            { core.guests.create(newGuest()) },
                        )
                    for (call in searches) {
                        val error = assertFailsWith<ApiError.Problem> { call() }
                        assertEquals(code, error.code)
                        assertEquals(field, error.fieldErrors.single().field)
                        assertFalse("Problem" in error.userMessage || code in error.userMessage)
                    }
                }
            }
        }

    @Test
    fun errorResponseThatEchoesNumberAndNameDoesNotLeakIntoMessage() =
        runTest {
            val echo = "Documento $CPF_NUMBER de $PERSON"
            val body =
                """{"code":"CODIGO_NOVO_DO_SERVIDOR","detail":"$echo","errors":[{"field":"documents[0].number",
                "code":"DOCUMENT_NUMBER_INVALID","message":"$echo"}]}"""
            core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.guests.create(newGuest()) }
                assertFalse(leaks(error.userMessage), "userMessage: ${error.userMessage}")
                assertFalse(leaks(error.message.orEmpty()))
                assertFalse(leaks(error.toString()), "toString: $error")
            }
        }

    @Test
    fun knownCodeWithEchoingDetailDoesNotLeakIntoMessage() =
        runTest {
            val body = """{"code":"DOCUMENT_NUMBER_INVALID","detail":"Documento $CPF_NUMBER inválido"}"""
            core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.guests.searchByDocument("CPF", CPF_NUMBER, null) }
                assertFalse(leaks(error.userMessage))
                assertFalse(leaks(error.toString()))
            }
        }

    @Test
    fun incompatibleBodyThatEchoesNumberHidesItInEveryCall() =
        runTest {
            val broken =
                listOf(
                    """{"items":[{"fullName":"$PERSON","documents":[{"maskedNumber":"$CPF_NUMBER""",
                    """{"items":[{"createdAt":"$CPF_NUMBER","fullName":"$PERSON","id":"$CPF_NUMBER"}]}""",
                    """{"items":"$PERSON $CPF_NUMBER"}""",
                    """["$PERSON","$CPF_NUMBER"]""",
                )
            for (text in broken) {
                core { json(text, HttpStatusCode.OK) }.use { core ->
                    val calls =
                        listOf<suspend () -> Unit>(
                            { core.guests.searchByName(PERSON, 5) },
                            { core.guests.searchByDocument("CPF", CPF_NUMBER, null) },
                        )
                    for (call in calls) {
                        val error = assertFailsWith<ApiError.IncompatibleResponse> { call() }
                        assertFalse(chainLeaks(error), "vazou com o corpo: $text")
                    }
                }
            }
            val created = """{"createdAt":"$CPF_NUMBER","fullName":"$PERSON","id":"$CPF_NUMBER"}"""
            core { json(created, HttpStatusCode.Created) }
                .use { core ->
                    val error = assertFailsWith<ApiError.IncompatibleResponse> { core.guests.create(newGuest()) }
                    assertFalse(chainLeaks(error))
                }
        }

    @Test
    fun networkFailureHidesNumberAndName() =
        runTest {
            core { throw IOException("conexão caiu") }.use { core ->
                val calls =
                    listOf<suspend () -> Unit>(
                        { core.guests.searchByName(PERSON, 5) },
                        { core.guests.searchByDocument("CPF", CPF_NUMBER, null) },
                        { core.guests.create(newGuest()) },
                    )
                for (call in calls) {
                    val error = assertFailsWith<ApiError.Network> { call() }
                    assertFalse(chainLeaks(error), "vazou na cadeia de causas")
                }
            }
        }

    @Test
    fun newGuestHidesDataEvenInStringTemplatesAndCopies() {
        val copy = newGuest().copy(fullName = "Outro Nome")
        assertEquals("NewGuest(***)", "$copy")
        assertFalse(leaks("${listOf(newGuest())} ${mapOf("k" to newGuest())}"))
    }
}
