package com.zeny.desktop.core.facade

import com.zeny.desktop.core.config.ServerUrl
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataque ao `ServerProbe`: respostas hostis, timeouts, fechamento do engine e vazamento de texto. */
class ServerProbeAttackTest {
    private val url = checkNotNull(ServerUrl.parse("http://servidor.teste:8088"))
    private val notZeny = "Esse endereço não é um servidor do Zeny Hotel Manager."
    private val network = "Não foi possível falar com o servidor. Confira o endereço e a rede."
    private val fixed = setOf(notZeny, network)

    private class SpyEngine(
        private val delegate: HttpClientEngine,
    ) : HttpClientEngine by delegate {
        var closed = 0

        override fun close() {
            closed++
            delegate.close()
        }
    }

    private fun body(
        text: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        type: String? = "application/json",
    ) = MockEngine {
        val headers = if (type == null) headersOf() else headersOf(HttpHeaders.ContentType, type)
        respond(text, status, headers)
    }

    private fun valid(
        application: String = "zeny-hotel-manager",
        apiVersion: String = "v1",
    ) = """{"apiVersion":"$apiVersion","application":"$application","version":"1.4.2"}"""

    private suspend fun assertFixed(
        expected: String,
        engine: SpyEngine,
        target: ServerUrl = url,
    ) {
        val result = assertIs<ProbeResult.Failed>(ServerProbe.check(target) { engine })
        assertEquals(expected, result.message)
        assertEquals(1, engine.closed, "o engine deve ser fechado exatamente uma vez")
    }

    @Test
    fun rightApplicationWithApiVersionV2IsNotZeny() =
        runTest {
            assertFixed(notZeny, SpyEngine(body(valid(apiVersion = "v2"))))
            assertFixed(notZeny, SpyEngine(body(valid(apiVersion = "V1"))))
            assertFixed(notZeny, SpyEngine(body(valid(apiVersion = "v1 "))))
            assertFixed(notZeny, SpyEngine(body(valid(application = "Zeny-Hotel-Manager"))))
        }

    @Test
    fun emptyBodyIsNotZenyWithoutThrowing() =
        runTest {
            assertFixed(notZeny, SpyEngine(body("")))
            assertFixed(notZeny, SpyEngine(body("", type = null)))
            assertFixed(notZeny, SpyEngine(body("", HttpStatusCode.NoContent, null)))
        }

