package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.http.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val SHIFT_REQUIRED = "CASHIER_SHIFT_REQUIRED"

/** O desfecho de um envio que terminou sem exceção; a falha vem pela exceção e passa por `classifyWriteFailure`. */
sealed interface WriteOutcome<out T> {
    /**
     * O servidor fez a escrita: a intenção acaba e [value] vai a quem enviou. O `toString()` não mostra [value] (a
     * resposta da caução imprime o cartão no dela).
     */
    data class Done<out T>(
        val value: T,
    ) : WriteOutcome<T> {
        override fun toString(): String = "Done"
    }

    /** Nada foi feito (a aprovação cancelada): destrava, mantém a intenção e mostra [message]. */
    data class Cancelled(
        val message: String,
    ) : WriteOutcome<Nothing>
}

/**
 * O ajudante comum de escrita dos formulários da conta (`FolioWriteForm`): `FolioWrite(scope, unconfirmedText,
 * storedText, openShift, onRefused)`, com `send(write: suspend (Intent) -> WriteOutcome<T>, onDone: (T) -> Unit)`,
 * `edited()` e `close()`. O formulário guarda uma instância, delega a ela [busy], [unconfirmed], [closeOnly], [message]
 * e [offersOpenShift] e continua dono dos campos, das validações e do próprio `close()`, que chama o [close] daqui.
 *
 * A `Idempotency-Key` é por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido (`classifyWriteFailure`
 * fora do `DEFINITIVE`), com [unconfirmed] e o texto [unconfirmedText]; o `5xx` guardado sob a chave deixa [closeOnly],
 * com [storedText]. A resposta definitiva gera outra intenção, mostra o texto do `ErrorCatalog` e só então chama
 * [onRefused] com o `code` (o gancho de `FOLIO_CLOSED` e das recusas da caução); o `CASHIER_SHIFT_REQUIRED` liga
 * [offersOpenShift] quando há [openShift]. O desfecho [WriteOutcome.Cancelled] destrava e mantém a intenção. Envio com
 * [canSend] falso não roda: nenhum duplo clique gera duas escritas. Depois de [close], nenhum envio roda e nenhuma
 * resposta tardia muda o estado.
 */
class FolioWrite(
    scope: CoroutineScope,
    private val unconfirmedText: String,
    private val storedText: String,
    private val openShift: (() -> Unit)?,
    private val onRefused: (code: String) -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var busy by mutableStateOf(false)
        private set

    var unconfirmed by mutableStateOf(false)
        private set

    var closeOnly by mutableStateOf(false)
        private set

    /** A validação recusada (escrita pelo formulário), o desfecho cancelado ou o resultado do último envio. */
    var message by mutableStateOf<String?>(null)

    // `closed` é estado observável: `canSend` o lê, e o Compose só recompõe o que leu estado.
    var closed by mutableStateOf(false)
        private set

    private var shiftRefused by mutableStateOf(false)
    private var intent: Intent? = null

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = closed || busy || unconfirmed || closeOnly

    /** O servidor pediu um turno aberto e há o atalho "Abrir turno"; sem [openShift], só o texto aparece. */
    val offersOpenShift: Boolean get() = shiftRefused && openShift != null

    /** Confirmar pode enviar (ou repetir a mesma intenção). */
    val canSend: Boolean get() = !closed && !busy && !closeOnly

    /** O Esc fecha fora de um envio em curso e sem resultado a repetir (com [closeOnly], fecha). */
    val closesOnEscape: Boolean get() = !busy && (!unconfirmed || closeOnly)

    /** Envia [write] com a intenção em curso (ou uma nova); o sucesso vai a [onDone]. Sem [canSend], nada. */
    fun <T> send(
        write: suspend (Intent) -> WriteOutcome<T>,
        onDone: (T) -> Unit,
    ) {
        if (!canSend) return
        busy = true
        message = null
        shiftRefused = false
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            // Só a escrita é classificada: a falha de [onDone] não é resultado desconhecido.
            val outcome =
                try {
                    write(current)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    fail(e as? ApiError ?: ApiError.Network(e))
                    null
                } finally {
                    busy = false
                }
            currentCoroutineContext().ensureActive()
            when (outcome) {
                is WriteOutcome.Done -> {
                    intent = null
                    unconfirmed = false
                    onDone(outcome.value)
                }

                is WriteOutcome.Cancelled -> {
                    unconfirmed = false
                    message = outcome.message
                }

                null -> {}
            }
        }
    }

    /** O formulário mudou uma entrada: com nada travado, é outra intenção. */
    fun edited() {
        if (!locked) intent = null
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) storedText else unconfirmedText
            return
        }
        unconfirmed = false
        intent = null
        message = failure.userMessage
        val code = (failure as? ApiError.Problem)?.code ?: return
        if (code == SHIFT_REQUIRED) shiftRefused = true
        onRefused(code)
    }

    override fun toString(): String = "FolioWrite(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}
