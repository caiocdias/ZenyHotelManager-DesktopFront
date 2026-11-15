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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val SECRET = "SEGREDO-DO-HOSPEDE"
private const val CAP = 20 * 1024 * 1024
private val MAGIC = "%PDF-".encodeToByteArray()
private val PDF = "%PDF-1.7\n$SECRET\n%%EOF".encodeToByteArray()

/** Ataques ao download binário da ficha: teto, tipos, erros, nomes, cancelamento e vazamento. */
class DocumentsFacadeAtaqueTest {
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

    private fun sized(total: Int): ByteArray = MAGIC + ByteArray(total - MAGIC.size)

    private suspend fun card(
        body: ByteArray = PDF,
        contentType: String? = "application/pdf",
        disposition: String? = null,
    ): PdfDocument = core { binary(body, contentType, disposition) }.use { it.documents.registrationCard(RES) }

    private suspend fun cardError(
        body: ByteArray = PDF,
        contentType: String? = "application/pdf",
    ): Throwable =
        core { binary(body, contentType) }.use { core ->
            assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
        }

    private suspend fun fileName(disposition: String?): String? = card(disposition = disposition).fileName

    private fun chain(error: Throwable): String =
        generateSequence(error) { it.cause }.joinToString("|") { "$it ${it.message} ${it.stackTraceToString()}" }

    // ---- teto de tamanho ----

    @Test
    fun bodyExactlyAtCapIsAccepted() =
        runTest {
            assertEquals(CAP, card(sized(CAP)).bytes.size)
        }

    @Test
    fun bodyOneByteOverCapIsIncompatible() =
        runTest {
            cardError(sized(CAP + 1))
        }

