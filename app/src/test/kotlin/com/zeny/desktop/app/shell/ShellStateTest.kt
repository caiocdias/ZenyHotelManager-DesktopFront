package com.zeny.desktop.app.shell

import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
            assertEquals(listOf(Area.HOUSEKEEPING to false), state.areas)
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
            assertEquals(listOf(Area.HOUSEKEEPING to false), state.areas)
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
                assertEquals(listOf(Area.HOUSEKEEPING to false), state.areas)
                assertNull(state.error)
            }
        }

    @Test
    fun businessDateFormattedWithoutZoneConversion() =
        runTest {
            val previous = java.util.TimeZone.getDefault()
            try {
                // Num fuso a oeste de UTC, uma conversão de meia-noite UTC voltaria um dia.
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Manaus"))
                val state = loaded(FakeConnection(businessDate = { BusinessDate.parse("2026-01-01") }))

                assertEquals("01/01/2026", state.businessDate)
            } finally {
                java.util.TimeZone.setDefault(previous)
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

            assertEquals(Area.entries.map { it to false }, dev.areas)
            assertEquals(emptyList(), release.areas)
        }

    @Test
    fun comingSoonAreaCannotBeSelected() =
        runTest {
            val state = loaded(FakeConnection(me = { Me("u-1", "admin", "Administrador", administrator) }), true)

            state.select(Area.RECEPTION)

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
        }
}
