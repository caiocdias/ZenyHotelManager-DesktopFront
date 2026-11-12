package com.zeny.desktop.app.shell

import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataques ao estado da casca (Task 7): leituras lentas, fora de ordem, falhas parciais e mensagens. */
class ShellStateAtaqueTest {
    private val ana = Me("u-1", "ana", "Ana Souza", setOf("rooms.status.read", "guests.profiles.read"))

    private class Fake(
        var me: suspend () -> Me,
        var hotel: suspend () -> HotelInfo = { HotelInfo("Hotel Mar Azul", configured = true) },
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

        override suspend fun hotel(): HotelInfo = hotel.invoke()

        override suspend fun businessDate(): BusinessDate = businessDate.invoke()

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() = Unit
    }

    /** Escopo fora do `runTest`: uma leitura que nunca termina não pendura o teste. */
    private fun TestScope.bg() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun forbidden() = ApiError.Problem(403, "FORBIDDEN", null, emptyList())

    @Test
    fun slowHotelReadDoesNotKeepTheMenuAndDateWaiting() =
        runTest {
            val never = CompletableDeferred<HotelInfo>()
            val fake = Fake(me = { ana }, hotel = { never.await() })
            val state = ShellState(fake, devMode = true, scope = bg())

            advanceUntilIdle()

            // O nome do hotel é leitura independente: com ela pendente, menu e data já devem aparecer.
            assertFalse(state.loading, "a carga ficou presa à leitura do hotel")
            assertEquals("07/03/2026", state.businessDate)
            assertEquals(2, state.areas.size)
        }

    @Test
    fun slowHotelReadDoesNotHideTheRetryOfAFailedMe() =
        runTest {
            val never = CompletableDeferred<HotelInfo>()
            val failure = ApiError.Network(RuntimeException("sem rede"))
            val state = ShellState(Fake(me = { throw failure }, hotel = { never.await() }), false, bg())

            advanceUntilIdle()

            assertFalse(state.loading, "o erro de me() ficou escondido atrás da leitura do hotel")
            assertEquals(failure, state.error)
        }

    @Test
    fun staleHotelReadDoesNotOverwriteReload() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake =
                Fake(
                    me = { ana },
                    hotel = {
                        gate.await()
                        HotelInfo("Hotel Velho", configured = true)
                    },
                )
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()
            fake.hotel = { HotelInfo("Hotel Novo", configured = true) }

            state.reload()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("Hotel Novo", state.hotelName)
        }

    @Test
    fun reloadAfterHotelFailureShowsTheRealName() =
        runTest {
            val fake = Fake(me = { ana }, hotel = { throw forbidden() })
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()
            assertEquals("Hotel", state.hotelName)

            fake.hotel = { HotelInfo("Hotel Mar Azul", configured = true) }
            state.reload()
            advanceUntilIdle()

            assertEquals("Hotel Mar Azul", state.hotelName)
        }

    @Test
    fun businessDateNeverDependsOnTheHotelRead() =
        runTest {
            var hotelCalls = 0
            val fake =
                Fake(
                    me = { ana },
                    hotel = {
                        hotelCalls++
                        throw forbidden()
                    },
                )
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()

            assertEquals("07/03/2026", state.businessDate)
            assertEquals(1, hotelCalls)
            assertNull(state.error)
        }

    @Test
    fun emptyPermissionsGiveNoAreasAndNoError() =
        runTest {
            val fake = Fake(me = { Me("u-1", "x", "Sem Perfil", emptySet()) })
            val dev = ShellState(fake, devMode = true, scope = bg())
            advanceUntilIdle()

            assertEquals(emptyList(), dev.areas)
            assertNull(dev.error)
            assertFalse(dev.loading)
        }

    @Test
    fun unknownPermissionsAndTracesOnlyOpenNothing() =
        runTest {
            val unknown = setOf("frontdesk.traces.read", "inventada.coisa.read", "", "FRONTDESK.STAYS.CHECKIN")
            val fake = Fake(me = { Me("u-1", "x", "Rastros", unknown) })
            val state = ShellState(fake, devMode = true, scope = bg())
            advanceUntilIdle()

            assertEquals(emptyList(), state.areas)
        }

    @Test
    fun failuresNeverLeakServerCodeOrRawJsonInTheMessage() =
        runTest {
            val raw = """{"code":"INTERNAL_BOOM","detail":"stack trace"}"""
            listOf<Exception>(
                ApiError.Problem(500, "INTERNAL_BOOM", raw, emptyList()),
                ApiError.Problem(422, "SOME_CODE", raw, emptyList()),
                ApiError.IncompatibleResponse(RuntimeException(raw)),
                RuntimeException(raw),
            ).forEach { failure ->
                val state = ShellState(Fake(me = { throw failure }), false, bg())
                advanceUntilIdle()

                val message = (state.error ?: error("sem erro")).userMessage
                val leaks = listOf("INTERNAL_BOOM", "{", "SOME_CODE").any { message.contains(it) }
                assertFalse(leaks, message)
            }
        }

    @Test
    fun reloadClearsTheErrorWhileTheNewReadIsInFlight() =
        runTest {
            val fake = Fake(me = { throw ApiError.Network(RuntimeException()) })
            val state = ShellState(fake, devMode = false, scope = bg())
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            fake.me = {
                gate.await()
                ana
            }

            state.reload()
            advanceUntilIdle()

            assertTrue(state.loading)
            assertNull(state.error)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(state.loading)
            assertEquals("Ana Souza", state.userName)
        }
}
