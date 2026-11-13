package com.zeny.desktop.core.facade

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.ResponseFormatException
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import de.quati.kotlin.util.Option
import java.time.DateTimeException
import java.time.ZoneId

/** O usuário corrente; `permissions` são só os códigos, e quem decide é o servidor (`403` tratado em cada ação). */
data class Me(
    val userId: String,
    val login: String,
    val name: String,
    val permissions: Set<String>,
)

class AccountFacade internal constructor(
    private val iam: IamApi,
    private val executor: ApiExecutor,
) {
    suspend fun me(): Me {
        val response = executor.execute { iam.getMe(it.headers) }.value
        return Me(
            userId = response.userId.toString(),
            login = response.login,
            name = response.name,
            permissions = response.permissions.map { it.code }.toSet(),
        )
    }
}

/** `tradeName` é nulo enquanto o hotel não foi configurado (ou o servidor o devolve nulo). */
data class HotelInfo(
    val tradeName: String?,
    val configured: Boolean,
)

/** O dia operacional e o fuso do hotel; `zone` é nulo quando o servidor não manda um fuso válido (nunca o da JVM). */
data class BusinessDay(
    val date: BusinessDate,
    val zone: ZoneId?,
)

/** As duas leituras são separadas: um `403` em `hotel()` não impede `businessDate()`. */
class HotelFacade internal constructor(
    private val hotel: HotelApi,
    private val executor: ApiExecutor,
) {
    suspend fun hotel(): HotelInfo {
        val response = executor.execute { hotel.getHotel(it.headers) }.value
        return HotelInfo((response.tradeName as? Option.Some)?.value?.takeIf { it.isNotBlank() }, response.configured)
    }

    /** Data ilegível é resposta incompatível; a causa leva só o tipo da falha, nunca o texto da data. */
    suspend fun businessDate(): BusinessDate {
        val text = executor.execute { hotel.getBusinessDate(it.headers) }.value.businessDate
        return try {
            BusinessDate.parse(text)
        } catch (e: IllegalArgumentException) {
            throw ApiError.IncompatibleResponse(ResponseFormatException(e))
        }
    }

    /** Uma chamada só: data ilegível é resposta incompatível; fuso ausente, em branco ou inválido vira `null`. */
    suspend fun businessDay(): BusinessDay {
        val response = executor.execute { hotel.getBusinessDate(it.headers) }.value
        val date =
            try {
                BusinessDate.parse(response.businessDate)
            } catch (e: IllegalArgumentException) {
                throw ApiError.IncompatibleResponse(ResponseFormatException(e))
            }
        val zone =
            try {
                response.timezone.takeIf { it.isNotBlank() }?.let(ZoneId::of)
            } catch (_: DateTimeException) {
                null
            }
        return BusinessDay(date, zone)
    }
}
