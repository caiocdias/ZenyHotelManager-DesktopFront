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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val TRACE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val DEPT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val DEPT_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val SECRET = "texto-secreto-do-recado"
private val DUE = BusinessDate.parse("2026-11-20")

/** Ataque à fachada de recados: query, ETag, If-Match, ids, erros tipados e paginação de setores. */
class TracesFacadeAtaqueTest {
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

    private fun trace() =
        """{"createdAt":"2026-11-14T10:00:00Z","createdBy":"$USER","departmentId":"$DEPT","departmentName":"Governança",
        "dueOn":"2026-11-20","id":"$TRACE","reservationId":"$RES","status":"OPEN","text":"$SECRET","version":3}"""

    private fun department(
        id: String,
        name: String,
    ) = """{"createdAt":"2026-11-01T10:00:00Z","id":"$id","name":"$name","updatedAt":"2026-11-01T10:00:00Z"}"""

    private fun newTrace(
        reservationId: String? = RES,
        roomId: String? = null,
    ) = NewTrace(DEPT, DUE, SECRET, reservationId, roomId)

    private fun change(assignee: String? = null) = TraceChange(DEPT_2, DUE, SECRET, assignee)

    private fun page(items: String = trace()) = """{"items":[$items]}"""

    @Test
    fun resolvedStatusAndDueOnFormatGoOnTheQuery() =
        runTest(timeout = 10.seconds) {
            core { json(page()) }.use { core ->
                core.traces.page(
                    TraceFilter(status = TraceStatusFilter.RESOLVED, dueOn = BusinessDate.parse("2026-01-05")),
                    null,
                    1,
                )
                val query = requests.single().url.parameters
                assertEquals("RESOLVED", query["status"])
                assertEquals("2026-01-05", query["dueOn"])
                assertEquals("1", query["limit"])
            }
        }

    @Test
    fun opaqueCursorWithReservedCharsArrivesIntact() =
        runTest(timeout = 10.seconds) {
            core { json(page()) }.use { core ->
                core.traces.page(TraceFilter(), "a b&c=d", 5)
                // O cursor opaco com caracteres reservados chega íntegro (codificado, sem virar outro parâmetro).
                assertEquals("a b&c=d", requests.single().url.parameters["cursor"])
                assertFalse(
                    requests
                        .single()
                        .url.parameters
                        .contains("c"),
                )
            }
        }

    @Test
    fun blankFilterIdsAreRejectedBeforeNetwork() =
        runTest(timeout = 10.seconds) {
            core { json(page()) }.use { core ->
                listOf("", " ", "   ").forEach { blank ->
                    assertFailsWith<IllegalArgumentException> {
                        core.traces.page(
                            TraceFilter(departmentId = blank),
                            null,
                            10,
                        )
                    }
                    assertFailsWith<IllegalArgumentException> {
                        core.traces.page(
                            TraceFilter(reservationId = blank),
                            null,
                            10,
                        )
                    }
                    assertFailsWith<IllegalArgumentException> {
                        core.traces.page(
                            TraceFilter(roomId = blank),
                            null,
                            10,
                        )
                    }
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun blankEtagOnTraceCreateAndUpdateIsIncompatible() =
        runTest(timeout = 10.seconds) {
            core { json(trace(), HttpStatusCode.OK, "ETag" to "") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.traces.trace(TRACE) }
                assertFailsWith<ApiError.IncompatibleResponse> { core.traces.update(TRACE, change(), "\"1\"") }
            }
            core { json(trace(), HttpStatusCode.Created, "ETag" to "   ") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.traces.create(newTrace()) }
            }
        }

    @Test
    fun missingEtagOnUpdateIsIncompatible() =
        runTest(timeout = 10.seconds) {
            core { json(trace()) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.traces.update(TRACE, change(), "\"1\"") }
            }
        }

