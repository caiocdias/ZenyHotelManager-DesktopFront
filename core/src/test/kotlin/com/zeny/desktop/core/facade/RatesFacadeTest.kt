package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PLAN_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val POLICY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b02"

/** A leitura do plano tarifário contra o `MockEngine`, pelo `ZenyCore` real. */
class RatesFacadeTest {
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

    private val plan =
        """{"active":true,"cancellationPolicyId":"$POLICY_ID","category":"NORMAL","channels":["DIRECT"],
        "code":"BAR","createdAt":"2026-11-12T10:00:00Z","guaranteePolicyId":"$POLICY_ID","id":"$PLAN_ID",
        "mealPlan":"BREAKFAST","name":"Tarifa Padrão","packageIds":[],"roomTypeIds":[],
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    @Test
    fun ratePlanReturnsName() =
        runTest {
            core { json(plan) }.use { core ->
                assertEquals("Tarifa Padrão", core.rates.ratePlan(PLAN_ID).name)
            }
            assertEquals("/api/v1/rate-plans/$PLAN_ID", requests.single().url.encodedPath)
        }

    @Test
    fun ratePlanNotFoundIsProblem() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "RATE_PLAN_NOT_FOUND") }.use { core ->
                assertEquals(404, assertFailsWith<ApiError.Problem> { core.rates.ratePlan(PLAN_ID) }.status)
            }
        }

    @Test
    fun invalidIdsFailBeforeNetwork() =
        runTest {
            core { json(plan) }.use { core ->
                for (id in listOf("", "segredo-123", "../rate-plans", "$PLAN_ID/x")) {
                    val error = assertFailsWith<IllegalArgumentException>("id $id") { core.rates.ratePlan(id) }
                    assertTrue(id.isEmpty() || id !in error.message.orEmpty(), "mensagem ecoa '$id'")
                }
            }
            assertEquals(0, requests.size)
        }
}
