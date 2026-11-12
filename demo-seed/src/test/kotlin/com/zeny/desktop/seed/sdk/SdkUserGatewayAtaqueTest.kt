package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.cli.Secret
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val ADMIN_PASSWORD = "Admin-Senha-000"
private const val PASSWORD = "Prov-Senha-777"
private const val PIN = "482915"
private const val ID_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ID_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ROLE_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val ROLE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val ROLE_3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"

private fun MockRequestHandleScope.reply(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/json",
    vararg extra: Pair<String, String>,
): HttpResponseData =
    respond(
        body,
        status,
        Headers.build {
            append(HttpHeaders.ContentType, contentType)
            extra.forEach { (k, v) -> append(k, v) }
        },
    )

private fun MockRequestHandleScope.problem(
    status: HttpStatusCode,
    code: String,
    detail: String,
) = reply("""{"code":"$code","detail":"$detail","status":${status.value}}""", status, "application/problem+json")

private const val LOGIN_BODY =
    """{"accessToken":"A1","accessTokenExpiresAt":"2036-11-11T13:15:00Z","passwordChangeRequired":false,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2036-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}"""

private fun role(
    id: String,
    template: String?,
    active: Boolean = true,
    explicitNull: Boolean = false,
) = """{"active":$active,"createdAt":"2026-01-01T00:00:00Z","customized":false,"description":"d","grants":[],
       |"id":"$id","name":"r$id","systemRole":false,${
    when {
        template != null -> "\"template\":\"$template\","
        explicitNull -> "\"template\":null,"
        else -> ""
    }
}
       |"updatedAt":"2026-01-01T00:00:00Z"}
    """.trimMargin()

private fun user(
    id: String,
    login: String,
    active: Boolean = true,
    roles: List<String> = emptyList(),
) = """{"active":$active,"administrator":false,"createdAt":"2026-01-01T00:00:00Z","hasPin":false,"id":"$id",
       |"login":"$login","name":"Nome","passwordChangeRequired":false,
       |"roles":[${roles.joinToString(",") { """{"active":true,"id":"$it","name":"n"}""" }}],
       |"updatedAt":"2026-01-01T00:00:00Z"}
    """.trimMargin()

private fun page(
    items: List<String>,
    next: String?,
) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

private fun chain(failure: Throwable): List<Throwable> = generateSequence(failure) { it.cause }.toList()

private fun secretsIn(text: String): List<String> = listOf(PASSWORD, PIN, ADMIN_PASSWORD).filter { it in text }

/** O que uma exceção mostra a quem a imprime: mensagem, toString e texto de usuário de toda a cadeia. */
private fun leaks(failure: Throwable): List<String> =
    chain(failure).flatMap { t ->
        secretsIn(t.message.orEmpty() + "|" + t.toString() + "|" + ((t as? ApiError)?.userMessage.orEmpty()))
    }

/** Campos estruturados que o `core` copia do problema do servidor (detail, errors, action, resourceRef). */
private fun leaksInFields(failure: Throwable): List<String> =
    chain(failure).flatMap { t ->
        val text =
            buildString {
                if (t is ApiError.Problem) {
                    append(t.detail.orEmpty())
                    t.fieldErrors.forEach { append('|').append(it.message).append(it.field).append(it.code) }
                }
                if (t is ApiError.ApprovalRequired) append(t.detail.orEmpty()).append(t.action).append(t.resourceRef)
                if (t is ApiError.PreconditionFailed) append(t.detail.orEmpty())
            }
        secretsIn(text)
    }

class SdkUserGatewayAtaqueTest {
    private class Sent(
        val method: HttpMethod,
        val url: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val rawBody: String,
    ) {
        val ifMatch get() = headers[HttpHeaders.IfMatch]?.single()
        val json: JsonObject? get() = rawBody.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
        val isLogin get() = path.endsWith("/login")
    }