    @Test
    fun incompatibleEtagErrorDoesNotLeakTraceText() =
        runTest(timeout = 10.seconds) {
            core { json(trace()) }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.traces.trace(TRACE) }
                assertFalse(error.toString().contains(SECRET))
                assertFalse(error.message.orEmpty().contains(SECRET))
                assertFalse(
                    error.cause
                        ?.message
                        .orEmpty()
                        .contains(SECRET),
                )
            }
        }

    @Test
    fun ifMatchIsSentVerbatimWeakAndQuoted() =
        runTest(timeout = 10.seconds) {
            core { json(trace(), HttpStatusCode.OK, "ETag" to "\"9\"") }.use { core ->
                listOf("W/\"7\"", "\"7\"", "7").forEach { core.traces.update(TRACE, change(), it) }
                assertEquals(listOf("W/\"7\"", "\"7\"", "7"), requests.map { it.headers["If-Match"] })
            }
        }

    @Test
    fun noTraceCallSendsIdempotencyKeyOrApprovalHeaders() =
        runTest(timeout = 10.seconds) {
            core { json(trace(), HttpStatusCode.OK, "ETag" to "\"1\"") }.use { core ->
                core.traces.trace(TRACE)
                core.traces.create(newTrace())
                core.traces.create(newTrace(null, ROOM))
                core.traces.update(TRACE, change(USER), "\"1\"")
                core.traces.resolve(TRACE)
            }
            core { json(page()) }.use { core ->
                core.traces.page(TraceFilter(DEPT, DUE, TraceStatusFilter.OPEN, RES, ROOM), "c", 3)
            }
            core { json("""{"items":[]}""") }.use { core -> core.traces.departments() }
            assertEquals(7, requests.size)
            requests.forEach {
                assertNull(it.headers["Idempotency-Key"], it.url.toString())
                assertNull(it.headers["X-Approval-Token"], it.url.toString())
            }
        }

    @Test
    fun invalidIdsWithBlankOrDecoratedFormsFailBeforeNetwork() =
        runTest(timeout = 10.seconds) {
            core { json(trace(), HttpStatusCode.OK, "ETag" to "\"1\"") }.use { core ->
                val invalid =
                    listOf("", " ", " $TRACE", "$TRACE ", "{$TRACE}", "$TRACE/resolve", "../$TRACE", "0199d6b8")
                val errors = mutableListOf<Throwable>()
                invalid.forEach { bad ->
                    errors += assertFailsWith<IllegalArgumentException> { core.traces.trace(bad) }
                    errors += assertFailsWith<IllegalArgumentException> { core.traces.resolve(bad) }
                    errors += assertFailsWith<IllegalArgumentException> { core.traces.update(bad, change(), "\"1\"") }
                    errors +=
                        assertFailsWith<IllegalArgumentException> { core.traces.update(TRACE, change(bad), "\"1\"") }
                    errors +=
                        assertFailsWith<IllegalArgumentException> {
                            core.traces.create(
                                newTrace(roomId = null, reservationId = bad),
                            )
                        }
                    errors +=
                        assertFailsWith<IllegalArgumentException> {
                            core.traces.create(
                                newTrace(reservationId = null, roomId = bad),
                            )
                        }
                }
                assertTrue(requests.isEmpty())
                errors.zip(invalid.flatMap { b -> List(6) { b } }).forEach { (e, bad) ->
                    if (bad.isNotBlank()) assertFalse(e.message.orEmpty().contains(bad), e.message)
                }
            }
        }

    @Test
    fun invalidAssigneeAndDepartmentOnUpdateFailBeforeNetworkWithoutEcho() =
        runTest(timeout = 10.seconds) {
            core { json(trace(), HttpStatusCode.OK, "ETag" to "\"1\"") }.use { core ->
                val bad = "segredo-xyz"
                val a =
                    assertFailsWith<IllegalArgumentException> {
                        core.traces.update(
                            TRACE,
                            TraceChange(bad, DUE, SECRET, null),
                            "\"1\"",
                        )
                    }
                val b =
                    assertFailsWith<IllegalArgumentException> {
                        core.traces.create(
                            NewTrace(bad, DUE, SECRET, RES, null),
                        )
                    }
                assertTrue(requests.isEmpty())
                listOf(a, b).forEach {
                    assertFalse(it.message.orEmpty().contains(bad))
                    assertFalse(it.message.orEmpty().contains(SECRET))
                }
            }
        }

    @Test
    fun linkErrorMessageDoesNotEchoTextOrIds() =
        runTest(timeout = 10.seconds) {
            core { json(trace()) }.use { core ->
                val both = assertFailsWith<IllegalArgumentException> { core.traces.create(newTrace(RES, ROOM)) }
                val none = assertFailsWith<IllegalArgumentException> { core.traces.create(newTrace(null, null)) }
                listOf(both, none).forEach {
                    assertFalse(it.message.orEmpty().contains(SECRET))
                    assertFalse(it.message.orEmpty().contains(RES))
                }
            }
        }

    @Test
    fun toStringNeverContainsTextEvenWithOddText() {
        val response = Json.decodeFromString<com.zeny.desktop.sdk.model.TraceResponse>(trace())
        listOf("\"quoted\" $SECRET", "$SECRET\nlinha", SECRET.uppercase()).forEach { text ->
            val strings =
                listOf(
                    NewTrace(DEPT, DUE, text, RES, null).toString(),
                    NewTrace(DEPT, DUE, text, null, ROOM).toString(),
                    TraceChange(DEPT, DUE, text, USER).toString(),
                    TraceChange(DEPT, DUE, text, null).toString(),
                )
            strings.forEach { assertFalse(it.contains(text) || it.contains("secreto"), it) }
        }
        assertFalse(TraceVersion(response, "\"3\"").toString().contains("secreto"))
        assertFalse(TraceVersion(response, "\"3\"").toString().contains(SECRET))
    }

    @Test
    fun errorsAreTypedForEveryOperation() =
        runTest(timeout = 10.seconds) {
            core { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED") }.use { core ->
                assertFailsWith<ApiError.PreconditionFailed> { core.traces.update(TRACE, change(), "\"1\"") }
            }
            core { problem(HttpStatusCode.NotFound, "TRACE_NOT_FOUND") }.use { core ->
                val e = assertFailsWith<ApiError.Problem> { core.traces.trace(TRACE) }
                assertEquals(404, e.status)
                assertEquals("TRACE_NOT_FOUND", e.code)
            }
            core { problem(HttpStatusCode.Conflict, "TRACE_ALREADY_RESOLVED") }.use { core ->
                val e = assertFailsWith<ApiError.Problem> { core.traces.resolve(TRACE) }
                assertEquals(409, e.status)
                assertEquals("TRACE_ALREADY_RESOLVED", e.code)
            }
            core { problem(HttpStatusCode.Forbidden, "FORBIDDEN") }.use { core ->
                assertEquals(
                    403,
                    assertFailsWith<ApiError.Problem> { core.traces.page(TraceFilter(), null, 10) }.status,
                )
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.traces.create(newTrace()) }.status)
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.traces.departments() }.status)
            }
        }

    @Test
    fun unauthorizedEndsSession() =
        runTest(timeout = 10.seconds) {
            core { problem(HttpStatusCode.Unauthorized, "UNAUTHENTICATED") }.use { core ->
                assertFailsWith<ApiError.SessionEnded> { core.traces.trace(TRACE) }
                assertFailsWith<ApiError.SessionEnded> { core.traces.departments() }
            }
        }

    @Test
    fun networkFailureIsNetworkErrorOnEveryOperation() =
        runTest(timeout = 10.seconds) {
            core { throw IOException("caiu") }.use { core ->
                assertTrue(assertFailsWith<ApiError> { core.traces.trace(TRACE) } is ApiError.Network)
                assertTrue(assertFailsWith<ApiError> { core.traces.create(newTrace()) } is ApiError.Network)
                assertTrue(
                    assertFailsWith<ApiError> { core.traces.update(TRACE, change(), "\"1\"") } is ApiError.Network,
                )
                assertTrue(assertFailsWith<ApiError> { core.traces.resolve(TRACE) } is ApiError.Network)
                assertTrue(assertFailsWith<ApiError> { core.traces.departments() } is ApiError.Network)
            }
        }

    @Test
    fun createNetworkFailureIsNotRetried() =
        runTest(timeout = 10.seconds) {
            core { throw IOException("caiu") }.use { core ->
                assertFailsWith<ApiError.Network> { core.traces.create(newTrace()) }
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun departmentsStopsWhenServerDoesNotAdvanceTheCursor() =
        runTest(timeout = 10.seconds) {
            core { json("""{"items":[${department(DEPT, "A")}],"nextCursor":"mesmo"}""") }.use { core ->
                val all = core.traces.departments()
                assertTrue(requests.size <= 3, "chamadas: ${requests.size}")
                assertEquals(DEPT, all.first().id)
            }
        }

    @Test
    fun departmentsStopsOnBlankCursorAndKeepsServerOrder() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json("""{"items":[${department(DEPT_2, "Z")}],"nextCursor":"p2"}""")
                    "p2" -> json("""{"items":[${department(DEPT, "A")}],"nextCursor":"  "}""")
                    else -> error("não deveria pedir mais")
                }
            }.use { core ->
                assertEquals(listOf("Z", "A"), core.traces.departments().map { it.name })
                assertEquals(2, requests.size)
            }
        }

    @Test
    fun departmentsEmptyListAndNullCursor() =
        runTest(timeout = 10.seconds) {
            core { json("""{"items":[],"nextCursor":null}""") }.use { core ->
                assertEquals(emptyList(), core.traces.departments())
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun departmentsFailureOnSecondPageIsTypedAndDropsPartialResult() =
        runTest(timeout = 10.seconds) {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json("""{"items":[${department(DEPT, "A")}],"nextCursor":"p2"}""")
                } else {
                    problem(HttpStatusCode.Forbidden, "FORBIDDEN")
                }
            }.use { core ->
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.traces.departments() }.status)
            }
        }
}
