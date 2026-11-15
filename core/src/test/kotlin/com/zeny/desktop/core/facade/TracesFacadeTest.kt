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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

private const val TRACE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val DEPT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val DEPT_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val ETAG = "\"3\""
private const val SECRET_TEXT = "texto-secreto-do-recado"
private val DUE = BusinessDate.parse("2026-11-20")

/** Recados e setores da `TracesFacade`, contra o `MockEngine`. */
class TracesFacadeTest {
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

    private fun trace(status: String = "OPEN") =
        """{"createdAt":"2026-11-14T10:00:00Z","createdBy":"$USER","departmentId":"$DEPT","departmentName":"Governança",
        "dueOn":"2026-11-20","id":"$TRACE","reservationId":"$RES","status":"$status","text":"$SECRET_TEXT",
        "version":3}"""

    private fun tracePage(next: String? = null): String {
        val cursor = if (next == null) "" else ""","nextCursor":"$next""""
        return """{"items":[${trace()}]$cursor}"""
    }

    private fun department(
        id: String,
        name: String,
    ) = """{"createdAt":"2026-11-01T10:00:00Z","id":"$id","name":"$name","updatedAt":"2026-11-01T10:00:00Z"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun newTrace(
        reservationId: String? = RES,
        roomId: String? = null,
        departmentId: String = DEPT,
        text: String = SECRET_TEXT,
    ) = NewTrace(departmentId, DUE, text, reservationId, roomId)

    private fun change(assignee: String? = null) = TraceChange(DEPT_2, DUE, SECRET_TEXT, assignee)

    @Test
    fun pageSendsFilters() =
        runTest {
            core { json(tracePage("next-2")) }.use { core ->
                val filter = TraceFilter(DEPT, DUE, TraceStatusFilter.OPEN, RES, ROOM)
                val page = core.traces.page(filter, "cur-1", 50)
                assertEquals("next-2", page.nextCursor)
                assertEquals(1, page.items.size)
                val query = requests.single().url.parameters
                assertEquals(DEPT, query["departmentId"])
                assertEquals("2026-11-20", query["dueOn"])
                assertEquals("OPEN", query["status"])
                assertEquals(RES, query["reservationId"])
                assertEquals(ROOM, query["roomId"])
                assertEquals("cur-1", query["cursor"])
                assertEquals("50", query["limit"])
            }
        }

    @Test
    fun pageOmitsNullFilters() =
        runTest {
            core { json(tracePage()) }.use { core ->
                val page = core.traces.page(TraceFilter(), null, 20)
                assertNull(page.nextCursor)
                val query = requests.single().url.parameters
                listOf("departmentId", "dueOn", "status", "reservationId", "roomId", "cursor").forEach {
                    assertFalse(query.contains(it), it)
                }
            }
        }

    @Test
    fun traceCarriesEtag() =
        runTest {
            core { json(trace(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                val version = core.traces.trace(TRACE)
                assertEquals(ETAG, version.etag)
                assertEquals("OPEN", version.trace.status)
                assertEquals(HttpMethod.Get, requests.single().method)
            }
        }

    @Test
    fun traceWithoutEtagIsIncompatible() =
        runTest {
            core { json(trace()) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.traces.trace(TRACE) }
            }
        }

    @Test
    fun createSendsBodyWithoutIdempotencyKey() =
        runTest {
            core { json(trace(), HttpStatusCode.Created, "ETag" to ETAG) }.use { core ->
                val version = core.traces.create(newTrace())
                assertEquals(ETAG, version.etag)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertNull(request.headers["Idempotency-Key"])
                val body = bodyOf(request)
                assertEquals(DEPT, body["departmentId"]!!.jsonPrimitive.content)
                assertEquals("2026-11-20", body["dueOn"]!!.jsonPrimitive.content)
                assertEquals(SECRET_TEXT, body["text"]!!.jsonPrimitive.content)
                assertEquals(RES, body["reservationId"]!!.jsonPrimitive.content)
                assertFalse(body.containsKey("roomId"))
            }
        }

    @Test
    fun createWithRoomSendsRoomOnly() =
        runTest {
            core { json(trace(), HttpStatusCode.Created, "ETag" to ETAG) }.use { core ->
                core.traces.create(newTrace(reservationId = null, roomId = ROOM))
                val body = bodyOf(requests.single())
                assertEquals(ROOM, body["roomId"]!!.jsonPrimitive.content)
                assertFalse(body.containsKey("reservationId"))
            }
        }

    @Test
    fun createWithoutEtagIsIncompatible() =
        runTest {
            core { json(trace(), HttpStatusCode.Created) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.traces.create(newTrace()) }
            }
        }

    @Test
    fun createNeedsExactlyOneLink() =
        runTest {
            core { json(trace(), HttpStatusCode.Created, "ETag" to ETAG) }.use { core ->
                assertFailsWith<IllegalArgumentException> { core.traces.create(newTrace(RES, ROOM)) }
                assertFailsWith<IllegalArgumentException> { core.traces.create(newTrace(null, null)) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun updateSendsIfMatch() =
        runTest {
            core { json(trace(), HttpStatusCode.OK, "ETag" to "\"4\"") }.use { core ->
                val version = core.traces.update(TRACE, change(), ETAG)
                assertEquals("\"4\"", version.etag)
                val request = requests.single()
                assertEquals(HttpMethod.Put, request.method)
                assertTrue(request.url.encodedPath.endsWith("/traces/$TRACE"), request.url.encodedPath)
                assertEquals(ETAG, request.headers["If-Match"])
                assertNull(request.headers["Idempotency-Key"])
                val body = bodyOf(request)
                assertEquals(DEPT_2, body["departmentId"]!!.jsonPrimitive.content)
                assertEquals(SECRET_TEXT, body["text"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun updateBodyCarriesAssignee() =
        runTest {
            core { json(trace(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                core.traces.update(TRACE, change(USER), ETAG)
                core.traces.update(TRACE, change(null), ETAG)
                assertEquals(USER, bodyOf(requests[0])["assigneeUserId"]!!.jsonPrimitive.content)
                assertFalse(bodyOf(requests[1]).containsKey("assigneeUserId"))
            }
        }

    @Test
    fun updatePreconditionFailedIsTyped() =
        runTest {
            core { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED") }.use { core ->
                assertFailsWith<ApiError.PreconditionFailed> { core.traces.update(TRACE, change(), ETAG) }
            }
        }

    @Test
    fun resolveReturnsTrace() =
        runTest {
            core { json(trace("RESOLVED")) }.use { core ->
                assertEquals("RESOLVED", core.traces.resolve(TRACE).status)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/traces/$TRACE/resolve"), request.url.encodedPath)
                assertNull(request.headers["Idempotency-Key"])
            }
        }

    @Test
    fun resolveAlreadyResolvedIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "TRACE_ALREADY_RESOLVED") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.traces.resolve(TRACE) }
                assertEquals("TRACE_ALREADY_RESOLVED", error.code)
            }
        }

    @Test
    fun departmentsReadsAllPages() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json("""{"items":[${department(DEPT, "Governança")}],"nextCursor":"c2"}""")
                } else {
                    json("""{"items":[${department(DEPT_2, "Manutenção")}]}""")
                }
            }.use { core ->
                val all = core.traces.departments()
                assertEquals(listOf(Department(DEPT, "Governança"), Department(DEPT_2, "Manutenção")), all)
                assertEquals(2, requests.size)
                assertEquals("200", requests[0].url.parameters["limit"])
                assertEquals("c2", requests[1].url.parameters["cursor"])
            }
        }

    @Test
    fun departmentsForbiddenIsTyped() =
        runTest {
            core { problem(HttpStatusCode.Forbidden, "FORBIDDEN") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.traces.departments() }
                assertEquals(403, error.status)
                assertEquals("FORBIDDEN", error.code)
            }
        }

    @Test
    fun invalidIdsFailBeforeNetwork() =
        runTest {
            core { json(trace(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                val bad = "segredo-nao-uuid"
                val facade = core.traces
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { facade.trace(bad) },
                        assertFailsWith<IllegalArgumentException> { facade.resolve(bad) },
                        assertFailsWith<IllegalArgumentException> { facade.update(bad, change(), ETAG) },
                        assertFailsWith<IllegalArgumentException> { facade.update(TRACE, change(bad), ETAG) },
                        assertFailsWith<IllegalArgumentException> { facade.create(newTrace(reservationId = bad)) },
                        assertFailsWith<IllegalArgumentException> { facade.create(newTrace(departmentId = bad)) },
                        assertFailsWith<IllegalArgumentException> {
                            facade.page(TraceFilter(departmentId = bad), null, 10)
                        },
                        assertFailsWith<IllegalArgumentException> { facade.page(TraceFilter(roomId = bad), null, 10) },
                    )
                assertTrue(requests.isEmpty())
                failures.forEach { assertFalse(it.message.orEmpty().contains(bad)) }
            }
        }

    @Test
    fun toStringHidesText() {
        val response = checkNotNull(Json.decodeFromString<com.zeny.desktop.sdk.model.TraceResponse>(trace()))
        val texts =
            listOf(
                newTrace().toString(),
                change().toString(),
                TraceVersion(response, ETAG).toString(),
            )
        texts.forEach { assertFalse(it.contains(SECRET_TEXT), it) }
    }
}
