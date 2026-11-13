package com.zeny.desktop.app.rooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val ROOMS_TOPIC = "rooms"
private const val ROOM_STATUS_CHANGED = "rooms.room-status"
private const val NO_FLOOR = "Sem andar"
private const val HTTP_FORBIDDEN = 403

/** Um ladrilho do mapa: o que o quadro mostra de uma UH ativa, na maior versão vista. */
data class RoomTile(
    val roomId: String,
    val number: String,
    val roomTypeId: String,
    val status: RoomDisplayStatus,
    val housekeeping: RoomDisplayStatus,
    val label: String,
    val version: Long,
)

/** O painel lateral: o status acompanha o mapa; o cadastro ([room]) fica `null` sem `rooms.registry.read`. */
data class RoomDetail(
    val room: RoomResponse?,
    val status: RoomStatusResponse,
)

/**
 * Estado do mapa de UHs ao vivo. Assina o tópico `rooms` e carrega tudo no primeiro `ready` ou `reset` (e de novo
 * a cada `reset`); a mensagem de status rebusca só a UH; qualquer outra (ou a de uma UH sem ladrilho) recarrega
 * tudo, com no máximo uma recarga em voo e uma pendente. Rebusca e recarga fazem *merge* por UH pela maior
 * `version`, então resposta atrasada não desfaz uma mais nova. Sem `rooms.registry.read` (`403`), o tipo aparece
 * pelo id e o detalhe só com o status; os demais erros viram [error].
 *
 * Como o `ShellState`: as mutações correm no [scope] da UI (um dispatcher de uma thread só), e por isso o estado
 * interno dispensa trava. Quem cria chama [close] quando o mapa sai de cena.
 */
@Suppress("TooManyFunctions") // as ações da tela e um passo por regra do protocolo do stream
class RoomMapState(
    private val source: RoomsSource,
    scope: CoroutineScope,
) {
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set

    /** Andares em ordem natural, com "Sem andar" no fim; UHs pelo número, também em ordem natural. */
    var floors by mutableStateOf<List<Pair<String, List<RoomTile>>>>(emptyList())
        private set

    /** Nome de cada tipo; o tipo sem nome lido aparece pelo próprio id. */
    var roomTypeNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Status principal das UHs que passam no filtro de tipo; todos os status aparecem, mesmo com zero. */
    var counters by mutableStateOf(RoomDisplayStatus.entries.associateWith { 0 })
        private set

    /** Vazio mostra todos. */
    var typeFilter by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Vazio mostra todos; a UH passa pelo status principal ou pelo de governança. */
    var statusFilter by mutableStateOf<Set<RoomDisplayStatus>>(emptySet())
        private set
    var selected by mutableStateOf<RoomDetail?>(null)
        private set
    var live by mutableStateOf(source.realtimeStatus.value)
        private set

    /** Filho do job da UI: o [close] cancela só o que é do mapa. */
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val mapScope = CoroutineScope(scope.coroutineContext + jobs)

    /** A maior versão vista de cada UH, inativas incluídas (para que resposta velha não as traga de volta). */
    private val rooms = mutableMapOf<String, RoomStatusResponse>()
    private var typeNames = emptyMap<String, String>()

    /** A assinatura já recebeu o primeiro `ready` ou `reset`: antes disso não há o que carregar nem aplicar. */
    private var synced = false
    private var subscription: Job? = null
    private var streamFailure: ApiError? = null
    private var reload: Job? = null
    private var reloadPending = false
    private var detail: Job? = null
    private var closed = false

    init {
        mapScope.launch { source.realtimeStatus.collect { live = it } }
        subscribe()
    }

    fun toggleType(id: String) {
        typeFilter = if (id in typeFilter) typeFilter - id else typeFilter + id
        publish()
    }

    fun toggleStatus(status: RoomDisplayStatus) {
        statusFilter = if (status in statusFilter) statusFilter - status else statusFilter + status
        publish()
    }

    /** Abre o detalhe com o status já conhecido e lê o cadastro da UH à parte. */
    fun select(roomId: String) {
        if (closed) return
        val status = rooms[roomId]?.takeIf { it.active } ?: return
        detail?.cancel()
        selected = RoomDetail(null, status)
        detail =
            mapScope.launch {
                try {
                    val room = source.room(roomId)
                    selected?.takeIf { it.status.roomId == status.roomId }?.let { selected = it.copy(room = room) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (!e.isForbidden()) error = e.asApiError()
                }
            }
    }

    fun clearSelection() {
        detail?.cancel()
        selected = null
    }

    /** Depois de um erro: assina de novo se a assinatura terminou; senão, recarrega. */
    fun retry() {
        if (closed) return
        loading = true
        error = null
        if (subscription?.isActive != true) {
            streamFailure = null
            subscribe()
        } else if (synced) {
            requestReload()
        }
    }

    /** O mapa saiu de cena: cancela a assinatura, as leituras em voo e não aceita mais ações que leiam. */
    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun subscribe() {
        synced = false
        subscription =
            mapScope.launch {
                try {
                    // Completa normalmente no fim da sessão: o mapa só para de ouvir.
                    source.events(setOf(ROOMS_TOPIC)).collect(::onEvent)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    val failure = e.asApiError()
                    streamFailure = failure
                    error = failure
                    loading = false
                }
            }
    }

    private fun onEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Ready -> {
                if (!synced) {
                    synced = true
                    requestReload()
                }
            }

            RealtimeEvent.Reset -> {
                synced = true
                requestReload()
            }

            is RealtimeEvent.Message -> {
                if (synced) onMessage(event)
            }
        }
    }

    private fun onMessage(message: RealtimeEvent.Message) {
        val current = rooms[message.id]
        when {
            message.type != ROOM_STATUS_CHANGED || current == null -> requestReload()
            message.version > current.version -> refetch(message.id)
            else -> Unit
        }
    }

    private fun refetch(id: String) {
        mapScope.launch {
            try {
                val fresh = source.status(id)
                val current = rooms[id]
                // UH que a recarga tirou do mapa enquanto a rebusca voava fica de fora.
                if (current != null && fresh.version > current.version) {
                    rooms[id] = fresh
                    publish()
                }
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                // A recarga diz o estado certo (UH removida) ou mostra o erro.
                requestReload()
            }
        }
    }

    private fun requestReload() {
        if (closed) return
        if (reload?.isActive == true) {
            reloadPending = true
            return
        }
        // Em voo desde o lançamento: o pedido que chega antes de a leitura sair também fica pendente.
        reloadPending = false
        reload =
            mapScope.launch {
                reloadOnce()
                while (reloadPending) {
                    reloadPending = false
                    reloadOnce()
                }
            }
    }

    private suspend fun reloadOnce() {
        try {
            val statuses = source.statuses()
            val names = readTypeNames()
            val merged =
                statuses.associateBy { it.roomId.toString() }.mapValues { (id, read) ->
                    rooms[id]?.takeIf { it.version > read.version } ?: read
                }
            rooms.clear()
            rooms.putAll(merged)
            typeNames = names
            error = streamFailure
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            error = e.asApiError()
        }
        loading = false
        publish()
    }

    private suspend fun readTypeNames(): Map<String, String> =
        try {
            source.roomTypes().associate { it.id.toString() to it.name }
        } catch (e: ApiError.Problem) {
            if (e.status != HTTP_FORBIDDEN) throw e
            emptyMap()
        }

    /** Recalcula o que a tela lê a partir das UHs e dos filtros. */
    private fun publish() {
        val active = rooms.values.filter { it.active }
        val ofType = active.filter { typeFilter.isEmpty() || it.roomTypeId.toString() in typeFilter }
        counters = RoomDisplayStatus.entries.associateWith { s -> ofType.count { displayStatus(it) == s } }
        val visible =
            ofType.filter {
                statusFilter.isEmpty() || displayStatus(it) in statusFilter || housekeepingStatus(it) in statusFilter
            }
        floors = groupByFloor(visible)
        val unnamed = active.map { it.roomTypeId.toString() }.filter { it !in typeNames }
        roomTypeNames = typeNames + unnamed.associateWith { it }
        followSelection()
    }

    private fun followSelection() {
        val open = selected ?: return
        val current = rooms[open.status.roomId.toString()]
        if (current == null || !current.active) {
            clearSelection()
        } else if (current !== open.status) {
            selected = open.copy(status = current)
        }
    }
}

