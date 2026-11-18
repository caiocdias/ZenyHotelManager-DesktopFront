package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val SECRET = "SEGREDO-DO-HOSPEDE"
private val PDF = "%PDF-1.7\n$SECRET\n%%EOF".encodeToByteArray()

/** Download binário da ficha de registro, contra o `MockEngine`. */
class DocumentsFacadeTest {
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

    private fun MockRequestHandleScope.binary(
        body: ByteArray,
        contentType: String? = "application/pdf",
        disposition: String? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData {
        val headers = mutableListOf<Pair<String, List<String>>>()
        contentType?.let { headers += HttpHeaders.ContentType to listOf(it) }
        disposition?.let { headers += HttpHeaders.ContentDisposition to listOf(it) }
        return respond(body, status, headersOf(*headers.toTypedArray()))
    }

    private suspend fun fileName(disposition: String?): String? =
        core { binary(PDF, disposition = disposition) }.use { it.documents.registrationCard(RES).fileName }

    @Test
    fun returnsBytesAndServerFileName() =
        runTest {
            core { binary(PDF, disposition = "attachment; filename=\"ficha-R-1.pdf\"") }.use { core ->
                val document = core.documents.registrationCard(RES)
                assertContentEquals(PDF, document.bytes)
                assertEquals("ficha-R-1.pdf", document.fileName)
                assertEquals(PdfDocument(PDF, "ficha-R-1.pdf"), document)
                assertEquals(PdfDocument(PDF, "ficha-R-1.pdf").hashCode(), document.hashCode())
            }
        }

    @Test
    fun sendsAcceptPdfWithoutIdempotencyKey() =
        runTest {
            core { binary(PDF) }.use { core ->
                core.documents.registrationCard(RES)
                val request = requests.single()
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.encodedPath.endsWith("/reservations/$RES/registration-card/pdf"))
                assertEquals("application/pdf", request.headers[HttpHeaders.Accept])
                assertNull(request.headers["Idempotency-Key"])
            }
        }

    @Test
    fun unavailableIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "REGISTRATION_CARD_UNAVAILABLE") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.documents.registrationCard(RES) }
                assertEquals(409, error.status)
                assertEquals("REGISTRATION_CARD_UNAVAILABLE", error.code)
            }
        }

    @Test
    fun notFoundIsProblem() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.documents.registrationCard(RES) }
                assertEquals("RESERVATION_NOT_FOUND", error.code)
            }
        }

    @Test
    fun sessionEndedOn401() =
        runTest {
            core { problem(HttpStatusCode.Unauthorized, "SESSION_REVOKED") }.use { core ->
                assertFailsWith<ApiError.SessionEnded> { core.documents.registrationCard(RES) }
            }
        }

    @Test
    fun networkFailureIsTyped() =
        runTest {
            core { throw IOException("queda") }.use { core ->
                assertFailsWith<ApiError.Network> { core.documents.registrationCard(RES) }
            }
        }

    /**
     * A conexão cai no meio do corpo antes de o `core` começar a ler: no Ktor, o canal fechado com causa devolve zero
     * bytes sem lançar. O fechamento acontece antes da resposta, então a ordem é sempre essa (sem corrida).
     */
    @Test
    fun bodyClosedWithCauseBeforeTheReadIsNetwork() =
        runTest {
            val channel = ByteChannel()
            channel.writeFully(PDF)
            channel.flush()
            channel.close(IOException("conexao caiu"))
            core {
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
            }.use { core ->
                assertFailsWith<ApiError.Network> { core.documents.registrationCard(RES) }
            }
        }

    @Test
    fun nonPdfBodyIsIncompatible() =
        runTest {
            core { binary("<html>$SECRET</html>".encodeToByteArray(), "text/html") }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
                assertFalse(error.toString().contains(SECRET))
                assertFalse(error.cause.toString().contains(SECRET))
            }
            core { json("""{"x":"$SECRET"}""") }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
                assertFalse(error.cause.toString().contains(SECRET))
            }
            core { binary("<html>$SECRET</html>".encodeToByteArray()) }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
                assertFalse(error.cause.toString().contains(SECRET))
            }
            core { binary(ByteArray(0)) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
            }
            val oversize = "%PDF-".encodeToByteArray() + ByteArray(20 * 1024 * 1024)
            core { binary(oversize) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
            }
        }

    @Test
    fun contentTypeWithParametersIsAccepted() =
        runTest {
            core { binary(PDF, "application/pdf; charset=binary") }.use { core ->
                assertContentEquals(PDF, core.documents.registrationCard(RES).bytes)
            }
        }

    @Test
    fun fileNameIsSanitizedOrNull() =
        runTest {
            assertEquals("x.pdf", fileName("attachment; filename=\"../../x.pdf\""))
            assertEquals("b.pdf", fileName("attachment; filename=\"a/b\""))
            assertEquals("b.pdf", fileName("attachment; filename=\"a\\\\b.pdf\""))
            assertNull(fileName(null))
            assertNull(fileName("attachment"))
            assertNull(fileName("attachment; filename=\"..\""))
            assertEquals("ficha-Ré.pdf", fileName("attachment; filename*=UTF-8''ficha-R%C3%A9.pdf"))
            assertEquals(
                "ficha-Ré.pdf",
                fileName("attachment; filename=\"ficha-R.pdf\"; filename*=UTF-8''ficha-R%C3%A9.pdf"),
            )
        }

    @Test
    fun invalidIdFailsBeforeNetwork() =
        runTest {
            core { binary(PDF) }.use { core ->
                val bad = "segredo-nao-uuid"
                val error = assertFailsWith<IllegalArgumentException> { core.documents.registrationCard(bad) }
                assertFalse(error.message.orEmpty().contains(bad))
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun folioStatementSendsAudienceAndValidatesPdf() =
        runTest {
            val folio = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
            core { binary(PDF) }.use { core ->
                core.documents.folioStatement(folio, StatementAudience.GUEST)
                core.documents.folioStatement(folio, StatementAudience.INTERNAL)
                assertTrue(requests.all { it.url.encodedPath.endsWith("/folios/$folio/statement/pdf") })
                assertEquals(listOf("GUEST", "INTERNAL"), requests.map { it.url.parameters["audience"] })
                assertEquals("application/pdf", requests[0].headers[HttpHeaders.Accept])
            }
            core { binary("<html>não é pdf</html>".encodeToByteArray(), contentType = "text/html") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> {
                    core.documents.folioStatement(folio, StatementAudience.GUEST)
                }
                assertFailsWith<IllegalArgumentException> {
                    core.documents.folioStatement("nao-uuid", StatementAudience.GUEST)
                }
            }
        }

    @Test
    fun toStringHidesBytes() {
        val text = PdfDocument(PDF, "ficha-$SECRET.pdf").toString()
        assertEquals("PdfDocument(${PDF.size} bytes)", text)
    }
}
