package com.zeny.desktop.app.shell

import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val READ = "reservations.reservations.read"
private const val MANAGE = "reservations.reservations.manage"

/**
 * Ataques ao conjunto de permissões da casca (M4 E06, ADR 0013): toda falha da carga, de qualquer tipo, deixa o
 * conjunto nulo; leituras fora de ordem nunca devolvem o conjunto de uma carga cancelada; a falha seguida da leitura
 * boa concede só o que a leitura boa trouxe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellStatePermissionsAtaqueTest {
    private class Fake(
        var me: suspend () -> Me,
        var businessDate: suspend () -> BusinessDate = { BusinessDate(LocalDate.of(2026, 3, 7)) },
    ) : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.Active("u-1", "s-1", false))

        override suspend fun login(
            login: String,
            password: String,
        ) = error("não usado")

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = error("não usado")

        override suspend fun logout() = error("não usado")

        override suspend fun me(): Me = me.invoke()

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = businessDate.invoke()

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() = Unit
    }

    private fun me(vararg permissions: String) = Me("u-1", "ana", "Ana Souza", setOf(*permissions))

    /** Escopo fora do `runTest`: uma leitura que nunca termina não pendura o teste. */
    private fun TestScope.bg() = CoroutineScope(StandardTestDispatcher(testScheduler))

    /** Toda falha que o `me` ou a data podem lançar: os subtipos de `ApiError` e exceções fora da API. */
    private val failures: List<Pair<String, () -> Exception>> =
        listOf(
            "Problem(403)" to { ApiError.Problem(403, "FORBIDDEN", null, emptyList()) },
            "Problem(500)" to { ApiError.Problem(500, null, null, emptyList()) },
            "ApprovalRequired" to { ApiError.ApprovalRequired("a", MANAGE, "r", null, null) },
            "PreconditionFailed" to { ApiError.PreconditionFailed(null) },
            "PasswordChangeRequired" to { ApiError.PasswordChangeRequired },
            "SessionEnded" to { ApiError.SessionEnded("SESSION_REVOKED") },
            "Network" to { ApiError.Network(RuntimeException("sem rede")) },
            "IncompatibleResponse" to { ApiError.IncompatibleResponse(IllegalStateException("json")) },
            "Unexpected" to { ApiError.Unexpected(418) },
            "IllegalStateException" to { IllegalStateException("fora da API") },
            "NullPointerException" to { NullPointerException("fora da API") },
            "IllegalArgumentException" to { IllegalArgumentException("fora da API") },
            "CancellationException da chamada" to { CancellationException("tempo esgotado") },
        )

    @Test
    fun everyMeFailureLeavesPermissionsNullAfterAGoodRead() =
        runTest {
            failures.forEach { (name, failure) ->
                val fake = Fake(me = { me(READ, MANAGE) })
                val state = ShellState(fake, devMode = false, scope = bg())
                advanceUntilIdle()
                assertEquals(setOf(READ, MANAGE), state.permissions, "$name: a leitura boa não deu o conjunto")

                fake.me = { throw failure() }
                state.reload()
                advanceUntilIdle()

                assertNotNull(state.error, "$name: a falha do me não virou erro da casca")
                assertNull(state.permissions, "$name: o conjunto da leitura anterior sobreviveu à falha do me")
                state.close()
            }
        }

    @Test
    fun everyBusinessDateFailureLeavesPermissionsNull() =
        runTest {
            failures.forEach { (name, failure) ->
                val fake = Fake(me = { me(READ, MANAGE) }, businessDate = { throw failure() })
                val state = ShellState(fake, devMode = false, scope = bg())
                advanceUntilIdle()

                assertNotNull(state.error, "$name: a falha da data não virou erro da casca")
                assertNull(state.permissions, "$name: o conjunto ficou ao lado do erro da data")
                state.close()
            }
        }

    @Test
    fun repeatedReloadsKeepOnlyTheLastAnswerEvenWhenTheFirstArrivesLast() =
        runTest {
            val first = CompletableDeferred<Me>()
            val second = CompletableDeferred<Me>()
            val fake = Fake(me = { me(READ) })
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()

            fake.me = { first.await() }
            state.reload()
            advanceUntilIdle()
            fake.me = { second.await() }
            state.reload()
            advanceUntilIdle()
            assertNull(state.permissions, "conjunto com as duas leituras em voo")

            second.complete(me(READ))
            advanceUntilIdle()
            assertEquals(setOf(READ), state.permissions)

            // A resposta da carga cancelada, maior, chega por último: não concede nada.
            first.complete(me(READ, MANAGE))
            advanceUntilIdle()
            assertEquals(setOf(READ), state.permissions, "a resposta da carga cancelada concedeu")
            state.close()
        }

    @Test
    fun cancelledLoadAnsweringFirstDoesNotFillTheGapBeforeTheLastAnswer() =
        runTest {
            val first = CompletableDeferred<Me>()
            val second = CompletableDeferred<Me>()
            val fake = Fake(me = { first.await() })
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()
            fake.me = { second.await() }
            state.reload()
            advanceUntilIdle()

            first.complete(me(READ, MANAGE))
            advanceUntilIdle()
            assertNull(state.permissions, "a carga cancelada pôs o conjunto dela antes da vigente responder")

            second.complete(me(READ))
            advanceUntilIdle()
            assertEquals(setOf(READ), state.permissions)
            state.close()
        }

    @Test
    fun failureThenGoodReadGrantsOnlyWhatTheGoodReadBrought() =
        runTest {
            val fake = Fake(me = { throw ApiError.Network(RuntimeException("sem rede")) })
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()
            assertNull(state.permissions)

            fake.me = { me(READ) }
            state.reload()
            advanceUntilIdle()

            assertNull(state.error)
            assertEquals(setOf(READ), state.permissions)
            state.close()
        }

    @Test
    fun closedShellKeepsNoLateAnswer() =
        runTest {
            val gate = CompletableDeferred<Me>()
            val state = ShellState(Fake(me = { gate.await() }), devMode = false, scope = bg())
            advanceUntilIdle()

            state.close()
            gate.complete(me(READ, MANAGE))
            advanceUntilIdle()

            assertNull(state.permissions, "a casca fechada aceitou a resposta tardia do me")
        }

    @Test
    fun closeTwiceKeepsTheParentScopeAliveAndReloadAfterCloseNeverReads() =
        runTest {
            val parent = Job()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            var calls = 0
            val fake =
                Fake(me = {
                    calls++
                    me(READ)
                })
            val state = ShellState(fake, devMode = false, scope = scope)
            advanceUntilIdle()
            assertEquals(setOf(READ), state.permissions)

            state.close()
            state.close()
            state.reload()
            advanceUntilIdle()

            assertTrue(parent.isActive, "o close da casca cancelou o escopo de quem a criou")
            assertEquals(1, calls, "o reload depois do close leu o me")
            assertEquals(setOf(READ), state.permissions, "o reload ignorado mexeu no conjunto")
            parent.cancel()
        }
}
