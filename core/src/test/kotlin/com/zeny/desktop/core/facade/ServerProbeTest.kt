package com.zeny.desktop.core.facade

import com.zeny.desktop.core.config.ServerUrl
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ConnectTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ServerProbeTest {
    private val url = checkNotNull(ServerUrl.parse("http://servidor.teste:8088"))
    private val notZeny = "Esse endereço não é um servidor do Zeny Hotel Manager."
    private val network = "Não foi possível falar com o servidor. Confira o endereço e a rede."

    private class SpyEngine(
        private val delegate: HttpClientEngine,
    ) : HttpClientEngine by delegate {
        var closed = 0

        override fun close() {
            closed++
            delegate.close()
        }
    }

    private fun json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun version(
        application: String = "zeny-hotel-manager",
        apiVersion: String = "v1",
    ) = """{"apiVersion":"$apiVersion","application":"$application","version":"1.4.2"}"""

    @Test
    fun probeOk() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v1/meta/version", request.url.encodedPath)
                    respond(version(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            assertEquals(ProbeResult.Ok("1.4.2"), ServerProbe.check(url) { engine })
        }

    @Test
    fun probeNotZeny() =
        runTest {
            assertEquals(ProbeResult.Failed(notZeny), ServerProbe.check(url) { json(version(application = "outro")) })
            assertEquals(ProbeResult.Failed(notZeny), ServerProbe.check(url) { json(version(apiVersion = "v2")) })
            val html =
                MockEngine {
                    val type = headersOf(HttpHeaders.ContentType, "text/html")
                    respond("<html>404</html>", HttpStatusCode.NotFound, type)
                }
            assertEquals(ProbeResult.Failed(notZeny), ServerProbe.check(url) { html })
        }

    @Test
    fun probeUnreachable() =
        runTest {
            val timeout = MockEngine { throw ConnectTimeoutException("sem resposta", null) }
            val unresolved = MockEngine { throw UnresolvedAddressException() }
            assertEquals(ProbeResult.Failed(network), ServerProbe.check(url) { timeout })
            assertEquals(ProbeResult.Failed(network), ServerProbe.check(url) { unresolved })
        }

    @Test
    fun probeClosesEngine() =
        runTest {
            val ok = SpyEngine(json(version()))
            ServerProbe.check(url) { ok }
            assertEquals(1, ok.closed)
            val failing = SpyEngine(MockEngine { throw UnresolvedAddressException() })
            ServerProbe.check(url) { failing }
            assertEquals(1, failing.closed)
        }

    @Test
    fun probeRequestTimeout() =
        runTest {
            val engine = SpyEngine(MockEngine { awaitCancellation() })
            val result = ServerProbe.probe(url, { engine }, requestTimeoutMillis = 50)
            assertEquals(ProbeResult.Failed(network), result)
            assertEquals(1, engine.closed)
        }

    @Test
    fun probePropagatesCancellation() =
        runTest {
            val engine = SpyEngine(MockEngine { throw CancellationException("cancelado") })
            assertFailsWith<CancellationException> { ServerProbe.check(url) { engine } }
            assertEquals(1, engine.closed)
            assertIs<ProbeResult.Ok>(ServerProbe.check(url) { json(version()) })
        }
}
