package com.zeny.desktop.app.folio

import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.folioWiring
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val BOUND = 500

/** Ataque ao `ReopenState` e ao portador da aprovação. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReopenAttackTest {
    private var done = 0
    private var rereads = 0

    private fun TestScope.state(source: FakeFolioAccountSource) =
        ReopenState(source, backgroundScope, FOLIO_ID.toString(), { it.run(null) }, { done++ }, { rereads++ })

    private fun ok() = ApiResponse(accountFolioOf(), null, 200)

    @Test
    fun trailingWhitespaceDoesNotCountTowardTheLimitOf500() =
        runTest {
            val form = state(FakeFolioAccountSource())
            form.reason = "a".repeat(BOUND) + " "
            assertEquals(BOUND + 1, form.reason.length, "o limite vale depois de aparar (KDoc e plano)")
        }

    @Test
    fun deniedApprovalTokenExpiredShowsCatalogTextAndUnlocks() =
        runTest {
            val source =
                FakeFolioAccountSource().apply {
                    reopenAction = {
                        ApprovableAction { throw ApiError.Problem(403, "APPROVAL_TOKEN_EXPIRED", null, emptyList()) }
                    }
                }
            val form = state(source)
            form.reason = "Engano"
            form.confirm()
            runCurrent()

            assertEquals("A aprovação expirou. Peça uma nova aprovação.", form.message)
            assertFalse(form.unconfirmed)
            assertEquals(0, rereads)
            assertEquals(0, done)
            assertTrue(form.canConfirm)
        }

    @Test
    fun reasonAndConfirmAreFrozenWhileBusy() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source =
                FakeFolioAccountSource().apply {
                    reopenAction = { ApprovableAction { gate.await().let { ok() } } }
                }
            val form = state(source)
            form.reason = "Engano"
            form.confirm()
            runCurrent()
            form.reason = "Outro"
            form.confirm()
            form.confirm()
            runCurrent()
            assertEquals("Engano", form.reason)
            assertEquals(1, source.reopens.size)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, done)
        }

    @Test
    fun unexpectedExceptionFromTheActionBecomesUnknownResult() =
        runTest {
            val source = FakeFolioAccountSource().apply { reopenAction = { ApprovableAction { error("boom") } } }
            val form = state(source)
            form.reason = "Engano"
            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertEquals(1, rereads)
            assertTrue(form.canConfirm)
        }

    @Test
    fun definitiveRefusalsShowCatalogTextWithoutRereadAndUnknownOnesRereadAndWarn() =
        runTest {
            val definitive =
                listOf(
                    ApiError.Problem(403, "APPROVAL_TOKEN_EXPIRED", null, emptyList()) to
                        "A aprovação expirou. Peça uma nova aprovação.",
                    ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList()) to
                        "Você não tem permissão para esta ação.",
                )
            definitive.forEach { (failure, text) ->
                rereads = 0
                val source = FakeFolioAccountSource().apply { reopenAction = { ApprovableAction { throw failure } } }
                val form = state(source)
                form.reason = "Engano"
                form.confirm()
                runCurrent()
                assertEquals(text, form.message, "$failure")
                assertFalse(form.unconfirmed, "$failure")
                assertEquals(0, rereads, "$failure")
                assertTrue(form.canConfirm, "$failure")
            }
            val unknown =
                listOf<ApiError>(
                    ApiError.SessionEnded("SESSION_EXPIRED"),
                    ApiError.IncompatibleResponse(IllegalStateException("formato")),
                )
            unknown.forEach { failure ->
                rereads = 0
                val source = FakeFolioAccountSource().apply { reopenAction = { ApprovableAction { throw failure } } }
                val form = state(source)
                form.reason = "Engano"
                form.confirm()
                runCurrent()
                assertTrue(form.unconfirmed, "$failure")
                assertEquals(1, rereads, "$failure")
                assertEquals(
                    "Não foi possível confirmar a reabertura. A conta foi relida; confira antes de tentar de novo.",
                    form.message,
                )
            }
        }

    @Test
    fun approvalTokenNeverAppearsInMessageOrToStringOfTheState() =
        runTest {
            val sentinel = "TOKEN-SENTINELA-7f3a91"
            val failures =
                listOf<ApiError>(
                    ApiError.Network(IOException("caiu")),
                    ApiError.Problem(503, "X", null, emptyList()),
                    ApiError.Problem(403, "APPROVAL_TOKEN_EXPIRED", null, emptyList()),
                )
            failures.forEach { failure ->
                val source =
                    FakeFolioAccountSource().apply {
                        reopenAction = {
                            ApprovableAction { token ->
                                if (token == null) {
                                    throw ApiError.ApprovalRequired("a", "p", "r", null, null)
                                }
                                throw failure
                            }
                        }
                    }
                val form =
                    ReopenState(
                        source,
                        backgroundScope,
                        FOLIO_ID.toString(),
                        { action ->
                            try {
                                action.run(null)
                            } catch (_: ApiError.ApprovalRequired) {
                                action.run(sentinel)
                            }
                        },
                        {},
                        {},
                    )
                form.reason = "Engano"
                form.confirm()
                runCurrent()
                assertTrue(sentinel !in form.message.orEmpty(), "$failure")
                assertTrue(sentinel !in form.toString(), "$failure")
                assertTrue(sentinel !in form.reason)
            }
        }

    @Test
    fun wiringApprovalReturnsTheApprovedResponse() =
        runTest {
            val coordinator = ApprovalCoordinator({ _, _ -> "tok" }, emptyFlow(), backgroundScope)
            val connection = FolioConnection(FakeFolioSource(), account = FakeFolioAccountSource())
            val wiring =
                assertNotNull(folioWiring(connection, setOf("billing.folios.read"), backgroundScope, coordinator, null))
            val approved = ApiResponse(accountFolioOf(), null, 200)
            val tokens = mutableListOf<String?>()
            val action =
                ApprovableAction<FolioResponse> { token ->
                    tokens += token
                    if (token == null) {
                        throw ApiError.ApprovalRequired("a", "p", "f", null, null)
                    }
                    approved
                }
            var result: ApiResponse<FolioResponse>? = null
            backgroundScope.launch { result = wiring.approveReopen(action) }
            runCurrent()
            assertNotNull(coordinator.dialog)
            coordinator.login = "gerente"
            coordinator.pin = "1234"
            coordinator.reason = "ok"
            coordinator.confirm()
            runCurrent()

            assertEquals(listOf<String?>(null, "tok"), tokens)
            assertSame(approved, result, "o ApiResponse aprovado volta a quem chamou")
        }
}
