package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CARD_RES = "20000000-0000-0000-0000-0000000000c1"

/** Um PDF mínimo de uma página em branco; o PDFBox reconstrói a tabela de referências. */
private val SAMPLE_PDF: ByteArray =
    (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n" +
            "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)

private fun cardProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

private class FakeCards : RegistrationCards {
    var result: suspend () -> PdfDocument = { PdfDocument(SAMPLE_PDF, "ficha.pdf") }
    var calls = 0

    override suspend fun registrationCard(reservationId: String): PdfDocument {
        calls++
        assertEquals(CARD_RES, reservationId)
        return result()
    }
}

class RegistrationCardStateTest {
    private val cards = FakeCards()
    private val created = mutableListOf<PdfDocumentState>()
    private val names = mutableListOf<String>()

    private fun TestScope.state(): RegistrationCardState {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RegistrationCardState(
            source = cards,
            scope = CoroutineScope(dispatcher),
            reservationId = CARD_RES,
            reservationCode = "R-7",
            newDocument = { bytes, scope ->
                PdfDocumentState(bytes, scope, dispatcher).also { created += it }
            },
            newActions = { bytes, name ->
                names += name
                PdfActions(
                    bytes,
                    name,
                    { null },
                    { PrintOutcome.CANCELLED },
                    dispatcher,
                    dispatcher,
                )
            },
        )
    }

    @Test
    fun opensViewerWithDocument() =
        runTest {
            val state = state()
            state.start()
            assertFalse(state.loading)
            assertNull(state.error)
            val viewer = assertNotNull(state.viewer)
            assertEquals(1, viewer.document.pageCount)
            assertEquals("Ficha de registro — R-7", state.title)
            assertEquals(listOf("ficha.pdf"), names)
            assertFalse(state.toString().contains("%PDF"))
            state.close()
        }

    @Test
    fun fallbackNameUsesCode() =
        runTest {
            cards.result = { PdfDocument(SAMPLE_PDF, null) }
            val state = state()
            state.start()
            assertEquals(listOf("ficha-R-7.pdf"), names)
            state.close()
        }

    @Test
    fun unavailableShowsMessageWithoutViewer() =
        runTest {
            cards.result = { throw cardProblem(409, "REGISTRATION_CARD_UNAVAILABLE") }
            val state = state()
            state.start()
            assertEquals("A ficha só existe para reservas confirmadas, hospedadas ou encerradas.", state.error)
            assertNull(state.viewer)
            assertFalse(state.loading)
        }

    @Test
    fun notFoundShowsMessage() =
        runTest {
            cards.result = { throw cardProblem(404, "RESERVATION_NOT_FOUND") }
            val state = state()
            state.start()
            assertEquals("A reserva não foi encontrada. Atualize o painel.", state.error)
            assertNull(state.viewer)
        }

    @Test
    fun forbiddenShowsMessage() =
        runTest {
            val problem = cardProblem(403, "FORBIDDEN")
            cards.result = { throw problem }
            val state = state()
            state.start()
            assertEquals(problem.userMessage, state.error)
            assertNull(state.viewer)
        }

    @Test
    fun networkShowsMessageAndRetryWorks() =
        runTest {
            var first = true
            cards.result = {
                if (first) {
                    first = false
                    throw ApiError.Network(IOException("x"))
                }
                PdfDocument(SAMPLE_PDF, "ficha.pdf")
            }
            val state = state()
            state.start()
            assertEquals(ApiError.Network(IOException("x")).userMessage, state.error)
            assertNull(state.viewer)
            state.retry()
            assertNull(state.error)
            assertNotNull(state.viewer)
            assertEquals(2, cards.calls)
            state.close()
        }

    @Test
    fun invalidPdfShowsOpenError() =
        runTest {
            cards.result = { PdfDocument("%PDF-1.4 lixo".toByteArray(), "ficha.pdf") }
            val state = state()
            state.start()
            assertEquals("Não foi possível abrir a ficha.", state.error)
            assertNull(state.viewer)
            assertTrue(created.single().error != null)
        }

    @Test
    fun closeDuringDownloadClosesLateDocument() =
        runTest {
            val gate = CompletableDeferred<PdfDocument>()
            cards.result = { withContext(NonCancellable) { gate.await() } }
            val state = state()
            state.start()
            assertTrue(state.loading)
            state.close()
            gate.complete(PdfDocument(SAMPLE_PDF, "ficha.pdf"))
            assertNull(state.viewer)
            assertNull(state.error)
            // Um documento fechado já não abre (open() devolve falso).
            created.forEach { assertFalse(it.open()) }
        }

    @Test
    fun startWhileLoadingIsNoOp() =
        runTest {
            val gate = CompletableDeferred<PdfDocument>()
            cards.result = { gate.await() }
            val state = state()
            state.start()
            state.start()
            assertEquals(1, cards.calls)
            gate.complete(PdfDocument(SAMPLE_PDF, "ficha.pdf"))
            assertNotNull(state.viewer)
            state.start()
            assertEquals(1, cards.calls)
            state.close()
        }
}
