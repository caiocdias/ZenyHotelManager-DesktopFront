package com.zeny.desktop.core.http

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ProblemMapper
import de.quati.ogen.client.ktor.HttpResponseTyped
import de.quati.ogen.client.ktor.ogenAuthAttr
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpHeaders
import io.ktor.serialization.ContentConvertException
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

class ApiResponse<T : Any>(
    val value: T,
    val etag: String?,
    val status: Int,
)

/** O corpo binário de uma resposta, com os cabeçalhos que o `core` interpreta. Os bytes nunca vão a `toString`. */
class BinaryResponse(
    val bytes: ByteArray,
    val contentType: String?,
    val contentDisposition: String?,
) {
    override fun toString(): String = "BinaryResponse(${bytes.size} bytes)"
}

/**
 * Executa uma chamada gerada do `sdk` e a traduz em `ApiResponse` ou `ApiError`.
 *
 * Contrato da `Intent` (decisão D2): `execute(intent)` envia a chave atual e, se a intenção já foi aprovada,
 * o token da última `executeApproved`; assim o reenvio por falha de rede mantém chave e token. Cada
 * `executeApproved` (token novo) gira a chave e guarda o novo token. As escritas sem `Idempotency-Key` (a reabertura
 * da conta) usam `executeWithoutKey`: sem `Intent`, e com o token, só o `X-Approval-Token`.
 */
class ApiExecutor {
    suspend fun <T : Any> execute(
        intent: Intent? = null,
        credentialCheck: Boolean = false,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> = run(CallOptions(intent?.key, intent?.approvalToken, credentialCheck), call)

    /** Repete a intenção com o token de aprovação e uma `Idempotency-Key` nova (decisão D2). */
    suspend fun <T : Any> executeApproved(
        intent: Intent,
        approvalToken: String,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> {
        val key = intent.rotateForApproval()
        intent.approvalToken = approvalToken
        return run(CallOptions(key, approvalToken), call)
    }

    /** Executa uma escrita sem `Idempotency-Key` nem `Intent`; com `approvalToken`, o único header é o do token. */
    internal suspend fun <T : Any> executeWithoutKey(
        approvalToken: String?,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> = run(CallOptions(null, approvalToken), call)

    /**
     * Executa uma chamada que devolve um corpo binário (sem `Intent`), com os mesmos erros tipados do `execute`.
     * Um corpo acima de `maxBytes` vira `IncompatibleResponse` sem ler o resto. A conexão que cai no meio do corpo é
     * `ApiError.Network`, nunca bytes parciais.
     */
    suspend fun executeBytes(
        maxBytes: Long,
        call: suspend (CallOptions) -> HttpStatement,
    ): BinaryResponse =
        try {
            val options = CallOptions(null, null)
            call(options).execute { raw ->
                if (raw.status.value !in HTTP_SUCCESS) throw errorOf(options, raw)
                val declared = raw.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declared != null && declared > maxBytes) throw tooLarge()
                val body = raw.bodyAsChannel()
                val bytes = body.readBuffer(maxBytes + 1).readByteArray()
                // Canal fechado com causa antes da leitura devolve o que sobrou (às vezes nada) sem lançar: é a rede.
                body.closedCause?.let { throw it }
                if (bytes.size > maxBytes) throw tooLarge()
                BinaryResponse(bytes, raw.headers[HttpHeaders.ContentType], raw.headers[HttpHeaders.ContentDisposition])
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: BadContentTypeFormatException) {
            throw incompatible(e)
        } catch (e: IOException) {
            throw ApiError.Network(e)
        } catch (e: UnresolvedAddressException) {
            throw ApiError.Network(e)
        }

    private fun tooLarge(): ApiError =
        ApiError.IncompatibleResponse(ResponseFormatException(IOException("corpo acima do teto")))

    private suspend fun <T : Any> run(
        options: CallOptions,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> =
        try {
            val typed = call(options)
            val raw = typed.raw
            if (raw.status.value !in HTTP_SUCCESS) throw errorOf(options, raw)
            ApiResponse(typed.body(), raw.headers[HttpHeaders.ETag], raw.status.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: ContentConvertException) {
            throw incompatible(e)
        } catch (e: NoTransformationFoundException) {
            throw incompatible(e)
        } catch (e: SerializationException) {
            throw incompatible(e)
        } catch (e: IOException) {
            throw ApiError.Network(e)
        } catch (e: UnresolvedAddressException) {
            throw ApiError.Network(e)
        }

    /** A mensagem do parser ecoa o corpo da resposta (no login, com os tokens): a causa leva só o tipo dela. */
    private fun incompatible(e: Exception): ApiError = ApiError.IncompatibleResponse(ResponseFormatException(e))

    private suspend fun errorOf(
        options: CallOptions,
        raw: HttpResponse,
    ): ApiError {
        val error = ProblemMapper.fromResponse(raw)
        val code = (error as? ApiError.Problem)?.code
        val secured =
            !raw.call.request.attributes
                .getOrNull(ogenAuthAttr)
                .isNullOrEmpty()
        val checksCredential = options.credentialCheck && code in CREDENTIAL_CODES
        return if (raw.status.value == HTTP_UNAUTHORIZED && secured && !checksCredential) {
            ApiError.SessionEnded(code)
        } else {
            error
        }
    }

    private companion object {
        /** Só 2xx é sucesso; um 3xx (o cliente não segue redirecionamento) vira erro tipado. */
        val HTTP_SUCCESS = 200..299
        const val HTTP_UNAUTHORIZED = 401
        val CREDENTIAL_CODES = setOf("INVALID_CREDENTIALS", "ACCOUNT_LOCKED")
    }
}

/** Causa de `ApiError.IncompatibleResponse`: o nome da classe e a pilha da falha original, sem a mensagem dela. */
internal class ResponseFormatException(
    original: Throwable,
) : Exception(original::class.qualifiedName) {
    init {
        stackTrace = original.stackTrace
    }
}
