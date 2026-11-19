package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val FOLIO_NOT_CLOSED = "FOLIO_NOT_CLOSED"

/**
 * O diálogo "Reabrir conta" da conta encerrada [folioId]: o motivo, obrigatório, até 500 caracteres contados depois
 * de aparar (a edição que passaria disso é recusada; vazio dá "Informe o motivo." sem chamar a porta).
 *
 * A reabertura não leva `Idempotency-Key`, então não usa o `FolioWrite`: passa pelo [approve] (o coordenador da
 * sessão) e classifica a falha direto. A aprovação cancelada ([approve] devolve `null`) destrava e diz que a conta não
 * foi reaberta. A falha que `classifyWriteFailure` não dá como definitiva deixa o resultado desconhecido
 * ([unconfirmed]): chama [onReread], avisa para conferir e destrava, para repetir só por clique (repetir é inofensivo:
 * a conta já aberta dá `FOLIO_NOT_CLOSED`). `FOLIO_NOT_CLOSED` mostra o texto do catálogo e chama [onReread]; as
 * demais recusas, o texto do catálogo. O sucesso chama [onDone]. Com envio em voo, [confirm] não roda (duplo clique);
 * depois do [close], nenhuma resposta muda o estado. O `toString()` não mostra o motivo.
 */
class ReopenState(
    private val source: FolioAccountSource,
    scope: CoroutineScope,
    private val folioId: String,
    private val approve: suspend (ApprovableAction<FolioResponse>) -> ApiResponse<FolioResponse>?,
    private val onDone: () -> Unit,
    private val onReread: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var reasonText by mutableStateOf("")
    private var closed by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set

    /** O resultado da última tentativa foi desconhecido e a conta foi relida; fechar o diálogo a relê de novo. */
    var unconfirmed by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** As entradas estão travadas (em voo ou fechado). */
    val locked: Boolean get() = closed || busy

    val canConfirm: Boolean get() = !locked

    /** O motivo, até 500 caracteres; o excesso é recusado. */
    var reason: String
        get() = reasonText
        set(value) {
            if (!locked && value.length <= CORRECTION_REASON_MAX) reasonText = value
        }

    fun confirm() {
        if (!canConfirm) return
        val text = reasonText.trim()
        if (text.isEmpty()) {
            message = FolioText.REOPEN_REASON_REQUIRED
            return
        }
        busy = true
        message = null
        ownScope.launch {
            try {
                send(text)
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        busy = false
        jobs.cancel()
    }

    private suspend fun send(text: String) {
        val response =
            try {
                approve(source.reopen(folioId, text))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(e as? ApiError ?: ApiError.Network(e))
                return
            }
        currentCoroutineContext().ensureActive()
        if (response == null) {
            // O servidor respondeu `APPROVAL_REQUIRED` agora: a conta continua encerrada, sem resultado desconhecido.
            unconfirmed = false
            message = FolioText.REOPEN_APPROVAL_CANCELLED
        } else {
            unconfirmed = false
            onDone()
        }
    }

    private fun fail(failure: ApiError) {
        if (classifyWriteFailure(failure) != WriteFailure.DEFINITIVE) {
            unconfirmed = true
            message = FolioText.REOPEN_UNCONFIRMED
            onReread()
            return
        }
        unconfirmed = false
        message = failure.userMessage
        if ((failure as? ApiError.Problem)?.code == FOLIO_NOT_CLOSED) onReread()
    }

    // O motivo digitado nunca vai a log nem a `toString`.
    override fun toString(): String = "ReopenState(busy=$busy, unconfirmed=$unconfirmed)"
}
