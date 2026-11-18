package com.zeny.desktop.app.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.input.DateInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Set as JavaSet

private const val UNCONFIGURED_HOTEL = "Hotel ainda não configurado"
private const val UNKNOWN_HOTEL = "Hotel"
private const val CASHIER_OPERATE = "billing.cashier.operate"

/** O indicador de turno do topo da casca. */
sealed interface ShiftIndicator {
    /** Sem `billing.cashier.operate` no conjunto, sem porta do caixa na conexão, ou antes da leitura. */
    data object Hidden : ShiftIndicator

    /** A leitura do turno corrente falhou: o topo não afirma nada. */
    data object Unknown : ShiftIndicator

    data object None : ShiftIndicator

    data class Open(
        val number: String,
        val cashPointName: String,
    ) : ShiftIndicator
}

/**
 * Estado da casca: usuário e áreas (`me`), data operacional e nome do hotel, lidos ao nascer e a cada `reload`.
 * Falha em `me` ou na data vira `error` (com "Tentar de novo"); o nome do hotel é independente: leitura negada
 * (o `403` da Governanta) ou falha dá só o nome genérico, sem impedir a data nem o menu. Quem cria chama
 * `close()` quando a casca sai de cena. [restoreArea] (a área aberta na casca da sessão anterior) só abre depois
 * que o `me` desta sessão a confirma no menu. O indicador de turno ([shift]) é lido depois do `me` e da data, num job
 * à parte como o nome do hotel, e só com `billing.cashier.operate` no conjunto: a falha dele não vira `error`.
 */
class ShellState(
    private val connection: ServerConnection,
    private val devMode: Boolean,
    private val scope: CoroutineScope,
    private var restoreArea: Area? = null,
) {
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set
    var hotelName by mutableStateOf("")
        private set

    /** `dd/MM/aaaa`, direto da data de negócio, sem fuso. */
    var businessDate by mutableStateOf("")
        private set
    var userName by mutableStateOf("")
        private set

    /** Áreas do menu, com `true` quando já têm tela (`visibleAreas`). */
    var areas by mutableStateOf<List<Pair<Area, Boolean>>>(emptyList())
        private set
    var selected by mutableStateOf<Area?>(null)
        private set

    /**
     * As permissões efetivas do `me` desta sessão, numa cópia imutável: a fonte única das áreas e do topo (ADR 0013).
     * Nulas do início de cada `reload` até o `me` e a data darem certo, e com `error`.
     */
    var permissions by mutableStateOf<Set<String>?>(null)
        private set

    /** O turno do usuário no topo; [ShiftIndicator.Hidden] do início de cada `reload` até a leitura dele. */
    var shift by mutableStateOf<ShiftIndicator>(ShiftIndicator.Hidden)
        private set

    private var load: Job? = null
    private var hotelRead: Job? = null
    private var shiftRead: Job? = null
    private var closed = false

    init {
        reload()
    }

    /**
     * Relê tudo; leituras anteriores ainda em voo são canceladas e não sobrescrevem as novas. O nome do hotel
     * corre à parte: `loading` e `error` dependem só de `me` e da data.
     */
    fun reload() {
        if (closed) return
        load?.cancel()
        hotelRead?.cancel()
        shiftRead?.cancel()
        loading = true
        error = null
        permissions = null
        shift = ShiftIndicator.Hidden
        hotelRead = scope.launch { hotelName = readHotelName() }
        load =
            scope.launch {
                try {
                    val me = connection.me()
                    val date = connection.businessDate()
                    userName = me.name
                    areas = visibleAreas(me.permissions, IMPLEMENTED_AREAS, devMode)
                    val read = JavaSet.copyOf(me.permissions)
                    businessDate = DateInput.format(date)
                    restoreArea?.let { selected = it }
                    restoreArea = null
                    if (selected !in openableAreas()) selected = null
                    // Por último: nenhuma falha da carga deixa o conjunto ao lado do `error`.
                    permissions = read
                    readShift(read)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Cancelamento desta carga se propaga; um vindo da chamada (tempo esgotado) é falha comum.
                    currentCoroutineContext().ensureActive()
                    permissions = null
                    error = e as? ApiError ?: ApiError.Network(e)
                }
                loading = false
            }
    }

    /** Fim da casca (logout, sessão encerrada): cancela as leituras em voo e não aceita outro `reload`. */
    fun close() {
        closed = true
        load?.cancel()
        hotelRead?.cancel()
        shiftRead?.cancel()
    }

    /**
     * Relê o turno do topo (a área de caixa chama depois de abrir ou fechar um turno); a leitura anterior em voo é
     * cancelada. Não faz nada depois do [close], antes do conjunto ou sem `billing.cashier.operate` nele.
     */
    fun refreshShift() {
        if (closed) return
        readShift(permissions ?: return)
    }

    /** Só áreas com tela podem ser abertas; as "(em breve)" ficam só no menu. */
    fun select(area: Area) {
        if (area in openableAreas()) selected = area
    }

    private fun openableAreas() = areas.filter { (_, implemented) -> implemented }.map { it.first }

    private fun readShift(read: Set<String>) {
        val cashier = connection.cashierSource()
        if (CASHIER_OPERATE !in read || cashier == null) return
        shiftRead?.cancel()
        shiftRead =
            scope.launch {
                val value =
                    try {
                        cashier.currentShift()?.let { ShiftIndicator.Open(it.number, it.cashPointName) }
                            ?: ShiftIndicator.None
                    } catch (
                        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                    ) {
                        currentCoroutineContext().ensureActive()
                        ShiftIndicator.Unknown
                    }
                // A resposta de uma leitura cancelada (reload, close ou outra releitura) não sobrescreve o valor.
                currentCoroutineContext().ensureActive()
                shift = value
            }
    }

    private suspend fun readHotelName(): String =
        try {
            val hotel = connection.hotel()
            hotel.tradeName ?: if (hotel.configured) UNKNOWN_HOTEL else UNCONFIGURED_HOTEL
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            UNKNOWN_HOTEL
        }
}
