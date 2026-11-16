package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.frontdesk.FakeTraces
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val DEP_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val DEP_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e02"
private const val TR_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val PASSWORD = "Senha-Secreta-123"
private const val TRACE_TEXT = "Texto-Sigiloso-do-Recado"
private val CATALOGED = setOf("TRACE_INVALID", "UNKNOWN_DEPARTMENT")
private val KEYS = listOf("RECEPTION", "HOUSEKEEPING", "MAINTENANCE", "FOOD_AND_BEVERAGE", "EVENTS", "ADMINISTRATION")
private val ATK_DAY = LocalDate.of(2026, 11, 15)

private const val LOGIN =
    """{"accessToken":"A1","accessTokenExpiresAt":"2036-11-11T13:15:00Z","passwordChangeRequired":false,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2036-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}"""

private fun MockRequestHandleScope.reply(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/json",
): HttpResponseData = respond(body, status, Headers.build { append(HttpHeaders.ContentType, contentType) })

private fun dep(
    id: String,
    key: String?,
) = """{"createdAt":"2026-01-01T00:00:00Z","id":"$id",${key?.let { "\"key\":\"$it\"," }.orEmpty()}
       |"name":"Setor","updatedAt":"2026-01-01T00:00:00Z"}
    """.trimMargin()

private fun tr(
    id: String,
    departmentId: String,
) = """{"createdAt":"2026-11-15T10:00:00Z","createdBy":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e",
       |"departmentId":"$departmentId","departmentName":"Setor","dueOn":"2026-11-15","id":"$id",
       |"status":"OPEN","text":"Texto","version":1}
    """.trimMargin()

private fun pg(
    items: List<String>,
    next: String?,
) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

private fun uuid(n: Int) = "0199d6b8-1f2e-7a3b-8c4d-%012x".format(Locale.ROOT, n)

