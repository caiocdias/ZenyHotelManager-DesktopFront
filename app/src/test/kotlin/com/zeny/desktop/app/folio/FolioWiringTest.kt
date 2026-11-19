package com.zeny.desktop.app.folio

import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.folioWiring
import com.zeny.desktop.app.reception.PdfKind
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.SplitPart
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

private const val READ = "billing.folios.read"
private const val NUMBER = "F-0001"
private const val OPERATE = "billing.cashier.operate"

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
    fun wiringCarriesTheExpiringSource() =
        runTest {
            val expiring = ExpiringPreAuthorizationSource { _, _, _ -> Page(emptyList(), null) }
            val connection = FolioConnection(FakeFolioSource(), payments = FakePaymentSource(), expiring = expiring)
            val wiring = assertNotNull(folioWiring(connection, setOf(READ), backgroundScope, null, null))
            assertSame(expiring, wiring.expiring)

            val withoutPayments = FolioConnection(FakeFolioSource(), expiring = expiring)
            val bare = assertNotNull(folioWiring(withoutPayments, setOf(READ), backgroundScope, null, null))
            assertNull(bare.expiring)
        }

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

    @Test
    fun wiringCarriesPaymentsAndApproval() =
        runTest {
            val payments = FakePaymentSource()
            val coordinator = ApprovalCoordinator({ _, _ -> "tok" }, emptyFlow(), backgroundScope)
            val connection = FolioConnection(FakeFolioSource(), payments = payments)
            val wiring = assertNotNull(folioWiring(connection, setOf(READ), backgroundScope, coordinator, null))
            assertSame(payments, wiring.payments)
            val tokens = mutableListOf<String?>()
            val action =
                ApprovableAction<PaymentResponse> { token ->
                    tokens += token
                    throw ApiError.ApprovalRequired("PAYMENT_REFUND", "billing.payments.refund", "payment", null, null)
                }

            backgroundScope.launch { wiring.approveRefund(action) }
            runCurrent()

            assertEquals(listOf<String?>(null), tokens, "a ação rodou, ao contrário do padrão { null }")
            assertNotNull(coordinator.dialog, "o coordenador viu o pedido de aprovação")
            coordinator.cancel()
        }

    @Test
    fun openShiftNeedsCashierOperate() =
        runTest {
            var opened = 0
            val connection = FolioConnection(FakeFolioSource())

            val reader = assertNotNull(folioWiring(connection, setOf(READ), backgroundScope, null, null) { opened++ })
            assertNull(reader.openShift)

            val operator =
                assertNotNull(folioWiring(connection, setOf(READ, OPERATE), backgroundScope, null, null) { opened++ })
            assertNotNull(operator.openShift).invoke()
            assertEquals(1, opened)
            val withoutShortcut = folioWiring(connection, setOf(READ, OPERATE), backgroundScope, null, null)
            assertNull(assertNotNull(withoutShortcut).openShift)
        }

    private val corrections =
        object : FolioCorrectionSource {
            override fun adjust(
                folioId: String,
                postingId: String,
                amount: Money,
                reason: String,
                intent: Intent,
            ): ApprovableAction<PostingResponse> = error("não usado")

            override suspend fun removeServiceCharge(
                folioId: String,
                postingId: String,
                intent: Intent,
            ): PostingResponse = error("não usado")

            override suspend fun transfer(
                folioId: String,
                postingIds: List<String>,
                targetFolioId: String,
                reason: String?,
                intent: Intent,
            ): TransferResponse = error("não usado")

            override suspend fun split(
                folioId: String,
                postingId: String,
                parts: List<SplitPart>,
                reason: String?,
                intent: Intent,
            ): TransferResponse = error("não usado")
        }

    private val account =
        object : FolioAccountSource {
            override suspend fun versionedFolio(folioId: String): VersionedFolio = error("não usado")

            override suspend fun updateFolio(
                folioId: String,
                etag: String,
                payerCompanyId: String?,
                creditLimit: Money?,
            ): VersionedFolio = error("não usado")

            override suspend fun activeCompanyPage(
                name: String?,
                cursor: String?,
                limit: Int,
            ): Page<CompanyChoice> = error("não usado")

            override suspend fun company(companyId: String): CompanyChoice = error("não usado")
        }

    @Test
    fun wiringCarriesTheAccountSource() =
        runTest {
            val with = FolioConnection(FakeFolioSource(), account = account)
            val wiring = assertNotNull(folioWiring(with, setOf(READ), backgroundScope, null, null))
            assertSame(account, wiring.account)

            val bare = FolioConnection(FakeFolioSource())
            val without = assertNotNull(folioWiring(bare, setOf(READ), backgroundScope, null, null))
            assertNull(without.account)
        }

    @Test
    fun wiringCarriesTheCorrections() =
        runTest {
            val with = FolioConnection(FakeFolioSource(), corrections = corrections)
            val wiring = assertNotNull(folioWiring(with, setOf(READ), backgroundScope, null, null))
            assertSame(corrections, wiring.corrections)

            val bare = FolioConnection(FakeFolioSource())
            val without = assertNotNull(folioWiring(bare, setOf(READ), backgroundScope, null, null))
            assertNull(without.corrections)
        }
}
