package com.zeny.desktop.core.error

import com.zeny.desktop.core.http.CoreJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Lê o problema RFC 9457 de forma tolerante: o corpo vira `JsonObject` e campo ausente ou de tipo errado fica `null`.
 * `401` vira `Problem` aqui; quem decide `SessionEnded` é o `Authenticator`.
 */
object ProblemMapper {
    private const val PRECONDITION_FAILED = 412
    private const val FORBIDDEN = 403
    private const val APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
    private const val PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"

    suspend fun fromResponse(response: HttpResponse): ApiError {
        val text =
            try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") ignored: Exception,
            ) {
                ""
            }
        return fromBody(response.status.value, text)
    }

    fun fromBody(
        status: Int,
        bodyText: String,
    ): ApiError {
        val obj = parseObject(bodyText)
        val code = obj?.string("code")
        val detail = obj?.string("detail")
        val forbidden = status == FORBIDDEN
        return when {
            status == PRECONDITION_FAILED -> ApiError.PreconditionFailed(detail)
            obj == null || (code == null && detail == null) -> ApiError.Unexpected(status)
            forbidden && code == PASSWORD_CHANGE_REQUIRED -> ApiError.PasswordChangeRequired
            forbidden && code == APPROVAL_REQUIRED -> approval(obj, detail) ?: problem(status, obj)
            else -> problem(status, obj)
        }
    }

    private fun problem(
        status: Int,
        obj: JsonObject,
    ) = ApiError.Problem(status, obj.string("code"), obj.string("detail"), fieldErrors(obj))

    fun problemCode(bodyText: String): String? = parseObject(bodyText)?.string("code")

    private fun approval(
        obj: JsonObject,
        detail: String?,
    ): ApiError.ApprovalRequired? {
        val action = obj.string("action")?.takeIf { it.isNotBlank() }
        val permission = obj.string("permission")?.takeIf { it.isNotBlank() }
        val resourceRef = obj.string("resourceRef")?.takeIf { it.isNotBlank() }
        if (action == null || permission == null || resourceRef == null) return null
        val limit = (obj["limit"] as? JsonObject)?.let(::limitOf)
        return ApiError.ApprovalRequired(action, permission, resourceRef, limit, detail)
    }

    private fun limitOf(limit: JsonObject): ApprovalLimit? {
        val key = limit.string("key")
        val amount = limit.string("amount")
        return if (key != null && amount != null) ApprovalLimit(key, amount) else null
    }

    private fun fieldErrors(obj: JsonObject): List<FieldError> =
        runCatching { obj["errors"]?.jsonArray }
            .getOrNull()
            .orEmpty()
            .mapNotNull { item ->
                val e = item as? JsonObject ?: return@mapNotNull null
                val field = e.string("field")
                val code = e.string("code")
                val message = e.string("message")
                if (field != null && code != null && message != null) FieldError(field, code, message) else null
            }

    private fun parseObject(text: String): JsonObject? =
        if (text.isBlank()) {
            null
        } else {
            runCatching { CoreJson.parseToJsonElement(text).jsonObject }.getOrNull()
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
}
