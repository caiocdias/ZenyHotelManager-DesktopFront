package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.people.SignIn
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertNotEquals

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val PASSWORD = "Senha-Secreta-123"

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun tokens(mustChange: Boolean) =
    """{"accessToken":"A1","accessTokenExpiresAt":"2026-11-11T13:15:00Z","passwordChangeRequired":$mustChange,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2026-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}
    """.trimMargin()

class SdkSessionTest {
    private fun factory(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkSessionFactory(SERVER) { MockEngine { request -> handler(request) } }

    private fun loginProblem(
        code: String,
        status: HttpStatusCode = HttpStatusCode.Unauthorized,
    ): MockEngine = MockEngine { json("""{"code":"$code","detail":"x"}""", status) }

    private suspend fun signInWith(
        code: String,
        status: HttpStatusCode = HttpStatusCode.Unauthorized,
    ): SignIn {
        val session = SdkSessionFactory(SERVER) { loginProblem(code, status) }.open()
        try {
            return session.signIn("admin", Secret(PASSWORD))
        } finally {
            session.close()
        }
    }

    @Test
    fun signInActive() =
        runTest {
            val session = factory { json(tokens(false)) }.open()
            assertEquals(SignIn.Active, session.signIn("admin", Secret(PASSWORD)))
            session.close()
        }

    @Test
    fun signInMapsPasswordChangeRequired() =
        runTest {
            val session = factory { json(tokens(true)) }.open()
            assertEquals(SignIn.MustChangePassword, session.signIn("admin", Secret(PASSWORD)))
            session.close()
        }

    @Test
    fun signInMapsInvalidCredentials() =
        runTest { assertEquals(SignIn.InvalidCredentials, signInWith("INVALID_CREDENTIALS")) }

    @Test
    fun signInMapsLocked() =
        runTest { assertEquals(SignIn.Locked, signInWith("ACCOUNT_LOCKED", HttpStatusCode.Locked)) }

    @Test
    fun disabledPropagates() =
        runTest {
            val e = assertFailsWith<ApiError.Problem> { signInWith("ACCOUNT_DISABLED", HttpStatusCode.Forbidden) }
            assertEquals("ACCOUNT_DISABLED", e.code)
        }

    @Test
    fun eachSessionHasOwnDeviceId() =
        runTest {
            val ids = CopyOnWriteArrayList<String>()
            val factory =
                factory { request ->
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    ids +=
                        body
                            .getValue("device")
                            .jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    json(tokens(false))
                }
            factory.open().also { it.signIn("a", Secret("x")) }.close()
            factory.open().also { it.signIn("a", Secret("x")) }.close()
            assertEquals(2, ids.size)
            assertNotEquals(ids[0], ids[1])
        }

    @Test
    fun signOutBoundedAndSilent() =
        runTest {
            val session =
                factory { request ->
                    if (request.url.encodedPath.endsWith("/logout")) awaitCancellation() else json(tokens(false))
                }.open()
            session.signIn("a", Secret("x"))
            val before = testScheduler.currentTime
            session.signOut()
            assertEquals(3_000L, testScheduler.currentTime - before)
            assertEquals(SessionState.LoggedOut, session.seed.core.session.state.value)
            session.close()
        }

    @Test
    fun signOutWithoutSignInIsHarmless() =
        runTest {
            val session = factory { json("", HttpStatusCode.Unauthorized) }.open()
            session.signOut()
            session.close()
        }

    @Test
    fun noSecretInExceptions() =
        runTest {
            val failures = mutableListOf<Throwable>()
            for ((code, status) in listOf(
                "ACCOUNT_DISABLED" to HttpStatusCode.Forbidden,
                "VALIDATION_FAILED" to HttpStatusCode.BadRequest,
            )) {
                try {
                    signInWith(code, status)
                } catch (e: ApiError) {
                    failures += e
                }
            }
            val offline = MockEngine { throw IOException("conexão recusada") }
            val network = SdkSessionFactory(SERVER) { offline }.open()
            try {
                network.signIn("a", Secret(PASSWORD))
            } catch (e: ApiError) {
                failures += e
            }
            network.close()
            assertFalse(failures.isEmpty())
            failures.forEach { failure ->
                val chain = generateSequence<Throwable>(failure) { it.cause }
                assertFalse(chain.any { PASSWORD in it.message.orEmpty() })
            }
        }
}
