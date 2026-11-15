package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.people.RoleEnsured
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
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val ATK_SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val ATK_PASSWORD = "Senha-Secreta-123"
private const val ATK_ECHO = "SEGREDO-NO-CORPO-777"
private const val R1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val R2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val R3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"
private const val NAME = "Recepção demo"
private val OWN_AB = mapOf("a.b" to "OWN")

private const val ATK_LOGIN =
    """{"accessToken":"A1","accessTokenExpiresAt":"2036-11-11T13:15:00Z","passwordChangeRequired":false,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2036-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}"""

private fun MockRequestHandleScope.reply(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/json",
    etag: String? = null,
): HttpResponseData =
    respond(
        body,
        status,
        Headers.build {
            append(HttpHeaders.ContentType, contentType)
            if (etag != null) append(HttpHeaders.ETag, etag)
        },
    )

private fun MockRequestHandleScope.problem(
    status: HttpStatusCode,
    code: String,
    detail: String = ATK_ECHO,
) = reply("""{"code":"$code","detail":"$detail","status":${status.value}}""", status, "application/problem+json")

private fun roleJson(
    id: String,
    name: String,
    active: Boolean = true,
    grants: Map<String, String> = emptyMap(),
) = """{"active":$active,"createdAt":"2026-01-01T00:00:00Z","customized":true,"description":"Desc","grants":[${
    grants.entries.joinToString(",") { (c, s) -> """{"limits":[],"permission":"$c","scope":"$s"}""" }
}],"id":"$id","name":"$name","systemRole":false,"updatedAt":"2026-01-01T00:00:00Z"}"""

private fun pageJson(
    items: List<String>,
    next: String?,
) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

class SdkUserGatewayRoleAtaqueTest {
    private class Sent(
        val method: HttpMethod,
        val path: String,
        val cursor: String?,
        val ifMatch: String?,
        val body: JsonObject?,
    )

    private val sent = CopyOnWriteArrayList<Sent>()
    private val wanted = mapOf("frontdesk.departments.read" to "ALL", "frontdesk.notes.write" to "OWN")

    private fun writes() = sent.filter { it.method != HttpMethod.Get && !it.path.endsWith("/login") }

    private fun steps() =
        sent.filter { !it.path.endsWith("/login") && !(it.method == HttpMethod.Get && it.path.endsWith("/roles")) }

    private suspend fun gateway(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SdkUserGateway {
        val engine =
            MockEngine { request ->
                val text = request.body.toByteArray().decodeToString()
                sent +=
                    Sent(
                        request.method,
                        request.url.encodedPath,
                        request.url.parameters["cursor"],
                        request.headers[HttpHeaders.IfMatch],
                        text.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject },
                    )
                if (request.url.encodedPath.endsWith("/login")) reply(ATK_LOGIN.trimMargin()) else handler(request)
            }
        val session = SdkSessionFactory(ATK_SERVER) { engine }.open()
        session.signIn("admin", Secret(ATK_PASSWORD))
        return SdkUserGateway(session.seed)
    }

    private fun isList(request: HttpRequestData) =
        request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/roles")

    /** Servidor com uma lista fixa; o GET por id devolve o ETag dado; escritas devolvem a role. */
    private suspend fun serverWith(
        listed: List<String>,
        etag: String? = "\"v1\"",
    ) = gateway { request ->
        when {
            isList(request) -> reply(pageJson(listed, null))
            request.method == HttpMethod.Get -> reply(roleJson(R2, NAME), etag = etag)
            else -> reply(roleJson(R2, NAME, true, wanted))
        }
    }

    private fun grantsSent(body: JsonObject?): Map<String, String> =
        body!!
            .getValue("grants")
            .jsonArray
            .associate {
                val o = it.jsonObject
                o.getValue("permission").jsonPrimitive.content to o.getValue("scope").jsonPrimitive.content
            }

