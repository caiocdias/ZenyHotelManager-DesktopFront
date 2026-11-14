package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.GuestResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.Flow

/** Do perfil do hóspede, o `app` guarda só o nome e o primeiro documento (já mascarado pelo servidor). */
data class HolderSummary(
    val name: String,
    val document: String?,
) {
    // Dado pessoal: nunca em log nem em `toString` de estado.
    override fun toString(): String = "HolderSummary(***)"
}

/** O nome social, se houver e não estiver em branco; senão o nome completo. */
fun GuestResponse.toHolderSummary(): HolderSummary {
    val social = (socialName as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    return HolderSummary(
        name = social ?: fullName,
        document = documents.firstOrNull()?.let { ReceptionText.document(it.type.value, it.maskedNumber) },
    )
}

/** O que a recepção usa de um servidor: as leituras do dia, da reserva e do titular, e o stream de tempo real. */
interface ReceptionSource {
    suspend fun permissions(): Set<String>

    suspend fun businessDay(): BusinessDay

    suspend fun dayPage(
        list: DayList,
        date: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse>

    suspend fun reservation(id: String): ReservationDetailResponse

    suspend fun history(id: String): List<ReservationHistoryResponse>

    suspend fun roomAssignments(id: String): List<RoomAssignmentResponse>

    suspend fun holder(guestId: String): HolderSummary

    suspend fun ratePlanName(id: String): String

    suspend fun roomTypes(): List<RoomTypeResponse>

    fun events(topics: Set<String>): Flow<RealtimeEvent>
}
