package com.zeny.desktop.core.error

/** Mensagens em português para os códigos de erro estáveis do contrato. */
object ErrorCatalog {
    const val GENERIC_MESSAGE = "Não foi possível concluir a operação."

    private val messages: Map<String, String> =
        mapOf(
            // Comuns (Problem.code)
            "VALIDATION_FAILED" to "Alguns campos estão inválidos. Corrija e tente de novo.",
            "MALFORMED_REQUEST" to "O pedido enviado está malformado. Atualize o app e tente de novo.",
            "INVALID_CURSOR" to "A lista expirou. Recarregue e tente de novo.",
            "UNAUTHENTICATED" to "Sua sessão expirou. Entre novamente.",
            "ACCESS_DENIED" to "Você não tem permissão para esta ação.",
            "PASSWORD_CHANGE_REQUIRED" to "Troque a senha para continuar.",
            "APPROVAL_REQUIRED" to "Esta ação precisa da aprovação de um superior.",
            "NOT_FOUND" to "O registro não foi encontrado.",
            "METHOD_NOT_ALLOWED" to "Esta operação não é permitida pelo servidor.",
            "NOT_ACCEPTABLE" to "O servidor não consegue responder no formato pedido. Atualize o app.",
            "CONFLICT" to "Esta operação entra em conflito com o estado atual do registro.",
            "PRECONDITION_FAILED" to "Alguém alterou este registro. Recarregue e tente de novo.",
            "PAYLOAD_TOO_LARGE" to "Os dados enviados são grandes demais.",
            "UNSUPPORTED_MEDIA_TYPE" to "O servidor não aceita o formato dos dados enviados. Atualize o app.",
            "BUSINESS_RULE_VIOLATED" to "A operação viola uma regra do hotel.",
            "PRECONDITION_REQUIRED" to "Recarregue o registro antes de alterá-lo.",
            "IDEMPOTENCY_KEY_REQUIRED" to "O pedido não pôde ser identificado. Tente de novo.",
            "IDEMPOTENCY_KEY_INVALID" to "O pedido não pôde ser identificado. Tente de novo.",
            "IDEMPOTENCY_KEY_IN_USE" to "Este pedido já está sendo processado. Aguarde um instante.",
            "IDEMPOTENCY_KEY_REUSED" to "Este pedido já foi usado para outra operação. Tente de novo.",
            "REQUEST_REJECTED" to "O servidor recusou o pedido.",
            "INTERNAL_ERROR" to "O servidor falhou ao processar o pedido. Tente de novo em instantes.",
            "SERVICE_UNAVAILABLE" to "O servidor está indisponível no momento. Tente de novo em instantes.",
            // Autenticação e aprovação
            "INVALID_CREDENTIALS" to "Login ou senha incorretos.",
            "ACCOUNT_LOCKED" to "Conta bloqueada por tentativas seguidas. Aguarde e tente de novo.",
            "ACCOUNT_DISABLED" to "Esta conta está desativada. Fale com um administrador.",
            "REFRESH_TOKEN_INVALID" to "Sua sessão não é mais válida. Entre novamente.",
            "REFRESH_TOKEN_REUSED" to "Sua sessão foi encerrada por segurança. Entre novamente.",
            "PASSWORD_UNCHANGED" to "A nova senha deve ser diferente da atual.",
            "APPROVAL_CREDENTIALS_INVALID" to "Login ou senha do aprovador incorretos.",
            "APPROVAL_NOT_APPLICABLE" to "Esta ação não precisa de aprovação.",
            "APPROVAL_TOKEN_EXPIRED" to "A aprovação expirou. Peça uma nova aprovação.",
            "APPROVAL_TOKEN_INVALID" to "A aprovação não é válida. Peça uma nova aprovação.",
            "APPROVAL_TOKEN_MISMATCH" to "A aprovação foi dada para outra ação. Peça uma nova aprovação.",
            "APPROVAL_TOKEN_USED" to "A aprovação já foi usada. Peça uma nova aprovação.",
            "APPROVER_IS_REQUESTER" to "Quem pede a ação não pode aprová-la.",
            "APPROVER_LOCKED" to "A conta do aprovador está bloqueada. Aguarde e tente de novo.",
            "APPROVER_NOT_ALLOWED" to "Este usuário não pode aprovar a ação.",
            "PERMISSION_DENIED" to "Você não tem permissão para esta ação.",
            "RESOURCE_OUT_OF_SCOPE" to "Este registro está fora do seu escopo de acesso.",
        )

    fun message(code: String): String? = messages[code]

    @Suppress("UnusedParameter") // `status` faz parte da API; o catálogo hoje decide só por código e detalhe.
    fun userMessage(
        status: Int,
        code: String?,
        detail: String?,
    ): String = code?.let(::message) ?: detail?.takeIf { it.isNotBlank() } ?: GENERIC_MESSAGE
}