    @Test
    fun ensureRoleScansManyPagesInOrderUntilTheLastOne() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> reply(pageJson(listOf(roleJson(R1, "Outra")), "c2"))
                        "c2" -> reply(pageJson(listOf(roleJson(R1, "Mais uma")), "c3"))
                        "c3" -> reply(pageJson(listOf(roleJson(R1, "Ainda outra")), "c4"))
                        "c4" -> reply(pageJson(listOf(roleJson(R2, "RECEPÇÃO DEMO", grants = wanted)), null))
                        else -> error("cursor inesperado")
                    }
                }
            assertEquals(RoleEnsured(R2, created = false), gateway.ensureRole(NAME, "Desc", wanted))
            assertEquals(listOf(null, "c2", "c3", "c4"), sent.filter { it.path.endsWith("/roles") }.map { it.cursor })
            assertTrue(writes().isEmpty())
        }

    @Test
    fun ensureRoleStopsWhenServerDoesNotAdvanceTheCursorAndThenCreates() =
        runTest {
            val gateway =
                gateway { request ->
                    when {
                        isList(request) -> reply(pageJson(listOf(roleJson(R1, "Outra")), "preso"))
                        else -> reply(roleJson(R3, NAME, true, wanted), HttpStatusCode.Created)
                    }
                }
            assertEquals(RoleEnsured(R3, created = true), gateway.ensureRole(NAME, "Desc", wanted))
            assertEquals(2, sent.count { it.method == HttpMethod.Get && it.path.endsWith("/roles") })
        }

    @Test
    fun ensureRoleNeverPicksARoleWhoseNameMerelyContainsTheTarget() =
        runTest {
            val gateway =
                gateway { request ->
                    when {
                        isList(request) -> {
                            reply(
                                pageJson(
                                    listOf(
                                        roleJson(R1, "Recepção demo 2", grants = wanted),
                                        roleJson(R1, "Super Recepção demo", grants = wanted),
                                        roleJson(R1, "Recepção dem", grants = wanted),
                                        roleJson(R1, " Recepção demo", grants = wanted),
                                    ),
                                    null,
                                ),
                            )
                        }

                        else -> {
                            reply(roleJson(R3, NAME, true, wanted), HttpStatusCode.Created)
                        }
                    }
                }
            assertEquals(RoleEnsured(R3, created = true), gateway.ensureRole(NAME, "Desc", wanted))
            assertEquals(listOf(HttpMethod.Post), writes().map { it.method })
        }

    @Test
    fun ensureRoleDoesNotFoldAccents() =
        runTest {
            val gateway =
                gateway { request ->
                    if (isList(request)) {
                        reply(pageJson(listOf(roleJson(R1, "Recepcao demo", grants = wanted)), null))
                    } else {
                        reply(roleJson(R3, NAME, true, wanted), HttpStatusCode.Created)
                    }
                }
            assertEquals(RoleEnsured(R3, created = true), gateway.ensureRole(NAME, "Desc", wanted))
        }

    @Test
    fun ensureRoleWithTwoNamesDifferingOnlyByCaseUsesTheFirstListed() =
        runTest {
            val gateway =
                serverWith(
                    listOf(
                        roleJson(R2, "RECEPÇÃO demo", grants = wanted),
                        roleJson(R3, "recepção DEMO", grants = wanted),
                    ),
                )
            assertEquals(RoleEnsured(R2, created = false), gateway.ensureRole(NAME, "Desc", wanted))
            assertTrue(writes().isEmpty())
        }

    @Test
    fun ensureRoleTreatsEqualGrantsInAnotherOrderAsEqual() =
        runTest {
            val reversed = wanted.entries.reversed().associate { it.key to it.value }
            val gateway = serverWith(listOf(roleJson(R2, NAME, true, reversed)))
            assertEquals(RoleEnsured(R2, created = false), gateway.ensureRole(NAME, "Desc", wanted))
            assertTrue(steps().isEmpty(), "nenhuma escrita nem GET por id: ${steps()}")
        }

    @Test
    fun ensureRoleRewritesASupersetToExactlyTheRequestedSet() =
        runTest {
            val superset = wanted + ("iam.users.write" to "ALL")
            val gateway = serverWith(listOf(roleJson(R2, NAME, true, superset)))
            gateway.ensureRole(NAME, "Desc", wanted)
            val put = writes().single()
            assertEquals(HttpMethod.Put, put.method)
            assertEquals(wanted, grantsSent(put.body))
            assertFalse("iam.users.write" in grantsSent(put.body))
        }

    @Test
    fun ensureRoleRewritesASubsetToExactlyTheRequestedSet() =
        runTest {
            val gateway = serverWith(listOf(roleJson(R2, NAME, true, mapOf("frontdesk.departments.read" to "ALL"))))
            gateway.ensureRole(NAME, "Desc", wanted)
            assertEquals(wanted, grantsSent(writes().single().body))
        }

    @Test
    fun ensureRoleRewritesWhenOnlyTheScopeDiffers() =
        runTest {
            val narrower = wanted + ("frontdesk.departments.read" to "OWN")
            val gateway = serverWith(listOf(roleJson(R2, NAME, true, narrower)))
            gateway.ensureRole(NAME, "Desc", wanted)
            val put = writes().single()
            assertEquals(HttpMethod.Put, put.method)
            assertEquals(wanted, grantsSent(put.body))
        }

    @Test
    fun ensureRoleEmptyGrantsAgainstEmptyIsEqualAndAgainstSomeIsRewrittenToEmpty() =
        runTest {
            val equal = serverWith(listOf(roleJson(R2, NAME, true, emptyMap())))
            equal.ensureRole(NAME, "Desc", emptyMap())
            assertTrue(writes().isEmpty())
            sent.clear()
            val different = serverWith(listOf(roleJson(R2, NAME, true, wanted)))
            different.ensureRole(NAME, "Desc", emptyMap())
            assertEquals(emptyMap(), grantsSent(writes().single().body))
        }

    @Test
    fun ensureRoleInactiveAndEqualOnlyReactivates() =
        runTest {
            val gateway = serverWith(listOf(roleJson(R2, NAME, false, wanted)))
            assertEquals(RoleEnsured(R2, created = false), gateway.ensureRole(NAME, "Desc", wanted))
            val only = steps().map { it.method to it.path }
            assertEquals(listOf(HttpMethod.Post to "/api/v1/roles/$R2/reactivate"), only)
        }

    @Test
    fun ensureRoleInactiveAndDifferentSendsTheEtagReadAfterReactivation() =
        runTest {
            var reactivated = false
            val gateway =
                gateway { request ->
                    when {
                        isList(request) -> {
                            reply(pageJson(listOf(roleJson(R2, NAME, false, OWN_AB)), null))
                        }

                        request.url.encodedPath.endsWith("/reactivate") -> {
                            reactivated = true
                            reply(roleJson(R2, NAME, true, OWN_AB))
                        }

                        request.method == HttpMethod.Get -> {
                            reply(roleJson(R2, NAME), etag = if (reactivated) "\"depois\"" else "\"antes\"")
                        }

                        else -> {
                            reply(roleJson(R2, NAME, true, wanted))
                        }
                    }
                }
            gateway.ensureRole(NAME, "Desc", wanted)
            assertEquals(
                listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Put),
                steps().map { it.method },
            )
            assertEquals("\"depois\"", steps().last().ifMatch)
            assertEquals(null, steps().first().ifMatch)
        }

    @Test
    fun ensureRoleRewriteBodyCarriesExactlyTheGivenNameDescriptionAndGrants() =
        runTest {
            val gateway = serverWith(listOf(roleJson(R2, "RECEPÇÃO DEMO", true, OWN_AB)))
            gateway.ensureRole(NAME, "Descrição nova", wanted)
            val body = writes().single().body!!
            assertEquals(setOf("name", "description", "grants"), body.keys)
            assertEquals(NAME, body.getValue("name").jsonPrimitive.content)
            assertEquals("Descrição nova", body.getValue("description").jsonPrimitive.content)
            assertEquals(wanted, grantsSent(body))
            body.getValue("grants").jsonArray.forEach {
                assertEquals(
                    0,
                    it.jsonObject
                        .getValue("limits")
                        .jsonArray.size,
                )
            }
        }

    @Test
    fun ensureRoleCreateBodyCarriesExactlyTheGivenFieldsAndNoIfMatch() =
        runTest {
            val gateway = serverWith(emptyList())
            assertEquals(RoleEnsured(R2, created = true), gateway.ensureRole(NAME, "Desc", wanted))
            val post = writes().single()
            assertEquals(HttpMethod.Post, post.method)
            assertEquals("/api/v1/roles", post.path)
            assertEquals(null, post.ifMatch)
            assertEquals(setOf("name", "description", "grants"), post.body!!.keys)
            assertEquals(wanted, grantsSent(post.body))
            assertEquals(
                2,
                post.body
                    .getValue("grants")
                    .jsonArray.size,
            )
        }

    @Test
    fun ensureRoleWithoutEtagIs428AndNeverWrites() =
        runTest {
            for (etag in listOf(null, "", "   ")) {
                sent.clear()
                val gateway = serverWith(listOf(roleJson(R2, NAME, true, OWN_AB)), etag)
                val failure =
                    assertFailsWith<ApiError.Problem>("etag=[$etag]") { gateway.ensureRole(NAME, "Desc", wanted) }
                assertEquals(428, failure.status)
                assertTrue(writes().isEmpty(), "etag=[$etag] escreveu: ${writes().map { it.path }}")
            }
        }

    @Test
    fun ensureRoleWriteRejectionsPropagateAsApiErrorAndAreNotRetried() =
        runTest {
            val rejections =
                listOf(
                    Triple(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED", "412"),
                    Triple(HttpStatusCode.PreconditionRequired, "PRECONDITION_REQUIRED", "428"),
                    Triple(HttpStatusCode.UnprocessableEntity, "GRANT_EXCEEDS_OWN_PERMISSIONS", "422"),
                    Triple(HttpStatusCode.Forbidden, "PERMISSION_DENIED", "403"),
                )
            for ((status, code, label) in rejections) {
                sent.clear()
                val gateway =
                    gateway { request ->
                        when {
                            isList(request) -> reply(pageJson(listOf(roleJson(R2, NAME, true, OWN_AB)), null))
                            request.method == HttpMethod.Get -> reply(roleJson(R2, NAME), etag = "\"v1\"")
                            else -> problem(status, code)
                        }
                    }
                val failure = assertFailsWith<ApiError>(label) { gateway.ensureRole(NAME, "Desc", wanted) }
                assertEquals(1, writes().size, "$label: mais de uma escrita ${writes().map { it.method to it.path }}")
                assertEquals(HttpMethod.Put, writes().single().method, label)
                if (failure is ApiError.Problem) assertEquals(code, failure.code, label)
            }
        }

    @Test
    fun ensureRoleCreateRejectionPropagatesAndDoesNotRetry() =
        runTest {
            for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.UnprocessableEntity)) {
                sent.clear()
                val gateway =
                    gateway { request ->
                        if (isList(request)) {
                            reply(pageJson(emptyList(), null))
                        } else {
                            problem(status, "GRANT_EXCEEDS_OWN_PERMISSIONS")
                        }
                    }
                assertFailsWith<ApiError> { gateway.ensureRole(NAME, "Desc", wanted) }
                assertEquals(listOf(HttpMethod.Post), writes().map { it.method }, "$status")
            }
        }

    @Test
    fun ensureRoleReactivationFailureStopsBeforeTheRewrite() =
        runTest {
            val gateway =
                gateway { request ->
                    when {
                        isList(request) -> reply(pageJson(listOf(roleJson(R2, NAME, false, OWN_AB)), null))
                        else -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                    }
                }
            assertFailsWith<ApiError> { gateway.ensureRole(NAME, "Desc", wanted) }
            assertEquals(listOf(HttpMethod.Post), steps().map { it.method })
        }

    @Test
    fun ensureRoleListFailureWritesNothing() =
        runTest {
            val gateway = gateway { problem(HttpStatusCode.InternalServerError, "INTERNAL") }
            assertFailsWith<ApiError> { gateway.ensureRole(NAME, "Desc", wanted) }
            assertTrue(writes().isEmpty())
        }

    @Test
    fun ensureRoleErrorsCarryNeitherResponseBodyNorSecretInTheirMessages() =
        runTest {
            val statuses =
                listOf(
                    HttpStatusCode.Forbidden to "PERMISSION_DENIED",
                    HttpStatusCode.UnprocessableEntity to "GRANT_EXCEEDS_OWN_PERMISSIONS",
                    HttpStatusCode.PreconditionFailed to "PRECONDITION_FAILED",
                    HttpStatusCode.PreconditionRequired to "PRECONDITION_REQUIRED",
                )
            for ((status, code) in statuses) {
                val gateway =
                    gateway { request ->
                        when {
                            isList(request) -> reply(pageJson(listOf(roleJson(R2, NAME, true, OWN_AB)), null))
                            request.method == HttpMethod.Get -> reply(roleJson(R2, NAME), etag = "\"v1\"")
                            else -> problem(status, code)
                        }
                    }
                val failure = assertFailsWith<ApiError> { gateway.ensureRole(NAME, "Desc", wanted) }
                val visible = listOf(failure.message.orEmpty(), failure.toString(), failure.userMessage)
                visible.forEach {
                    assertFalse(ATK_ECHO in it, "$code vazou o corpo: $it")
                    assertFalse(ATK_PASSWORD in it, "$code vazou a senha: $it")
                }
                assertNotNull(failure)
            }
        }
}
