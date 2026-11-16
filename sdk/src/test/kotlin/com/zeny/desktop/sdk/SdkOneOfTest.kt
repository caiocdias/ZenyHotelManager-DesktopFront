package com.zeny.desktop.sdk

import com.zeny.desktop.sdk.client.GuestsApi
import com.zeny.desktop.sdk.model.GuestResponse
import com.zeny.desktop.sdk.model.GuestWriteSummaryResponse
import com.zeny.desktop.sdk.model.MergeGuestRequest
import com.zeny.desktop.sdk.model.RestrictGuestRequest
import de.quati.ogen.client.ktor.HttpClientOgen
import de.quati.ogen.client.ktor.HttpResponseTyped
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Caracterização do primeiro `oneOf` do contrato (ADR 0010): restrictGuest, liftGuestRestriction e mergeGuests
 * devolvem o perfil inteiro ou o resumo. O tipo gerado é `JsonElement`: o corpo chega inteiro, sem perda nem
 * reescrita, e cada ramo decodifica com o serializer gerado. Engine HTTP falso, sem rede.
 */
class SdkOneOfTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val someId = Uuid.parse(id)

    private fun api(body: String): GuestsApi {
        val engine =
            MockEngine {
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.ETag to listOf("W/\"7\""),
                    ),
                )
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return GuestsApi(HttpClientOgen.Base(http, "http://servidor.teste", {}, json))
    }

    private fun fullJson(extra: String = "") =
        """{"createdAt":"2026-11-10T13:00:00Z","documents":[],"fullName":"Maria da Silva",""" +
            """"hasSensitiveData":false,"id":"$id","preferences":{},"status":"ACTIVE",""" +
            """"updatedAt":"2026-11-10T13:00:00Z","vip":true$extra}"""

    private fun summaryJson(
        restricted: Boolean,
        extra: String = "",
    ) = """{"id":"$id","restricted":$restricted$extra}"""

    private suspend fun assertIntact(
        sent: String,
        response: HttpResponseTyped<JsonElement>,
    ) {
        assertEquals(Json.parseToJsonElement(sent), response.body())
        assertEquals("W/\"7\"", response.raw.headers[HttpHeaders.ETag])
    }

    private fun assertFullBranch(sent: String) {
        assertEquals("Maria da Silva", json.decodeFromString(GuestResponse.serializer(), sent).fullName)
    }

    private fun assertSummaryBranch(
        sent: String,
        restricted: Boolean,
    ) {
        val summary = json.decodeFromString(GuestWriteSummaryResponse.serializer(), sent)
        assertEquals(someId, summary.id)
        assertEquals(restricted, summary.restricted)
    }

    @Test
    fun restrictGuestFullBranchArrivesIntact() =
        runBlocking {
            val sent = fullJson()
            val response: HttpResponseTyped<JsonElement> =
                api(sent).restrictGuest(someId, RestrictGuestRequest("motivo"))

            assertIntact(sent, response)
            assertFullBranch(sent)
        }

    @Test
    fun fullBranchWithRestrictedArrivesIntact() =
        runBlocking {
            val sent = fullJson(""","restricted":true""")
            val response: HttpResponseTyped<JsonElement> =
                api(sent).restrictGuest(someId, RestrictGuestRequest("motivo"))

            assertIntact(sent, response)
            assertFullBranch(sent)
        }

    @Test
    fun restrictGuestSummaryBranchArrivesIntact() =
        runBlocking {
            val sent = summaryJson(restricted = true)
            val response: HttpResponseTyped<JsonElement> =
                api(sent).restrictGuest(someId, RestrictGuestRequest("motivo"))

            assertIntact(sent, response)
            assertSummaryBranch(sent, restricted = true)
        }

    @Test
    fun liftGuestRestrictionBothBranchesArriveIntact() =
        runBlocking {
            val full = fullJson()
            val fullResponse: HttpResponseTyped<JsonElement> = api(full).liftGuestRestriction(someId)
            assertIntact(full, fullResponse)
            assertFullBranch(full)

            val summary = summaryJson(restricted = false)
            val summaryResponse: HttpResponseTyped<JsonElement> = api(summary).liftGuestRestriction(someId)
            assertIntact(summary, summaryResponse)
            assertSummaryBranch(summary, restricted = false)
        }

    @Test
    fun mergeGuestsBothBranchesArriveIntact() =
        runBlocking {
            val request =
                MergeGuestRequest(
                    mergedGuestId = Uuid.parse("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"),
                    takeFromMerged = emptyList(),
                )
            val full = fullJson()
            val fullResponse: HttpResponseTyped<JsonElement> = api(full).mergeGuests(someId, "W/\"6\"", request)
            assertIntact(full, fullResponse)
            assertFullBranch(full)

            val summary = summaryJson(restricted = true)
            val summaryResponse: HttpResponseTyped<JsonElement> = api(summary).mergeGuests(someId, "W/\"6\"", request)
            assertIntact(summary, summaryResponse)
            assertSummaryBranch(summary, restricted = true)
        }

    @Test
    fun summaryBranchKeepsRestrictedFalse() =
        runBlocking {
            val sent = summaryJson(restricted = false)
            val response: HttpResponseTyped<JsonElement> = api(sent).liftGuestRestriction(someId)

            assertIntact(sent, response)
            assertSummaryBranch(sent, restricted = false)
        }

    @Test
    fun summaryWithUnknownPropertyIsKeptVerbatim() =
        runBlocking {
            val sent = summaryJson(restricted = true, extra = ""","novo":1""")
            val response: HttpResponseTyped<JsonElement> = api(sent).restrictGuest(someId, RestrictGuestRequest(null))

            assertIntact(sent, response)
            assertSummaryBranch(sent, restricted = true)
        }
}
