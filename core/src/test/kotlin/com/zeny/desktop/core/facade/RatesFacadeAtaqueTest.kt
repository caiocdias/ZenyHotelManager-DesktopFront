package com.zeny.desktop.core.facade

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.sdk.model.RatePlanResponse
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val R_PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val R_POLICY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val R_SECRET = "SEGREDO-DO-PLANO"

/** Ataque à leitura do plano tarifário: id inválido, `401`/`403`/`404`, corpo malformado e enum fechado. */
class RatesFacadeAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private var server = FakeZenyServer()

    private suspend fun core(routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore {
        server = FakeZenyServer()
        server.routes = routes
        val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings(), server.engine)
        core.session.login("maria", "segredo")
        return core
    }

    private fun plan(category: String = "NORMAL") =
        """{"active":true,"cancellationPolicyId":"$R_POLICY","category":"$category","channels":["DIRECT"],
        "code":"BAR","createdAt":"2026-11-12T10:00:00Z","guaranteePolicyId":"$R_POLICY","id":"$R_PLAN",
        "mealPlan":"BREAKFAST","name":"Tarifa Padrão","packageIds":[],"roomTypeIds":[],
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun planRequests() = server.requests.filter { it.url.encodedPath.startsWith("/api/v1/rate-plans") }

    @Test
    fun uppercaseIdIsSentCanonicalLowercase() =
        runTest(timeout = 10.seconds) {
            core { json(plan()) }.use { core -> core.rates.ratePlan(R_PLAN.uppercase()) }
            assertEquals("/api/v1/rate-plans/$R_PLAN", planRequests().single().url.encodedPath)
        }

    @Test
    fun everyCategoryOfTheContractIsReadAndTheNameIsUntouched() =
        runTest(timeout = 10.seconds) {
            for (category in RatePlanResponse.Category.entries) {
                core { json(plan(category = category.value)) }.use { core ->
                    val result = core.rates.ratePlan(R_PLAN)
                    assertEquals(category, result.category)
                    assertEquals("Tarifa Padrão", result.name)
                }
            }
        }

    @Test
    fun invalidIdsFailBeforeNetworkWithoutEchoingTheId() =
        runTest(timeout = 10.seconds) {
            core { json(plan()) }.use { core ->
                val before = server.requests.size
                val bad =
                    listOf(
                        " $R_PLAN",
                        "$R_PLAN ",
                        "$R_PLAN\n",
                        R_PLAN.dropLast(1),
                        "${R_PLAN}0",
                        "{$R_PLAN}",
                        "$R_PLAN/x",
                        "id-$R_SECRET",
                        "   ",
                    )
                for (id in bad) {
                    val error = assertFailsWith<IllegalArgumentException>("id '$id'") { core.rates.ratePlan(id) }
                    val text = "$error ${error.message}"
                    assertFalse(R_SECRET in text)
                    assertFalse(R_PLAN in text, "mensagem ecoa parte de um UUID")
                }
                assertEquals(before, server.requests.size, "nenhuma requisição para id inválido")
            }
            assertTrue(planRequests().isEmpty())
        }

    @Test
    fun unauthorizedIsSessionEnded() =
        runTest(timeout = 10.seconds) {
            core { json(plan()) }.use { core ->
                server.rejectAllWith = "SESSION_REVOKED"
                val ended = assertFailsWith<ApiError.SessionEnded> { core.rates.ratePlan(R_PLAN) }
                assertEquals("SESSION_REVOKED", ended.code)
                assertIs<SessionState.Ended>(core.session.state.value)
            }
        }

    @Test
    fun forbiddenAndNotFoundAreProblemsAndKeepTheSession() =
        runTest(timeout = 10.seconds) {
            val cases =
                listOf(
                    HttpStatusCode.Forbidden to "PERMISSION_DENIED",
                    HttpStatusCode.NotFound to "RATE_PLAN_NOT_FOUND",
                )
            for ((http, code) in cases) {
                core { problem(http, code) }.use { core ->
                    val error = assertFailsWith<ApiError.Problem>(code) { core.rates.ratePlan(R_PLAN) }
                    assertEquals(http.value, error.status)
                    assertFalse(R_PLAN in "$error ${error.userMessage}")
                    assertIs<SessionState.Active>(core.session.state.value)
                }
            }
        }

    @Test
    fun malformedBodiesAreIncompatibleWithoutEchoingTheBody() =
        runTest(timeout = 10.seconds) {
            val bodies =
                mapOf(
                    "categoria fechada desconhecida" to plan(category = R_SECRET),
                    "campo obrigatorio faltando" to plan().replace("\"name\":\"Tarifa Padrão\",", ""),
                    "id nao e uuid" to plan().replace("\"id\":\"$R_PLAN\"", "\"id\":\"$R_SECRET\""),
                    "html" to "<html>$R_SECRET</html>",
                    "vazio" to "",
                )
            for ((label, body) in bodies) {
                core { json(body) }.use { core ->
                    val error = assertFailsWith<ApiError.IncompatibleResponse>(label) { core.rates.ratePlan(R_PLAN) }
                    val chain = generateSequence(error as Throwable) { it.cause }.toList()
                    assertTrue(chain.none { R_SECRET in "$it" || R_SECRET in it.message.orEmpty() }, "$label ecoa")
                    assertIs<SessionState.Active>(core.session.state.value)
                }
            }
        }
}
