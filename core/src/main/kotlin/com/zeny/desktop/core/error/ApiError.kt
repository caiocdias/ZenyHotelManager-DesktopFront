package com.zeny.desktop.core.error

data class FieldError(
    val field: String,
    val code: String,
    val message: String,
)

/** O `amount` é copiado do problema, sem reformatar. */
data class ApprovalLimit(
    val key: String,
    val amount: String,
)

/** Erro tipado das chamadas do `core`; `userMessage` é sempre texto pronto para o usuário. */
sealed class ApiError(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {
    class Problem(
        val status: Int,
        val code: String?,
        val detail: String?,
        val fieldErrors: List<FieldError>,
    ) : ApiError(ErrorCatalog.userMessage(status, code, detail))

    class ApprovalRequired(
        val action: String,
        val permission: String,
        val resourceRef: String,
        val limit: ApprovalLimit?,
        val detail: String?,
    ) : ApiError("Esta ação precisa da aprovação de um superior.")

    class PreconditionFailed(
        val detail: String?,
    ) : ApiError("Alguém alterou este registro. Recarregue e tente de novo.")

    data object PasswordChangeRequired : ApiError("Troque a senha para continuar.")

    class SessionEnded(
        val code: String?,
    ) : ApiError("Sua sessão foi encerrada. Entre novamente.")

    class Network(
        cause: Throwable,
    ) : ApiError("Não foi possível falar com o servidor. Confira o endereço e a rede.", cause)

    class IncompatibleResponse(
        cause: Throwable,
    ) : ApiError("O servidor respondeu num formato que esta versão do app não reconhece. Atualize o app.", cause)

    class Unexpected(
        val status: Int,
    ) : ApiError("O servidor respondeu com um erro inesperado (HTTP $status).")
}
