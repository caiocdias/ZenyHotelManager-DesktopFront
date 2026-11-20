package com.zeny.desktop.sdk

import com.zeny.desktop.sdk.client.HousekeepingApi
import com.zeny.desktop.sdk.model.AssignmentAdjustRequest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Ataque da M0 E04 (testador): o primeiro `PATCH` do contrato, `adjustAssignmentPlan`, amarrado pelo nome e pelo que
 * ele manda, não só pela contagem de 376 do `SdkAttackTest` (um `PATCH` não gerado e uma operação a mais em outro
 * lugar dariam a mesma contagem).
 */
class SdkPatchOperationAttackTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<HttpRequestData>()
    private val taskId = Uuid.parse("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d")

    private fun client(): HttpClientOgen {
        val engine =
            MockEngine { request ->
                requests += request
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return HttpClientOgen.Base(http, "http://s.teste", {}, json)
    }

    @Test
    fun adjustAssignmentPlanIsGeneratedByNameInTheHousekeepingApi() {
        val names =
            HousekeepingApi::class
                .java.declaredMethods
                .map { it.name }
                .toSet()

        assertTrue("adjustAssignmentPlan" in names, "falta adjustAssignmentPlan em HousekeepingApi")
        assertTrue("prepareAdjustAssignmentPlan" in names, "falta prepareAdjustAssignmentPlan em HousekeepingApi")
    }

    @Test
    fun contractHasExactlyOnePatchAndItIsAdjustAssignmentPlan() {
        val lines = File("../api/openapi.yaml").readLines()
        val patches = lines.withIndex().filter { it.value == "    patch:" }

        assertEquals(1, patches.size, "o contrato fixado tem um único PATCH")
        val operationId =
            lines
                .drop(patches.single().index + 1)
                .first { it.trim().startsWith("operationId:") }
                .substringAfter("operationId:")
                .trim()
        assertEquals("adjustAssignmentPlan", operationId)
    }

    @Test
    fun adjustAssignmentPlanSendsPatchToThePlanOfTheDateWithIfMatchAndOnlyTheGivenFields() =
        runBlocking {
            val request = AssignmentAdjustRequest(remove = Option.Some(listOf(taskId)))

            HousekeepingApi(client()).prepareAdjustAssignmentPlan("2026-11-20", "\"7\"", request).execute()

            val sent = requests.single()
            assertEquals(HttpMethod.Patch, sent.method)
            assertEquals("/api/v1/assignment-plans/2026-11-20", sent.url.encodedPath)
            assertEquals("\"7\"", sent.headers[HttpHeaders.IfMatch])
            val raw = String((sent.body as OutgoingContent.ByteArrayContent).bytes())
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(taskId.toString()))), body["remove"])
            assertNull(body["items"], "items em Option.Undefined não vai no corpo")
        }
}
