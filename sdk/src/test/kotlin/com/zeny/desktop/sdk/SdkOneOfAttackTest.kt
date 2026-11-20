package com.zeny.desktop.sdk

import com.zeny.desktop.sdk.client.GuestsApi
import com.zeny.desktop.sdk.model.GuestResponse
import com.zeny.desktop.sdk.model.GuestWriteSummaryResponse
import com.zeny.desktop.sdk.model.MergeGuestRequest
import com.zeny.desktop.sdk.model.RestrictGuestRequest
import de.quati.kotlin.util.Option
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Ataque à caracterização do `oneOf` (ADR 0010): requisição que sai (If-Match, método, caminho, corpo), ramos que
 * não se distinguem pelo serializer sozinho, ausência de perda na decodificação e corpos que não são objeto.
 * O que o cliente gerado faz com corpo que não é objeto é só caracterizado; a decisão é da fachada (M5 E02).
 */
class SdkOneOfAttackTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val otherId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"
    private val someId = Uuid.parse(id)
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(
        body: String,
        etag: String? = "W/\"7\"",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): GuestsApi {
        val engine =
            MockEngine { request ->
                requests += request
                val headers =
                    buildList {
                        add(HttpHeaders.ContentType to listOf("application/json"))
                        if (etag != null) add(HttpHeaders.ETag to listOf(etag))
                    }
                respond(body, status, headersOf(*headers.toTypedArray()))
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return GuestsApi(HttpClientOgen.Base(http, "http://servidor.teste", {}, json))
    }

    private fun mergeRequest() =
        MergeGuestRequest(mergedGuestId = Uuid.parse(otherId), takeFromMerged = Option.Some(emptyList()))

    @Test
    fun mergeGuestsSendsIfMatchPostPathAndJsonBody() =
        runBlocking {
            api("""{"id":"$id","restricted":false}""").mergeGuests(someId, "W/\"6\"", mergeRequest())

            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/guests/$id/merge", request.url.encodedPath)
            assertEquals("W/\"6\"", request.headers[HttpHeaders.IfMatch])
            assertTrue(request.headers[HttpHeaders.Accept].orEmpty().contains("application/json"))
            val bytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
            val sent = Json.parseToJsonElement(String(bytes))
            assertEquals(otherId, sent.jsonObject["mergedGuestId"]?.jsonPrimitive?.content)
        }

    @Test
    fun mergeGuestsWithoutIfMatchSendsNoHeader() =
        runBlocking {
            api("""{"id":"$id","restricted":false}""").mergeGuests(someId, null, mergeRequest())

            assertNull(requests.single().headers[HttpHeaders.IfMatch])
        }

    @Test
    fun restrictAndLiftUseTheContractedMethodAndPath() =
        runBlocking {
            val api = api("""{"id":"$id","restricted":true}""")
            api.restrictGuest(someId, RestrictGuestRequest("motivo"))
            api.liftGuestRestriction(someId)

            val (restrict, lift) = requests
            assertEquals("/api/v1/guests/$id/restrict", restrict.url.encodedPath)
            assertEquals("/api/v1/guests/$id/lift-restriction", lift.url.encodedPath)
            assertEquals(HttpMethod.Post, restrict.method)
            assertEquals(HttpMethod.Post, lift.method)
        }

    @Test
    fun summaryBranchWithoutETagStillArrivesAndHeaderIsNull() =
        runBlocking {
            val response = api("""{"id":"$id","restricted":false}""", etag = null).liftGuestRestriction(someId)

            assertNull(response.raw.headers[HttpHeaders.ETag])
            val restricted = response.body().jsonObject["restricted"]
            assertEquals(false, restricted?.jsonPrimitive?.boolean)
        }

    @Test
    fun summaryWithoutRestrictedDoesNotDecodeAsSummary() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(GuestWriteSummaryResponse.serializer(), """{"id":"$id"}""")
        }
    }

    @Test
    fun summaryDoesNotDecodeAsFullProfile() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(GuestResponse.serializer(), """{"id":"$id","restricted":true}""")
        }
    }

    @Test
    fun fullProfileWithRestrictedDecodesAsBothBranchesSoSerializerAloneCannotChoose() {
        val full =
            """{"createdAt":"2026-11-10T13:00:00Z","documents":[],"fullName":"Maria","hasSensitiveData":false,""" +
                """"id":"$id","preferences":{},"status":"ACTIVE","updatedAt":"2026-11-10T13:00:00Z","vip":true,""" +
                """"restricted":true}"""

        assertEquals("Maria", json.decodeFromString(GuestResponse.serializer(), full).fullName)
        assertEquals(true, json.decodeFromString(GuestWriteSummaryResponse.serializer(), full).restricted)
    }

    @Test
    fun bodyKeepsPrecisionAndUnicodeAndNestingWithoutRewriting() =
        runBlocking {
            val sent =
                """{"id":"$id","restricted":false,"big":12345678901234567890,"d":1.10,"u":"açaí é","n":{"a":[1,null,{}]}}"""
            val body = api(sent).liftGuestRestriction(someId).body().jsonObject

            assertEquals("12345678901234567890", body["big"]?.jsonPrimitive?.content)
            assertEquals("1.10", body["d"]?.jsonPrimitive?.content)
            assertEquals("açaí é", body["u"]?.jsonPrimitive?.content)
            assertEquals(Json.parseToJsonElement(sent), body)
        }

    @Test
    fun arrayBodyArrivesAsJsonArrayWithoutError() =
        runBlocking {
            val body: JsonElement = api("""[{"id":"$id","restricted":true}]""").liftGuestRestriction(someId).body()

            assertTrue(body is JsonArray)
        }

    @Test
    fun nullBodyArrivesAsJsonNull() =
        runBlocking {
            val body: JsonElement = api("null").liftGuestRestriction(someId).body()

            assertEquals(JsonNull, body)
        }

    @Test
    fun scalarBodyArrivesAsPrimitive() =
        runBlocking {
            val body: JsonElement = api("true").liftGuestRestriction(someId).body()

            assertEquals(JsonPrimitive(true), body)
            assertTrue(body !is JsonObject)
        }

    @Test
    fun emptyBodyFailsToDecodeInsteadOfBecomingAnObject() {
        val response = runBlocking { api("").liftGuestRestriction(someId) }

        val failure = runCatching { runBlocking { response.body() } }.exceptionOrNull()
        assertTrue(failure != null, "corpo vazio não pode virar JsonElement válido")
    }
}
