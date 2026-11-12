package com.zeny.desktop.app.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val MIN_LENGTH = 12
private const val MAX_LENGTH = 128
private const val BLANK_CURRENT = "Informe a senha atual."
private const val BAD_LENGTH = "A nova senha deve ter de $MIN_LENGTH a $MAX_LENGTH caracteres."
private const val MISMATCH = "A confirmação não confere com a nova senha."
private const val SAME_AS_CURRENT = "A nova senha deve ser diferente da atual."
private const val GENERIC_FAILURE = "Não foi possível trocar a senha."

/**
 * Estado da troca de senha. Com `awaitingRefresh()` a senha já foi trocada e só falta renovar a sessão:
 * então só `retry` roda, nunca `change`. As senhas nunca aparecem em `toString` ou mensagem.
 */
class PasswordChangeState(
    private val change: suspend (current: String, new: String) -> Unit,
    private val retry: suspend () -> Unit,
    private val awaitingRefresh: () -> Boolean,
    private val scope: CoroutineScope,
) {
    var current by mutableStateOf("")
    var new by mutableStateOf("")
    var confirm by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun submit(onDone: () -> Unit) {
        if (busy) return
        message = null
        val retryOnly = awaitingRefresh()
        val currentPassword = current
        val newPassword = new
        if (!retryOnly) {
            message = validate(currentPassword, newPassword)
            if (message != null) return
        }
        busy = true
        // As senhas saem do estado já aqui: não depende de a corrotina chegar a rodar.
        current = ""
        new = ""
        confirm = ""
        scope
            .launch {
                var succeeded = false
                try {
                    if (retryOnly) retry() else change(currentPassword, newPassword)
                    succeeded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    message = (e as? ApiError)?.userMessage ?: GENERIC_FAILURE
                }
                if (succeeded) onDone()
            }.invokeOnCompletion {
                current = ""
                new = ""
                confirm = ""
                busy = false
            }
    }

    private fun validate(
        currentPassword: String,
        newPassword: String,
    ): String? =
        when {
            currentPassword.isEmpty() -> BLANK_CURRENT
            newPassword.length !in MIN_LENGTH..MAX_LENGTH -> BAD_LENGTH
            newPassword != confirm -> MISMATCH
            newPassword == currentPassword -> SAME_AS_CURRENT
            else -> null
        }

    override fun toString() = "PasswordChangeState(busy=$busy)"
}
