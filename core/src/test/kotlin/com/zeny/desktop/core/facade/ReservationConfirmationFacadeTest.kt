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
import kotlinx.coroutines.test.runTest
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

/** A confirmação da reserva em PDF (`DocumentsFacade.reservationConfirmation`), contra o `MockEngine`. */
class ReservationConfirmationFacadeTest {
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
    ): HttpResponseData {
        val headers = mutableListOf<Pair<String, List<String>>>()
        contentType?.let { headers += HttpHeaders.ContentType to listOf(it) }
        disposition?.let { headers += HttpHeaders.ContentDisposition to listOf(it) }
        return respond(body, HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
    }

    @Test
    fun confirmationReturnsPdfAndFileName() =
        runTest {
            core { binary(PDF, disposition = "attachment; filename=\"confirmacao-R1.pdf\"") }.use { core ->
                val document = core.documents.reservationConfirmation(RES)
                assertContentEquals(PDF, document.bytes)
                assertEquals("confirmacao-R1.pdf", document.fileName)
                val request = requests.single()
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.encodedPath.endsWith("/reservations/$RES/confirmation/pdf"))
                assertEquals("application/pdf", request.headers[HttpHeaders.Accept])
                assertNull(request.headers["Idempotency-Key"])
            }
        }

    @Test
    fun confirmationRejectsNonPdf() =
        runTest {
            core { binary("<html>$SECRET</html>".encodeToByteArray(), "text/html") }.use { core ->
                val error =
                    assertFailsWith<ApiError.IncompatibleResponse> { core.documents.reservationConfirmation(RES) }
                assertFalse(error.cause.toString().contains(SECRET))
            }
            core { binary("<html>$SECRET</html>".encodeToByteArray()) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.reservationConfirmation(RES) }
            }
            core { binary(ByteArray(0)) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.reservationConfirmation(RES) }
            }
            core { json("""{"x":"$SECRET"}""") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.reservationConfirmation(RES) }
            }
            val oversize = "%PDF-".encodeToByteArray() + ByteArray(20 * 1024 * 1024)
            core { binary(oversize) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.reservationConfirmation(RES) }
            }
        }

    @Test
    fun confirmationUnavailableIsProblem409() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "CONFIRMATION_UNAVAILABLE") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.documents.reservationConfirmation(RES) }
                assertEquals(409, error.status)
                assertEquals("CONFIRMATION_UNAVAILABLE", error.code)
            }
        }

    @Test
    fun invalidIdFailsBeforeNetwork() =
        runTest {
            core { binary(PDF) }.use { core ->
                val bad = "segredo-nao-uuid"
                val error = assertFailsWith<IllegalArgumentException> { core.documents.reservationConfirmation(bad) }
                assertFalse(error.message.orEmpty().contains(bad))
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun registrationCardStillWorks() =
        runTest {
            core { binary(PDF, disposition = "attachment; filename=\"ficha-R-1.pdf\"") }.use { core ->
                val document = core.documents.registrationCard(RES)
                assertContentEquals(PDF, document.bytes)
                assertEquals("ficha-R-1.pdf", document.fileName)
                assertTrue(
                    requests
                        .single()
                        .url.encodedPath
                        .endsWith("/reservations/$RES/registration-card/pdf"),
                )
            }
            core { binary("<html/>".encodeToByteArray(), "text/html") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.documents.registrationCard(RES) }
            }
        }
}
