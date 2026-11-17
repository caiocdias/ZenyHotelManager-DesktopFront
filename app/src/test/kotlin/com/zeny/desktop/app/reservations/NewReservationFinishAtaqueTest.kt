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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NF_UNCONFIRMED_TEXT =
    "Não foi possível confirmar a reserva. Conclua de novo sem mudar nada; " +
        "se fechar, confira na Busca antes de refazer."

/**
 * Ataques da M3 E06 ao `finish` da `NewReservationState`: o servidor respondeu a criação com `reservations` vazia. A
 * tela não cai, nada vira "criada", a lista não é recarregada por uma reserva que a tela não viu, e a repetição leva a
 * mesma `Idempotency-Key` (o servidor responde o mesmo, e a reserva que ele criou não se duplica).
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
    fun emptyReservationsIsAnUnknownResultWithTheStandardTextAndNoLeak() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)

            go(state)

            assertTrue(state.unconfirmed)
            assertEquals(NF_UNCONFIRMED_TEXT, state.error)
            assertTrue(state.fieldErrors.isEmpty())
            assertFalse(state.toString().contains("NoSuchElement"))
        }

    @Test
    fun emptyReservationsRetryKeepsTheKeyAndLocksTheInputs() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)

            go(state)
            state.specialRequests = "mudou"
            assertEquals("", state.specialRequests)
            go(state)

            assertEquals(2, source.runs.size)
            assertEquals(source.runs[0].key, source.runs[1].key)
            assertEquals(0, changes)
        }

    @Test
    fun emptyReservationsThenAnAnswerWithTheReservationFinishesOnce() =
        runTest {
            val source = FakeNewReservationSource().apply { response = empty() }
            val state = state(source)
            go(state)
            source.response = etResponse()

            go(state)

            assertNotNull(state.done)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertEquals(1, changes)
            assertEquals(source.runs[0].key, source.runs[1].key)
            go(state)
            assertEquals(2, source.runs.size, "concluída, a tela não envia de novo")
        }
}
