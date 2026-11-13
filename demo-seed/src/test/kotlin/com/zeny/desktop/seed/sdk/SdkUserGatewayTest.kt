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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val PASSWORD = "Senha-Secreta-123"
private const val PIN = "482915"
private const val ID_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ID_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ROLE_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val ROLE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val ROLE_3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    vararg extra: Pair<String, String>,
): HttpResponseData =
    respond(
        body,
        status,
        headersOf(HttpHeaders.ContentType, "application/json").let { base ->
            io.ktor.http.Headers.build {
                appendAll(base)
                extra.forEach { (k, v) -> append(k, v) }
            }
        },
    )

private const val LOGIN_BODY =
    """{"accessToken":"A1","accessTokenExpiresAt":"2036-11-11T13:15:00Z","passwordChangeRequired":false,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2036-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}"""

private fun role(
    id: String,
    template: String?,
    active: Boolean,
) = """{"active":$active,"createdAt":"2026-01-01T00:00:00Z","customized":false,"description":"d","grants":[],
       |"id":"$id","name":"r$id","systemRole":true,${template?.let { "\"template\":\"$it\"," }.orEmpty()}
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

class SdkUserGatewayTest {
    private class Sent(
        val method: HttpMethod,
        val path: String,
        val query: String,
        val ifMatch: String?,
        val idempotencyKey: String?,
        val body: JsonObject?,
    )

    private val sent = CopyOnWriteArrayList<Sent>()

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
                        request.url.encodedQuery,
                        request.headers[HttpHeaders.IfMatch],
                        request.headers["Idempotency-Key"],
                        text.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject },
                    )
                if (request.url.encodedPath.endsWith("/login")) json(LOGIN_BODY.trimMargin()) else handler(request)
            }
        val session = SdkSessionFactory(SERVER) { engine }.open()
        session.signIn("admin", Secret(PASSWORD))
        return SdkUserGateway(session.seed)
    }

    @Test
    fun rolesByTemplateIgnoresInactive() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json(page(listOf(role(ROLE_1, "MANAGER", true), role(ROLE_2, "RECEPTIONIST", false)), "c2"))
                    } else {
                        val custom = role("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d04", null, true)
                        json(page(listOf(role(ROLE_3, "RECEPTIONIST", true), custom), null))
                    }
                }
            assertEquals(mapOf("MANAGER" to ROLE_1, "RECEPTIONIST" to ROLE_3), gateway.roleIdsByTemplate())
        }

    @Test
    fun usersReadAllPages() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> json(page(listOf(user(ID_A, "ana")), "c2"))
                        "c2" -> json(page(listOf(user(ID_B, "gerente", roles = listOf(ROLE_1))), null))
                        else -> error("cursor inesperado")
                    }
                }
            val found = gateway.findUser("gerente")
            assertEquals(ID_B, found?.id)
            assertEquals(setOf(ROLE_1), found?.roleIds)
            assertEquals(2, sent.count { it.path.endsWith("/users") })
        }

    @Test
    fun findUserIgnoresCase() =
        runTest {
            val gateway = gateway { json(page(listOf(user(ID_A, "Gerente", active = false)), null)) }
            val found = gateway.findUser("gerENTE")
            assertEquals(ID_A, found?.id)
            assertFalse(found!!.active)
            assertNull(gateway.findUser("outro"))
        }

    @Test
    fun setRolesUsesIfMatch() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.method) {
                        HttpMethod.Get -> json(user(ID_A, "ana"), HttpStatusCode.OK, HttpHeaders.ETag to "\"v3\"")
                        else -> json(user(ID_A, "ana", roles = listOf(ROLE_1, ROLE_2)))
                    }
                }
            gateway.setRoles(ID_A, listOf(ROLE_1, ROLE_2))
            val put = sent.single { it.method == HttpMethod.Put }
            assertEquals("\"v3\"", put.ifMatch)
            val sentRoles =
                put.body!!
                    .getValue("roleIds")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(listOf(ROLE_1, ROLE_2), sentRoles)
        }

    @Test
    fun createUserSendsPinOnlyWhenGiven() =
        runTest {
            val gateway = gateway { json(user(ID_A, "ana"), HttpStatusCode.Created) }
            assertEquals(ID_A, gateway.createUser("ana", "Ana", Secret(PASSWORD), listOf(ROLE_1), null))
            gateway.createUser("gerente", "Ger", Secret(PASSWORD), listOf(ROLE_1), Secret(PIN))
            val posts = sent.filter { it.method == HttpMethod.Post && it.path.endsWith("/users") }
            val (without, with) = posts.map { it.body!! }
            assertFalse("pin" in without)
            assertEquals(PIN, with.getValue("pin").jsonPrimitive.content)
            assertEquals(PASSWORD, with.getValue("password").jsonPrimitive.content)
        }

    @Test
    fun reactivateAndResetsGoToTheirEndpoints() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/reactivate")) {
                        json(user(ID_A, "ana"))
                    } else {
                        json("", HttpStatusCode.NoContent)
                    }
                }
            gateway.reactivate(ID_A)
            gateway.resetPassword(ID_A, Secret(PASSWORD))
            gateway.resetPin(ID_A, Secret(PIN))
            val paths = sent.filter { !it.path.endsWith("/login") }
            assertEquals(
                listOf("reactivate", "reset-password", "reset-pin").map { "/api/v1/users/$ID_A/$it" },
                paths.map { it.path },
            )
            assertEquals(
                PASSWORD,
                paths[1]
                    .body!!
                    .getValue("newPassword")
                    .jsonPrimitive.content,
            )
            assertEquals(
                PIN,
                paths[2]
                    .body!!
                    .getValue("newPin")
                    .jsonPrimitive.content,
            )
            assertTrue(paths.all { it.idempotencyKey == null })
        }

    @Test
    fun noSecretInExceptions() =
        runTest {
            val gateway =
                gateway { json("""{"code":"VALIDATION_FAILED","detail":"x"}""", HttpStatusCode.BadRequest) }
            val failures =
                listOf(
                    assertFailsWith<ApiError> {
                        gateway.createUser("a", "A", Secret(PASSWORD), listOf(ROLE_1), Secret(PIN))
                    },
                    assertFailsWith<ApiError> { gateway.resetPassword(ID_A, Secret(PASSWORD)) },
                    assertFailsWith<ApiError> { gateway.resetPin(ID_A, Secret(PIN)) },
                )
            failures.forEach { failure ->
                val chain = generateSequence<Throwable>(failure) { it.cause }
                assertFalse(chain.any { PASSWORD in it.message.orEmpty() || PIN in it.message.orEmpty() })
            }
        }
}
