package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.CancellationPenaltyResponse
import com.zeny.desktop.sdk.model.CancellationResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

private val paRes = Uuid.parse("20000000-0000-0000-0000-0000000000e1")

/** A reserva lida, com o `penaltiesCharged` [charged] e, se dada, a multa do cancelamento. */
private fun paDetail(
    charged: String,
    status: String = "CONFIRMED",
    amount: String? = null,
    waived: Boolean = false,
): ReservationDetailResponse {
    val base = confirmDetail(paRes, code = "R1", status = status)
    val cancellation =
        amount?.let {
            CancellationResponse(
                cancelledAt = "2026-11-16T12:00:00Z",
                penalty = CancellationPenaltyResponse(amount = it, deadline = "2026-11-15T17:00:00Z", waived = waived),
                previousStatus = "CONFIRMED",
                reason = "GUEST_REQUEST",
            )
        }
    return base.copy(
        reservation =
            base.reservation.copy(
                penaltiesCharged = charged,
                cancellation = cancellation?.let { Option.Some(it) } ?: Option.Undefined,
            ),
    )
}

/**
 * Ataque do testador de etapa (M4 E12) ao texto da multa: o `penaltiesCharged` negativo, menor que a multa nova, igual
 * a ela com outra escala, e o aviso antes de cancelar ([ReservationCancelState.previousPenalty]) com valores fora do
 * esperado e depois de uma releitura que falha.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationCancelPenaltyAttackTest {
    private suspend fun approve(
        action: ApprovableAction<ReservationDetailResponse>,
    ): ApiResponse<ReservationDetailResponse>? = action.run(null)

    private fun TestScope.started(source: FakeReservationCancelSource): ReservationCancelState =
        ReservationCancelState(source, backgroundScope, paRes.toString(), ::approve) {}.also {
            it.start()
            runCurrent()
        }

    private fun TestScope.doneWith(
        amount: String,
        waived: Boolean,
        charged: String,
    ): String? {
        val source =
            FakeReservationCancelSource().apply {
                reservations[paRes.toString()] = paDetail("0.00")
                response = paDetail(charged, "CANCELLED", amount, waived)
            }
        val state = started(source)
        state.waivePenalty = waived
        state.confirm()
        runCurrent()
        assertNull(state.error)
        return state.done
    }

    private fun TestScope.noticeFor(charged: String): String? {
        val source = FakeReservationCancelSource().apply { reservations[paRes.toString()] = paDetail(charged) }
        return started(source).previousPenalty
    }

    @Test
    fun previousPenaltyNoticeCopiesTheServerValue() =
        runTest {
            assertEquals("Esta reserva já tem R$ 150,00 de multa de cancelamento na conta.", noticeFor("150.00"))
            assertEquals("Esta reserva já tem R$ 0,01 de multa de cancelamento na conta.", noticeFor("0.01"))
        }

    @Test
    fun previousPenaltyNoticeIsAbsentForZeroNegativeOrUnreadable() =
        runTest {
            for (charged in listOf("0.00", "0", "-150.00", "cento e cinquenta", "", "1.2.3", "150,00")) {
                assertNull(noticeFor(charged), "penaltiesCharged \"$charged\"")
            }
        }

    @Test
    fun failedRereadDropsThePreviousPenaltyNotice() =
        runTest {
            val source = FakeReservationCancelSource().apply { reservations[paRes.toString()] = paDetail("150.00") }
            val state = started(source)
            assertEquals("Esta reserva já tem R$ 150,00 de multa de cancelamento na conta.", state.previousPenalty)

            source.readError = ApiError.Network(IOException("caiu"))
            state.start()
            runCurrent()

            assertNull(state.previousPenalty)
            assertNull(state.terms)
        }

    @Test
    fun negativeChargedNeverShowsATotalOrAPreviousPenalty() =
        runTest {
            for ((amount, waived) in listOf("320.00" to false, "320.00" to true, "0.00" to false)) {
                val done = doneWith(amount, waived, "-150.00").orEmpty()
                assertFalse("Total de multas" in done, "$amount/$waived: $done")
                assertFalse("já tem" in done, "$amount/$waived: $done")
                assertFalse("-" in done.substringAfter("Reserva R1 "), "$amount/$waived: $done")
            }
            assertEquals(
                "Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.",
                doneWith("320.00", false, "-150.00"),
            )
        }

    @Test
    fun chargedBelowTheNewPenaltyShowsOnlyTheNewOne() =
        runTest {
            assertEquals(
                "Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.",
                doneWith("320.00", false, "100.00"),
            )
        }

    @Test
    fun chargedEqualToTheNewPenaltyInAnotherScaleShowsNoTotal() =
        runTest {
            assertEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.", doneWith("320.00", false, "320"))
            assertEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.", doneWith("320", false, "320.0"))
        }

    @Test
    fun chargedJustAboveTheNewPenaltyShowsTheTotal() =
        runTest {
            assertEquals(
                "Reserva R1 cancelada. Multa lançada na conta: R$ 320,00. " +
                    "Total de multas de cancelamento desta reserva: R$ 320,01.",
                doneWith("320.00", false, "320.01"),
            )
        }

    @Test
    fun noNewPenaltyWithTinyPreviousSaysSo() =
        runTest {
            assertEquals(
                "Reserva R1 cancelada. Sem multa nova. A conta já tem R$ 0,01 de multa de cancelamento anterior.",
                doneWith("0", false, "0.01"),
            )
        }

    @Test
    fun waivedWithoutPreviousNeverShowsTheTotal() =
        runTest {
            assertEquals("Reserva R1 cancelada. Multa isenta: R$ 320,00.", doneWith("320.00", true, "0"))
            assertEquals("Reserva R1 cancelada. Multa isenta: R$ 320,00.", doneWith("320.00", true, "-1.00"))
        }
}
