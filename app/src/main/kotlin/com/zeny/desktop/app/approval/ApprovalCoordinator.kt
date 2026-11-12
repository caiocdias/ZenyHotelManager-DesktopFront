package com.zeny.desktop.app.approval

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.approval.ApprovalDialogModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Aprovação de superior para qualquer ação: `run` tenta sem token e, no `APPROVAL_REQUIRED`, abre o diálogo e
 * espera. "Aprovar" pede o token (`grant`) e repete a ação uma vez com ele (nova `Idempotency-Key`, D2);
 * "Cancelar" devolve `null`. Uma recusa do aprovador mantém o diálogo com a mensagem e limpa o PIN; a sessão
 * encerrada no `grant` fecha o diálogo e sobe. Cada emissão de `sessionEnded` cancela a pendência (`run` devolve
 * `null`) e limpa login, PIN e motivo. Uma pendência por vez. Estado confinado à thread da UI, como as telas;
 * quem cria chama `close()` quando a sessão sai de cena.
 */
class ApprovalCoordinator(
    private val grant: suspend (ApiError.ApprovalRequired, ApprovalInput) -> String,
    sessionEnded: Flow<Unit>,
    scope: CoroutineScope,
) : AutoCloseable {
    var pending by mutableStateOf<ApiError.ApprovalRequired?>(null)
        private set
    var login by mutableStateOf("")
    var pin by mutableStateOf("")
    var reason by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    /** O que o diálogo mostra enquanto há pendência; `detail` é o texto pronto do erro, nunca os campos crus. */
    val dialog: ApprovalDialogModel?
        get() = pending?.let { ApprovalDialogModel(it.userMessage, login, pin, reason, message, busy) }

    private var request: Request? = null

    // Sem atraso na inscrição: um fim de sessão logo depois de criar o coordenador não se perde.
    private val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { sessionEnded.collect { end() } }

    suspend fun <T : Any> run(action: ApprovableAction<T>): ApiResponse<T>? {
        check(request == null) { "Já há uma aprovação pendente." }
        val required =
            try {
                return action.run(null)
            } catch (e: ApiError.ApprovalRequired) {
                e
            }
        return approve(required)?.let { token -> action.run(token) }
    }

    /** Envia o pedido de aprovação; dois cliques, ou campos fora das regras, não fazem outra chamada. */
    fun confirm() {
        val current = request ?: return
        if (dialog?.canConfirm != true) return
        busy = true
        message = null
        val input = ApprovalInput(login.trim(), pin, reason.trim())
        pin = ""
        current.decisions.trySend(input)
    }

    /** Fecha o diálogo sem aprovar; com o pedido em voo, espera a resposta. */
    fun cancel() {
        if (busy) return
        val current = request ?: return
        dismiss(current)
        current.decisions.close()
    }

    /** Cancela a pendência e para de observar o fim da sessão. */
    override fun close() {
        watcher.cancel()
        end()
    }

    private fun end() {
        val current = request ?: return
        dismiss(current)
        current.job?.cancel()
    }

    /** Devolve o token, ou `null` se o usuário cancelou ou a sessão acabou. */
    private suspend fun approve(required: ApiError.ApprovalRequired): String? {
        check(request == null) { "Já há uma aprovação pendente." }
        val current = Request()
        request = current
        pending = required
        return try {
            coroutineScope {
                current.job = coroutineContext.job
                awaitToken(required, current)
            }
        } catch (e: CancellationException) {
            // Cancelamento de quem chamou sobe; o da pendência (fim de sessão) vira `null`.
            currentCoroutineContext().ensureActive()
            if (current.ended) null else throw e
        } finally {
            dismiss(current)
        }
    }

    private suspend fun awaitToken(
        required: ApiError.ApprovalRequired,
        current: Request,
    ): String? {
        for (input in current.decisions) {
            try {
                return grant(required, input)
            } catch (e: ApiError.SessionEnded) {
                throw e
            } catch (e: ApiError) {
                message = e.userMessage
                busy = false
            }
        }
        return null
    }

    /** Fecha o diálogo desta pendência (se ainda for a atual) e tira login, PIN e motivo do estado. */
    private fun dismiss(current: Request) {
        current.ended = true
        if (request !== current) return
        request = null
        pending = null
        login = ""
        pin = ""
        reason = ""
        message = null
        busy = false
    }

    override fun toString() = "ApprovalCoordinator(pending=${pending != null}, busy=$busy)"

    private class Request {
        val decisions = Channel<ApprovalInput>(Channel.UNLIMITED)
        var job: Job? = null
        var ended = false
    }
}
