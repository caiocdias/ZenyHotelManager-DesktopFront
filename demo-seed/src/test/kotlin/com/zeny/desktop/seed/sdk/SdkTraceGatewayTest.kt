package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.frontdesk.TraceRef
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val DEP_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val DEP_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e02"
private const val DEP_3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e03"
private const val TRACE_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val TRACE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val RESERVATION = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"

private const val LOGIN_BODY =
    """{"accessToken":"A1","accessTokenExpiresAt":"2036-11-11T13:15:00Z","passwordChangeRequired":false,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2036-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}"""

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/json",
): HttpResponseData = respond(body, status, Headers.build { append(HttpHeaders.ContentType, contentType) })

private fun department(
    id: String,
    key: String?,
) = """{"createdAt":"2026-01-01T00:00:00Z","id":"$id",${key?.let { "\"key\":\"$it\"," }.orEmpty()}
       |"name":"Setor","updatedAt":"2026-01-01T00:00:00Z"}
    """.trimMargin()

private fun trace(
    id: String,
    departmentId: String,
) = """{"createdAt":"2026-11-15T10:00:00Z","createdBy":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e",
       |"departmentId":"$departmentId","departmentName":"Setor","dueOn":"2026-11-15","id":"$id",
       |"status":"OPEN","text":"Texto","version":1}
    """.trimMargin()

private fun page(
    items: List<String>,
    next: String?,
) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

class SdkTraceGatewayTest {
    private class Sent(
        val method: HttpMethod,
        val path: String,
        val query: Map<String, String>,
        val idempotencyKey: String?,
        val body: JsonObject?,
    )

    private val sent = CopyOnWriteArrayList<Sent>()

    private suspend fun gateway(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SdkTraceGateway {
        val engine =
            MockEngine { request ->
                val text = request.body.toByteArray().decodeToString()
                sent +=
                    Sent(
                        request.method,
                        request.url.encodedPath,
                        request.url.parameters
                            .entries()
                            .associate { it.key to it.value.first() },
                        request.headers["Idempotency-Key"],
                        text.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject },
                    )
                if (request.url.encodedPath.endsWith("/login")) json(LOGIN_BODY.trimMargin()) else handler(request)
            }
        val session = SdkSessionFactory(SERVER) { engine }.open()
        session.signIn("admin", Secret("Senha-Secreta-123"))
        return SdkTraceGateway(session.seed)
    }

    @Test
    fun departmentsByKey() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json(page(listOf(department(DEP_1, "HOUSEKEEPING"), department(DEP_2, null)), "c2"))
                    } else {
                        json(page(listOf(department(DEP_3, "RECEPTION")), null))
                    }
                }
            assertEquals(mapOf("HOUSEKEEPING" to DEP_1, "RECEPTION" to DEP_3), gateway.departmentIdsByKey())
            assertEquals(2, sent.count { it.path.endsWith("/departments") })
        }

    @Test
    fun tracesFilteredByReservationOrRoom() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> json(page(listOf(trace(TRACE_1, DEP_1)), "c2"))
                        "c2" -> json(page(listOf(trace(TRACE_2, DEP_2)), null))
                        else -> error("cursor inesperado")
                    }
                }
            val byReservation = gateway.traces(RESERVATION, null)
            assertEquals(listOf(TraceRef(TRACE_1, DEP_1), TraceRef(TRACE_2, DEP_2)), byReservation)
            val first = sent.first { it.path.endsWith("/traces") }
            assertEquals(RESERVATION, first.query["reservationId"])
            assertFalse("roomId" in first.query)
            sent.clear()
            gateway.traces(null, ROOM)
            val byRoom = sent.first { it.path.endsWith("/traces") }
            assertEquals(ROOM, byRoom.query["roomId"])
            assertFalse("reservationId" in byRoom.query)
        }

    @Test
    fun createSendsBodyWithoutIdempotencyKey() =
        runTest {
            val gateway = gateway { json(trace(TRACE_1, DEP_1), HttpStatusCode.Created) }
            val id = gateway.create(DEP_1, LocalDate.of(2026, 11, 15), "Texto", null, ROOM)
            assertEquals(TRACE_1, id)
            val post = sent.single { it.method == HttpMethod.Post && it.path.endsWith("/traces") }
            assertEquals(null, post.idempotencyKey)
            val body = post.body!!
            assertEquals(DEP_1, body.getValue("departmentId").jsonPrimitive.content)
            assertEquals("2026-11-15", body.getValue("dueOn").jsonPrimitive.content)
            assertEquals("Texto", body.getValue("text").jsonPrimitive.content)
            assertEquals(ROOM, body.getValue("roomId").jsonPrimitive.content)
            assertFalse("reservationId" in body)
        }

    @Test
    fun errorsAreApiError() =
        runTest {
            val gateway =
                gateway {
                    json(
                        """{"code":"TRACE_INVALID","detail":"x","errors":[]}""",
                        HttpStatusCode.BadRequest,
                        "application/problem+json",
                    )
                }
            assertTrue(
                assertFailsWith<ApiError.Problem> {
                    gateway.create(DEP_1, LocalDate.of(2026, 11, 15), "Texto", RESERVATION, null)
                }.code == "TRACE_INVALID",
            )
            assertFailsWith<ApiError.Problem> { gateway.departmentIdsByKey() }
            assertFailsWith<ApiError.Problem> { gateway.traces(RESERVATION, null) }
        }
}
