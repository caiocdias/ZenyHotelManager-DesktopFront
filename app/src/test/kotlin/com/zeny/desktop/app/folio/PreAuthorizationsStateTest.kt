package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"

/** A seção "Cauções" do painel da conta ([PreAuthorizationsState]). */
@OptIn(ExperimentalCoroutinesApi::class)
class PreAuthorizationsStateTest {
    private fun TestScope.holder(
        source: FakePaymentSource,
        permissions: Set<String> = setOf(READ, RECEIVE),
        folioOpen: Boolean = true,
    ) = PreAuthorizationsState(
        wiringOf(FakeFolioSource(), permissions, payments = source),
        backgroundScope,
        FOLIO_ID.toString(),
        folioOpen = { folioOpen },
        otherDialogOpen = { false },
        notify = {},
        onChanged = {},
    ).also {
        it.reload()
        runCurrent()
    }

    private fun TestScope.folio(source: FakePaymentSource) =
        FolioState(
            wiringOf(FakeFolioSource(), setOf(READ, RECEIVE), payments = source),
            backgroundScope,
            FOLIO_ID.toString(),
        ).also {
            it.start()
            runCurrent()
        }

    @Test
    fun rowsShowWhatTheServerSends() =
        runTest {
            val source =
                FakePaymentSource().apply {
                    preAuthorizations = {
                        listOf(
                            preAuthorizationOf(
                                amount = "300.00",
                                captured = "100.00",
                                capturable = "200.00",
                                status = "PARTIALLY_CAPTURED",
                                cardBrand = "Visa",
                                cardLast4 = "1234",
                            ),
                            preAuthorizationOf(capturable = "0.00", status = "RELEASED", released = "300.00"),
                        )
                    }
                }

            val (partial, released) = holder(source).rows

            assertEquals("Capturada em parte", partial.status)
            assertEquals("PARTIALLY_CAPTURED", partial.statusCode)
            assertEquals("Visa final 1234", partial.card)
            assertEquals("R$ 300,00", partial.amount)
            assertEquals("R$ 100,00", partial.captured)
            assertEquals(Money.parse("200.00"), partial.capturable)
            assertNull(partial.released)
            assertEquals("19/11/2026 23:59", partial.expiresAt, "no fuso do hotel")
            assertFalse("1234" in partial.toString(), "o toString não mostra o cartão")
            assertEquals("Liberada", released.status)
            assertNull(released.card)
            assertNull(released.capturable, "capturável zero não aparece")
            assertEquals("R$ 300,00", released.released)
        }

    @Test
    fun unknownStatusShowsAsOther() =
        runTest {
            val codes = listOf("ACTIVE", "PARTIALLY_CAPTURED", "CAPTURED", "RELEASED", "EXPIRED", "HELD")
            val source =
                FakePaymentSource().apply {
                    preAuthorizations = { codes.map { code -> preAuthorizationOf(status = code) } }
                }

            val labels = holder(source).rows.map { it.status }

            assertEquals(
                listOf("Ativa", "Capturada em parte", "Capturada", "Liberada", "Vencida", "Outro (HELD)"),
                labels,
            )
        }

    @Test
    fun emptyTextWaitsForTheFirstRead() =
        runTest {
            val gate = CompletableDeferred<List<CardPreAuthorizationResponse>>()
            val source = FakePaymentSource().apply { preAuthorizations = { gate.await() } }

            val holder = holder(source)
            assertFalse(holder.loaded)

            gate.complete(emptyList())
            runCurrent()
            assertTrue(holder.loaded)
            assertTrue(holder.rows.isEmpty())
            assertNull(holder.error)
        }

    @Test
    fun readFailureKeepsTheFolio() =
        runTest {
            val source =
                FakePaymentSource().apply { preAuthorizations = { throw ApiError.Network(IOException("x")) } }

            val folio = folio(source)
            val holder = assertNotNull(folio.preAuthorizations)

            assertNotNull(folio.view)
            assertNull(folio.error)
            assertEquals("Não foi possível ler as cauções.", holder.error)
            assertTrue(holder.loaded)

            source.preAuthorizations = { listOf(preAuthorizationOf()) }
            folio.reload()
            runCurrent()
            assertNull(holder.error)
            assertEquals(1, holder.rows.size, "relida junto da conta")
        }

    @Test
    fun releaseStaysAvailableOnAClosedFolio() =
        runTest {
            val codes = listOf("ACTIVE", "PARTIALLY_CAPTURED", "EXPIRED", "CAPTURED", "RELEASED")
            val source =
                FakePaymentSource().apply {
                    preAuthorizations = { codes.map { code -> preAuthorizationOf(status = code) } }
                }

            val holder = holder(source, folioOpen = false)

            assertFalse(holder.canRegister)
            assertEquals(listOf(true, true, true, false, false), holder.rows.map(holder::canRelease))
            holder.startRegister()
            assertNull(holder.register)
            holder.startRelease(holder.rows.first())
            assertNotNull(holder.release)
        }

    @Test
    fun writesNeedPaymentsReceive() =
        runTest {
            val source = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }

            val holder = holder(source, permissions = setOf(READ))

            assertEquals(1, holder.rows.size)
            assertFalse(holder.canRegister)
            assertFalse(holder.canRelease(holder.rows.single()))
            holder.startRegister()
            holder.startRelease(holder.rows.single())
            assertNull(holder.register)
            assertNull(holder.release)
            assertTrue(holder(source).canRegister)
        }

    @Test
    fun zoneFailureKeepsTheSection() =
        runTest {
            val source =
                FakePaymentSource().apply {
                    preAuthorizations = { listOf(preAuthorizationOf(expiresAt = "2026-11-20T02:59:59Z")) }
                    businessDay = { throw ApiError.Network(IOException("x")) }
                }

            val holder = holder(source)

            assertNull(holder.error)
            assertEquals("20/11/2026 (UTC)", holder.rows.single().expiresAt)
            holder.startRegister()
            assertEquals(
                "Não foi possível ler o fuso do hotel. Feche e abra de novo.",
                assertNotNull(holder.register).message,
            )
        }

    @Test
    fun oneDialogAtATimeAcrossFolioAndHolder() =
        runTest {
            val source = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }
            val folio = folio(source)
            val holder = assertNotNull(folio.preAuthorizations)

            folio.startPayment()
            assertNotNull(folio.payment)
            holder.startRegister()
            holder.startRelease(holder.rows.single())
            assertNull(holder.register)
            assertNull(holder.release)

            folio.finishPayment()
            holder.startRegister()
            assertNotNull(holder.register)
            folio.startPayment()
            assertNull(folio.payment, "o diálogo do holder conta no painel")
            holder.startRelease(holder.rows.single())
            assertNull(holder.release, "um diálogo por vez no holder")

            folio.close()
            assertTrue(assertNotNull(holder.register).locked, "fecha com a conta")
        }
}
