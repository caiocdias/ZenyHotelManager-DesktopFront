package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.people.SignIn
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val PASSWORD = "Senha-Secreta-123"
private const val NEW_PASSWORD = "Outra-Senha-456"
private const val BASE = "/api/v1/auth"

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun tokens(
    access: String,
    refresh: String,
    mustChange: Boolean,
) = """{"accessToken":"$access","accessTokenExpiresAt":"2026-11-11T13:15:00Z","passwordChangeRequired":$mustChange,
       |"refreshToken":"$refresh","refreshTokenExpiresAt":"2026-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}
    """.trimMargin()

private fun allMessages(failure: Throwable): List<String> =
    generateSequence(failure) { it.cause }.flatMap { listOf(it.message.orEmpty(), it.toString()) }.toList()

@OptIn(ExperimentalCoroutinesApi::class)
class SdkSessionAtaqueTest {
    private val seen = CopyOnWriteArrayList<HttpRequestData>()

    private fun factory(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkSessionFactory(SERVER) {
            MockEngine { request ->
                seen += request
                handler(request)
            }
        }

    private fun SdkSession.state() = seed.core.session.state.value

    private suspend fun signInOutcome(
        status: HttpStatusCode,
        body: String,
    ): Result<SignIn> {
        val session = factory { json(body, status) }.open()
        return try {
            runCatching { session.signIn("admin", Secret(PASSWORD)) }
        } finally {
            session.close()
        }
    }

    @Test
    fun unauthorizedWithoutCodeIsNotInvalidCredentials() =
        runTest {
            val outcome = signInOutcome(HttpStatusCode.Unauthorized, """{"detail":"x"}""")
            assertIs<ApiError.Problem>(outcome.exceptionOrNull())
        }

    @Test
    fun unauthorizedWithEmptyBodyPropagates() =
        runTest {
            val e = signInOutcome(HttpStatusCode.Unauthorized, "").exceptionOrNull()
            assertIs<ApiError>(e)
        }

    @Test
    fun invalidCredentialsCodeOnOtherStatusStillMapsOnlyByCode() =
        runTest {
            // 429 com code de rate limit nao pode virar credencial invalida nem bloqueio.
            val e = signInOutcome(HttpStatusCode.TooManyRequests, """{"code":"RATE_LIMITED","detail":"x"}""")
            val problem = assertIs<ApiError.Problem>(e.exceptionOrNull())
            assertEquals(429, problem.status)
        }

    @Test
    fun serverErrorsPropagateAsApiError() =
        runTest {
            val withProblem = signInOutcome(HttpStatusCode.InternalServerError, """{"code":"INTERNAL","detail":"x"}""")
            assertIs<ApiError.Problem>(withProblem.exceptionOrNull())
            val html = signInOutcome(HttpStatusCode.BadGateway, "<html>502</html>")
            assertEquals(502, assertIs<ApiError.Unexpected>(html.exceptionOrNull()).status)
        }

    @Test
    fun nonRfcBodyOn401WithCodeKeyOfWrongTypeDoesNotMap() =
        runTest {
            val outcome = signInOutcome(HttpStatusCode.Unauthorized, """{"code":401,"detail":"x"}""")
            assertTrue(outcome.getOrNull() != SignIn.InvalidCredentials)
            assertIs<ApiError>(outcome.exceptionOrNull())
        }

    @Test
    fun unreadableSuccessBodyIsIncompatibleResponse() =
        runTest {
            val e = signInOutcome(HttpStatusCode.OK, "isto nao e json").exceptionOrNull()
            assertIs<ApiError.IncompatibleResponse>(e)
        }

    @Test
    fun networkFailureIsApiNetworkWithoutSecret() =
        runTest {
            val session = SdkSessionFactory(SERVER) { MockEngine { throw IOException("recusada") } }.open()
            val e = assertFailsWith<ApiError.Network> { session.signIn("a", Secret(PASSWORD)) }
            session.close()
            assertTrue(allMessages(e).none { PASSWORD in it })
        }

    @Test
    fun sessionStaysAuthenticatedAfterChangePassword() =
        runTest {
            val session =
                factory { request ->
                    when (request.url.encodedPath) {
                        "$BASE/login" -> json(tokens("A1", "R1", true))
                        "$BASE/password" -> respond("", HttpStatusCode.NoContent)
                        "$BASE/refresh" -> json(tokens("A2", "R2", false))
                        else -> respond("", HttpStatusCode.NoContent)
                    }
                }.open()
            assertEquals(SignIn.MustChangePassword, session.signIn("admin", Secret(PASSWORD)))
            session.changePassword(Secret(PASSWORD), Secret(NEW_PASSWORD))
            val active = assertIs<SessionState.Active>(session.state())
            assertFalse(active.passwordChangeRequired)
            assertFalse(active.passwordChangeAwaitingRefresh)
            session.signOut()
            val logout = seen.last { it.url.encodedPath == "$BASE/logout" }
            assertEquals("Bearer A2", logout.headers[HttpHeaders.Authorization])
            session.close()
        }

    @Test
    fun failedRefreshAfterChangePasswordSurfacesApiErrorAndMarksPending() =
        runTest {
            val session =
                factory { request ->
                    when (request.url.encodedPath) {
                        "$BASE/login" -> {
                            json(tokens("A1", "R1", true))
                        }

                        "$BASE/password" -> {
                            respond("", HttpStatusCode.NoContent)
                        }

                        "$BASE/refresh" -> {
                            json("""{"code":"UNAVAILABLE","detail":"x"}""", HttpStatusCode.ServiceUnavailable)
                        }

                        else -> {
                            respond("", HttpStatusCode.NoContent)
                        }
                    }
                }.open()
            session.signIn("admin", Secret(PASSWORD))
            val e = assertFailsWith<ApiError> { session.changePassword(Secret(PASSWORD), Secret(NEW_PASSWORD)) }
            assertTrue(allMessages(e).none { PASSWORD in it || NEW_PASSWORD in it })
            assertTrue(assertIs<SessionState.Active>(session.state()).passwordChangeAwaitingRefresh)
            session.close()
        }

    @Test
    fun wrongCurrentPasswordOnChangeKeepsSessionAndSurfaces() =
        runTest {
            val session =
                factory { request ->
                    when (request.url.encodedPath) {
                        "$BASE/login" -> {
                            json(tokens("A1", "R1", true))
                        }

                        "$BASE/password" -> {
                            json("""{"code":"INVALID_CREDENTIALS","detail":"x"}""", HttpStatusCode.Unauthorized)
                        }

                        else -> {
                            respond("", HttpStatusCode.NoContent)
                        }
                    }
                }.open()
            session.signIn("admin", Secret(PASSWORD))
            val e = assertFailsWith<ApiError.Problem> { session.changePassword(Secret("errada"), Secret(NEW_PASSWORD)) }
            assertEquals("INVALID_CREDENTIALS", e.code)
            assertTrue(assertIs<SessionState.Active>(session.state()).passwordChangeRequired)
            session.close()
        }

    @Test
    fun signOutOnHangingServerEndsAtThreeVirtualSeconds() =
        runTest {
            val session =
                factory { request ->
                    if (request.url.encodedPath == "$BASE/logout") {
                        awaitCancellation()
                    } else {
                        json(tokens("A1", "R1", false))
                    }
                }.open()
            session.signIn("a", Secret("x"))
            val before = testScheduler.currentTime
            session.signOut()
            assertEquals(3_000L, testScheduler.currentTime - before)
            assertEquals(SessionState.LoggedOut, session.state())
            session.close()
        }

    @Test
    fun signOutSwallowsServerAndNetworkFailures() =
        runTest {
            for (failing in listOf<suspend MockRequestHandleScope.() -> HttpResponseData>(
                { json("""{"code":"X","detail":"x"}""", HttpStatusCode.InternalServerError) },
                { throw IOException("sem rede") },
                { json("lixo", HttpStatusCode.BadGateway) },
            )) {
                val session =
                    factory { request ->
                        if (request.url.encodedPath == "$BASE/logout") failing() else json(tokens("A1", "R1", false))
                    }.open()
                session.signIn("a", Secret("x"))
                session.signOut()
                assertEquals(SessionState.LoggedOut, session.state())
                session.close()
            }
        }

    @Test
    fun signOutTwiceAndWithoutLoginAreHarmless() =
        runTest {
            val session = factory { json(tokens("A1", "R1", false)) }.open()
            session.signOut()
            session.signIn("a", Secret("x"))
            session.signOut()
            session.signOut()
            assertEquals(SessionState.LoggedOut, session.state())
            session.close()
        }

    @Test
    fun signOutAfterCloseDoesNotThrowApiErrorOrHang() =
        runTest {
            val session = factory { json(tokens("A1", "R1", false)) }.open()
            session.signIn("a", Secret("x"))
            session.close()
            val outcome = runCatching { session.signOut() }
            val failure = outcome.exceptionOrNull()
            // Documentado no core: depois do close as chamadas terminam em CancellationException, nunca ApiError.
            assertTrue(failure == null || failure is CancellationException, "signOut apos close lancou $failure")
        }

    @Test
    fun closeIsIdempotentAndClosesOnlyItsOwnEngine() {
        val own = MockEngine { respond("", HttpStatusCode.NoContent) }
        val other = MockEngine { respond("", HttpStatusCode.NoContent) }
        val session = SdkSessionFactory(SERVER) { own }.open()
        session.close()
        session.close()
        session.seed.close()
        assertFalse(own.coroutineContext[Job]!!.isActive)
        assertTrue(other.coroutineContext[Job]!!.isActive)
        other.close()
    }

    @Test
    fun closeWithoutAnyCallIsHarmless() {
        SdkSessionFactory(SERVER) { MockEngine { respond("", HttpStatusCode.NoContent) } }.open().close()
    }

    @Test
    fun factoryCallsEngineFactoryOncePerOpenAndNeverSharesEngines() {
        val engines = CopyOnWriteArrayList<MockEngine>()
        val factory =
            SdkSessionFactory(SERVER) {
                MockEngine { respond("", HttpStatusCode.NoContent) }.also { engines += it }
            }
        val a = factory.open()
        val b = factory.open()
        assertEquals(2, engines.size)
        a.close()
        assertTrue(engines[1].coroutineContext[Job]!!.isActive)
        b.close()
    }

    @Test
    fun distinctDeviceIdsAndNoJavaPreferencesWritten() =
        runTest {
            val before = prefsSnapshot()
            val a = factory { json(tokens("A1", "R1", false)) }.open()
            val b = factory { json(tokens("A1", "R1", false)) }.open()
            a.signIn("maria", Secret("x"))
            b.signIn("maria", Secret("x"))
            a.close()
            b.close()
            assertEquals(before, prefsSnapshot())
        }

    private fun prefsSnapshot(): Map<String, Map<String, String>> {
        val root = Preferences.userRoot()
        return (listOf("") + root.childrenNames()).associateWith { name ->
            val node = if (name.isEmpty()) root else root.node(name)
            node.keys().associateWith { node.get(it, "") }
        }
    }

    @Test
    fun noSecretInToStringOfSessionOrSeedCore() =
        runTest {
            val session = factory { json(tokens("A1", "R1", true)) }.open()
            session.signIn("admin", Secret(PASSWORD))
            val texts = listOf(session.toString(), session.seed.toString(), session.state().toString())
            session.close()
            assertTrue(texts.none { PASSWORD in it || NEW_PASSWORD in it || "R1" in it })
        }

    @Test
    fun signInIsCancellableAndCancellationIsNotSwallowed() =
        runTest {
            val session = factory { awaitCancellation() }.open()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { session.signIn("a", Secret("x")) }
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            session.close()
        }

    @Test
    fun cancelledSignOutPropagatesCancellationAndStillClearsSession() =
        runTest {
            val session =
                factory { request ->
                    if (request.url.encodedPath == "$BASE/logout") {
                        awaitCancellation()
                    } else {
                        json(tokens("A1", "R1", false))
                    }
                }.open()
            session.signIn("a", Secret("x"))
            val job = launch(start = CoroutineStart.UNDISPATCHED) { session.signOut() }
            job.cancel()
            job.join()
            assertTrue(job.isCancelled, "signOut engoliu o cancelamento")
            assertEquals(SessionState.LoggedOut, session.state())
            session.close()
        }
}
