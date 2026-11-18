package com.zeny.desktop.app.folio

import com.zeny.desktop.app.folioWiring
import com.zeny.desktop.app.reception.PdfKind
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val READ = "billing.folios.read"
private const val NUMBER = "F-0001"

@OptIn(ExperimentalCoroutinesApi::class)
class FolioWiringTest {
    private val names = mutableListOf<String>()

    /** O estado do PDF com as peças do portador (o adaptador e o `PdfKind`), mas com ações que guardam o nome. */
    private fun TestScope.viewerOf(
        source: FolioSource,
        audience: StatementAudience,
        scope: CoroutineScope,
    ) = RegistrationCardState(
        source.statementCards(audience),
        scope,
        FOLIO_ID.toString(),
        NUMBER,
        newDocument = { bytes, s -> PdfDocumentState(bytes, s, StandardTestDispatcher(testScheduler)) },
        newActions = { bytes, name ->
            names += name
            PdfActions(
                bytes,
                name,
                { null },
                { PrintOutcome.CANCELLED },
                UnconfinedTestDispatcher(),
                UnconfinedTestDispatcher(),
            )
        },
        kind = audience.pdfKind,
    )

    @Test
    fun guestPdfAsksGuestAndSaysGuest() =
        runTest {
            val source = FakeFolioSource()
            val wiring = assertNotNull(folioWiring(FolioConnection(source), setOf(READ), backgroundScope, null, null))

            val state = assertNotNull(wiring.guestPdf).invoke(FOLIO_ID.toString(), NUMBER)
            state.start()
            runCurrent()

            assertEquals("Extrato — $NUMBER", state.title)
            assertEquals(listOf(FOLIO_ID.toString() to StatementAudience.GUEST), source.pdfCalls)
            state.close()

            val viewer = viewerOf(source, StatementAudience.GUEST, backgroundScope)
            viewer.start()
            runCurrent()
            assertNotNull(viewer.viewer)
            assertEquals(listOf("extrato-F-0001.pdf"), names)
            assertEquals(PdfKind.FOLIO_STATEMENT_GUEST, StatementAudience.GUEST.pdfKind)
            viewer.close()
        }

    @Test
    fun internalPdfAsksInternalAndSaysInternal() =
        runTest {
            val source = FakeFolioSource()
            val wiring = assertNotNull(folioWiring(FolioConnection(source), setOf(READ), backgroundScope, null, null))

            val state = assertNotNull(wiring.internalPdf).invoke(FOLIO_ID.toString(), NUMBER)
            state.start()
            runCurrent()

            assertEquals("Extrato interno — $NUMBER", state.title)
            assertEquals(listOf(FOLIO_ID.toString() to StatementAudience.INTERNAL), source.pdfCalls)
            state.close()

            // O servidor dá `extrato-<número>.pdf` às duas audiências: o interno sai com o prefixo dele.
            val viewer = viewerOf(source, StatementAudience.INTERNAL, backgroundScope)
            viewer.start()
            runCurrent()
            assertNotNull(viewer.viewer)
            assertEquals(listOf("extrato-interno-F-0001.pdf"), names)
            assertEquals(PdfKind.FOLIO_STATEMENT_INTERNAL, StatementAudience.INTERNAL.pdfKind)
            viewer.close()
        }

    @Test
    fun noWiringWithoutReadOrSource() =
        runTest {
            assertNull(folioWiring(FolioConnection(FakeFolioSource()), emptySet(), backgroundScope, null, null))
            assertNull(folioWiring(FolioConnection(null), setOf(READ), backgroundScope, null, null))
        }

    @Test
    fun wiringCarriesTheShellPermissions() =
        runTest {
            val source = FakeFolioSource()
            val permissions = setOf(READ, "billing.postings.create")

            val wiring = assertNotNull(folioWiring(FolioConnection(source), permissions, backgroundScope, null, null))

            assertEquals(permissions, wiring.permissions)
            assertEquals(source, wiring.source)
        }
}