    private val sent = CopyOnWriteArrayList<Sent>()
    private val calls get() = sent.filter { !it.isLogin }

    private suspend fun gateway(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SdkUserGateway {
        val engine =
            MockEngine { request ->
                sent +=
                    Sent(
                        request.method,
                        request.url.toString(),
                        request.url.encodedPath,
                        request.headers.entries().associate { it.key to it.value },
                        request.body.toByteArray().decodeToString(),
                    )
                if (request.url.encodedPath.endsWith("/login")) {
                    reply(LOGIN_BODY.trimMargin())
                } else {
                    handler(request)
                }
            }
        val session = SdkSessionFactory(SERVER) { engine }.open()
        session.signIn("admin", Secret(ADMIN_PASSWORD))
        return SdkUserGateway(session.seed)
    }

    private suspend fun SdkUserGateway.everyOperation(): List<Pair<String, suspend () -> Any?>> =
        listOf<Pair<String, suspend () -> Any?>>(
            "createUser" to { createUser("a", "A", Secret(PASSWORD), listOf(ROLE_1), Secret(PIN)) },
            "resetPassword" to { resetPassword(ID_A, Secret(PASSWORD)) },
            "resetPin" to { resetPin(ID_A, Secret(PIN)) },
            "reactivate" to { reactivate(ID_A) },
            "setRoles" to { setRoles(ID_A, listOf(ROLE_1)) },
            "roleIdsByTemplate" to { roleIdsByTemplate() },
            "findUser" to { findUser("a") },
        )

    // ---------- segredos: URL, headers, log, mensagens ----------

    @Test
    fun secretsTravelOnlyInTheJsonBodyOfTheirOwnCall() =
        runTest {
            val gateway = gateway { reply(user(ID_A, "a"), HttpStatusCode.Created) }
            gateway.createUser("a", "A", Secret(PASSWORD), listOf(ROLE_1), Secret(PIN))
            val ok = gateway.runCatching { resetPassword(ID_A, Secret(PASSWORD)) }
            gateway.runCatching { resetPin(ID_A, Secret(PIN)) }
            assertNotNull(ok)
            assertTrue(calls.isNotEmpty())
            calls.forEach { call ->
                assertFalse(PASSWORD in call.url || PIN in call.url, "segredo na URL: ${call.url}")
                call.headers.forEach { (name, values) ->
                    values.forEach { assertFalse(PASSWORD in it || PIN in it, "segredo no header $name") }
                }
            }
            // a senha provisória nunca vai no corpo do reset de PIN, nem o PIN no do reset de senha
            val bodies = calls.filter { it.path.endsWith("/reset-password") }.map { it.rawBody }
            assertTrue(bodies.all { PIN !in it })
            val pinBodies = calls.filter { it.path.endsWith("/reset-pin") }.map { it.rawBody }
            assertTrue(pinBodies.all { PASSWORD !in it })
        }

    @Test
    fun noSecretReachesStdoutOrStderrEvenOnFailure() =
        runTest {
            val gateway =
                gateway { problem(HttpStatusCode.UnprocessableEntity, "VALIDATION_FAILED", "ruim: $PASSWORD $PIN") }
            val out = ByteArrayOutputStream()
            val oldOut = System.out
            val oldErr = System.err
            System.setOut(PrintStream(out, true))
            System.setErr(PrintStream(out, true))
            try {
                gateway.everyOperation().forEach { (_, op) -> runCatching { op() } }
            } finally {
                System.setOut(oldOut)
                System.setErr(oldErr)
            }
            val text = out.toString()
            assertFalse(PASSWORD in text || PIN in text, "segredo no stdout/stderr: $text")
        }

    @Test
    fun serverEchoingTheSecretsInA400OrA422NeverReachesTheApiErrorChain() =
        runTest {
            for (status in listOf(
                HttpStatusCode.BadRequest,
                HttpStatusCode.UnprocessableEntity,
                HttpStatusCode.Conflict,
            )) {
                val gateway =
                    gateway {
                        reply(
                            """{"code":"VALIDATION_FAILED","detail":"senha $PASSWORD e pin $PIN inválidos",
                              |"errors":[{"field":"pin","code":"INVALID","message":"o valor $PIN não serve"},
                              |{"field":"password","code":"INVALID","message":"$PASSWORD fraca"}]}
                            """.trimMargin(),
                            status,
                            "application/problem+json",
                        )
                    }
                listOf<suspend () -> Any?>(
                    { gateway.createUser("a", "A", Secret(PASSWORD), listOf(ROLE_1), Secret(PIN)) },
                    { gateway.resetPassword(ID_A, Secret(PASSWORD)) },
                    { gateway.resetPin(ID_A, Secret(PIN)) },
                ).forEachIndexed { i, op ->
                    val failure = assertFailsWith<ApiError> { op() }
                    assertEquals(emptyList(), leaks(failure), "status $status, operação #$i")
                }
            }
        }

    @Test
    fun serverEchoInTheStructuredFieldsOfTheProblemDoesNotKeepTheSecrets() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        """{"code":"VALIDATION_FAILED","detail":"senha $PASSWORD e pin $PIN",
                          |"errors":[{"field":"pin","code":"INVALID","message":"o valor $PIN não serve"}]}
                        """.trimMargin(),
                        HttpStatusCode.BadRequest,
                        "application/problem+json",
                    )
                }
            val failure =
                assertFailsWith<ApiError> {
                    gateway.createUser(
                        "a",
                        "A",
                        Secret(PASSWORD),
                        listOf(ROLE_1),
                        Secret(PIN),
                    )
                }
            assertEquals(emptyList(), leaksInFields(failure))
        }

    @Test
    fun problemWithACodeOutsideTheCatalogEchoingTheSecretDoesNotShowItInTheUserMessage() =
        runTest {
            val gateway = gateway { problem(HttpStatusCode.UnprocessableEntity, "PIN_TOO_WEAK", "O PIN $PIN é fraco") }
            val failure = assertFailsWith<ApiError> { gateway.resetPin(ID_A, Secret(PIN)) }
            assertEquals(emptyList(), leaks(failure))
        }

    @Test
    fun unparseableSuccessBodyEchoingTheSecretNeverReachesTheCause() =
        runTest {
            val gateway = gateway { reply("""{"id":"$PASSWORD-$PIN","login":""", HttpStatusCode.Created) }
            val failure =
                assertFailsWith<ApiError> { gateway.createUser("a", "A", Secret(PASSWORD), listOf(), Secret(PIN)) }
            assertIs<ApiError.IncompatibleResponse>(failure)
            assertEquals(emptyList(), leaks(failure))
            // o corpo bem formado, mas de tipo errado, também
            val wrongType = gateway { reply("""{"id":"$PIN","login":"$PASSWORD"}""", HttpStatusCode.Created) }
            val f2 = assertFailsWith<ApiError> { wrongType.createUser("a", "A", Secret(PASSWORD), listOf(), null) }
            assertEquals(emptyList(), leaks(f2))
        }

    @Test
    fun failuresOfEveryKindCarryNoSecret() =
        runTest {
            val echo = "$PASSWORD $PIN"
            val responders: List<Pair<String, suspend MockRequestHandleScope.() -> HttpResponseData>> =
                listOf(
                    "403 PERMISSION_DENIED" to { problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED", echo) },
                    "404 sem código" to { problem(HttpStatusCode.NotFound, "NOT_FOUND", echo) },
                    "500 html" to { reply("<html>$echo</html>", HttpStatusCode.InternalServerError, "text/html") },
                    "503 problema" to { problem(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", echo) },
                    "412" to { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED", echo) },
                    "428" to { problem(HttpStatusCode.PreconditionRequired, "PRECONDITION_REQUIRED", echo) },
                    "401" to { problem(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", echo) },
                )
            for ((label, responder) in responders) {
                val gateway = gateway { responder() }
                gateway.everyOperation().forEach { (name, op) ->
                    val failure = runCatching { op() }.exceptionOrNull()
                    assertNotNull(failure, "$label/$name deveria falhar")
                    assertIs<ApiError>(failure, "$label/$name: ${failure::class}")
                    assertEquals(emptyList(), leaks(failure), "$label/$name")
                }
            }
        }

    @Test
    fun networkFailureIsApiErrorNetworkWithoutSecrets() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith(
                            "/login",
                        )
                    ) {
                        reply(LOGIN_BODY.trimMargin())
                    } else {
                        throw IOException("queda $PIN")
                    }
                }
            val session = SdkSessionFactory(SERVER) { engine }.open()
            session.signIn("admin", Secret(ADMIN_PASSWORD))
            val failure = assertFailsWith<ApiError.Network> { SdkUserGateway(session.seed).resetPin(ID_A, Secret(PIN)) }
            assertNotNull(failure)
            // a mensagem do IOException pode vir na causa; aqui só o que o código do seed controla: userMessage
            assertFalse(PASSWORD in failure.userMessage || PIN in failure.userMessage)
        }

    // ---------- PIN ausente ----------

    @Test
    fun absentPinIsAbsentFromTheRawJsonNotNull() =
        runTest {
            val gateway = gateway { reply(user(ID_A, "a"), HttpStatusCode.Created) }
            gateway.createUser("a", "A", Secret(PASSWORD), emptyList(), null)
            val raw = calls.single().rawBody
            assertFalse("pin" in raw, raw)
            assertFalse("null" in raw, raw)
            val body = calls.single().json!!
            assertEquals(setOf("login", "name", "password", "roleIds"), body.keys)
            assertEquals(0, body.getValue("roleIds").jsonArray.size)
        }

    @Test
    fun pinWithLeadingZerosIsSentAsTheExactString() =
        runTest {
            val gateway = gateway { reply(user(ID_A, "a"), HttpStatusCode.Created) }
            gateway.createUser("a", "A", Secret(PASSWORD), listOf(ROLE_1), Secret("0042"))
            assertTrue("\"pin\":\"0042\"" in calls.single().rawBody.replace(" ", ""), calls.single().rawBody)
        }

    // ---------- paginação ----------

    @Test
    fun usersAreSearchedAcrossThreePagesWithTheMaximumPageSize() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> reply(page(listOf(user(ID_A, "um")), "c2"))
                        "c2" -> reply(page(emptyList(), "c3"))
                        "c3" -> reply(page(listOf(user(ID_B, "alvo")), ""))
                        else -> error("cursor inesperado")
                    }
                }
            assertEquals(ID_B, gateway.findUser("alvo")?.id)
            val gets = calls.filter { it.path.endsWith("/users") }
            assertEquals(3, gets.size)
            assertTrue(gets.all { "limit=200" in it.url }, gets.map { it.url }.toString())
        }

    @Test
    fun emptyFirstPageAndEmptyCursorMeansNoUser() =
        runTest {
            val gateway = gateway { reply(page(emptyList(), "")) }
            assertNull(gateway.findUser("x"))
            assertEquals(1, calls.size)
        }

    @Test
    fun serverThatRepeatsTheCursorDoesNotLoopForever() =
        runTest {
            val gateway = gateway { reply(page(listOf(user(ID_A, "a")), "mesmo")) }
            assertNull(gateway.findUser("zzz"))
            assertTrue(calls.size <= 3, "chamadas: ${calls.size}")
        }

    @Test
    fun rolesAreReadAcrossPagesIncludingAnEmptyMiddlePage() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> reply(page(listOf(role(ROLE_1, "MANAGER")), "p2"))
                        "p2" -> reply(page(emptyList(), "p3"))
                        "p3" -> reply(page(listOf(role(ROLE_2, "HOUSEKEEPER")), null))
                        else -> error("cursor inesperado")
                    }
                }
            assertEquals(mapOf("MANAGER" to ROLE_1, "HOUSEKEEPER" to ROLE_2), gateway.roleIdsByTemplate())
            assertEquals(3, calls.size)
        }

    @Test
    fun noRolesAtAllGivesAnEmptyMap() =
        runTest {
            val gateway = gateway { reply(page(emptyList(), null)) }
            assertEquals(emptyMap(), gateway.roleIdsByTemplate())
        }

    // ---------- roles ----------

    @Test
    fun rolesWithoutTemplateOrWithNullTemplateAreIgnored() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        page(
                            listOf(
                                role(ROLE_1, null),
                                role(ROLE_2, null, explicitNull = true),
                                role(ROLE_3, "WAITER"),
                            ),
                            null,
                        ),
                    )
                }
            assertEquals(mapOf("WAITER" to ROLE_3), gateway.roleIdsByTemplate())
        }

    @Test
    fun onlyInactiveRolesGiveAnEmptyMap() =
        runTest {
            val gateway = gateway { reply(page(listOf(role(ROLE_1, "MANAGER", active = false)), null)) }
            assertEquals(emptyMap(), gateway.roleIdsByTemplate())
        }

    @Test
    fun unknownTemplateOfTheClosedEnumIsAnIncompatibleResponseNotASilentSkip() =
        runTest {
            // o contrato fecha o enum de template (12 valores); um valor novo é servidor mais novo que o app
            val gateway = gateway { reply(page(listOf(role(ROLE_1, "ASTRONAUT")), null)) }
            val failure = assertFailsWith<ApiError> { gateway.roleIdsByTemplate() }
            assertIs<ApiError.IncompatibleResponse>(failure)
        }

    @Test
    fun twoActiveRolesOfTheSameTemplateDoNotCrashAndResolveToOneOfThem() =
        runTest {
            val gateway =
                gateway { reply(page(listOf(role(ROLE_1, "MANAGER"), role(ROLE_2, "MANAGER")), null)) }
            val map = gateway.roleIdsByTemplate()
            assertEquals(setOf("MANAGER"), map.keys)
            assertTrue(map.getValue("MANAGER") in setOf(ROLE_1, ROLE_2))
        }

    @Test
    fun inactiveDuplicateNeverShadowsTheActiveRoleOfTheTemplate() =
        runTest {
            val gateway =
                gateway {
                    reply(page(listOf(role(ROLE_1, "MANAGER"), role(ROLE_2, "MANAGER", active = false)), null))
                }
            assertEquals(mapOf("MANAGER" to ROLE_1), gateway.roleIdsByTemplate())
        }

    // ---------- findUser ----------

    @Test
    fun findUserMatchesAnyCaseOnBothSidesAndReturnsInactiveUsers() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        page(
                            listOf(
                                user(ID_A, "GERENTE", active = false, roles = listOf(ROLE_1, ROLE_2)),
                                user(ID_B, "outro"),
                            ),
                            null,
                        ),
                    )
                }
            val found = assertNotNull(gateway.findUser("gerente"))
            assertEquals(ID_A, found.id)
            assertFalse(found.active)
            assertEquals(setOf(ROLE_1, ROLE_2), found.roleIds)
            assertNull(gateway.findUser("gerent"))
            assertNull(gateway.findUser(""))
        }

    @Test
    fun findUserOfAUserWithoutRolesGivesAnEmptySet() =
        runTest {
            val gateway = gateway { reply(page(listOf(user(ID_A, "a")), null)) }
            assertEquals(emptySet(), gateway.findUser("a")?.roleIds)
        }

    // ---------- setRoles ----------

    @Test
    fun setRolesSendsTheWeakEtagOfTheGetUntouchedAndReadsBeforeWriting() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        reply(user(ID_A, "a"), HttpStatusCode.OK, "application/json", HttpHeaders.ETag to "W/\"v9\"")
                    } else {
                        reply(user(ID_A, "a"))
                    }
                }
            gateway.setRoles(ID_A, emptyList())
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Put), calls.map { it.method })
            assertEquals("W/\"v9\"", calls[1].ifMatch)
            assertEquals("/api/v1/users/$ID_A", calls[0].path)
            assertEquals("/api/v1/users/$ID_A/roles", calls[1].path)
            assertEquals(
                0,
                calls[1]
                    .json!!
                    .getValue("roleIds")
                    .jsonArray.size,
            )
        }

    @Test
    fun setRolesNeverWritesWithoutAnIfMatch() =
        runTest {
            // GET sem ETag: o contrato exige If-Match no PUT (428 sem); escrever sem precondição é perder a trava
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        reply(user(ID_A, "a"))
                    } else if (request.headers[HttpHeaders.IfMatch] == null) {
                        problem(HttpStatusCode.PreconditionRequired, "PRECONDITION_REQUIRED", "falta If-Match")
                    } else {
                        reply(user(ID_A, "a"))
                    }
                }
            runCatching { gateway.setRoles(ID_A, listOf(ROLE_1)) }
            val puts = calls.filter { it.method == HttpMethod.Put }
            assertTrue(puts.all { it.ifMatch != null }, "PUT enviado sem If-Match: ${puts.size} requisição(ões)")
        }

    @Test
    fun setRolesWithoutEtagStillEndsInAnApiError() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        reply(user(ID_A, "a"))
                    } else {
                        problem(HttpStatusCode.PreconditionRequired, "PRECONDITION_REQUIRED", "falta If-Match")
                    }
                }
            val failure = assertFailsWith<ApiError> { gateway.setRoles(ID_A, listOf(ROLE_1)) }
            assertIs<ApiError.Problem>(failure)
            assertEquals(428, failure.status)
        }

    @Test
    fun setRoles412IsPreconditionFailedWithASingleWriteAttempt() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        reply(user(ID_A, "a"), HttpStatusCode.OK, "application/json", HttpHeaders.ETag to "\"v1\"")
                    } else {
                        problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED", "versão antiga")
                    }
                }
            assertFailsWith<ApiError.PreconditionFailed> { gateway.setRoles(ID_A, listOf(ROLE_1)) }
            assertEquals(1, calls.count { it.method == HttpMethod.Put })
            assertEquals(1, calls.count { it.method == HttpMethod.Get })
        }

    @Test
    fun setRolesDoesNotWriteWhenTheUserCannotBeRead() =
        runTest {
            val gateway = gateway { problem(HttpStatusCode.NotFound, "USER_NOT_FOUND", "sem usuário") }
            val failure = assertFailsWith<ApiError.Problem> { gateway.setRoles(ID_A, listOf(ROLE_1)) }
            assertEquals(404, failure.status)
            assertTrue(calls.none { it.method == HttpMethod.Put })
        }

    // ---------- aprovação e erros de sessão ----------

    private fun MockRequestHandleScope.approvalRequired() =
        reply(
            """{"code":"APPROVAL_REQUIRED","detail":"precisa de aprovação","status":403,"action":"iam.user.create",
              |"permission":"iam.users.manage","resourceRef":"iam.user:$ID_A"}
            """.trimMargin(),
            HttpStatusCode.Forbidden,
            "application/problem+json",
        )

    @Test
    fun approvalRequiredRisesAsApprovalRequiredWithoutRetryOrToken() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get && request.url.encodedPath.contains("/users/")) {
                        reply(user(ID_A, "a"), HttpStatusCode.OK, "application/json", HttpHeaders.ETag to "\"v1\"")
                    } else {
                        approvalRequired()
                    }
                }
            val ops =
                listOf<Pair<String, suspend () -> Any?>>(
                    "createUser" to { gateway.createUser("a", "A", Secret(PASSWORD), listOf(ROLE_1), Secret(PIN)) },
                    "reactivate" to { gateway.reactivate(ID_A) },
                    "setRoles" to { gateway.setRoles(ID_A, listOf(ROLE_1)) },
                    "resetPassword" to { gateway.resetPassword(ID_A, Secret(PASSWORD)) },
                    "resetPin" to { gateway.resetPin(ID_A, Secret(PIN)) },
                )
            ops.forEach { (name, op) ->
                sent.clear()
                val failure = assertFailsWith<ApiError.ApprovalRequired>(name) { op() }
                assertEquals("iam.users.manage", failure.permission)
                val writes = calls.filter { it.method != HttpMethod.Get }
                assertEquals(1, writes.size, "$name repetiu a escrita")
                assertTrue(
                    calls.none { it.headers.keys.any { h -> h.equals("X-Approval-Token", ignoreCase = true) } },
                    "$name enviou X-Approval-Token",
                )
                assertEquals(emptyList(), leaks(failure))
            }
        }

    @Test
    fun readsDoNotSendIdempotencyKeyOrApprovalTokenEither() =
        runTest {
            val gateway = gateway { reply(page(emptyList(), null)) }
            gateway.roleIdsByTemplate()
            gateway.findUser("x")
            assertTrue(
                calls.none {
                    it.headers.keys.any { h -> h.equals("Idempotency-Key", true) || h.equals("X-Approval-Token", true) }
                },
            )
        }

    @Test
    fun a403PermissionDeniedIsAProblemAndNotASessionEnd() =
        runTest {
            val gateway = gateway { problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED", "sem permissão") }
            val failure = assertFailsWith<ApiError.Problem> { gateway.findUser("a") }
            assertEquals(403, failure.status)
            assertEquals("PERMISSION_DENIED", failure.code)
            assertEquals(1, calls.size)
        }

    @Test
    fun a401OnASecuredCallRisesAsAnApiErrorWithoutLooping() =
        runTest {
            val gateway = gateway { problem(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "expirou") }
            assertFailsWith<ApiError> { gateway.resetPin(ID_A, Secret(PIN)) }
            assertTrue(calls.size <= 4, "chamadas após o login: ${calls.map { it.path }}")
        }

    @Test
    fun a5xxIsNotRetriedAndRisesAsAnApiError() =
        runTest {
            val gateway = gateway { reply("boom", HttpStatusCode.InternalServerError, "text/plain") }
            val failure =
                assertFailsWith<ApiError.Unexpected> { gateway.createUser("a", "A", Secret(PASSWORD), listOf(), null) }
            assertEquals(500, failure.status)
            assertEquals(1, calls.size)
        }

    // ---------- reativação e resets ----------

    @Test
    fun resetsReturnQuietlyOnA204AndReactivateOnA200() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/reactivate")) {
                        reply(user(ID_A, "a"))
                    } else {
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            gateway.reactivate(ID_A)
            gateway.resetPassword(ID_A, Secret(PASSWORD))
            gateway.resetPin(ID_A, Secret(PIN))
            assertEquals(3, calls.size)
            assertTrue(calls.all { it.method == HttpMethod.Post })
        }

    @Test
    fun createUserReturnsTheIdOfTheCreatedUserAndSendsAllRoles() =
        runTest {
            val gateway = gateway { reply(user(ID_B, "novo"), HttpStatusCode.Created) }
            assertEquals(ID_B, gateway.createUser("novo", "Novo", Secret(PASSWORD), listOf(ROLE_1, ROLE_2), null))
            val ids =
                calls
                    .single()
                    .json!!
                    .getValue("roleIds")
                    .jsonArray
                    .map { it.toString().trim('"') }
            assertEquals(listOf(ROLE_1, ROLE_2), ids)
        }
}
