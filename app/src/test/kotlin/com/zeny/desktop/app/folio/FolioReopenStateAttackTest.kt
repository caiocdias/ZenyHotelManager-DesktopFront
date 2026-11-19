package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.FolioResponse
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
private const val MANAGE = "billing.folios.manage"
private const val REOPEN = "billing.folios.reopen"

/** Ataque ao `FolioState` com "Reabrir conta": permissões, sessão trocada com o envio em voo e leituras exatas. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioReopenStateAttackTest {
    private fun closedSource(onRead: () -> Unit = {}) =
        FakeFolioSource().apply {
            folio = {
                onRead()
                folioOf(status = FolioResponse.Status.CLOSED)
            }
        }

    private fun TestScope.stateOf(
        folios: FakeFolioSource,
        account: FolioAccountSource?,
        permissions: Set<String>,
    ): FolioState {
        val wiring =
            FolioWiring(
                folios,
                permissions,
                { it.run(null) },
                null,
                null,
                account = account,
                approveReopen = { it.run(null) },
            )
        return FolioState(wiring, backgroundScope, FOLIO_ID.toString()).also {
            it.start()
            runCurrent()
        }
    }

    @Test
    fun startReopenWithoutPermissionOrPortOpensNothing() =
        runTest {
            val withoutPermission = stateOf(closedSource(), FakeFolioAccountSource(), setOf(READ))
            withoutPermission.startReopen()
            assertNull(withoutPermission.reopen)
            assertFalse(withoutPermission.canReopen)

            val withoutPort = stateOf(closedSource(), null, setOf(READ, REOPEN))
            withoutPort.startReopen()
            assertNull(withoutPort.reopen)

            val openSource = FakeFolioSource().apply { folio = { folioOf() } }
            val openFolio = stateOf(openSource, FakeFolioAccountSource(), setOf(READ, MANAGE, REOPEN))
            openFolio.startReopen()
            assertNull(openFolio.reopen)
        }

    @Test
    fun closingTheStateWithTheReopenInFlightDropsTheLateSuccess() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val account =
                FakeFolioAccountSource().apply {
                    reopenAction = { ApprovableAction { gate.await().let { ok() } } }
                }
            var reads = 0
            val state = stateOf(closedSource { reads++ }, account, setOf(READ, REOPEN))
            state.startReopen()
            val form = assertNotNull(state.reopen)
            form.reason = "Engano"
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            val before = reads

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(state.notice, "a resposta tardia não avisa")
            assertEquals(before, reads, "a resposta tardia não relê a conta")
            assertFalse(state.canReopen)
        }

    @Test
    fun unknownResultThenBackRereadsExactlyOncePerStep() =
        runTest {
            var reads = 0
            val account =
                FakeFolioAccountSource().apply {
                    reopenAction = { ApprovableAction { throw ApiError.Network(IOException("caiu")) } }
                }
            val state = stateOf(closedSource { reads++ }, account, setOf(READ, REOPEN))
            assertEquals(1, reads, "a leitura inicial")

            state.startReopen()
            val form = assertNotNull(state.reopen)
            form.reason = "Engano"
            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertEquals(2, reads, "o resultado desconhecido relê uma vez")

            state.dismissReopen()
            runCurrent()
            assertNull(state.reopen)
            assertEquals(3, reads, "fechar relê mais uma vez, não mais")
        }

    @Test
    fun successRereadsOnceAndLeavesNoticeAndNoDialog() =
        runTest {
            var reads = 0
            val account =
                FakeFolioAccountSource().apply {
                    reopenAction = { ApprovableAction { ApiResponse(accountFolioOf(), null, 200) } }
                }
            val state = stateOf(closedSource { reads++ }, account, setOf(READ, MANAGE))
            state.startReopen()
            val form = assertNotNull(state.reopen)
            form.reason = "Engano"
            form.confirm()
            runCurrent()

            assertNull(state.reopen)
            assertEquals("Conta reaberta.", state.notice)
            assertEquals(2, reads, "a leitura inicial e uma releitura")
        }

    private fun ok() = ApiResponse(accountFolioOf(), null, 200)
}
