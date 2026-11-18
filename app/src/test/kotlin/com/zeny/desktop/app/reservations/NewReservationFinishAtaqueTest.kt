package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.BookingResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NF_NO_RESERVATION_TEXT =
    "O servidor confirmou a criação, mas não devolveu a reserva. Feche e confira na busca de reservas."

/**
 * Ataques da M3 E06 ao `finish` da `NewReservationState`: o servidor respondeu a criação com `reservations` vazia. A
 * tela não cai, nada vira "criada", a lista não é recarregada por uma reserva que a tela não viu, e a resposta é um
 * incompatível definitivo (M4 E01): só resta fechar e conferir, e nenhuma ação chama a criação de novo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewReservationFinishAtaqueTest {
    private var changes = 0
    private val guests = FakeReceptionSource().apply { businessDay = BusinessDay(etStay.arrival, ZoneId.of("UTC")) }

    private suspend fun approve(action: ApprovableAction<BookingResponse>): ApiResponse<BookingResponse>? =
        action.run(null)

    private fun TestScope.state(source: FakeNewReservationSource) =
        NewReservationState(
            source,
            guests,
            backgroundScope,
            etOffer(),
            etStay,
            setOf("guests.profiles.read"),
            ::approve,
        ) { changes++ }.also { it.picker.pick(etGuest) }

    private fun TestScope.go(state: NewReservationState) {
        state.confirm()
        runCurrent()
    }

    private fun empty() = etResponse().copy(reservations = emptyList())

    @Test
    fun emptyReservationsNeitherCrashesNorMarksDone() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)

            go(state)

            assertNull(state.done)
            assertFalse(state.busy)
            assertEquals(0, changes, "sem reserva vista, a lista não é avisada")
            assertEquals(1, source.runs.size)
        }

    @Test
    fun emptyReservationsIsADefinitiveIncompatibleAnswerWithTheOwnTextAndNoLeak() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)

            go(state)

            assertFalse(state.unconfirmed)
            assertTrue(state.closeOnly)
            assertEquals(NF_NO_RESERVATION_TEXT, state.error)
            assertTrue(state.fieldErrors.isEmpty())
            assertFalse(state.toString().contains("NoSuchElement"))
        }

    @Test
    fun emptyReservationsRetryNeverCallsCreateAgainAndKeepsTheInputsLocked() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)

            go(state)
            state.specialRequests = "mudou"
            assertEquals("", state.specialRequests)
            go(state)

            assertEquals(1, source.runs.size)
            assertFalse(state.canConfirm)
            assertEquals(0, changes)
        }

    @Test
    fun emptyReservationsThenAServerThatWouldAnswerNowIsNeverCalledAndNeverFinishes() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)
            go(state)
            source.response = etResponse()

            go(state)
            go(state)

            assertEquals(1, source.runs.size, "nenhuma ação chama createBooking de novo")
            assertNull(state.done)
            assertTrue(state.closeOnly)
            assertEquals(NF_NO_RESERVATION_TEXT, state.error)
            assertEquals(0, changes)
        }
}