    @Test
    fun declaredContentLengthOverCapFailsWithoutReadingBody() =
        runTest {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                // o canal nunca fecha: se o executor tentasse ler o corpo, o teste travaria
                val channel = ByteChannel()
                scope.launch {
                    runCatching {
                        channel.writeFully(PDF)
                        channel.flush()
                    }
                }
                core {
                    respond(
                        channel,
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ContentType to listOf("application/pdf"),
                            HttpHeaders.ContentLength to listOf("${CAP + 1}"),
                        ),
                    )
                }.use { core ->
                    assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun streamWithoutContentLengthStopsReadingPastCap() =
        runTest {
            val produced = AtomicLong()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val channel = ByteChannel()
                scope.launch {
                    runCatching {
                        val chunk = sized(64 * 1024)
                        while (produced.get() < 400L * 1024 * 1024) {
                            channel.writeFully(chunk)
                            channel.flush()
                            produced.addAndGet(chunk.size.toLong())
                        }
                        channel.close()
                    }
                }
                core {
                    respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
                }.use { core ->
                    assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
                }
                assertTrue(produced.get() < 3L * CAP, "leu ${produced.get()} bytes de um fluxo sem fim")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun streamedBodyWithinCapWithoutContentLengthIsAccepted() =
        runTest {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val channel = ByteChannel()
                scope.launch {
                    runCatching {
                        channel.writeFully(PDF)
                        channel.close()
                    }
                }
                core {
                    respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
                }.use { core ->
                    assertContentEquals(PDF, core.documents.registrationCard(RES).bytes)
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun networkFailureMidBodyIsNetworkWithoutPartialBytes() =
        runTest {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val channel = ByteChannel()
                scope.launch {
                    runCatching {
                        channel.writeFully(PDF)
                        channel.flush()
                        channel.close(IOException("conexao caiu"))
                    }
                }
                core {
                    respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
                }.use { core ->
                    val error = assertFailsWith<ApiError.Network> { core.documents.registrationCard(RES) }
                    assertFalse(chain(error).contains(SECRET))
                }
            } finally {
                scope.cancel()
            }
        }

    // ---- tipos de conteúdo e assinatura ----

    @Test
    fun contentTypeVariantsAreAccepted() =
        runTest {
            for (type in listOf("APPLICATION/PDF", "Application/Pdf; charset=binary", "application/pdf ; q=1")) {
                assertContentEquals(PDF, card(contentType = type).bytes, type)
            }
        }

    @Test
    fun wrongContentTypesAreIncompatibleEvenWithPdfBytes() =
        runTest {
            val types =
                listOf("application/octet-stream", "text/html", "*/*", "application/*", "application/pdfx", null)
            for (type in types) {
                cardError(PDF, type)
            }
        }

    @Test
    fun malformedContentTypeIsIncompatibleNotAnUntypedException() =
        runTest {
            for (type in listOf("garbage", "application/", "/pdf", "application/pdf; =")) {
                cardError(PDF, type)
            }
        }

    @Test
    fun shortOrDisguisedBodiesAreIncompatible() =
        runTest {
            cardError("%PDF".encodeToByteArray())
            cardError("%".encodeToByteArray())
            cardError(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + PDF)
            cardError(" ".encodeToByteArray() + PDF)
            cardError("\n".encodeToByteArray() + PDF)
            cardError("%pdf-1.7".encodeToByteArray())
        }

    @Test
    fun exactlyFiveByteMagicIsAccepted() =
        runTest {
            assertContentEquals(MAGIC, card(MAGIC).bytes)
        }

    @Test
    fun incompatibleErrorsNeverCarryBodyOrName() =
        runTest {
            val body = "<html>$SECRET</html>".encodeToByteArray()
            core { binary(body, "text/html", "attachment; filename=\"$SECRET.pdf\"") }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
                assertFalse(chain(error).contains(SECRET))
            }
        }

    // ---- erros HTTP ----

    @Test
    fun httpErrorsAreMappedLikeTheTypedPath() =
        runTest {
            for ((status, code) in listOf(
                HttpStatusCode.Forbidden to "PERMISSION_DENIED",
                HttpStatusCode.InternalServerError to "INTERNAL",
                HttpStatusCode.Conflict to "REGISTRATION_CARD_UNAVAILABLE",
            )) {
                core { problem(status, code) }.use { core ->
                    val error = assertFailsWith<ApiError.Problem> { core.documents.registrationCard(RES) }
                    assertEquals(status.value, error.status)
                    assertEquals(code, error.code)
                }
            }
        }

    @Test
    fun errorBodiesThatAreNotProblemJsonAreTypedAndDoNotLeak() =
        runTest {
            val statuses =
                listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway)
            for (status in statuses) {
                core { binary("<html>$SECRET</html>".encodeToByteArray(), "text/html", status = status) }.use { core ->
                    val error = assertFailsWith<ApiError> { core.documents.registrationCard(RES) }
                    assertFalse(chain(error).contains(SECRET))
                }
            }
        }

    @Test
    fun errorStatusWithPdfBodyIsStillAnError() =
        runTest {
            core { binary(PDF, status = HttpStatusCode.InternalServerError) }.use { core ->
                val error = assertFailsWith<ApiError> { core.documents.registrationCard(RES) }
                assertFalse(chain(error).contains(SECRET))
            }
        }

    @Test
    fun sessionEndedOn401WithNonProblemBody() =
        runTest {
            val unauthorized = HttpStatusCode.Unauthorized
            core { binary("nope".encodeToByteArray(), "text/plain", status = unauthorized) }.use { core ->
                assertFailsWith<ApiError.SessionEnded> { core.documents.registrationCard(RES) }
            }
        }

    @Test
    fun noContentOn204IsIncompatible() =
        runTest {
            core { binary(ByteArray(0), null, status = HttpStatusCode.NoContent) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
            }
        }

    @Test
    fun jsonOkWithPdfMagicStillRejected() =
        runTest {
            core { json(String(PDF)) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
            }
        }

    @Test
    fun failureDoesNotPoisonTheNextCall() =
        runTest {
            var first = true
            core {
                if (first) {
                    first = false
                    problem(HttpStatusCode.Conflict, "REGISTRATION_CARD_UNAVAILABLE")
                } else {
                    binary(PDF)
                }
            }.use { core ->
                assertFailsWith<ApiError.Problem> { core.documents.registrationCard(RES) }
                assertContentEquals(PDF, core.documents.registrationCard(RES).bytes)
            }
        }

    // ---- cancelamento ----

    @Test
    fun callerCancellationIsPropagatedNotSwallowedAsNetwork() =
        runTest {
            val started = CompletableDeferred<Unit>()
            core {
                started.complete(Unit)
                awaitCancellation()
            }.use { core ->
                val job = async(Dispatchers.Default) { core.documents.registrationCard(RES) }
                started.await()
                job.cancel()
                val error = runCatching { job.await() }.exceptionOrNull()
                assertTrue(error is CancellationException, "veio $error")
            }
        }

    @Test
    fun cancellationThrownByEngineIsNotWrapped() =
        runTest {
            core { throw CancellationException("cancelado") }.use { core ->
                val error = assertFailsWith<CancellationException> { core.documents.registrationCard(RES) }
            }
        }

    // ---- cabeçalhos enviados ----

    @Test
    fun sendsNoIdempotencyKeyNorApprovalToken() =
        runTest {
            core { binary(PDF) }.use { core ->
                core.documents.registrationCard(RES)
                val headers = requests.single().headers
                assertNull(headers["Idempotency-Key"])
                assertNull(headers["X-Approval-Token"])
                assertNull(headers[HttpHeaders.IfMatch])
                assertEquals("application/pdf", headers.getAll(HttpHeaders.Accept)?.first())
            }
        }

    @Test
    fun invalidIdsFailBeforeNetworkWithoutEchoingValue() =
        runTest {
            core { binary(PDF) }.use { core ->
                for (bad in listOf("", " ", "../x", "$RES/../x", "$RES?x=1", "%00", RES + "0")) {
                    val error = assertFailsWith<IllegalArgumentException> { core.documents.registrationCard(bad) }
                    if (bad.isNotBlank()) assertFalse(error.message.orEmpty().contains(bad))
                }
                assertTrue(requests.isEmpty())
            }
        }

    // ---- nome do arquivo ----

    @Test
    fun fileNameDecodesExtendedAndFallsBackOnUnknownCharset() =
        runTest {
            assertEquals("ficha-Ré.pdf", fileName("attachment; filename*=UTF-8''ficha-R%C3%A9.pdf"))
            assertEquals("ficha-Ré.pdf", fileName("attachment; filename*=ISO-8859-1''ficha-R%E9.pdf"))
            assertEquals("ficha-Ré.pdf", fileName("attachment; filename*=utf-8'pt'ficha-R%C3%A9.pdf"))
            assertEquals("plain.pdf", fileName("attachment; filename=\"plain.pdf\"; filename*=XYZ-9''x.pdf"))
            assertNull(fileName("attachment; filename*=XYZ-9''x.pdf"))
            assertEquals("plain.pdf", fileName("attachment; filename=\"plain.pdf\"; filename*=UTF-8''%4"))
            assertNull(fileName("attachment; filename*=UTF-8''%ZZ.pdf"))
            assertNull(fileName("attachment; filename*=UTF-8''%"))
            assertNull(fileName("attachment; filename*=sem-aspas-simples"))
        }

    @Test
    fun fileNameWithInvalidUtf8DoesNotCrash() =
        runTest {
            val name = fileName("attachment; filename*=UTF-8''ficha-R%E9.pdf")
            assertTrue(name == null || name.endsWith(".pdf"))
        }

    @Test
    fun extendedNameWinsOverPlainWhenBothPresent() =
        runTest {
            assertEquals("novo.pdf", fileName("attachment; filename=\"velho.pdf\"; filename*=UTF-8''novo.pdf"))
            assertEquals("novo.pdf", fileName("attachment; filename*=UTF-8''novo.pdf; filename=\"velho.pdf\""))
        }

    @Test
    fun quotedNameWithSemicolonStaysWhole() =
        runTest {
            assertEquals("a;b.pdf", fileName("attachment; filename=\"a;b.pdf\""))
        }

    @Test
    fun traversalAndWindowsPathsKeepOnlyLastSegment() =
        runTest {
            assertEquals("x.pdf", fileName("attachment; filename=\"../../../etc/x.pdf\""))
            assertEquals("y.pdf", fileName("attachment; filename=\"C:\\\\x\\\\y.pdf\""))
            assertEquals("x.pdf", fileName("attachment; filename*=UTF-8''..%2F..%2Fx.pdf"))
            assertEquals("x.pdf", fileName("attachment; filename*=UTF-8''..%5C..%5Cx.pdf"))
            assertEquals("x.pdf", fileName("attachment; filename*=UTF-8''%2Fetc%2Fx.pdf"))
            assertNull(fileName("attachment; filename*=UTF-8''%2E%2E"))
            assertNull(fileName("attachment; filename=\"a..b.pdf\""))
            assertNull(fileName("attachment; filename=\"/\""))
            assertNull(fileName("attachment; filename=\"\""))
            assertNull(fileName("attachment; filename=\"   \""))
        }

    @Test
    fun controlCharactersMakeNameNull() =
        runTest {
            assertNull(fileName("attachment; filename*=UTF-8''a%00b.pdf"))
            assertNull(fileName("attachment; filename*=UTF-8''a%0Ab.pdf"))
            assertNull(fileName("attachment; filename*=UTF-8''a%0D%0AX-Evil:1.pdf"))
            assertNull(fileName("attachment; filename*=UTF-8''a%1Fb.pdf"))
            assertNull(fileName("attachment; filename*=UTF-8''a%7Fb.pdf"))
        }

    @Test
    fun bidiOverrideCharactersMakeNameNull() =
        runTest {
            // U+202E inverte a exibição: "ficha\u202Efdp.exe" aparece como "fichaexe.pdf"
            assertNull(fileName("attachment; filename*=UTF-8''ficha%E2%80%AEfdp.exe"))
        }

    @Test
    fun nameWithoutPdfExtensionGetsOne() =
        runTest {
            assertEquals("ficha.pdf", fileName("attachment; filename=\"ficha\""))
            assertEquals("ficha.txt.pdf", fileName("attachment; filename=\"ficha.txt\""))
            assertEquals("FICHA.PDF", fileName("attachment; filename=\"FICHA.PDF\""))
        }

    @Test
    fun veryLongNameDoesNotCrash() =
        runTest {
            val name = fileName("attachment; filename=\"${"a".repeat(100_000)}.pdf\"")
            assertTrue(name == null || name.endsWith(".pdf"))
        }

    @Test
    fun hugeDispositionHeaderDoesNotCrash() =
        runTest {
            val name = fileName("attachment; " + "x=\"y\"; ".repeat(5000) + "filename=\"ok.pdf\"")
            assertTrue(name == null || name == "ok.pdf")
        }

    // ---- vazamento ----

    @Test
    fun pdfDocumentEqualityAndToStringHideContent() {
        val a = PdfDocument(PDF, "n.pdf")
        assertEquals(a, PdfDocument(PDF.copyOf(), "n.pdf"))
        assertNotEquals(a, PdfDocument(PDF, "outro.pdf"))
        assertNotEquals(a, PdfDocument(PDF, null))
        assertNotEquals(a, PdfDocument(PDF + byteArrayOf(0), "n.pdf"))
        assertNotEquals<Any?>(a, null)
        assertEquals("PdfDocument(0 bytes)", PdfDocument(ByteArray(0), SECRET).toString())
        assertFalse(a.toString().contains("n.pdf"))
        assertFalse(a.toString().contains(SECRET))
    }
}
