package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
