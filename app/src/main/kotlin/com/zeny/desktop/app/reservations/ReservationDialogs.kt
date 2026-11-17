package com.zeny.desktop.app.reservations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.CardTarget
import com.zeny.desktop.app.reception.DetailActions
import com.zeny.desktop.app.reception.RegistrationCardUi
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option

private const val MANAGE = "reservations.reservations.manage"
private const val READ = "reservations.reservations.read"
private val CHANGEABLE_STATUSES = setOf("PROVISIONAL", "CONFIRMED", "IN_HOUSE")
private val NO_CONFIRMATION_STATUSES = setOf("CANCELLED", "NO_SHOW")

/** Um diálogo aberto e a reserva do detalhe em que abriu: trocar de reserva o fecha. */
private class Opened<T>(
    val ownerId: String?,
    val state: T,
    val code: String,
)

/**
 * Os botões "Alterar", "Confirmar reserva" e "Confirmação (PDF)" do detalhe da busca e os diálogos que eles abrem. Os
 * botões dependem da reserva aberta, das permissões e da fábrica da ação; o servidor continua decidindo. Cada diálogo
 * é da reserva que estava aberta ao pedir: trocar de reserva, fechar o detalhe ou sair da composição (área, sessão) o
 * fecha. Fechar um diálogo relê o detalhe: uma recusa definitiva deixa o diálogo aberto sem avisar a tela de fundo.
 */
@Stable
internal class ReservationDialogs(
    private val detail: ReservationDetailState,
    private val actions: () -> ReservationsActions?,
) {
    private var change by mutableStateOf<Opened<ReservationChangeState>?>(null)
    private var confirm by mutableStateOf<Opened<ReservationConfirmState>?>(null)
    private val card = RegistrationCardUi(detail) { actions()?.confirmation }

    /** Os botões da reserva aberta no detalhe; nenhum sem ações, sem reserva carregada ou sem a permissão. */
    fun detailActions(): DetailActions {
        val current = actions()
        val reservation = detail.reservation?.reservation
        return if (current == null || reservation == null) DetailActions() else actionsFor(current, reservation)
    }

    private fun actionsFor(
        current: ReservationsActions,
        reservation: ReservationResponse,
    ): DetailActions {
        val manage = MANAGE in current.permissions
        val status = reservation.status
        val id = reservation.id.toString()
        return DetailActions(
            onChange =
                current.change
                    ?.takeIf { manage && status in CHANGEABLE_STATUSES && !reservation.dayUse }
                    ?.let { make -> { openChange(make, id, reservation.code) } },
            onConfirmReservation =
                current.confirm
                    ?.takeIf { manage && needsConfirmation(reservation) }
                    ?.let { make -> { openConfirm(make, id, reservation.code) } },
            onConfirmationPdf =
                current.confirmation
                    ?.takeIf { READ in current.permissions && status !in NO_CONFIRMATION_STATUSES }
                    ?.let { { card.open(CardTarget(id, reservation.code)) } },
        )
    }

    private fun openChange(
        make: (String) -> ReservationChangeState,
        id: String,
        code: String,
    ) {
        change?.state?.close()
        change = Opened(detail.openId, make(id), code)
    }

    private fun openConfirm(
        make: (String) -> ReservationConfirmState,
        id: String,
        code: String,
    ) {
        confirm?.state?.close()
        confirm = Opened(detail.openId, make(id), code)
    }

    @Composable
    fun Show() {
        ShowChange()
        ShowConfirm()
        card.Show()
    }

    @Composable
    private fun ShowChange() {
        val shown = change?.takeIf { it.ownerId == detail.openId }
        DisposableEffect(shown) {
            onDispose {
                shown?.state?.close()
                if (change === shown) change = null
            }
        }
        shown?.let {
            ReservationChangeDialog(it.state) {
                change = null
                detail.reload()
            }
        }
    }

    @Composable
    private fun ShowConfirm() {
        val shown = confirm?.takeIf { it.ownerId == detail.openId }
        DisposableEffect(shown) {
            onDispose {
                shown?.state?.close()
                if (confirm === shown) confirm = null
            }
        }
        shown?.let { open ->
            val current = actions()
            val canOpenPdf = current?.confirmation != null && current.permissions.contains(READ)
            ReservationConfirmDialog(
                open.state,
                onConfirmation =
                    if (canOpenPdf) {
                        { showConfirmation(open) }
                    } else {
                        null
                    },
            ) {
                confirm = null
                detail.reload()
            }
        }
    }

    /** "Ver confirmação" do diálogo de confirmar: ele fecha e o PDF da mesma reserva abre. */
    private fun showConfirmation(open: Opened<ReservationConfirmState>) {
        val id = detail.openId ?: return
        open.state.close()
        confirm = null
        detail.reload()
        card.open(CardTarget(id, open.code))
    }
}

/**
 * Pede a confirmação da reserva: a pré-reserva, ou a confirmada cuja política exige garantia (`CARD` ou `DEPOSIT`) e
 * que ainda não a tem registrada, ou a tem de outro meio.
 */
private fun needsConfirmation(reservation: ReservationResponse): Boolean {
    val required = reservation.guaranteeTerms.type
    val registered = (reservation.guarantee as? Option.Some)?.value?.method
    val guaranteePending = required in BookingText.GUARANTEES && registered != required
    return reservation.status == "PROVISIONAL" || (reservation.status == "CONFIRMED" && guaranteePending)
}
