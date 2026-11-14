package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.NewGuest
import com.zeny.desktop.core.facade.WalkInInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.GuestResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import com.zeny.desktop.sdk.model.WalkInResponse
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

/** Um hóspede achado na busca ou recém-cadastrado: o id e o resumo (nome e documento mascarado). */
data class GuestPick(
    val id: String,
    val summary: HolderSummary,
) {
    // Dado pessoal: nunca em log nem em `toString` de estado.
    override fun toString(): String = "GuestPick(***)"
}

fun GuestResponse.toGuestPick(): GuestPick = GuestPick(id.toString(), toHolderSummary())

/**
 * O que a recepção usa de um servidor: as leituras do dia, da reserva e do titular, as ações de alocação, check-in e
 * cadastro de hóspede, e o stream de tempo real.
 */
@Suppress("TooManyFunctions") // porta que espelha as operações da recepção sobre o `core`
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

    suspend fun roomSuggestions(id: String): List<RoomSuggestionResponse>

    suspend fun roomStatuses(): List<RoomStatusResponse>

    suspend fun assignRoom(
        id: String,
        roomId: String,
        doNotMove: Boolean?,
    ): ReservationDetailResponse

    suspend fun unassignRoom(id: String): ReservationDetailResponse

    suspend fun setDoNotMove(
        id: String,
        doNotMove: Boolean,
    ): ReservationDetailResponse

    suspend fun earlyLateQuote(id: String): EarlyLateQuoteResponse

    fun checkIn(
        id: String,
        input: CheckInInput,
        intent: Intent,
    ): ApprovableAction<CheckInResponse>

    /** A cotação da estadia no canal direto, com as ofertas na ordem do servidor. */
    suspend fun quoteStay(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse

    /** Os ids das UHs alocadas a reservas provisórias ou confirmadas que tocam o período (`to` inclusive). */
    suspend fun assignedRoomIds(
        from: BusinessDate,
        to: BusinessDate,
    ): Set<String>

    fun walkIn(
        input: WalkInInput,
        intent: Intent,
    ): ApprovableAction<WalkInResponse>

    /** A busca por nome (até 20 hóspedes). */
    suspend fun searchGuests(name: String): List<GuestPick>

    suspend fun searchGuestsByDocument(
        type: String,
        number: String,
        issuingCountry: String?,
    ): List<GuestPick>

    suspend fun createGuest(input: NewGuest): GuestPick

    fun events(topics: Set<String>): Flow<RealtimeEvent>
}
