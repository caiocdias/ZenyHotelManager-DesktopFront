@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ATK_RES = "20000000-0000-0000-0000-0000000000c1"
private const val UNAVAILABLE = "A ficha só existe para reservas confirmadas, hospedadas ou encerradas."
private const val NOT_FOUND = "A reserva não foi encontrada. Atualize o painel."
private const val OPEN_FAIL = "Não foi possível abrir a ficha."
private const val NETWORK = "Não foi possível falar com o servidor. Confira o endereço e a rede."

private val ATK_PDF: ByteArray =
    (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n" +
            "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)

private class AtkCards : RegistrationCards {
    var result: suspend () -> PdfDocument = { PdfDocument(ATK_PDF, "ficha.pdf") }
    var calls = 0

    override suspend fun registrationCard(reservationId: String): PdfDocument {
        calls++
        return result()
    }
}

class RegistrationCardStateAtaqueTest {
    private val cards = AtkCards()
    private val created = mutableListOf<PdfDocumentState>()
    private val names = mutableListOf<String>()

    private fun TestScope.state(
        callerScope: CoroutineScope? = null,
        documentDispatcher: CoroutineDispatcher? = null,
        code: String = "R-7",
    ): RegistrationCardState {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RegistrationCardState(
            source = cards,
            scope = callerScope ?: CoroutineScope(dispatcher),
            reservationId = ATK_RES,
            reservationCode = code,
            newDocument = { bytes, scope ->
                PdfDocumentState(bytes, scope, documentDispatcher ?: dispatcher).also { created += it }
            },
            newActions = { bytes, name ->
                names += name
                PdfActions(bytes, name, { null }, { PrintOutcome.CANCELLED }, dispatcher, dispatcher)
            },
        )
    }

    private suspend fun assertClosed(document: PdfDocumentState) {
        assertFalse(document.open(), "documento deveria estar fechado")
    }

    private fun problem(
        status: Int,
        code: String?,
        detail: String? = "detalhe do servidor",
    ) = ApiError.Problem(status, code, detail, emptyList())

    private fun TestScope.failWith(error: Throwable): RegistrationCardState {
        cards.result = { throw error }
        return state().also { it.start() }
    }

    @Test
    fun closeDuringDownloadNeverExposesAndNeverOpensDocument() =
        runTest {
            val gate = CompletableDeferred<PdfDocument>()
            cards.result = { gate.await() }
            val state = state()
            state.start()
            state.close()
            gate.complete(PdfDocument(ATK_PDF, "ficha.pdf"))
            assertNull(state.viewer)
            assertNull(state.error)
            assertFalse(state.loading)
            assertTrue(names.isEmpty())
            created.forEach { assertClosed(it) }
        }

    @Test
    fun closeDuringOpenDiscardsViewerAndClosesDocument() =
        runTest {
            val state = state(documentDispatcher = StandardTestDispatcher(testScheduler))
            state.start()
            assertTrue(state.loading)
            assertEquals(1, created.size)
            state.close()
            advanceUntilIdle()
            assertNull(state.viewer)
            assertNull(state.error)
            assertFalse(state.loading)
            assertTrue(names.isEmpty())
            assertClosed(created.single())
        }

    @Test
    fun closeIsIdempotentAndClosesViewerDocument() =
        runTest {
            val state = state()
            state.start()
            val document = assertNotNull(state.viewer).document
            state.close()
            state.close()
            assertNull(state.viewer)
            assertClosed(document)
        }

    @Test
    fun startAfterCloseDoesNothing() =
        runTest {
            val state = state()
            state.close()
            state.start()
            state.retry()
            assertEquals(0, cards.calls)
            assertFalse(state.loading)
            assertNull(state.viewer)
        }

    @Test
    fun retryWhileLoadingIsNoOp() =
        runTest {
            val gate = CompletableDeferred<PdfDocument>()
            cards.result = { gate.await() }
            val state = state()
            state.start()
            state.retry()
            assertEquals(1, cards.calls)
            gate.complete(PdfDocument(ATK_PDF, "ficha.pdf"))
            assertNotNull(state.viewer)
            assertEquals(1, created.size)
            state.close()
        }

    @Test
    fun retryAfterErrorCreatesNewDocumentAndClosesPrevious() =
        runTest {
            cards.result = { PdfDocument("%PDF-1.4 lixo".toByteArray(), "ficha.pdf") }
            val state = state()
            state.start()
            assertEquals(OPEN_FAIL, state.error)
            cards.result = { PdfDocument(ATK_PDF, "ficha.pdf") }
            state.retry()
            assertNull(state.error)
            assertEquals(2, created.size)
            assertNotSame(created[0], created[1])
            assertClosed(created[0])
            assertEquals(created[1], assertNotNull(state.viewer).document)
            state.close()
            assertClosed(created[1])
        }

    @Test
    fun retryAfterSuccessReplacesViewerWithNewDocumentAndClosesOld() =
        runTest {
            val state = state()
            state.start()
            val first = assertNotNull(state.viewer)
            state.retry()
            val second = assertNotNull(state.viewer)
            assertNotSame(first.document, second.document)
            assertEquals(2, cards.calls)
            assertClosed(first.document)
            state.close()
            assertClosed(second.document)
        }

    @Test
    fun startTwiceAfterErrorRetriesWithNewDocument() =
        runTest {
            val state = failWith(ApiError.Network(IOException("x")))
            assertEquals(NETWORK, state.error)
            cards.result = { PdfDocument(ATK_PDF, "ficha.pdf") }
            state.start()
            assertNull(state.error)
            assertNotNull(state.viewer)
            assertEquals(2, cards.calls)
            assertEquals(1, created.size)
            state.start()
            assertEquals(2, cards.calls)
            state.close()
        }

    @Test
    fun errorClearsOldViewerOnRetryFailure() =
        runTest {
            val state = state()
            state.start()
            val document = assertNotNull(state.viewer).document
            cards.result = { throw ApiError.Network(IOException("x")) }
            state.retry()
            assertNull(state.viewer)
            assertEquals(NETWORK, state.error)
            assertClosed(document)
        }

    @Test
    fun unavailableUsesExactTextNotCode() =
        runTest {
            val state = failWith(problem(409, "REGISTRATION_CARD_UNAVAILABLE"))
            assertEquals(UNAVAILABLE, state.error)
            assertNull(state.viewer)
            assertTrue(created.isEmpty())
        }

    @Test
    fun notFoundUsesExactText() =
        runTest {
            assertEquals(NOT_FOUND, failWith(problem(404, "RESERVATION_NOT_FOUND")).error)
        }

    @Test
    fun forbiddenShowsCommonPermissionTextWithoutRawCode() =
        runTest {
            val error = failWith(problem(403, "FORBIDDEN", null)).error
            assertNotNull(error)
            assertFalse(error.contains("FORBIDDEN"))
            assertEquals(problem(403, "FORBIDDEN", null).userMessage, error)
        }

    @Test
    fun unknownClientErrorWithDetailNeverShowsRawCode() =
        runTest {
            val error = failWith(problem(422, "SOME_UNKNOWN_CODE")).error
            assertNotNull(error)
            assertFalse(error.contains("SOME_UNKNOWN_CODE"))
            assertEquals("detalhe do servidor", error)
        }

    @Test
    fun unknownServerErrorWithoutDetailShowsGenericTextNotCode() =
        runTest {
            val error = failWith(problem(500, "INTERNAL_BOOM", null)).error
            assertNotNull(error)
            assertFalse(error.contains("INTERNAL_BOOM"))
            assertTrue(error.isNotBlank())
        }

    @Test
    fun codeAsDetailNeverShown() =
        runTest {
            val error = failWith(problem(500, null, "INTERNAL_BOOM")).error
            assertNotNull(error)
            assertFalse(error.contains("INTERNAL_BOOM"))
        }

    @Test
    fun networkShowsCommonNetworkText() =
        runTest {
            assertEquals(NETWORK, failWith(ApiError.Network(IOException("boom"))).error)
        }

    @Test
    fun sessionEndedShowsItsText() =
        runTest {
            val state = failWith(ApiError.SessionEnded("TOKEN_EXPIRED"))
            assertEquals("Sua sessão foi encerrada. Entre novamente.", state.error)
            assertNull(state.viewer)
        }

    @Test
    fun incompatibleResponseShowsItsText() =
        runTest {
            val state = failWith(ApiError.IncompatibleResponse(IllegalStateException("x")))
            assertEquals(
                "O servidor respondeu num formato que esta versão do app não reconhece. Atualize o app.",
                state.error,
            )
            assertNull(state.viewer)
        }

    @Test
    fun nonApiExceptionBecomesCommonTextWithoutLeakingMessage() =
        runTest {
            val state = failWith(IllegalStateException("segredo-interno %PDF"))
            assertEquals(NETWORK, state.error)
            assertFalse(state.error!!.contains("segredo"))
            assertFalse(state.loading)
        }

    @Test
    fun callerScopeCancellationPropagatesWithoutErrorText() =
        runTest {
            val parent = Job()
            val gate = CompletableDeferred<PdfDocument>()
            cards.result = { gate.await() }
            val state = state(callerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + parent))
            state.start()
            assertTrue(state.loading)
            parent.cancel()
            gate.complete(PdfDocument(ATK_PDF, "ficha.pdf"))
            assertNull(state.error)
            assertNull(state.viewer)
            assertFalse(state.loading)
            assertTrue(created.isEmpty())
        }

    @Test
    fun cancellationExceptionFromSourceUnderCancelledScopeIsNotAnError() =
        runTest {
            val parent = Job()
            cards.result = {
                parent.cancel()
                throw CancellationException("cancelado")
            }
            val state = state(callerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + parent))
            state.start()
            assertNull(state.error)
            assertNull(state.viewer)
            assertFalse(state.loading)
        }

    @Test
    fun invalidPdfBytesShowOpenErrorWithoutViewerAndCloseDocument() =
        runTest {
            cards.result = { PdfDocument("%PDF-1.4 lixo".toByteArray(), "ficha.pdf") }
            val state = state()
            state.start()
            assertEquals(OPEN_FAIL, state.error)
            assertNull(state.viewer)
            assertFalse(state.loading)
            assertTrue(names.isEmpty())
            assertClosed(created.single())
        }

    @Test
    fun suggestedNameFallsBackWhenServerNameInvalid() =
        runTest {
            for (bad in listOf("", "   ", "..", "a\u0000b.pdf", "CON", "...")) {
                names.clear()
                cards.result = { PdfDocument(ATK_PDF, bad) }
                val state = state()
                state.start()
                assertEquals(listOf("ficha-R-7.pdf"), names, "nome do servidor: '$bad'")
                state.close()
            }
        }

    @Test
    fun suggestedNameSanitizesOddCode() =
        runTest {
            for (code in listOf("R/7", "R 7", "..\\..\\x", "R:7*?", "a/../b", "")) {
                names.clear()
                cards.result = { PdfDocument(ATK_PDF, null) }
                val state = state(code = code)
                state.start()
                val name = names.single()
                assertTrue(name.endsWith(".pdf"), "código '$code' -> $name")
                assertFalse(name.contains('/') || name.contains('\\') || name.contains(".."), "código '$code' -> $name")
                assertFalse(name.any { it in ":*?<>|\"" }, "código '$code' -> $name")
                state.close()
            }
        }

    @Test
    fun serverNameWithPathIsReducedToLastSegment() =
        runTest {
            cards.result = { PdfDocument(ATK_PDF, "../../etc/ficha-9.pdf") }
            val state = state()
            state.start()
            assertEquals(listOf("ficha-9.pdf"), names)
            state.close()
        }

    @Test
    fun toStringOfStateAndViewerNeverHasBytesOrName() =
        runTest {
            cards.result = { PdfDocument(ATK_PDF, "nome-secreto-hospede.pdf") }
            val state = state()
            state.start()
            val viewer = assertNotNull(state.viewer)
            for (text in listOf(state.toString(), viewer.toString())) {
                assertFalse(text.contains("%PDF"), text)
                assertFalse(text.contains("nome-secreto"), text)
                assertFalse(text.contains("hospede"), text)
                assertFalse(text.contains("MediaBox"), text)
            }
            state.close()
        }
}
