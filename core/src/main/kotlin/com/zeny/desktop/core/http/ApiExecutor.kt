package com.zeny.desktop.core.http

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ProblemMapper
import de.quati.ogen.client.ktor.HttpResponseTyped
import de.quati.ogen.client.ktor.ogenAuthAttr
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.http.HttpHeaders
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

class ApiResponse<T : Any>(
    val value: T,
    val etag: String?,
    val status: Int,
)

/** Executa uma chamada gerada do `sdk` e a traduz em `ApiResponse` ou `ApiError`. */
class ApiExecutor {
    suspend fun <T : Any> execute(
        intent: Intent? = null,
        credentialCheck: Boolean = false,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> = run(CallOptions(intent?.key, null, credentialCheck), call)

    /** Repete a intenção com o token de aprovação e uma `Idempotency-Key` nova (decisão D2). */
    suspend fun <T : Any> executeApproved(
        intent: Intent,
        approvalToken: String,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> = run(CallOptions(intent.rotateForApproval(), approvalToken), call)

    private suspend fun <T : Any> run(
        options: CallOptions,
        call: suspend (CallOptions) -> HttpResponseTyped<T>,
    ): ApiResponse<T> =
        try {
            val typed = call(options)
            val raw = typed.raw
            if (raw.status.value >= HTTP_ERROR) throw errorOf(options, typed)
            ApiResponse(typed.body(), raw.headers[HttpHeaders.ETag], raw.status.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: ContentConvertException) {
            throw ApiError.IncompatibleResponse(e)
        } catch (e: NoTransformationFoundException) {
            throw ApiError.IncompatibleResponse(e)
        } catch (e: SerializationException) {
            throw ApiError.IncompatibleResponse(e)
        } catch (e: IOException) {
            throw ApiError.Network(e)
        } catch (e: UnresolvedAddressException) {
            throw ApiError.Network(e)
        }

    private suspend fun errorOf(
        options: CallOptions,
        typed: HttpResponseTyped<*>,
    ): ApiError {
        val raw = typed.raw
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
        const val HTTP_ERROR = 400
        const val HTTP_UNAUTHORIZED = 401
        val CREDENTIAL_CODES = setOf("INVALID_CREDENTIALS", "ACCOUNT_LOCKED")
    }
}
