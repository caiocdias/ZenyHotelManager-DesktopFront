package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d06"
private const val ECHO = "hóspede $THE_NAME CPF $THE_CPF"
private val CANCEL_REASONS = listOf("GUEST_REQUEST", "HOTEL_REQUEST", "DUPLICATE", "NO_GUARANTEE", "OTHER", "EXPIRED")

/** Ataque da Task 2 (E05): o cancelamento do seed não deixa dado de hóspede em nenhum caminho de erro. */
class SdkPendingGatewayCancelAtaqueTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val bodies = CopyOnWriteArrayList<String>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkPendingGateway(
            SeedCore(
                SERVER_A,
                MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                },
            ),
        )

    private fun assertClean(failure: Throwable) {
        everyText(failure).forEach {
            assertFalse(THE_CPF in it, it)
            assertFalse("Sigilosa" in it, it)
            assertFalse("hóspede" in it, it)
        }
    }

    private suspend fun cancelFailure(
        status: HttpStatusCode,
        body: String,
        contentType: String = "application/json",
    ): Throwable {
        val gateway = gateway { respond(body, status, headersOf(HttpHeaders.ContentType, contentType)) }
        return assertFailsWith<Throwable> { gateway.cancelReservation(RES, "GUEST_REQUEST") }
    }

    @Test
    fun problemWithFieldErrorsDropsDetailAndFieldMessages() =
        runTest {
            val body =
                """{"code":"VALIDATION_ERROR","title":"T","status":422,"detail":"$ECHO",
                |"errors":[{"field":"reason","code":"X","message":"$ECHO"}]}
                """.trimMargin()
            val error = cancelFailure(HttpStatusCode.UnprocessableEntity, body)
            val problem = error as ApiError.Problem
            assertEquals(422, problem.status)
            assertEquals("VALIDATION_ERROR", problem.code)
            assertEquals(emptyList(), problem.fieldErrors.map { it.message })
            assertClean(error)
        }

    @Test
    fun nonJsonRefusalBodyCarriesOnlyStatus() =
        runTest {
            val error = cancelFailure(HttpStatusCode.BadGateway, "<html>$ECHO</html>", "text/html")
            assertClean(error)
        }

    @Test
    fun problemWithoutCatalogCodeDoesNotEchoDetailInUserMessage() =
        runTest {
            val error =
                cancelFailure(HttpStatusCode.Conflict, """{"code":"ZZ_UNKNOWN","status":409,"detail":"$ECHO"}""")
            assertClean(error)
        }

    @Test
    fun approvalRequiredRefusalDoesNotCarryServerDetail() =
        runTest {
            val body =
                """{"code":"APPROVAL_REQUIRED","status":403,"detail":"$ECHO","action":"reservation.cancel",
                |"permission":"reservations.cancel","resourceRef":"reservation:$RES"}
                """.trimMargin()
            val error = cancelFailure(HttpStatusCode.Forbidden, body)
            assertClean(error)
            val approval = assertIs<ApiError.ApprovalRequired>(error, "o 403 APPROVAL_REQUIRED continua tipado")
            assertEquals(null, approval.detail)
            assertEquals("reservation.cancel", approval.action)
        }

    @Test
    fun preconditionFailedRefusalDoesNotCarryServerDetail() =
        runTest {
            val error =
                cancelFailure(HttpStatusCode.PreconditionFailed, """{"code":"STALE","status":412,"detail":"$ECHO"}""")
            assertClean(error)
            val stale = assertIs<ApiError.PreconditionFailed>(error, "o 412 continua tipado")
            assertEquals(null, stale.detail)
        }

    @Test
    fun malformedSuccessBodyDoesNotEchoTheBody() =
        runTest {
            assertClean(cancelFailure(HttpStatusCode.OK, """{"reservation":"$ECHO""""))
            assertClean(cancelFailure(HttpStatusCode.OK, """{"unexpected":"$ECHO"}"""))
        }

    @Test
    fun networkFailureDoesNotCarryRequestData() =
        runTest {
            val gateway = gateway { throw IOException("falha ao enviar $ECHO") }
            val error = assertFailsWith<ApiError.Network> { gateway.cancelReservation(RES, "GUEST_REQUEST") }
            assertEquals(1, requests.size, "sem repetição às cegas")
            assertEquals(error.userMessage, error.message)
        }

    @Test
    fun networkFailureCauseCarriesNoRequestBodyKeyOrToken() =
        runTest {
            var sentKey = ""
            val gateway =
                gateway { request ->
                    sentKey = request.headers["Idempotency-Key"].orEmpty()
                    throw IOException("Connection reset")
                }
            val error = assertFailsWith<ApiError.Network> { gateway.cancelReservation(RES, "GUEST_REQUEST") }
            assertTrue(sentKey.isNotBlank())
            val texts = everyText(error)
            assertTrue(texts.any { "Connection reset" in it }, "a causa é a falha da rede: $texts")
            texts.forEach {
                assertFalse(sentKey in it, "a chave de idempotência não sobe no erro: $it")
                assertFalse("GUEST_REQUEST" in it, "o corpo do pedido não sobe no erro: $it")
                assertFalse("waivePenalty" in it, it)
                assertFalse("Bearer" in it, it)
            }
        }

    @Test
    fun unauthorizedRefusalIsATypedSessionEnd() =
        runTest {
            val error = cancelFailure(HttpStatusCode.Unauthorized, """{"code":"X","status":401,"detail":"$ECHO"}""")
            assertClean(error)
        }

    @Test
    fun everyContractReasonIsSentVerbatimWithWaiverFalse() =
        runTest {
            val gateway =
                gateway { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            CANCEL_REASONS.forEach { runCatching { gateway.cancelReservation(RES, it) } }
            assertEquals(
                CANCEL_REASONS,
                bodies.map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("reason")
                        .jsonPrimitive.content
                },
            )
            assertTrue(
                bodies.all {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("waivePenalty")
                        .jsonPrimitive.content ==
                        "false"
                },
            )
            assertEquals(CANCEL_REASONS.size, requests.map { it.headers["Idempotency-Key"] }.toSet().size)
        }

    @Test
    fun reasonOutsideTheEnumIsCaseSensitiveAndFailsBeforeTheNetwork() =
        runTest {
            val gateway = gateway { error("não devia ir à rede") }
            listOf("guest_request", "", " OTHER", "OTHER ").forEach {
                assertFailsWith<IllegalArgumentException> { gateway.cancelReservation(RES, it) }
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun cancelKeyDiffersFromTheCreationKey() =
        runTest {
            val booking =
                """{"channel":"WEBSITE","code":"B-1","createdAt":"$NOW","holderGuestId":"$U1","id":"$U1",
                |"origin":"WEBSITE","reservations":[],"restrictionAlerts":[],"version":1}
                """.trimMargin()
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/cancel")) {
                        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        reply(booking, HttpStatusCode.Created)
                    }
                }
            runCatching {
                gateway.createReservation(
                    com.zeny.desktop.seed.pending.PendingReservationSpec(
                        "DEMO-FUT-C1",
                        U1,
                        U2,
                        U3,
                        "PHONE",
                        DAY.plusDays(5),
                        DAY.plusDays(7),
                        2,
                    ),
                )
            }
            runCatching { gateway.cancelReservation(RES, "GUEST_REQUEST") }
            val keys = requests.map { it.headers["Idempotency-Key"].orEmpty() }
            assertEquals(2, keys.size)
            assertTrue(keys.all { it.isNotBlank() })
            assertNotEquals(keys[0], keys[1])
        }
}
