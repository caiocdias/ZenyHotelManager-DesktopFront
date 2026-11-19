package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.CallOptions
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.model.ApprovalLimitRequest
import com.zeny.desktop.sdk.model.ApprovalRequest
import de.quati.kotlin.util.Option
import de.quati.ogen.client.ktor.HttpResponseTyped

/**
 * Uma ação que pode pedir aprovação: `run(null)` tenta sem token; `run(token)` repete com o token (e, nas escritas
 * com `Idempotency-Key`, com chave nova; as sem chave levam só o token).
 */
fun interface ApprovableAction<T : Any> {
    suspend fun run(approvalToken: String?): ApiResponse<T>
}

/**
 * O ajudante das escritas com `Idempotency-Key` que podem pedir aprovação: a mesma `Intent` vale na tentativa e na
 * repetição aprovada (decisão D2). Sem token, `execute`; com token, `executeApproved`, que renova a chave e anexa o
 * token.
 */
internal fun <T : Any> ApiExecutor.approvable(
    intent: Intent,
    call: suspend (CallOptions) -> HttpResponseTyped<T>,
): ApprovableAction<T> =
    ApprovableAction { token ->
        if (token == null) execute(intent, call = call) else executeApproved(intent, token, call)
    }

/** O ajudante das escritas sem `Idempotency-Key` que podem pedir aprovação: a repetição leva só o token. */
internal fun <T : Any> ApiExecutor.approvableWithoutKey(
    call: suspend (CallOptions) -> HttpResponseTyped<T>,
): ApprovableAction<T> = ApprovableAction { token -> executeWithoutKey(token, call) }

/** Para as fachadas das features: o mesmo ajudante sobre o executor do `core`. */
internal fun <T : Any> ZenyCore.approvable(
    intent: Intent,
    call: suspend (CallOptions) -> HttpResponseTyped<T>,
): ApprovableAction<T> = sdkExecutor.approvable(intent, call)

/** O PIN fica fora do `toString`. */
data class ApprovalInput(
    val approverLogin: String,
    val approverPin: String,
    val reason: String,
) {
    override fun toString(): String = "ApprovalInput(approverLogin=$approverLogin, approverPin=***, reason=$reason)"
}

class ApprovalFacade internal constructor(
    private val iam: IamApi,
    private val executor: ApiExecutor,
) {
    /** Pede o token de aprovação; `action`, `permission`, `resourceRef` e `limit` vão como o servidor mandou. */
    suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String {
        val limit =
            required.limit?.let { Option.Some(ApprovalLimitRequest(it.amount, it.key)) } ?: Option.Undefined
        val request =
            ApprovalRequest(
                action = required.action,
                approverLogin = input.approverLogin,
                approverPin = input.approverPin,
                limit = limit,
                permission = required.permission,
                reason = input.reason,
                resourceRef = required.resourceRef,
            )
        return executor.execute { iam.grantApproval(request, it.headers) }.value.approvalToken
    }
}
