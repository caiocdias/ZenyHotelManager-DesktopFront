package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.table.Column
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val PARALLEL_HOLDER_READS = 4
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

private const val UNKNOWN = "—"

/** Uma linha de lista de reservas, já em texto: a busca da tabela acha qualquer célula, alertas incluídos. */
data class ReservationRow(
    val id: String,
    val code: String,
    val holder: String,
    val room: String,
    val roomType: String,
    val arrival: String,
    val departure: String,
    val guests: String,
    val status: String,
) {
    // O titular é dado pessoal: nunca em log nem em `toString`.
    override fun toString(): String =
        "ReservationRow(id=$id, code=$code, room=$room, roomType=$roomType, arrival=$arrival, " +
            "departure=$departure, guests=$guests, status=$status)"
}

/**
 * O cache de nomes de titular: só em memória, enquanto quem o criou vive. [resolve] lê o que falta (até
 * [PARALLEL_HOLDER_READS] leituras em paralelo); sem acesso ao titular (`403`) ou apagado (`404`), o nome é "—".
 * Os demais erros sobem. Quem precisa que a mesma leitura não saia duas vezes em paralelo serializa as chamadas.
 */
internal class HolderNames(
    private val read: suspend (String) -> HolderSummary,
) {
    private val names = mutableMapOf<String, String>()

    /** O nome de cada id, lido no máximo uma vez. */
    suspend fun resolve(ids: Collection<String>): Map<String, String> {
        val slots = Semaphore(PARALLEL_HOLDER_READS)
        coroutineScope {
            ids.distinct().filter { it !in names }.forEach { id ->
                launch { slots.withPermit { names[id] = nameOf(id) } }
            }
        }
        return ids.associateWith { names.getValue(it) }
    }

    private suspend fun nameOf(id: String): String =
        try {
            read(id).name
        } catch (e: ApiError.Problem) {
            if (e.status != HTTP_FORBIDDEN && e.status != HTTP_NOT_FOUND) throw e
            UNKNOWN
        }
}

/** As colunas de qualquer lista de reservas: o painel do dia e a busca. */
internal val reservationColumns: List<Column<ReservationRow>> =
    listOf(
        Column("code", "Reserva") { it.code },
        Column("holder", "Titular") { it.holder },
        Column("room", "UH") { it.room },
        Column("roomType", "Tipo") { it.roomType },
        Column("arrival", "Chegada") { it.arrival },
        Column("departure", "Saída") { it.departure },
        Column("guests", "Hóspedes") { it.guests },
        Column("status", "Situação") { it.status },
    )

/** O nome de cada tipo de UH pelo id; sem nome fica de fora. `ApiError.Problem` vira mapa vazio, e os demais sobem. */
internal suspend fun readRoomTypeNames(read: suspend () -> List<RoomTypeResponse>): Map<String, String> =
    try {
        read().filter { it.name.isNotBlank() }.associate { it.id.toString() to it.name }
    } catch (_: ApiError.Problem) {
        emptyMap()
    }

/**
 * Monta as linhas de uma página de reservas: os titulares pelo [HolderNames] e os tipos de UH lidos uma vez, até
 * [forgetRoomTypes]. Uma página por vez resolve os dois: a mesma leitura não sai duas vezes por páginas em paralelo.
 */
internal class ReservationRowResolver(
    readHolder: suspend (String) -> HolderSummary,
    private val readRoomTypes: suspend () -> List<RoomTypeResponse>,
) {
    private val holderNames = HolderNames(readHolder)
    private var roomTypeNames: Map<String, String>? = null
    private val lookups = Mutex()

    /** As linhas de [items], na mesma ordem. */
    suspend fun rows(items: List<ReservationListItemResponse>): List<ReservationRow> =
        lookups.withLock {
            val types = roomTypeNames ?: readRoomTypeNames(readRoomTypes).also { roomTypeNames = it }
            val holders = holderNames.resolve(items.map { it.holderGuestId.toString() })
            items.map { it.toReservationRow(holders.getValue(it.holderGuestId.toString()), types) }
        }

    /** A próxima página relê os tipos. */
    fun forgetRoomTypes() {
        roomTypeNames = null
    }
}

internal fun ReservationListItemResponse.toReservationRow(
    holder: String,
    roomTypeNames: Map<String, String>,
): ReservationRow {
    val assignment = (roomAssignment as? Option.Some)?.value
    val roomNumber = (assignment?.roomNumber as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    return ReservationRow(
        id = id.toString(),
        code = code,
        holder = holder,
        room = roomNumber ?: UNKNOWN,
        roomType = roomTypeNames[roomTypeId.toString()] ?: UNKNOWN,
        arrival = ReceptionText.date(arrival),
        departure = ReceptionText.date(departure),
        guests = ReceptionText.guests(adults, children),
        status = statusText(),
    )
}

/** O rótulo da situação seguido dos alertas que valem, nesta ordem. */
private fun ReservationListItemResponse.statusText(): String =
    listOfNotNull(
        ReceptionText.status(status).label,
        "Day use".takeIf { dayUse },
        "Chegada pendente".takeIf { arrivalOverdue },
        "Saída pendente".takeIf { departureOverdue },
    ).joinToString(" · ")