private fun floorOf(status: RoomStatusResponse): String? =
    (status.floor as? Option.Some)?.value?.takeIf { it.isNotBlank() }

private fun groupByFloor(statuses: List<RoomStatusResponse>): List<Pair<String, List<RoomTile>>> {
    val byNumber = compareBy(NaturalOrder) { status: RoomStatusResponse -> status.number }
    val (withFloor, withoutFloor) = statuses.partition { floorOf(it) != null }
    val floors =
        withFloor
            .groupBy { checkNotNull(floorOf(it)) }
            .toSortedMap(NaturalOrder)
            .map { (floor, list) -> "Andar $floor" to list.sortedWith(byNumber).map(::tileOf) }
    if (withoutFloor.isEmpty()) return floors
    return floors + (NO_FLOOR to withoutFloor.sortedWith(byNumber).map(::tileOf))
}

private fun tileOf(status: RoomStatusResponse) =
    RoomTile(
        roomId = status.roomId.toString(),
        number = status.number,
        roomTypeId = status.roomTypeId.toString(),
        status = displayStatus(status),
        housekeeping = housekeepingStatus(status),
        label = tileLabel(status),
        version = status.version,
    )

/** "2" antes de "10": trechos numéricos comparam pelo valor; empate desfeito pelo texto, para nunca juntar dois. */
private object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(
        a: String,
        b: String,
    ): Int {
        val left = chunk.findAll(a).map { it.value }.toList()
        val right = chunk.findAll(b).map { it.value }.toList()
        for ((x, y) in left.zip(right)) {
            val byChunk = compareChunks(x, y)
            if (byChunk != 0) return byChunk
        }
        return if (left.size != right.size) left.size.compareTo(right.size) else a.compareTo(b)
    }

    private fun compareChunks(
        x: String,
        y: String,
    ): Int =
        if (x[0] in '0'..'9' && y[0] in '0'..'9') {
            val xs = x.trimStart('0')
            val ys = y.trimStart('0')
            compareValuesBy(xs, ys, { it.length }, { it })
        } else {
            x.compareTo(y)
        }
}

private fun Exception.isForbidden() = this is ApiError.Problem && status == HTTP_FORBIDDEN

private fun Exception.asApiError(): ApiError = this as? ApiError ?: ApiError.Network(this)
