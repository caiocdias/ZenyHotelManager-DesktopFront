package com.zeny.desktop.app.reception

import com.zeny.desktop.app.reservations.ReservationConfirmations
import com.zeny.desktop.app.reservations.asCards
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KIND_RES = "20000000-0000-0000-0000-0000000000c2"

/** Um PDF mínimo de uma página em branco; o PDFBox reconstrói a tabela de referências. */
private val KIND_PDF: ByteArray =
    (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n" +
            "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)

private fun kindProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

class RegistrationCardKindTest {
    private var result: suspend () -> PdfDocument = { PdfDocument(KIND_PDF, null) }
    private val names = mutableListOf<String>()
    private val saves = mutableListOf<String>()
    private val prints = mutableListOf<String>()

    private fun TestScope.state(kind: PdfKind?): RegistrationCardState {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = RegistrationCards { result() }
        val scope = CoroutineScope(dispatcher)
        val newDocument = { bytes: ByteArray, s: CoroutineScope -> PdfDocumentState(bytes, s, dispatcher) }
        val newActions = { bytes: ByteArray, name: String ->
            names += name
            PdfActions(
                bytes,
                name,
                {
                    saves += name
                    null
                },
                {
                    prints += name
                    PrintOutcome.CANCELLED
                },
                dispatcher,
                dispatcher,
            )
        }
        return if (kind == null) {
            RegistrationCardState(source, scope, KIND_RES, "R1", newDocument, newActions)
        } else {
            RegistrationCardState(source, scope, KIND_RES, "R1", newDocument, newActions, kind)
        }
    }

    @Test
    fun defaultKindKeepsCardTitleAndName() =
        runTest {
            val state = state(null)
            assertEquals("Ficha de registro — R1", state.title)
            state.start()
            assertEquals(listOf("ficha-R1.pdf"), names)
            state.close()
        }

    @Test
    fun confirmationKindUsesItsTitleAndName() =
        runTest {
            val state = state(PdfKind.CONFIRMATION)
            assertEquals("Confirmação da reserva — R1", state.title)
            state.start()
            assertEquals(listOf("confirmacao-R1.pdf"), names)
            state.close()

            result = { PdfDocument(KIND_PDF, "confirmacao-ABC.pdf") }
            names.clear()
            val named = state(PdfKind.CONFIRMATION)
            named.start()
            assertEquals(listOf("confirmacao-ABC.pdf"), names)
            named.close()
        }

    @Test
    fun confirmationRefusalShowsCatalogText() =
        runTest {
            val problem = kindProblem(409, "CONFIRMATION_UNAVAILABLE")
            result = { throw problem }
            val state = state(PdfKind.CONFIRMATION)
            state.start()
            assertEquals(problem.userMessage, state.error)
            assertFalse(state.error.orEmpty().contains("CONFIRMATION_UNAVAILABLE"))
            assertNull(state.viewer)
        }

    @Test
    fun confirmationTextsDoNotMentionCard() =
        runTest {
            result = { PdfDocument("%PDF-1.4 lixo".toByteArray(), null) }
            val opening = state(PdfKind.CONFIRMATION)
            opening.start()
            assertEquals("Não foi possível abrir a confirmação.", opening.error)

            result = { throw kindProblem(404, "RESERVATION_NOT_FOUND") }
            val missing = state(PdfKind.CONFIRMATION)
            missing.start()
            assertEquals("A reserva não foi encontrada. Refaça a busca.", missing.error)

            listOf(opening.error, missing.error).forEach {
                assertFalse(it.orEmpty().contains("ficha", ignoreCase = true))
                assertFalse(it.orEmpty().contains("painel", ignoreCase = true))
            }
        }

    @Test
    fun confirmationNeverSavesByItself() =
        runTest {
            val state = state(PdfKind.CONFIRMATION)
            state.start()
            assertNotNull(state.viewer)
            state.close()
            assertTrue(saves.isEmpty())
            assertTrue(prints.isEmpty())
        }

    @Test
    fun asCardsDelegates() =
        runTest {
            val document = PdfDocument(KIND_PDF, "confirmacao-X.pdf")
            var asked: String? = null
            val confirmations =
                ReservationConfirmations {
                    asked = it
                    document
                }
            assertEquals(document, confirmations.asCards().registrationCard(KIND_RES))
            assertEquals(KIND_RES, asked)
        }
}