class SdkTraceGatewayAtaqueTest {
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
                if (request.url.encodedPath.endsWith("/login")) reply(LOGIN.trimMargin()) else handler(request)
            }
        val session = SdkSessionFactory(SERVER) { engine }.open()
        session.signIn("admin", Secret(PASSWORD))
        return SdkTraceGateway(session.seed)
    }

    private fun calls(suffix: String) = sent.filter { it.path.endsWith(suffix) }

    private fun problem(
        status: HttpStatusCode,
        code: String,
        detail: String = "x",
    ) = """{"code":"$code","status":${status.value},"title":"t","detail":"$detail","errors":[]}"""

    // ---- departamentos ----

    @Test
    fun departmentsAcrossManyPagesAreAllRead() =
        runTest {
            val gateway =
                gateway { request ->
                    val n =
                        request.url.parameters["cursor"]
                            ?.removePrefix("c")
                            ?.toInt() ?: 0
                    val next = if (n < KEYS.lastIndex) "c${n + 1}" else null
                    reply(pg(listOf(dep(uuid(n + 1), KEYS[n])), next))
                }
            val result = gateway.departmentIdsByKey()
            assertEquals(KEYS.size, result.size)
            assertEquals(uuid(KEYS.size), result["ADMINISTRATION"])
            assertEquals(KEYS.size, calls("/departments").size)
        }

    @Test
    fun departmentsWithServerThatDoesNotAdvanceCursorStops() =
        runTest {
            val gateway = gateway { reply(pg(listOf(dep(DEP_A, "RECEPTION")), "preso")) }
            assertEquals(mapOf("RECEPTION" to DEP_A), gateway.departmentIdsByKey())
            assertTrue(calls("/departments").size <= 3, "chamadas: ${calls("/departments").size}")
        }

    @Test
    fun departmentsWithBlankNextCursorStop() =
        runTest {
            val gateway = gateway { reply(pg(listOf(dep(DEP_A, "RECEPTION")), "  ")) }
            gateway.departmentIdsByKey()
            assertEquals(1, calls("/departments").size)
        }

    @Test
    fun departmentsWithoutKeyAreOmittedAndEmptyListGivesEmptyMap() =
        runTest {
            val gateway = gateway { reply(pg(listOf(dep(DEP_A, null)), null)) }
            assertEquals(emptyMap(), gateway.departmentIdsByKey())
        }

    @Test
    fun departmentsWithDuplicatedKeyKeepTheLastOne() =
        runTest {
            val gateway = gateway { reply(pg(listOf(dep(DEP_A, "RECEPTION"), dep(DEP_B, "RECEPTION")), null)) }
            // Documenta o comportamento: a chave duplicada não falha, vale a última.
            assertEquals(mapOf("RECEPTION" to DEP_B), gateway.departmentIdsByKey())
        }

    @Test
    fun departmentsAreRequestedWithPageLimitAndCursorOnGet() =
        runTest {
            val gateway = gateway { reply(pg(emptyList(), null)) }
            gateway.departmentIdsByKey()
            val call = calls("/departments").single()
            assertEquals(HttpMethod.Get, call.method)
            assertEquals("200", call.query["limit"])
            assertFalse("cursor" in call.query)
            assertNull(call.idempotencyKey)
        }

    // ---- listagem de recados ----

    @Test
    fun tracesWithBothFiltersNullSendNoFilterAtAll() =
        runTest {
            val gateway = gateway { reply(pg(listOf(tr(TR_1, DEP_A)), null)) }
            val result = gateway.traces(null, null)
            val call = calls("/traces").single()
            assertEquals(setOf("limit"), call.query.keys)
            // Documenta: sem filtro o adaptador lista TODOS os recados do hotel.
            assertEquals(listOf(TraceRef(TR_1, DEP_A)), result)
        }

    @Test
    fun tracesSendOnlyTheLinkFilterAndNeverStatusDepartmentOrDueOn() =
        runTest {
            val gateway = gateway { reply(pg(emptyList(), null)) }
            gateway.traces(RES, null)
            gateway.traces(null, ROOM_ID)
            gateway.traces(RES, ROOM_ID)
            val (byRes, byRoom, both) = calls("/traces")
            assertEquals(setOf("limit", "reservationId"), byRes.query.keys)
            assertEquals(setOf("limit", "roomId"), byRoom.query.keys)
            assertEquals(setOf("limit", "reservationId", "roomId"), both.query.keys)
            assertEquals(RES, both.query["reservationId"])
            assertEquals(ROOM_ID, both.query["roomId"])
            assertTrue(calls("/traces").all { it.method == HttpMethod.Get && it.idempotencyKey == null })
        }

    @Test
    fun tracesAcrossManyPagesKeepOrderAndRepeatTheFilterOnEveryPage() =
        runTest {
            val gateway =
                gateway { request ->
                    val n =
                        request.url.parameters["cursor"]
                            ?.removePrefix("c")
                            ?.toInt() ?: 0
                    reply(pg(listOf(tr(uuid(100 + n), DEP_A)), if (n < 4) "c${n + 1}" else null))
                }
            val result = gateway.traces(RES, null)
            assertEquals((0..4).map { TraceRef(uuid(100 + it), DEP_A) }, result)
            assertEquals(5, calls("/traces").size)
            assertTrue(calls("/traces").all { it.query["reservationId"] == RES })
        }

    @Test
    fun tracesWithServerThatDoesNotAdvanceCursorStopsWithoutDuplicatingForever() =
        runTest {
            val gateway = gateway { reply(pg(listOf(tr(TR_1, DEP_A)), "preso")) }
            val result = gateway.traces(RES, null)
            assertTrue(result.size <= 3, "itens: ${result.size}")
            assertTrue(calls("/traces").size <= 3)
        }

    @Test
    fun tracesEmptyPageGivesEmptyList() =
        runTest {
            val gateway = gateway { reply(pg(emptyList(), null)) }
            assertEquals(emptyList(), gateway.traces(RES, null))
        }

    @Test
    fun tracesWithMalformedIdFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { reply(pg(emptyList(), null)) }
            val before = sent.size
            assertFailsWith<IllegalArgumentException> { gateway.traces("nao-e-uuid", null) }
            assertEquals(before, sent.size)
        }

    // ---- criação ----

    @Test
    fun createBodyWithReservationHasExactlyTheFourFields() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            assertEquals(TR_1, gateway.create(DEP_A, ATK_DAY, TRACE_TEXT, RES, null))
            val post = calls("/traces").single()
            assertEquals(HttpMethod.Post, post.method)
            assertNull(post.idempotencyKey)
            val body = post.body!!
            assertEquals(setOf("departmentId", "dueOn", "text", "reservationId"), body.keys)
            assertEquals(DEP_A, body.getValue("departmentId").jsonPrimitive.content)
            assertEquals("2026-11-15", body.getValue("dueOn").jsonPrimitive.content)
            assertEquals(TRACE_TEXT, body.getValue("text").jsonPrimitive.content)
            assertEquals(RES, body.getValue("reservationId").jsonPrimitive.content)
        }

    @Test
    fun createBodyWithRoomHasExactlyTheFourFields() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            gateway.create(DEP_A, ATK_DAY, "Texto", null, ROOM_ID)
            val body = calls("/traces").single().body!!
            assertEquals(setOf("departmentId", "dueOn", "text", "roomId"), body.keys)
            assertEquals(ROOM_ID, body.getValue("roomId").jsonPrimitive.content)
        }

    @Test
    fun createDueOnIsZeroPaddedIsoDate() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            gateway.create(DEP_A, LocalDate.of(2026, 1, 5), "Texto", null, ROOM_ID)
            assertEquals(
                "2026-01-05",
                calls("/traces")
                    .single()
                    .body!!
                    .getValue("dueOn")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun createKeepsTextVerbatimWithAccentsQuotesAndNewlines() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            val text = "Atenção \"ar\" condicionado\n  ação ✓ "
            gateway.create(DEP_A, ATK_DAY, text, null, ROOM_ID)
            assertEquals(
                text,
                calls("/traces")
                    .single()
                    .body!!
                    .getValue("text")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun createDoesNotRetryOnServerError() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        problem(HttpStatusCode.InternalServerError, "INTERNAL_ERROR"),
                        HttpStatusCode.InternalServerError,
                        "application/problem+json",
                    )
                }
            assertFailsWith<ApiError.Problem> { gateway.create(DEP_A, ATK_DAY, "Texto", null, ROOM_ID) }
            assertEquals(1, calls("/traces").size)
        }

    @Test
    fun createWithBothLinksSendsBothAndLeavesTheXorToTheServer() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            gateway.create(DEP_A, ATK_DAY, "Texto", RES, ROOM_ID)
            // Documenta: o adaptador nao valida reserva XOR UH; quem recusa e o servidor (TRACE_INVALID).
            assertEquals(
                setOf("departmentId", "dueOn", "text", "reservationId", "roomId"),
                calls("/traces").single().body!!.keys,
            )
        }

    @Test
    fun createWithNoLinkSendsNeitherLinkField() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            gateway.create(DEP_A, ATK_DAY, "Texto", null, null)
            assertEquals(setOf("departmentId", "dueOn", "text"), calls("/traces").single().body!!.keys)
        }

    @Test
    fun createRejectedByServerForLinkRuleIsApiErrorProblemWithoutRetry() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        problem(HttpStatusCode.BadRequest, "TRACE_INVALID"),
                        HttpStatusCode.BadRequest,
                        "application/problem+json",
                    )
                }
            val failure = assertFailsWith<ApiError.Problem> { gateway.create(DEP_A, ATK_DAY, "Texto", RES, ROOM_ID) }
            assertEquals("TRACE_INVALID", failure.code)
            assertEquals(1, calls("/traces").size)
        }

    @Test
    fun createWithMalformedIdsFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { reply(tr(TR_1, DEP_A), HttpStatusCode.Created) }
            assertFailsWith<IllegalArgumentException> { gateway.create("x", ATK_DAY, "Texto", null, ROOM_ID) }
            assertFailsWith<IllegalArgumentException> { gateway.create(DEP_A, ATK_DAY, "Texto", "x", null) }
            assertTrue(calls("/traces").isEmpty())
        }

    // ---- erros ----

    @Test
    fun problemStatusesPropagateAsApiErrorWithoutLeakingTextOrSecrets() =
        runTest {
            val cases =
                listOf(
                    HttpStatusCode.BadRequest to "TRACE_INVALID",
                    HttpStatusCode.BadRequest to "UNKNOWN_DEPARTMENT",
                    HttpStatusCode.Forbidden to "FORBIDDEN",
                    HttpStatusCode.InternalServerError to "INTERNAL_ERROR",
                    HttpStatusCode.ServiceUnavailable to "SERVICE_UNAVAILABLE",
                )
            for ((status, code) in cases) {
                val gateway =
                    gateway {
                        reply(
                            problem(
                                status,
                                code,
                                if (code in CATALOGED) "eco $TRACE_TEXT $PASSWORD" else "falha",
                            ),
                            status,
                            "application/problem+json",
                        )
                    }
                val failure =
                    assertFailsWith<ApiError>(
                        "$status $code",
                    ) { gateway.create(DEP_A, ATK_DAY, TRACE_TEXT, null, ROOM_ID) }
                assertNoLeak(failure)
                if (failure is ApiError.Problem) {
                    assertEquals(status.value, failure.status)
                    assertEquals(code, failure.code)
                }
            }
        }

    @Test
    fun unauthorizedOnEveryOperationIsApiErrorAndLeaksNothing() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        problem(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "eco $TRACE_TEXT"),
                        HttpStatusCode.Unauthorized,
                        "application/problem+json",
                    )
                }
            assertNoLeak(assertFailsWith<ApiError> { gateway.create(DEP_A, ATK_DAY, TRACE_TEXT, null, ROOM_ID) })
            assertNoLeak(assertFailsWith<ApiError> { gateway.traces(RES, null) })
            assertNoLeak(assertFailsWith<ApiError> { gateway.departmentIdsByKey() })
        }

    @Test
    fun htmlServerErrorBodyIsApiErrorWithoutLeakingBody() =
        runTest {
            val gateway =
                gateway { reply("<html>$TRACE_TEXT $PASSWORD</html>", HttpStatusCode.BadGateway, "text/html") }
            assertNoLeak(assertFailsWith<ApiError> { gateway.create(DEP_A, ATK_DAY, TRACE_TEXT, null, ROOM_ID) })
            assertNoLeak(assertFailsWith<ApiError> { gateway.traces(null, ROOM_ID) })
        }

    @Test
    fun networkFailureIsApiErrorNetworkWithoutTextOrSecrets() =
        runTest {
            val gateway = gateway { throw IOException("queda $TRACE_TEXT $PASSWORD") }
            assertNoLeak(
                assertFailsWith<ApiError.Network> { gateway.create(DEP_A, ATK_DAY, TRACE_TEXT, null, ROOM_ID) },
            )
            assertNoLeak(assertFailsWith<ApiError.Network> { gateway.traces(RES, null) })
            assertNoLeak(assertFailsWith<ApiError.Network> { gateway.departmentIdsByKey() })
        }

    @Test
    fun errorOnSecondPageOfListingPropagatesInsteadOfReturningPartialList() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        reply(pg(listOf(tr(TR_1, DEP_A)), "c2"))
                    } else {
                        reply(
                            problem(HttpStatusCode.InternalServerError, "INTERNAL_ERROR"),
                            HttpStatusCode.InternalServerError,
                            "application/problem+json",
                        )
                    }
                }
            assertFailsWith<ApiError.Problem> { gateway.traces(RES, null) }
        }

    private fun assertNoLeak(failure: Throwable) {
        val all = "${failure.message} ${(failure as? ApiError)?.userMessage}"
        assertFalse(TRACE_TEXT in all, "texto vazou: $all")
        assertFalse(PASSWORD in all, "senha vazou: $all")
    }

    // ---- cancelamento ----

    @Test
    fun cancellationFromTransportPropagatesAndIsNotWrappedAsNetwork() =
        runTest {
            val gateway = gateway { throw CancellationException("cancelado") }
            assertFailsWith<CancellationException> { gateway.create(DEP_A, ATK_DAY, "Texto", null, ROOM_ID) }
            assertFailsWith<CancellationException> { gateway.traces(RES, null) }
            assertFailsWith<CancellationException> { gateway.departmentIdsByKey() }
        }

    @Test
    fun cancellingTheCallerWhileCreateIsInFlightCancelsIt() =
        runTest {
            val gateway = gateway { awaitCancellation() }
            val job =
                async(start = CoroutineStart.UNDISPATCHED) { gateway.create(DEP_A, ATK_DAY, "Texto", null, ROOM_ID) }
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            assertTrue(job.isCancelled)
        }

    // ---- FakeTraces: paridade com o adaptador para o que a Task 2 usa ----

    @Test
    fun fakeListsByReservationOrRoomLikeTheAdapter() =
        runTest {
            val fake = FakeTraces()
            val a = fake.create("dep-reception", ATK_DAY, "a", "res-1", null)
            val b = fake.create("dep-housekeeping", ATK_DAY, "b", null, "room-1")
            fake.create("dep-maintenance", ATK_DAY, "c", "res-2", null)
            assertEquals(listOf(TraceRef(a, "dep-reception")), fake.traces("res-1", null))
            assertEquals(listOf(TraceRef(b, "dep-housekeeping")), fake.traces(null, "room-1"))
            assertEquals(emptyList(), fake.traces("res-1", "room-9").filter { it.id == b })
            assertEquals(emptyList(), fake.traces("inexistente", null))
        }

    @Test
    fun fakeWithBothFiltersNullReturnsNothingWhileAdapterReturnsEverything() =
        runTest {
            val fake = FakeTraces()
            fake.create("dep-reception", ATK_DAY, "a", "res-1", null)
            // Divergência documentada: o adaptador, sem filtro, lista todos os recados.
            assertEquals(emptyList(), fake.traces(null, null))
        }

    @Test
    fun fakeCreatedIdsAreDistinctAndDepartmentKeysAreStable() =
        runTest {
            val fake = FakeTraces()
            val ids = (1..5).map { fake.create("dep-reception", ATK_DAY, "t$it", null, "room-$it") }
            assertEquals(ids.size, ids.toSet().size)
            assertEquals(setOf("RECEPTION", "HOUSEKEEPING", "MAINTENANCE"), fake.departmentIdsByKey().keys)
            assertEquals(5, fake.createCalls)
        }

    @Test
    fun fakeInjectedFailureIsThrownOnceCountsTheCallAndStoresNothing() =
        runTest {
            val fake = FakeTraces()
            fake.failures += ApiError.Network(IOException("x"))
            assertFailsWith<ApiError.Network> { fake.create("dep-reception", ATK_DAY, "t", null, "room-1") }
            assertEquals(1, fake.createCalls)
            assertTrue(fake.traces.isEmpty())
            val id = fake.create("dep-reception", ATK_DAY, "t", null, "room-1")
            assertEquals(2, fake.createCalls)
            assertEquals(listOf(TraceRef(id, "dep-reception")), fake.traces(null, "room-1"))
        }

    @Test
    fun fakeRejectsBothOrNoLinkOnCreate() =
        runTest {
            val fake = FakeTraces()
            assertFailsWith<IllegalArgumentException> { fake.create("dep-reception", ATK_DAY, "t", "r", "u") }
            assertFailsWith<IllegalArgumentException> { fake.create("dep-reception", ATK_DAY, "t", null, null) }
            assertTrue(fake.traces.isEmpty())
        }

    @Test
    fun fakeCreatedIdsDoNotCollideAfterFailuresInterleave() =
        runTest {
            val fake = FakeTraces()
            val first = fake.create("dep-reception", ATK_DAY, "t", null, "room-1")
            fake.failures += ApiError.Unexpected(500)
            runCatching { fake.create("dep-reception", ATK_DAY, "t", null, "room-2") }
            val second = fake.create("dep-reception", ATK_DAY, "t", null, "room-2")
            assertTrue(first != second)
        }
}
