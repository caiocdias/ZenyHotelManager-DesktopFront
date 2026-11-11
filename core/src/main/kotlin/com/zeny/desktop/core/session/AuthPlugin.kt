package com.zeny.desktop.core.session

import com.zeny.desktop.core.error.ProblemMapper
import com.zeny.desktop.core.http.CredentialCheck
import de.quati.ogen.client.ktor.ogenAuthAttr
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

private const val UNAUTHENTICATED = "UNAUTHENTICATED"
private val CREDENTIAL_CODES = setOf("INVALID_CREDENTIALS", "ACCOUNT_LOCKED")

/** O `authenticator` é um provedor, chamado a cada requisição, para quebrar o ciclo cliente ↔ sessão. */
class AuthPluginConfig {
    var authenticator: () -> Authenticator = { error("AuthPlugin sem authenticator") }
}

/**
 * Põe o `Bearer` só nas operações com `ogenAuthAttr` não vazio e guarda o par usado. `401 UNAUTHENTICATED`
 * pede um refresh e uma única repetição, sempre com um token da mesma sessão do envio (sessão trocada ou
 * encerrada no meio: devolve o `401`, sem repetir). `401` de conferência de credencial (`CredentialCheck`)
 * mantém a sessão; qualquer outro `401`, o da repetição inclusive, encerra a sessão do envio, se ainda é a
 * atual. O corpo segue legível para o `ProblemMapper`.
 */
val AuthPlugin =
    createClientPlugin("ZenyAuth", ::AuthPluginConfig) {
        val provider = pluginConfig.authenticator
        on(Send) { request ->
            if (request.attributes.getOrNull(ogenAuthAttr).isNullOrEmpty()) return@on proceed(request)
            val auth = provider()
            val sent = auth.currentTokens() ?: return@on proceed(request)
            request.headers[HttpHeaders.Authorization] = "Bearer ${sent.accessToken}"
            val call = proceed(request)
            if (!call.isUnauthorized()) return@on call
            val code = problemCode(call)
            if (code != UNAUTHENTICATED) return@on call.also { endUnlessCredentialCheck(auth, request, sent, code) }
            val fresh = auth.refreshAfterRejection(sent) ?: return@on call
            request.headers[HttpHeaders.Authorization] = "Bearer ${fresh.accessToken}"
            proceed(request).also { retry ->
                if (retry.isUnauthorized()) endUnlessCredentialCheck(auth, request, fresh, problemCode(retry))
            }
        }
    }

private fun HttpClientCall.isUnauthorized() = response.status == HttpStatusCode.Unauthorized

/** Lê o corpo salvo da resposta, que continua legível para o `ProblemMapper` depois. */
private suspend fun problemCode(call: HttpClientCall): String? = ProblemMapper.problemCode(call.response.bodyAsText())

private fun endUnlessCredentialCheck(
    auth: Authenticator,
    request: HttpRequestBuilder,
    rejected: Tokens,
    code: String?,
) {
    val checksCredential = request.attributes.getOrNull(CredentialCheck) == true && code in CREDENTIAL_CODES
    if (!checksCredential) auth.endSession(rejected, code)
}
