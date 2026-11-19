package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.sdk.model.PostingResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

private const val CREATE = "billing.postings.create"
private const val ADJUST = "billing.postings.adjust"
private const val SERVICE_CHARGE_REMOVE = "billing.servicecharge.remove"

/** As recusas que dizem que a conta mudou em outro lugar: a conta é relida. */
private val REREADS =
    setOf(
        "FOLIO_CLOSED",
        "POSTING_ALREADY_REVERSED",
        "POSTING_NOTHING_TO_TRANSFER",
        "POSTING_NOTHING_TO_REVERSE",
        "UNKNOWN_FOLIO",
    )

/**
 * As correções da conta [folioId], criadas pelo `FolioState` quando há a porta delas no [wiring]: "Ajustar"
 * ([startAdjustment]) e "Remover a taxa" ([startRemoval]) na linha. Cada ação aparece só com a conta aberta
 * ([folioOpen]), pela regra da linha (o `rowOf` só oferece o que o servidor deixa: o restante lido diferente de zero
 * e o lançamento não estornado) e pela permissão da casca: o ajuste com `billing.postings.create` ou
 * `billing.postings.adjust` (sem esta, o servidor pede a aprovação, pelo `approve` do [wiring]); a remoção, só numa
 * taxa de serviço, com `billing.servicecharge.remove`. Um diálogo por vez: nunca com outro do painel aberto
 * ([otherDialogOpen]).
 *
 * O sucesso fecha o diálogo, chama [onChanged] (a conta, os lançamentos, o extrato à vista e a lista das contas) e
 * avisa o painel por [notify]. As recusas que dizem que a conta mudou em outro lugar (`FOLIO_CLOSED`,
 * `POSTING_ALREADY_REVERSED` e afins) mostram o texto do catálogo e relêem a conta; fechar um diálogo sem resultado
 * conhecido também, para conferir. [close] cancela o que é daqui, e nenhuma resposta tardia muda o painel.
 */
@Suppress("LongParameterList", "TooManyFunctions") // o que o painel passa; abrir, fechar e o resultado de cada diálogo
class FolioCorrectionsState(
    private val wiring: FolioWiring,
    scope: CoroutineScope,
    private val folioId: String,
    private val folioOpen: () -> Boolean,
    private val otherDialogOpen: () -> Boolean,
    private val notify: (String) -> Unit,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var closed = false

    /** O diálogo "Ajustar" aberto. */
    var adjustment by mutableStateOf<AdjustmentState?>(null)
        private set

    /** A confirmação "Remover a taxa" aberta. */
    var removal by mutableStateOf<ServiceChargeRemovalState?>(null)
        private set

    private val canAdjustAny =
        wiring.corrections != null && (CREATE in wiring.permissions || ADJUST in wiring.permissions)
    private val canRemoveAny = wiring.corrections != null && SERVICE_CHARGE_REMOVE in wiring.permissions

    val dialogOpen: Boolean get() = adjustment != null || removal != null

    /** "Ajustar" na linha: um `REGULAR` não estornado com restante, com uma das duas permissões e a conta aberta. */
    fun canAdjust(row: PostingRow): Boolean = !closed && canAdjustAny && folioOpen() && row.adjustable

    /** "Remover a taxa" na linha: só numa taxa não estornada com restante, com a permissão e a conta aberta. */
    fun canRemoveServiceCharge(row: PostingRow): Boolean =
        !closed && canRemoveAny && folioOpen() && row.serviceChargeRemovable

    /** Abre "Ajustar" para o lançamento [row]; fora das regras ou com outro diálogo aberto, nada. */
    fun startAdjustment(row: PostingRow) {
        val source = wiring.corrections
        if (!canAdjust(row) || !noDialogOpen() || source == null) return
        adjustment = AdjustmentState(source, ownScope, folioId, row, wiring.approve, ::adjusted, ::refused)
    }

    /** "Voltar" ou "Fechar" do ajuste; sem resultado conhecido, a conta é relida para conferir. */
    fun finishAdjustment() {
        val form = adjustment ?: return
        if (form.busy) return
        form.close()
        adjustment = null
        if (form.unconfirmed || form.closeOnly) changed()
    }

    /** Abre "Remover a taxa" para a taxa [row]; fora das regras ou com outro diálogo aberto, nada. */
    fun startRemoval(row: PostingRow) {
        val source = wiring.corrections
        if (!canRemoveServiceCharge(row) || !noDialogOpen() || source == null) return
        removal = ServiceChargeRemovalState(source, ownScope, folioId, row, ::removed, ::refused)
    }

    /** "Voltar" ou "Fechar" da remoção; sem resultado conhecido, a conta é relida para conferir. */
    fun finishRemoval() {
        val form = removal ?: return
        if (form.busy) return
        form.close()
        removal = null
        if (form.unconfirmed || form.closeOnly) changed()
    }

    fun close() {
        closed = true
        jobs.cancel()
        adjustment?.close()
        removal?.close()
    }

    /** Um diálogo por vez no painel: nem o daqui nem outro do `FolioState`. */
    private fun noDialogOpen(): Boolean = !dialogOpen && !otherDialogOpen()

    private fun adjusted() {
        adjustment?.close()
        adjustment = null
        changed()
        notify(FolioText.ADJUSTMENT_DONE)
    }

    private fun removed(response: PostingResponse) {
        removal?.close()
        removal = null
        changed()
        val reason = (response.reason as? Option.Some)?.value?.takeIf { it.isNotBlank() }
        notify(FolioText.serviceChargeRemoved(reason))
    }

    /** A recusa definitiva (o texto já está no diálogo): a conta mudou em outro lugar, então é relida. */
    private fun refused(code: String) {
        if (code in REREADS) changed()
    }

    private fun changed() {
        if (closed) return
        onChanged()
    }

    override fun toString(): String =
        "FolioCorrectionsState(adjustment=${adjustment != null}, removal=${removal != null})"
}
