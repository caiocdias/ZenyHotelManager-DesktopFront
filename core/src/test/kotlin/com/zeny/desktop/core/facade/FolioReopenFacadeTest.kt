package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val SECRET_ID = "id-secreto-que-nao-e-uuid"
private const val SECRET_REASON = "motivo-secreto-"
private const val MAX_REASON = 500

/** A reabertura da conta encerrada: escrita sem `Idempotency-Key`, que pode pedir aprovação. */
class FolioReopenFacadeTest {
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

    private val openFolio =
        """{"balance":"0.00","creditLimitExceeded":false,"id":"$FOLIO","number":"F-1",
        "openedAt":"2026-11-18T10:00:00Z","openedOn":"2026-11-18",
        "owner":{"id":"$FOLIO","type":"RESERVATION"},"status":"OPEN",
        "updatedAt":"2026-11-18T10:00:00Z"}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"billing.folio.reopen","permission":"billing.folios.reopen",
        "resourceRef":"billing.folio:$FOLIO"}"""

    private suspend fun reasonOf(request: HttpRequestData): String =
        Json
            .parseToJsonElement(request.body.toByteArray().decodeToString())
            .jsonObject
            .getValue("reason")
            .jsonPrimitive.content

    @Test
    fun reopenSendsTheTrimmedReasonAndNoIdempotencyKey() =
        runTest {
            core { json(openFolio) }.use { core ->
                val done = core.folios.reopen(FOLIO, "  conta encerrada por engano  ").run(null)
                assertEquals(FOLIO, done.value.id.toString())
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/reopen"))
                assertEquals("conta encerrada por engano", reasonOf(request))
                assertNull(request.headers["Idempotency-Key"])
                assertNull(request.headers["X-Approval-Token"])
            }
        }

    @Test
    fun reopenApprovedSendsOnlyTheToken() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(openFolio)
                }
            }.use { core ->
                val action = core.folios.reopen(FOLIO, "engano")
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                val done = action.run("tok-gerente")
                assertEquals(FOLIO, done.value.id.toString())
                assertEquals(2, requests.size)
                assertEquals("tok-gerente", requests[1].headers["X-Approval-Token"])
                requests.forEach { assertNull(it.headers["Idempotency-Key"]) }
            }
        }

    @Test
    fun approvalRequiredRisesAsApprovalRequired() =
        runTest {
            core { json(approvalProblem, HttpStatusCode.Forbidden) }.use { core ->
                val required =
                    assertFailsWith<ApiError.ApprovalRequired> { core.folios.reopen(FOLIO, "engano").run(null) }
                assertEquals("billing.folio.reopen", required.action)
                assertEquals("billing.folios.reopen", required.permission)
                assertEquals("billing.folio:$FOLIO", required.resourceRef)
            }
        }

    @Test
    fun folioNotClosedRises() =
        runTest {
            core {
                json("""{"code":"FOLIO_NOT_CLOSED","detail":"d"}""", HttpStatusCode.Conflict)
            }.use { core ->
                val problem = assertFailsWith<ApiError.Problem> { core.folios.reopen(FOLIO, "engano").run(null) }
                assertEquals("FOLIO_NOT_CLOSED", problem.code)
                assertEquals(409, problem.status)
            }
        }

    @Test
    fun blankOrLongReasonAndNonUuidFailBeforeTheNetwork() =
        runTest {
            core { json(openFolio) }.use { core ->
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { core.folios.reopen(FOLIO, "   ") },
                        assertFailsWith<IllegalArgumentException> { core.folios.reopen(FOLIO, "") },
                        assertFailsWith<IllegalArgumentException> {
                            core.folios.reopen(FOLIO, SECRET_REASON + "x".repeat(MAX_REASON))
                        },
                        assertFailsWith<IllegalArgumentException> { core.folios.reopen(SECRET_ID, "engano") },
                    )
                failures.forEach {
                    assertTrue(SECRET_ID !in it.message.orEmpty())
                    assertTrue(SECRET_REASON !in it.message.orEmpty())
                }
                core.folios.reopen(FOLIO, "x".repeat(MAX_REASON))
                assertTrue(requests.isEmpty(), "a validação não vai à rede; só rodar a ação vai")
            }
        }
}
