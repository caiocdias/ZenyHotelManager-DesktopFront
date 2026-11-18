package com.zeny.desktop.app.shell

import androidx.compose.runtime.snapshots.Snapshot
import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.cashier.shiftOf
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellStateTest {
    // Fixtures da Task 3 (AreasTest): templates de role do servidor, só com as permissões que o menu consulta
    // ou que poderiam confundi-lo; o Administrador recebe todas.
    private val administrator =
        Area.entries.flatMap { it.permissions }.toSet() +
            setOf("frontdesk.traces.read", "hotel.settings.read", "rates.plans.read", "rooms.registry.read")

    private val receptionist =
        setOf(
            "frontdesk.stays.checkin",
            "frontdesk.stays.checkout",
            "frontdesk.traces.read",
            "reservations.reservations.read",
            "billing.folios.read",
            "billing.cashier.operate",
            "guests.profiles.read",
            "rooms.status.read",
            "rooms.registry.read",
            "hotel.settings.read",
            "rates.plans.read",
        )

    private class FakeConnection(
        var me: suspend () -> Me = { Me("user-1", "ana", "Ana Souza", setOf("rooms.status.read")) },
        var hotel: () -> HotelInfo = { HotelInfo("Hotel Mar Azul", configured = true) },
        var businessDate: () -> BusinessDate = { BusinessDate(LocalDate.of(2026, 3, 7)) },
        var cashier: CashierSource? = null,
    ) : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.Active("user-1", "session-1", false))
        var meCalls = 0

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

        override suspend fun me(): Me {
            meCalls++
            return me.invoke()
        }

        override suspend fun hotel(): HotelInfo = hotel.invoke()

        override suspend fun businessDate(): BusinessDate = businessDate.invoke()

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun cashierSource(): CashierSource? = cashier

        override fun close() = Unit
    }

    private fun forbidden() = ApiError.Problem(403, "FORBIDDEN", null, emptyList())

    private fun TestScope.loaded(
        connection: FakeConnection,
        devMode: Boolean = false,
    ) = ShellState(connection, devMode, this).also { advanceUntilIdle() }

    @Test
    fun loadsHeaderAndAreas() =
        runTest {
            val connection = FakeConnection()
            val state = ShellState(connection, devMode = true, scope = this)
            assertTrue(state.loading)

            advanceUntilIdle()

            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals("Hotel Mar Azul", state.hotelName)
            assertEquals("07/03/2026", state.businessDate)
            assertEquals("Ana Souza", state.userName)
            assertEquals(listOf(Area.HOUSEKEEPING to true), state.areas)
            assertNull(state.selected)
        }

    @Test
    fun unconfiguredHotelShowsFallback() =
        runTest {
            val state = loaded(FakeConnection(hotel = { HotelInfo(null, configured = false) }))

            assertEquals("Hotel ainda não configurado", state.hotelName)
            assertNull(state.error)
        }

    @Test
    fun forbiddenHotelStillShowsBusinessDate() =
        runTest {
            val state = loaded(FakeConnection(hotel = { throw forbidden() }), devMode = true)

            assertEquals("Hotel", state.hotelName)
            assertEquals("07/03/2026", state.businessDate)
            assertEquals(listOf(Area.HOUSEKEEPING to true), state.areas)
            assertNull(state.error)
        }

    @Test
    fun hotelNetworkErrorShowsFallbackName() =
        runTest {
            listOf<ApiError>(
                ApiError.Network(RuntimeException("sem rede")),
                ApiError.Problem(503, null, null, emptyList()),
                ApiError.Unexpected(502),
            ).forEach { failure ->
                val state = loaded(FakeConnection(hotel = { throw failure }), devMode = true)

                assertEquals("Hotel", state.hotelName, failure.toString())
                assertEquals("07/03/2026", state.businessDate)
                assertEquals(listOf(Area.HOUSEKEEPING to true), state.areas)
                assertNull(state.error)
            }
        }

    @Test
    fun businessDateFormattedWithoutZoneConversion() =
        runTest {
            // Datas de borda: uma conversão por instante (meia-noite UTC num fuso a oeste) mudaria o dia, o mês
            // ou o ano. O texto do servidor vira o mesmo dia, mês e ano em dd/MM/aaaa.
            mapOf(
                "2026-01-01" to "01/01/2026",
                "2025-12-31" to "31/12/2025",
                "2028-02-29" to "29/02/2028",
            ).forEach { (api, shown) ->
                val state = loaded(FakeConnection(businessDate = { BusinessDate.parse(api) }))

                assertEquals(shown, state.businessDate, api)
            }
        }

    @Test
    fun meErrorKeepsRetry() =
        runTest {
            val failure = ApiError.Network(RuntimeException("sem rede"))
            val connection = FakeConnection(me = { throw failure })
            val state = loaded(connection)

            assertFalse(state.loading)
            assertEquals(failure, state.error)
            assertEquals(emptyList(), state.areas)

            connection.me = { Me("user-1", "ana", "Ana Souza", setOf("rooms.status.read")) }
            state.reload()
            advanceUntilIdle()

            assertEquals(2, connection.meCalls)
            assertNull(state.error)
            assertEquals("Ana Souza", state.userName)
        }

    @Test
    fun businessDateErrorKeepsRetry() =
        runTest {
            val failure = ApiError.Problem(500, null, null, emptyList())
            val connection = FakeConnection(businessDate = { throw failure })
            val state = loaded(connection)

            assertEquals(failure, state.error)

            connection.businessDate = { BusinessDate(LocalDate.of(2026, 3, 8)) }
            state.reload()
            advanceUntilIdle()

            assertNull(state.error)
            assertEquals("08/03/2026", state.businessDate)
        }

    @Test
    fun unknownFailureBecomesUserMessageError() =
        runTest {
            val state = loaded(FakeConnection(me = { error("detalhe interno") }))

            val error = state.error
            assertTrue(error is ApiError)
            assertFalse(error.userMessage.contains("detalhe interno"))
        }

    @Test
    fun reloadRereadsMe() =
        runTest {
            val connection = FakeConnection()
            val state = loaded(connection, devMode = true)
            connection.me = { Me("user-1", "ana", "Ana Souza", receptionist) }

            state.reload()
            advanceUntilIdle()

            assertEquals(2, connection.meCalls)
            assertEquals(
                listOf(Area.RECEPTION, Area.RESERVATIONS, Area.ACCOUNTS, Area.GUESTS, Area.HOUSEKEEPING),
                state.areas.map { it.first },
            )
        }

    @Test
    fun receptionistAndAdminDiffer() =
        runTest {
            val admin = loaded(FakeConnection(me = { Me("u-1", "admin", "Administrador", administrator) }), true)
            val front = loaded(FakeConnection(me = { Me("u-2", "rec", "Recepcionista", receptionist) }), true)

            assertEquals(Area.entries, admin.areas.map { it.first })
            assertEquals(
                listOf(Area.RECEPTION, Area.RESERVATIONS, Area.ACCOUNTS, Area.GUESTS, Area.HOUSEKEEPING),
                front.areas.map { it.first },
            )
            assertNotEquals(admin.areas, front.areas)
        }

    @Test
    fun devModeShowsComingSoonAreas() =
        runTest {
            val me = { Me("u-1", "admin", "Administrador", administrator) }

            val dev = loaded(FakeConnection(me = me), devMode = true)
            val release = loaded(FakeConnection(me = me), devMode = false)

            assertEquals(
                Area.entries.map {
                    it to (it in setOf(Area.RECEPTION, Area.RESERVATIONS, Area.ACCOUNTS, Area.HOUSEKEEPING))
                },
                dev.areas,
            )
            assertEquals(
                listOf(
                    Area.RECEPTION to true,
                    Area.RESERVATIONS to true,
                    Area.ACCOUNTS to true,
                    Area.HOUSEKEEPING to true,
                ),
                release.areas,
            )
        }

    @Test
    fun comingSoonAreaCannotBeSelected() =
        runTest {
            val state = loaded(FakeConnection(me = { Me("u-1", "admin", "Administrador", administrator) }), true)

            state.select(Area.GUESTS)

            assertNull(state.selected)
        }

    @Test
    fun staleReadDoesNotOverwriteReload() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val connection =
                FakeConnection(
                    me = {
                        gate.await()
                        Me("u-1", "admin", "Administrador", administrator)
                    },
                )
            val state = ShellState(connection, devMode = true, scope = this)
            advanceUntilIdle()
            connection.me = { Me("u-2", "rec", "Recepcionista", receptionist) }

            state.reload()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("Recepcionista", state.userName)
            assertEquals(Area.RECEPTION, state.areas.first().first)
            assertFalse(state.loading)
            // A carga cancelada pelo reload não vira erro.
            assertNull(state.error)
        }

    @Test
    fun cancellationFromTheCallBecomesRetryableError() =
        runTest {
            // Um tempo esgotado no core chega como CancellationException sem que a carga tenha sido cancelada.
            val connection = FakeConnection(me = { throw CancellationException("tempo esgotado") })
            val state = loaded(connection)

            assertFalse(state.loading)
            assertTrue(state.error is ApiError.Network)

            connection.me = { Me("user-1", "ana", "Ana Souza", emptySet()) }
            state.reload()
            advanceUntilIdle()
            assertNull(state.error)
        }

    @Test
    fun cancellationFromTheHotelCallGivesFallbackName() =
        runTest {
            val state = loaded(FakeConnection(hotel = { throw CancellationException("tempo esgotado") }))

            assertEquals("Hotel", state.hotelName)
            assertNull(state.error)
        }

    @Test
    fun closeCancelsTheLoadAndStopsReloads() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var cancelled = 0
            val connection =
                FakeConnection(
                    me = {
                        try {
                            gate.await()
                        } catch (e: CancellationException) {
                            cancelled++
                            throw e
                        }
                        Me("u-1", "ana", "Ana Souza", emptySet())
                    },
                )
            val state = ShellState(connection, devMode = false, scope = this)
            advanceUntilIdle()

            assertNull(state.error)

            state.close()
            advanceUntilIdle()
            // A carga cancelada pelo close não vira erro.
            assertNull(state.error)
            state.reload()
            advanceUntilIdle()

            assertEquals(1, cancelled)
            assertEquals(1, connection.meCalls)
            assertNull(state.error)
        }

    @Test
    fun restoredAreaOpensOnlyAfterMeAllowsIt() =
        runTest {
            val state = ShellState(FakeConnection(), devMode = false, scope = this, restoreArea = Area.HOUSEKEEPING)
            assertNull(state.selected, "a área abriu antes de o me da sessão nova confirmar")

            advanceUntilIdle()

            assertEquals(Area.HOUSEKEEPING, state.selected)
        }

    @Test
    fun restoredAreaDroppedWhenNotAllowed() =
        runTest {
            val connection = FakeConnection(me = { Me("u-2", "bia", "Bia Lima", setOf("guests.profiles.read")) })
            val state = ShellState(connection, devMode = true, scope = this, restoreArea = Area.HOUSEKEEPING)

            advanceUntilIdle()

            assertNull(state.selected)
        }

    @Test
    fun permissionsFollowTheSuccessfulMe() =
        runTest {
            val connection = FakeConnection(me = { Me("u-1", "rec", "Recepcionista", receptionist) })
            val state = ShellState(connection, devMode = false, scope = this)
            assertNull(state.permissions, "conjunto antes da leitura")

            advanceUntilIdle()

            assertEquals(receptionist, state.permissions)
        }

    @Test
    fun reloadClearsPermissionsBeforeTheAnswer() =
        runTest {
            val connection = FakeConnection(me = { Me("u-1", "rec", "Recepcionista", receptionist) })
            val state = loaded(connection)
            assertEquals(receptionist, state.permissions)
            val gate = CompletableDeferred<Me>()
            connection.me = { gate.await() }

            state.reload()
            assertNull(state.permissions, "o conjunto velho sobreviveu ao início do reload")
            advanceUntilIdle()
            assertNull(state.permissions, "o conjunto velho voltou com o me em voo")

            gate.complete(Me("u-1", "rec", "Recepcionista", setOf("rooms.status.read")))
            advanceUntilIdle()
            assertEquals(setOf("rooms.status.read"), state.permissions)
        }

    @Test
    fun failedMeLeavesPermissionsNull() =
        runTest {
            val connection = FakeConnection(me = { throw ApiError.Network(RuntimeException("sem rede")) })
            val state = loaded(connection)
            assertTrue(state.error != null)
            assertNull(state.permissions)

            // A data que falha também deixa o conjunto nulo, mesmo com o me bom (e depois de um conjunto lido).
            connection.me = { Me("u-1", "rec", "Recepcionista", receptionist) }
            state.reload()
            advanceUntilIdle()
            assertEquals(receptionist, state.permissions)
            connection.businessDate = { throw ApiError.Problem(500, null, null, emptyList()) }
            state.reload()
            advanceUntilIdle()
            assertTrue(state.error != null)
            assertNull(state.permissions)
        }

    @Test
    fun errorNeverComesWithPermissions() =
        runTest {
            val connection = FakeConnection(me = { Me("u-1", "rec", "Recepcionista", receptionist) })
            val state = loaded(connection)
            assertEquals(receptionist, state.permissions)

            // O me e a data respondem, mas um passo da carga falha depois de o conjunto existir: a primeira escrita
            // de estado com o conjunto já visível lança (o observador de escrita do snapshot faz as vezes de qualquer
            // falha tardia, como a da formatação da data ou da área restaurada).
            var armed = false
            var thrown = false
            val snapshot =
                Snapshot.takeMutableSnapshot(writeObserver = {
                    if (armed && !thrown && state.permissions != null) {
                        thrown = true
                        error("falha depois do conjunto")
                    }
                })
            try {
                snapshot.enter {
                    state.reload()
                    armed = true
                    advanceUntilIdle()

                    assertTrue(thrown, "a carga não chegou a ter o conjunto")
                    assertNotNull(state.error, "a carga que falhou depois do me não deu erro")
                    assertNull(state.permissions, "o conjunto ficou junto com o erro")
                }
            } finally {
                snapshot.dispose()
            }
        }

    @Test
    fun emptyPermissionsReadWithSuccessAreAnEmptySetNotNull() =
        runTest {
            val state = loaded(FakeConnection(me = { Me("u-1", "ana", "Ana Souza", emptySet()) }))

            assertNull(state.error)
            assertEquals(emptySet(), state.permissions)
        }

    @Test
    fun permissionsAreAnImmutableCopy() =
        runTest {
            val read = mutableSetOf("reservations.reservations.read")
            val state = loaded(FakeConnection(me = { Me("u-1", "ana", "Ana Souza", read) }))
            val permissions = assertNotNull(state.permissions)

            read += "reservations.reservations.manage"

            assertEquals(setOf("reservations.reservations.read"), state.permissions)
            assertFailsWith<UnsupportedOperationException> {
                (permissions as MutableSet<String>).add("reservations.reservations.manage")
            }
            assertEquals(setOf("reservations.reservations.read"), state.permissions)
        }

    private fun receptionistWith(cashier: FakeCashierSource?) =
        FakeConnection(me = { Me("u-1", "rec", "Recepcionista", receptionist) }, cashier = cashier)

    @Test
    fun shiftIndicatorFollowsTheRead() =
        runTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf(number = "CX-0003", cashPointName = "Bar") }
            val state = loaded(receptionistWith(cashier))

            assertEquals(ShiftIndicator.Open("CX-0003", "Bar"), state.shift)

            cashier.current = { null }
            state.refreshShift()
            advanceUntilIdle()

            assertEquals(ShiftIndicator.None, state.shift)
            assertEquals(2, cashier.currentCalls)
        }

    @Test
    fun shiftHiddenWithoutOperate() =
        runTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf() }
            val reader = setOf("billing.folios.read", "rooms.status.read")
            val state = loaded(FakeConnection(me = { Me("u-1", "ana", "Ana Souza", reader) }, cashier = cashier))

            assertEquals(ShiftIndicator.Hidden, state.shift)
            state.refreshShift()
            advanceUntilIdle()
            assertEquals(ShiftIndicator.Hidden, state.shift)
            assertEquals(0, cashier.currentCalls, "leu o turno sem billing.cashier.operate")

            // Sem a porta do caixa (as conexões falsas), o topo também não mostra nada.
            assertEquals(ShiftIndicator.Hidden, loaded(receptionistWith(null)).shift)
        }

    @Test
    fun shiftReadFailureKeepsTheShell() =
        runTest {
            listOf<ApiError>(
                ApiError.Network(RuntimeException("sem rede")),
                ApiError.Problem(500, null, null, emptyList()),
                ApiError.Problem(403, "FORBIDDEN", null, emptyList()),
            ).forEach { failure ->
                val cashier = FakeCashierSource()
                cashier.current = { throw failure }
                val state = loaded(receptionistWith(cashier))

                assertEquals(ShiftIndicator.Unknown, state.shift, failure.toString())
                assertNull(state.error)
                assertFalse(state.loading)
                assertEquals(receptionist, state.permissions)
                assertEquals("Recepcionista", state.userName)
            }
        }

    @Test
    fun reloadResetsShiftToHidden() =
        runTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf() }
            val connection = receptionistWith(cashier)
            val state = loaded(connection)
            assertEquals(ShiftIndicator.Open("CX-0001", "Recepção"), state.shift)
            val gate = CompletableDeferred<Me>()
            connection.me = { gate.await() }

            state.reload()
            assertEquals(ShiftIndicator.Hidden, state.shift, "o turno velho sobreviveu ao início do reload")
            advanceUntilIdle()
            assertEquals(ShiftIndicator.Hidden, state.shift, "leu o turno antes do me")

            gate.complete(Me("u-1", "rec", "Recepcionista", receptionist))
            advanceUntilIdle()
            assertEquals(ShiftIndicator.Open("CX-0001", "Recepção"), state.shift)
        }

    @Test
    fun lateShiftAnswerAfterReloadIsDropped() =
        runTest {
            val cashier = FakeCashierSource()
            val late = CompletableDeferred<Unit>()
            cashier.current = {
                late.await()
                shiftOf(number = "CX-OLD")
            }
            val state = loaded(receptionistWith(cashier))
            assertEquals(ShiftIndicator.Hidden, state.shift)

            cashier.current = { null }
            state.reload()
            advanceUntilIdle()
            late.complete(Unit)
            advanceUntilIdle()

            assertEquals(ShiftIndicator.None, state.shift)
        }

    @Test
    fun lateShiftAnswerAfterRefreshOrCloseIsDropped() =
        runTest {
            val cashier = FakeCashierSource()
            val late = CompletableDeferred<Unit>()
            cashier.current = {
                late.await()
                shiftOf(number = "CX-OLD")
            }
            val state = loaded(receptionistWith(cashier))

            cashier.current = { null }
            state.refreshShift()
            advanceUntilIdle()
            late.complete(Unit)
            advanceUntilIdle()
            assertEquals(ShiftIndicator.None, state.shift)

            val gate = CompletableDeferred<Unit>()
            cashier.current = {
                gate.await()
                shiftOf()
            }
            state.refreshShift()
            advanceUntilIdle()
            state.close()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(ShiftIndicator.None, state.shift, "a resposta depois do close sobrescreveu o valor")

            val calls = cashier.currentCalls
            state.refreshShift()
            advanceUntilIdle()
            assertEquals(calls, cashier.currentCalls, "releu o turno depois do close")
        }
}
