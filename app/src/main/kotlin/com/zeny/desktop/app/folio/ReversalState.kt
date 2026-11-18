package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.PostingResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val FOLIO_CLOSED = "FOLIO_CLOSED"
private const val REASON_MAX = 500

/**
 * O diálogo "Estornar" do lançamento [postingId] da conta [folioId]: o motivo é obrigatório (até 500 caracteres,
 * enviado sem os espaços das pontas). O estorno é um lançamento novo do servidor; o original fica.
 *
 * O estorno passa pelo [approve] (o coordenador da sessão): o `APPROVAL_REQUIRED` abre o diálogo de aprovação, e a
 * aprovação cancelada deixa "Aprovação cancelada. O estorno não foi feito." e destrava as entradas, com a mesma
 * intenção. A `Idempotency-Key` é por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`), com as entradas travadas; resposta definitiva gera outra. O `5xx` guardado sob a chave
 * deixa o estado [closeOnly]: só resta fechar, e quem fecha relê a conta para conferir. Depois do sucesso ([done]) e
 * do `FOLIO_CLOSED`, [onChanged] roda para a conta ser relida. [close] cancela o que é daqui.
 */
class ReversalState(
    private val source: FolioSource,
    scope: CoroutineScope,
    private val folioId: String,
    private val postingId: String,
    private val approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>?,
    private val onChanged: () -> Unit,
) : FolioWriteForm {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    private var reasonText by mutableStateOf("")

    override var busy by mutableStateOf(false)
        private set

    override var unconfirmed by mutableStateOf(false)
        private set

    override var closeOnly by mutableStateOf(false)
        private set

    /** A validação recusada, a aprovação cancelada ou o resultado do último envio que não deu certo. */
    override var message by mutableStateOf<String?>(null)
        private set

    /** O estorno foi feito. */
    var done by mutableStateOf(false)
        private set

    // `closed` é estado observável: `canConfirm` o lê, e o Compose só recompõe o que leu estado.
    private var closed by mutableStateOf(false)
    private var intent: Intent? = null

    /** As entradas estão travadas (em voo, resultado desconhecido, fechado ou feito). */
    val locked: Boolean get() = closed || busy || unconfirmed || closeOnly || done

    override val canConfirm: Boolean get() = !closed && !busy && !closeOnly && !done

    override val closesOnEscape: Boolean get() = !busy && (!unconfirmed || closeOnly)

    /** O motivo, até 500 caracteres; o excesso é recusado. Mudá-lo (depois da aprovação cancelada) é outra intenção. */
    var reason: String
        get() = reasonText
        set(value) {
            if (locked || value == reasonText || value.length > REASON_MAX) return
            reasonText = value
            intent = null
        }

    override fun confirm() {
        if (!canConfirm) return
        val text = reasonText.trim()
        if (text.isEmpty()) {
            message = FolioText.REASON_REQUIRED
            return
        }
        busy = true
        message = null
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val approved = approve(source.reverse(folioId, postingId, text, current))
                currentCoroutineContext().ensureActive()
                if (approved == null) approvalCancelled() else finish()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(e as? ApiError ?: ApiError.Network(e))
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun finish() {
        intent = null
        unconfirmed = false
        message = null
        done = true
        onChanged()
    }

    /**
     * Aprovação cancelada: o servidor respondeu `APPROVAL_REQUIRED` na mesma chave, então nada foi feito. Destrava as
     * entradas (inclusive depois de um resultado desconhecido) e mantém a intenção (a chave não muda).
     */
    private fun approvalCancelled() {
        unconfirmed = false
        message = FolioText.APPROVAL_CANCELLED
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) FolioText.REVERSAL_SERVER_UNCONFIRMED else FolioText.REVERSAL_UNCONFIRMED
            return
        }
        unconfirmed = false
        intent = null
        message = failure.userMessage
        if ((failure as? ApiError.Problem)?.code == FOLIO_CLOSED) onChanged()
    }

    override fun toString(): String =
        "ReversalState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly, done=$done)"
}
