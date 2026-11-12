package com.zeny.desktop.app.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.input.DateInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val UNCONFIGURED_HOTEL = "Hotel ainda não configurado"
private const val UNKNOWN_HOTEL = "Hotel"

/**
 * Estado da casca: usuário e áreas (`me`), data operacional e nome do hotel, lidos ao nascer e a cada `reload`.
 * Falha em `me` ou na data vira `error` (com "Tentar de novo"); o nome do hotel é independente: leitura negada
 * (o `403` da Governanta) ou falha dá só o nome genérico, sem impedir a data nem o menu.
 */
class ShellState(
    private val connection: ServerConnection,
    private val devMode: Boolean,
    private val scope: CoroutineScope,
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

    private var job: Job? = null

    init {
        reload()
    }

    /** Relê tudo; uma leitura anterior ainda em voo é cancelada e não sobrescreve a nova. */
    fun reload() {
        job?.cancel()
        loading = true
        error = null
        job =
            scope.launch {
                try {
                    val me = connection.me()
                    val date = connection.businessDate()
                    userName = me.name
                    areas = visibleAreas(me.permissions, IMPLEMENTED_AREAS, devMode)
                    businessDate = DateInput.format(date)
                    if (selected !in openableAreas()) selected = null
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    error = e as? ApiError ?: ApiError.Network(e)
                }
                hotelName = readHotelName()
                loading = false
            }
    }

    /** Só áreas com tela podem ser abertas; as "(em breve)" ficam só no menu. */
    fun select(area: Area) {
        if (area in openableAreas()) selected = area
    }

    private fun openableAreas() = areas.filter { (_, implemented) -> implemented }.map { it.first }

    private suspend fun readHotelName(): String =
        try {
            val hotel = connection.hotel()
            hotel.tradeName ?: if (hotel.configured) UNKNOWN_HOTEL else UNCONFIGURED_HOTEL
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            UNKNOWN_HOTEL
        }
}
