package com.zeny.desktop.core.facade

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.ResponseFormatException
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.model.CheckOutRequest
import de.quati.kotlin.util.Option
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private const val FOLIO_BALANCE_NOT_ZERO = "FOLIO_BALANCE_NOT_ZERO"
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** O late check-out oferecido pela política vigente: o horário limite em `HH:mm` e o valor, como o servidor cotou. */
data class LateCheckOutQuote(
    val limitTime: String,
    val fee: Money,
)

/** O saldo de uma conta que impede o check-out, como o servidor mandou; o `number` identifica a conta na tela. */
data class FolioDue(
    val folioId: String,
    val number: String,
    val balance: Money,
)

/** O desfecho do check-out: concluído, ou o saldo devido por conta e o total (`409 FOLIO_BALANCE_NOT_ZERO`). */
sealed interface CheckOutResult {
    data object Done : CheckOutResult

    data class BalanceDue(
        val folios: List<FolioDue>,
        val total: Money,
    ) : CheckOutResult
}

/**
 * O check-out da reserva hospedada e a cotação do late check-out. O check-out leva `Idempotency-Key` pela `Intent`; o
 * front não calcula saldo, devido, total nem late. Um `id` que não é UUID lança `IllegalArgumentException` antes de ir
 * à rede, sem o valor na mensagem.
 */
class CheckOutFacade internal constructor(
    private val frontdesk: FrontdeskApi,
    private val executor: ApiExecutor,
) {
    /**
     * O late check-out da cotação, ou `null` quando não é oferecido ou vem sem valor ou sem horário. Valor ou horário
     * ilegível é resposta incompatível; a causa leva só o tipo da falha.
     */
    suspend fun lateCheckOutQuote(reservationId: String): LateCheckOutQuote? {
        val id = idOf(reservationId)
        val late = executor.execute { frontdesk.getEarlyLateQuote(id, it.headers) }.value.lateCheckOut
        val fee = (late.fee as? Option.Some)?.value
        val limitTime = (late.limitTime as? Option.Some)?.value
        if (!late.offered || fee == null || limitTime == null) return null
        return try {
            LateCheckOutQuote(LocalTime.parse(limitTime).format(HOUR_MINUTE), Money.parse(fee))
        } catch (e: DateTimeException) {
            throw ApiError.IncompatibleResponse(ResponseFormatException(e))
        } catch (e: IllegalArgumentException) {
            throw ApiError.IncompatibleResponse(ResponseFormatException(e))
        }
    }

    /**
     * Faz o check-out; sem [lateFee], vai sem corpo. `409 FOLIO_BALANCE_NOT_ZERO` com `folios` e `total` legíveis vira
     * [CheckOutResult.BalanceDue] (resposta definitiva); ilegíveis, a `ApiError.Problem` original sobe, com o texto do
     * catálogo. As outras falhas sobem como `ApiError`.
     */
    suspend fun checkOut(
        reservationId: String,
        lateFee: Money?,
        intent: Intent,
    ): CheckOutResult {
        val id = idOf(reservationId)
        val request = lateFee?.let { CheckOutRequest(lateCheckOutFee = Option.Some(it.toApi())) }
        return try {
            executor.execute(intent) {
                val block = if (request == null) withoutBody(it.headers) else it.headers
                frontdesk.checkOutReservation(id, it.requireIdempotencyKey(), request, block)
            }
            CheckOutResult.Done
        } catch (e: ApiError.Problem) {
            if (e.code != FOLIO_BALANCE_NOT_ZERO) throw e
            balanceDueOf(e.extensions) ?: throw e
        }
    }

    /** O `sdk` manda o `null` como JSON; o corpo é opcional, então a chamada sem late vai sem corpo e sem tipo. */
    private fun withoutBody(headers: HttpRequestBuilder.() -> Unit): HttpRequestBuilder.() -> Unit =
        {
            headers()
            setBody(EmptyContent)
            this.headers.remove(HttpHeaders.ContentType)
        }

    /** Qualquer parte ausente ou ilegível invalida o devido inteiro: nenhum valor é inventado nem omitido. */
    private fun balanceDueOf(extensions: JsonObject): CheckOutResult.BalanceDue? {
        val folios = (extensions["folios"] as? JsonArray)?.map { (it as? JsonObject)?.let(::folioDueOf) }
        val total = extensions.money("total")
        return if (total == null || folios.isNullOrEmpty() || null in folios) {
            null
        } else {
            CheckOutResult.BalanceDue(folios.filterNotNull(), total)
        }
    }

    private fun folioDueOf(item: JsonObject): FolioDue? {
        val folioId = item.string("folioId")?.takeIf { Uuid.parseOrNull(it) != null }
        val number = item.string("number")?.takeIf { it.isNotBlank() }
        val balance = item.money("balance")
        return if (folioId != null && number != null && balance != null) FolioDue(folioId, number, balance) else null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.money(key: String): Money? =
        string(key)?.let {
            try {
                Money.parse(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun idOf(id: String): Uuid =
        Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de reserva inválido")
}
