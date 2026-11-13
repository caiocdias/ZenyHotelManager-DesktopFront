package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** As origens do erro do mapa (assinatura, recarga, detalhe) e a assinatura perdida sem erro. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapStateRecoveryTest {
    private val a = roomId(1)
    private val b = roomId(2)

    private fun forbidden() = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())

    private fun FakeRoomsSource.withTwoRooms() =
        apply {
            put(roomStatus(a, "101", version = 1), roomStatus(b, "102", version = 1))
        }

    private fun TestScope.mapState(source: FakeRoomsSource) = RoomMapState(source, backgroundScope)

    private fun TestScope.loaded(source: FakeRoomsSource) =
        mapState(source).also {
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()
        }

    private fun RoomMapState.numbers() = floors.flatMap { it.second }.map { it.number }

    @Test
    fun reloadKeepsDetailError() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.holdStatuses = true
            source.roomError = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            state.select(a)
            runCurrent()

            state.retry()
            runCurrent()
            assertNotNull(state.error, "o detalhe relido falhou de novo")
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertNotNull(state.error, "a recarga apagou o erro do detalhe")
        }

    @Test
    fun reloadClearsOnlyItsOwnError() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.statusesError = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertNotNull(state.error)

            source.statusesError = null
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertNull(state.error, "a recarga seguinte não limpou o erro que a anterior deixou")
        }

    @Test
    fun successfulSelectClearsDetailError() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.roomError = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            state.select(a)
            runCurrent()
            assertNotNull(state.error)

            source.roomError = null
            state.select(b)
            runCurrent()

            assertNull(state.error, "o detalhe lido não limpou o próprio erro")
            assertNotNull(state.selected?.room)
        }

    @Test
    fun successfulSelectKeepsStreamFailure() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.roomError = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            state.select(a)
            runCurrent()
            val failure = forbidden()
            source.end(failure)
            runCurrent()

            source.roomError = null
            state.select(b)
            runCurrent()

            assertSame(failure, state.error, "a falha da assinatura sumiu com o detalhe lido")
        }

    @Test
    fun syncedMapLosingSubscriptionIsDisconnected() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            assertFalse(state.disconnected)

            source.end()
            runCurrent()

            assertTrue(state.disconnected, "o mapa sincronizado perdeu a assinatura sem avisar")
            assertNull(state.error)
            assertEquals(listOf("101", "102"), state.numbers())

            state.retry()
            runCurrent()
            assertFalse(state.disconnected)
            assertEquals(2, source.subscriptions)
            assertEquals(1, source.activeSubscriptions)
        }

    @Test
    fun subscriptionEndingBeforeSyncIsNotDisconnected() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = mapState(source)
            runCurrent()

            source.end()
            runCurrent()

            assertFalse(state.loading)
            assertFalse(state.disconnected)
        }

    @Test
    fun failedSubscriptionIsErrorNotDisconnected() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            source.end(forbidden())
            runCurrent()

            assertNotNull(state.error)
            assertFalse(state.disconnected)
        }

    @Test
    fun retriedSubscriptionEndingBeforeSyncKeepsSyncedMapDisconnected() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.end()
            runCurrent()

            state.retry()
            runCurrent()
            source.end()
            runCurrent()

            assertFalse(state.loading)
            assertTrue(state.disconnected, "os ladrilhos da sincronização anterior ficaram sem aviso")
        }
}
