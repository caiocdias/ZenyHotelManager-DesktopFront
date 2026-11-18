package com.zeny.desktop.app.reservations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.folio.ReservationFolioDialog
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
private val CANCELLABLE_STATUSES = setOf("PROVISIONAL", "CONFIRMED")
private val REACTIVABLE_STATUSES = setOf("CANCELLED", "NO_SHOW")

/** Um diálogo aberto e a reserva do detalhe em que abriu: trocar de reserva o fecha. */
private class Opened<T>(
    val ownerId: String?,
    val state: T,
    val code: String,
)

/**
 * Desenha o diálogo [opened] enquanto ele é da reserva aberta ([ownerId]); ao sair da composição ou trocar de reserva,
 * encerra o estado com [close] e se solta com [release].
 */
@Composable
private fun <T> Hosted(
    opened: Opened<T>?,
    ownerId: String?,
    release: (Opened<T>) -> Unit,
    close: (T) -> Unit,
    content: @Composable (Opened<T>) -> Unit,
) {
    val shown = opened?.takeIf { it.ownerId == ownerId }
    DisposableEffect(shown) {
        onDispose {
            shown?.let {
                close(it.state)
                release(it)
            }
        }
    }
    shown?.let { content(it) }
}

/**
 * Os botões "Alterar", "Confirmar reserva", "Confirmação (PDF)", "Cancelar reserva", "Reativar" e "Conta" do detalhe
 * da reserva (da busca ou das pendências) e os diálogos que eles abrem. Os botões dependem da reserva aberta, das
 * permissões e da fábrica da ação (o "Conta", do portador da conta); o servidor continua decidindo. Cada diálogo é da
 * reserva que estava aberta ao pedir: trocar de reserva, fechar o detalhe ou sair da composição (área, sessão) o
 * fecha. Fechar um diálogo relê o detalhe: uma recusa definitiva deixa o diálogo aberto sem avisar a tela de fundo.
 */
@Stable
internal class ReservationDialogs(
    private val detail: ReservationDetailState,
    private val actions: () -> ReservationsActions?,
) {
    private var change by mutableStateOf<Opened<ReservationChangeState>?>(null)
    private var confirm by mutableStateOf<Opened<ReservationConfirmState>?>(null)
    private var cancel by mutableStateOf<Opened<ReservationCancelState>?>(null)
    private var reactivate by mutableStateOf<Opened<ReservationReactivateState>?>(null)
    private var folio by mutableStateOf<Opened<String>?>(null)
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
            onCancel =
                current.cancel
                    ?.takeIf { manage && status in CANCELLABLE_STATUSES }
                    ?.let { make -> { openCancel(make, id, reservation.code) } },
            onReactivate =
                current.reactivate
                    ?.takeIf { manage && status in REACTIVABLE_STATUSES }
                    ?.let { make -> { openReactivate(make, id, reservation.code) } },
            onFolio = current.folio?.let { { folio = Opened(detail.openId, id, reservation.code) } },
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

    private fun openCancel(
        make: (String) -> ReservationCancelState,
        id: String,
        code: String,
    ) {
        cancel?.state?.close()
        cancel = Opened(detail.openId, make(id), code)
    }

    private fun openReactivate(
        make: (String, String) -> ReservationReactivateState,
        id: String,
        code: String,
    ) {
        reactivate?.state?.close()
        reactivate = Opened(detail.openId, make(id, code), code)
    }

    @Composable
    fun Show() {
        Hosted(change, detail.openId, { if (change === it) change = null }, ReservationChangeState::close) {
            ReservationChangeDialog(it.state) {
                change = null
                detail.reload()
            }
        }
        Hosted(confirm, detail.openId, { if (confirm === it) confirm = null }, ReservationConfirmState::close) {
            ShowConfirm(it)
        }
        Hosted(cancel, detail.openId, { if (cancel === it) cancel = null }, ReservationCancelState::close) {
            ReservationCancelDialog(it.state) {
                cancel = null
                detail.reload()
            }
        }
        Hosted(
            reactivate,
            detail.openId,
            { if (reactivate === it) reactivate = null },
            ReservationReactivateState::close,
        ) {
            ReservationReactivateDialog(it.state) {
                reactivate = null
                detail.reload()
            }
        }
        card.Show()
        val wiring = actions()?.folio
        Hosted(folio?.takeIf { wiring != null }, detail.openId, { if (folio === it) folio = null }, {}) {
            if (wiring != null) ReservationFolioDialog(wiring, it.state, it.code) { folio = null }
        }
    }

    @Composable
    private fun ShowConfirm(open: Opened<ReservationConfirmState>) {
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
