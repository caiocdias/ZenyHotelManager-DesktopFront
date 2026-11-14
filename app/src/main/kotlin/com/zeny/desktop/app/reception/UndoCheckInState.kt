package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val UNDO_TRANSITION_NOT_ALLOWED = "RESERVATION_TRANSITION_NOT_ALLOWED"

/**
 * Estado do desfazer check-in: uma confirmação aprovada pelo [approve], sem `Idempotency-Key` (o contrato não tem).
 * Como o desfazer não tem chave, quando o resultado pode ter mudado a reserva (`RESERVATION_TRANSITION_NOT_ALLOWED`,
 * rede, resposta incompatível) o [onChanged] também roda, para o detalhe mostrar o estado real. Aprovação cancelada
 * não muda nada. O estado é dono da trava: com `busy`, `done` ou depois de [close], [confirm] é no-op.
 */
class UndoCheckInState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    private val reservationId: String,
    private val approve: suspend (ApprovableAction<UndoCheckInResponse>) -> ApiResponse<UndoCheckInResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    private var closed = false

    fun confirm() {
        if (closed || busy || done) return
        busy = true
        val previousError = error
        error = null
        ownScope.launch {
            try {
                val response = approve(source.undoCheckIn(reservationId))
                if (response == null) error = previousError else finish()
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
        done = true
        onChanged()
    }

    private fun fail(failure: ApiError) {
        error = ActionText.undoError(failure)
        val refresh =
            failure is ApiError.Network ||
                failure is ApiError.IncompatibleResponse ||
                (failure is ApiError.Problem && failure.code == UNDO_TRANSITION_NOT_ALLOWED)
        if (refresh) onChanged()
    }
}
