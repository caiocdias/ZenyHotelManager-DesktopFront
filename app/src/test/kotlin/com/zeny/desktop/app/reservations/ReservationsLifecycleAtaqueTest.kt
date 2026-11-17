package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val LA_MANAGE = "reservations.reservations.manage"
private const val LA_READ = "reservations.reservations.read"
private const val LA_CANCEL = "open-reservation-cancel"
private const val LA_REACTIVATE = "open-reservation-reactivate"

private fun laDetail(
    item: ReservationListItemResponse,
    status: String = item.status,
) = ReservationDetailResponse(
    bookingCode = item.bookingCode,
    holderGuestId = item.holderGuestId,
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = item.arrival,
            bookingId = item.bookingId,
            cancellationTerms =
                CancellationTermsResponse(
                    deadlineHours = 24,
                    name = "Flexível",
                    penaltyNights = Option.Some(1),
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = item.code,
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = item.departure,
            folioId = item.folioId,
            guaranteeTerms =
                GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
            guests = emptyList(),
            id = item.id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = item.ratePlanId,
            roomTotal = "300.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = status,
            total = "300.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

/**
 * Ataque à fiação dos diálogos de reativar e cancelar no detalhe da busca (M3 E04): o diálogo de reativar é da
 * reserva em que abriu (trocar de reserva o fecha e encerra o estado, inclusive com o pedido em voo) e os botões
 * seguem a situação que o servidor devolve, sem situação desconhecida virando ação.
 */
@OptIn(ExperimentalTestApi::class)
class ReservationsLifecycleAtaqueTest {
    private class Rig(
        val search: FakeReservationSearchSource = FakeReservationSearchSource(),
        val reception: FakeReceptionSource = FakeReceptionSource(),
        val reactivate: FakeReservationReactivateSource = FakeReservationReactivateSource(),
        val cancel: FakeReservationCancelSource = FakeReservationCancelSource(),
    )

    private fun rig(vararg items: Pair<ReservationListItemResponse, String>): Rig {
        val rig = Rig()
        rig.reception.permissions = setOf(LA_MANAGE, LA_READ)
        items.forEach { (item, status) ->
            val holder = HolderSummary("Titular ${item.code}", null)
            val id = item.id.toString()
            rig.search.reservations += item
            rig.search.holders[item.holderGuestId.toString()] = holder
            rig.reception.reservations[id] = laDetail(item, status)
            rig.reception.holders[item.holderGuestId.toString()] = holder
            rig.reception.histories[id] = emptyList()
            rig.reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
            rig.cancel.reservations[id] = laDetail(item, status)
            rig.reactivate.response = laDetail(item, "CONFIRMED")
        }
        return rig
    }

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private class Made {
        val reactivates = mutableListOf<ReservationReactivateState>()
        var changes = 0
    }

    /** A busca com o detalhe e as ações de cancelar e reativar, como a `ReservationsArea` as monta. */
    private fun ComposeUiTest.screen(
        rig: Rig,
        made: Made,
    ): ReservationDetailState {
        lateinit var detail: ReservationDetailState
        setContent {
            val scope = rememberCoroutineScope()
            val search = remember { ReservationSearchState(rig.search, scope, pageSize = 5) }
            detail = remember { ReservationDetailState(rig.reception, scope) }
            val actions =
                ReservationsActions(
                    setOf(LA_MANAGE, LA_READ),
                    change = null,
                    confirm = null,
                    confirmation = null,
                    cancel = { id -> ReservationCancelState(rig.cancel, scope, id, { it.run(null) }, {}) },
                    reactivate = { id, code ->
                        ReservationReactivateState(rig.reactivate, scope, id, code, { it.run(null) }) {
                            made.changes++
                        }.also { made.reactivates += it }
                    },
                )
            ZenyTheme { ReservationsScreen(search, detail, actions) }
            remember(search) { search.start() }
        }
        waitForIdle()
        return detail
    }

    @Test
    fun reactivateDialogClosesWhenTheReservationChangesAndItsStateIsClosed() =
        runComposeUiTest {
            val first = searchItemOf(code = "R-1", status = "CANCELLED")
            val second = searchItemOf(code = "R-2", status = "NO_SHOW")
            val rig = rig(first to "CANCELLED", second to "NO_SHOW")
            val made = Made()
            val detail = screen(rig, made)

            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag(LA_REACTIVATE).performClick()
            waitForIdle()
            onNodeWithTag("reactivate-dialog").assertExists()
            assertTrue(hasTag("reactivate-question"))

            detail.open(second.id.toString())
            waitForIdle()

            onNodeWithTag("reactivate-dialog").assertDoesNotExist()
            val old = made.reactivates.single()
            old.confirm()
            waitForIdle()
            assertTrue(rig.reactivate.calls.isEmpty(), "o estado da reserva anterior não foi encerrado")
            assertTrue(hasTag(LA_REACTIVATE), "a outra reserva, em no-show, oferece Reativar")

            detail.open(first.id.toString())
            waitForIdle()
            onNodeWithTag("reactivate-dialog").assertDoesNotExist()
        }

    @Test
    fun reservationChangeWithReactivationInFlightDropsTheAnswer() =
        runComposeUiTest {
            val first = searchItemOf(code = "R-1", status = "CANCELLED")
            val second = searchItemOf(code = "R-2", status = "CONFIRMED")
            val rig = rig(first to "CANCELLED", second to "CONFIRMED")
            rig.reactivate.hold = true
            val made = Made()
            val detail = screen(rig, made)

            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag(LA_REACTIVATE).performClick()
            waitForIdle()
            onNodeWithTag("reactivate-submit").performClick()
            waitForIdle()
            assertEquals(1, rig.reactivate.calls.size)
            assertTrue(made.reactivates.single().busy)

            detail.open(second.id.toString())
            waitForIdle()
            rig.reactivate.release()
            waitForIdle()

            onNodeWithTag("reactivate-dialog").assertDoesNotExist()
            assertEquals(null, made.reactivates.single().done)
            assertEquals(0, made.changes, "a resposta tardia não avisa a tela")
            assertTrue(hasTag(LA_CANCEL), "a reserva aberta agora é a confirmada")
            assertFalse(hasTag(LA_REACTIVATE))
        }

    @Test
    fun unknownStatusOffersNeitherCancelNorReactivate() =
        runComposeUiTest {
            val item = searchItemOf(code = "R-9", status = "WAITLISTED")
            val rig = rig(item to "WAITLISTED")
            screen(rig, Made())

            onNodeWithText("R-9").performClick()
            waitForIdle()

            assertTrue(hasTag("reservation-detail"))
            assertFalse(hasTag(LA_CANCEL))
            assertFalse(hasTag(LA_REACTIVATE))
        }
}