    @Test
    fun truncatedAndMalformedJsonIsNotZenyWithoutEchoingTheBody() =
        runTest {
            val secret = "SEGREDO-NO-CORPO"
            assertFixed(notZeny, SpyEngine(body("""{"apiVersion":"v1","application":"zeny-hotel-man""")))
            assertFixed(notZeny, SpyEngine(body("""{"apiVersion":"v1","note":"$secret"""")))
            assertFixed(notZeny, SpyEngine(body("[1,2,3]")))
            assertFixed(notZeny, SpyEngine(body("null")))
            assertFixed(notZeny, SpyEngine(body("""{"application":"zeny-hotel-manager"}""")))
            assertFixed(notZeny, SpyEngine(body("""{"apiVersion":1,"application":null,"x":"$secret"}""")))
        }

    @Test
    fun jsonContentWithWrongContentTypeIsNotZeny() =
        runTest {
            assertFixed(notZeny, SpyEngine(body(valid(), type = "text/plain")))
            assertFixed(notZeny, SpyEngine(body(valid(), type = "text/html")))
        }

    @Test
    fun redirectIsNotFollowedAndIsNotZeny() =
        runTest {
            val requests = CopyOnWriteArrayList<String>()
            val engine =
                SpyEngine(
                    MockEngine { request ->
                        requests += request.url.toString()
                        respond(
                            valid(),
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "http://outro.teste/api/v1/meta/version"),
                        )
                    },
                )
            assertFixed(notZeny, engine)
            assertEquals(1, requests.size, "o 302 não pode ser seguido: $requests")
        }

    @Test
    fun authAndServerErrorStatusesAreNotZenyWithoutEchoingTheBody() =
        runTest {
            val secret = """{"code":"SEGREDO","detail":"token A1 em servidor.teste"}"""
            listOf(401, 403, 404, 429, 500, 502, 503).forEach { status ->
                val code = HttpStatusCode.fromValue(status)
                assertFixed(notZeny, SpyEngine(body(secret, code)))
                assertFixed(notZeny, SpyEngine(body("<html>$secret</html>", code, "text/html")))
            }
        }

    @Test
    fun ioFailuresOfAnyKindBecomeTheNetworkMessage() =
        runTest {
            assertFixed(network, SpyEngine(MockEngine { throw ConnectException("recusou em servidor.teste:8088") }))
            assertFixed(network, SpyEngine(MockEngine { throw SSLHandshakeException("certificado de servidor.teste") }))
            assertFixed(network, SpyEngine(MockEngine { throw IOException("Connection reset") }))
        }

    @Test
    fun slowServerGivesUpWithTheNetworkMessageAndClosesTheEngine() =
        runTest {
            val engine = SpyEngine(MockEngine { awaitCancellation() })
            val result = ServerProbe.probe(url, { engine }, requestTimeoutMillis = 30)
            assertEquals(ProbeResult.Failed(network), result)
            assertEquals(1, engine.closed)
        }

    @Test
    fun httpsUrlIsRequestedOverHttpsWithoutCredentials() =
        runTest {
            val seen = CopyOnWriteArrayList<String>()
            val auth = CopyOnWriteArrayList<String?>()
            val engine =
                MockEngine { request ->
                    seen += request.url.toString()
                    auth += request.headers[HttpHeaders.Authorization]
                    respond(valid(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val https = checkNotNull(ServerUrl.parse("https://hotel.exemplo.com:8443/"))
            assertEquals(ProbeResult.Ok("1.4.2"), ServerProbe.check(https) { engine })
            assertEquals(listOf("https://hotel.exemplo.com:8443/api/v1/meta/version"), seen)
            assertEquals(listOf<String?>(null), auth, "a sondagem não leva Authorization")
        }

    @Test
    fun engineFactoryThatThrowsGivesFailedNotAnException() =
        runTest {
            val result = ServerProbe.check(url) { throw IllegalStateException("sem engine em servidor.teste") }
            val failed = assertIs<ProbeResult.Failed>(result)
            assertTrue(failed.message in fixed, "mensagem fora do catálogo: ${failed.message}")
        }

    @Test
    fun cancellationWhileWaitingClosesTheEngineAndPropagates() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val engine =
                SpyEngine(
                    MockEngine {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                )
            val job = async(start = CoroutineStart.UNDISPATCHED) { ServerProbe.check(url) { engine } }
            started.await()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            assertEquals(1, engine.closed, "cancelar não pode vazar o engine")
        }

    @Test
    fun everyFailureMessageIsFixedAndNeverEchoesBodyOrCredentialUrl() =
        runTest {
            val secret = "SEGREDO-123"
            val engines =
                listOf(
                    body("""{"x":"$secret"}"""),
                    body("<html>$secret</html>", HttpStatusCode.InternalServerError, "text/html"),
                    body("""{"code":"$secret"}""", HttpStatusCode.Unauthorized),
                    MockEngine { throw IOException("falha $secret em servidor.teste:8088") },
                )
            engines.forEach { engine ->
                val failed = assertIs<ProbeResult.Failed>(ServerProbe.check(url) { engine })
                assertTrue(failed.message in fixed, "mensagem fora do catálogo: ${failed.message}")
            }
            // Endereço com credencial na URL nem chega a ser aceito: a sondagem nunca teria o que ecoar.
            assertNull(ServerUrl.parse("http://maria:$secret@servidor.teste:8088"))
        }
}
