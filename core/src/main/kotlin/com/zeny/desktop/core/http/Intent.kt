package com.zeny.desktop.core.http

import io.ktor.client.request.HttpRequestBuilder
import kotlin.uuid.Uuid

/** Uma intenção do usuário: a mesma `Idempotency-Key` em toda chamada dela, trocada só ao repetir com aprovação. */
class Intent {
    var key: String = Uuid.random().toString()
        private set

    /** Token da última `executeApproved`; o reenvio por rede da mesma intenção o leva junto com a mesma chave. */
    internal var approvalToken: String? = null

    /** Depois do `403 APPROVAL_REQUIRED` a repetição com token usa uma chave nova (decisão D2). */
    internal fun rotateForApproval(): String {
        key = Uuid.random().toString()
        return key
    }
}

class CallOptions(
    val idempotencyKey: String?,
    val approvalToken: String?,
    val credentialCheck: Boolean = false,
) {
    fun requireIdempotencyKey(): String = idempotencyKey ?: error("operação exige Intent")

    /** `headers[nome] = valor` substitui o que o cliente gerado pôs, então o header sai uma vez só (ADR 0007). */
    val headers: HttpRequestBuilder.() -> Unit = {
        idempotencyKey?.let { headers["Idempotency-Key"] = it }
        approvalToken?.let { headers["X-Approval-Token"] = it }
        if (credentialCheck) attributes.put(CredentialCheck, true)
    }
}
