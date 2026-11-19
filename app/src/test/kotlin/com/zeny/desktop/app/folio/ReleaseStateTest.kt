package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"

/** A confirmação "Liberar" da caução ([ReleaseState]), aberta pelo [PreAuthorizationsState]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseStateTest {
    private var changed = 0
    private val notices = mutableListOf<String>()

    private fun TestScope.holder(source: FakePaymentSource) =
        PreAuthorizationsState(
            wiringOf(FakeFolioSource(), setOf(READ, RECEIVE), payments = source),
            backgroundScope,
            FOLIO_ID.toString(),
            folioOpen = { true },
            otherDialogOpen = { false },
            notify = { notices += it },
            onChanged = { changed++ },
        ).also {
            it.reload()
            runCurrent()
        }

    private fun source(capturable: String = "300.00") =
        FakePaymentSource().apply {
            preAuthorizations = { listOf(preAuthorizationOf(capturable = capturable)) }
        }

    private fun PreAuthorizationsState.opened(): ReleaseState {
        startRelease(rows.single())
        return assertNotNull(release)
    }

    @Test
    fun releaseSendsOnceAndReloads() =
        runTest {
            val gate = CompletableDeferred<CardPreAuthorizationResponse>()
            val source = source().apply { release = { gate.await() } }
            val holder = holder(source)
            val form = holder.opened()
            val id = holder.rows.single().id
            assertEquals("A sobra de R$ 300,00 será liberada na maquininha.", form.text)

            form.confirm()
            form.confirm()
            runCurrent()
            form.confirm()
            gate.complete(preAuthorizationOf(status = "RELEASED", released = "300.00"))
            runCurrent()

            val call = source.releaseCalls.single()
            assertEquals(id, call.preAuthorizationId)
            assertNull(holder.release)
            assertEquals(listOf("Sobra liberada."), notices)
            assertEquals(2, source.preAuthorizationCalls.size, "as cauções são relidas")
            assertEquals(1, changed, "a conta é relida")

            val empty = holder(source("0.00")).opened()
            assertEquals("A sobra será liberada na maquininha.", empty.text)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            var failures = 1
            val source =
                source().apply {
                    release = {
                        if (failures-- > 0) throw ApiError.Network(IOException("caiu"))
                        preAuthorizationOf(status = "RELEASED")
                    }
                }
            val form = holder(source).opened()

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertEquals(
                "Não foi possível confirmar a liberação. Libere de novo; se fechar, confira a conta antes de liberar " +
                    "de novo.",
                form.message,
            )
            form.confirm()
            runCurrent()

            val (first, second) = source.releaseCalls
            assertSame(first.intent, second.intent)
        }

    @Test
    fun settledShowsMessageAndReloads() =
        runTest {
            val failure = ApiError.Problem(409, "PRE_AUTHORIZATION_SETTLED", null, emptyList())
            val source = source().apply { release = { throw failure } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()

            assertEquals(failure.userMessage, form.message)
            assertFalse(form.unconfirmed)
            assertEquals(2, source.preAuthorizationCalls.size, "as cauções são relidas")
            assertSame(form, holder.release, "o diálogo fica com a mensagem")
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source =
                source().apply { release = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()

            assertTrue(form.closeOnly)
            assertFalse(form.canConfirm)
            assertEquals(
                "O servidor não confirmou a liberação. Feche e confira a conta antes de liberar de novo.",
                form.message,
            )

            holder.finishRelease()
            runCurrent()
            assertNull(holder.release)
            assertEquals(2, source.preAuthorizationCalls.size, "fechar sem resultado relê as cauções")
            assertEquals(1, changed, "e a conta")
        }
}
