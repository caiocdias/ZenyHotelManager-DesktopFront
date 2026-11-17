package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.ReservationChangeInput
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.CompletableDeferred

/** Uma chamada de `changeReservation` (a criação da ação) registrada pelo falso. */
data class ChangeCall(
    val id: String,
    val input: ReservationChangeInput,
    val etag: String,
)

/** Uma execução (`run`) de uma ação de `changeReservation`: o `etag` em uso e o token, se houve. */
data class ChangeRun(
    val etag: String,
    val token: String?,
)

private val CHANGE_SEGMENTS = setOf("LEISURE", "BUSINESS", "EVENT", "OTHER")

/**
 * Servidor de mentira para a alteração de reserva.
 *
 * - [versions] (por id), [businessDay], [quote] e [changeResponse] são programáveis.
 * - Cada chamada fica registrada ([versionCalls], [businessDayCalls], [quoteCalls], [changeCalls], [changeRuns]).
 * - [changeOutcomes] é o resultado de cada execução da alteração, na ordem (`null` = sucesso); sem fila, sucesso.
 * - [changeRequiresApproval]: a execução sem token falha com `ApprovalRequired`; a com token passa.
 * - [holdVersion], [holdQuote] e [holdChange] prendem a próxima resposta até o teste completar o portão.
 * - Como a porta real, segmento fora do contrato e `etag` em branco lançam `IllegalArgumentException`.
 */
class FakeReservationChangeSource : ReservationChangeSource {
    var businessDay: BusinessDay = BusinessDay(BusinessDate.parse("2026-11-13"), null)
    val versions = mutableMapOf<String, ReservationVersion>()
    var quote: QuoteResponse =
        QuoteResponse(
            adults = 2,
            arrival = "2026-11-20",
            channel = "DIRECT",
            childAges = emptyList(),
            departure = "2026-11-22",
            nights = 2,
            offers = emptyList(),
        )
    var changeResponse: ReservationDetailResponse? = null
    var changeRequiresApproval: ApiError.ApprovalRequired? = null
    val changeOutcomes = ArrayDeque<Throwable?>()

    var versionError: Exception? = null
    var businessDayError: Exception? = null
    var quoteError: Exception? = null

    var holdVersion = false
    val heldVersions = mutableListOf<CompletableDeferred<Unit>>()
    var holdQuote = false
    val heldQuotes = mutableListOf<CompletableDeferred<Unit>>()
    var holdChange = false
    val heldChanges = mutableListOf<CompletableDeferred<Unit>>()

    val versionCalls = mutableListOf<String>()
    var businessDayCalls = 0
        private set
    val quoteCalls = mutableListOf<QuoteCall>()
    val changeCalls = mutableListOf<ChangeCall>()
    val changeRuns = mutableListOf<ChangeRun>()

    override suspend fun businessDay(): BusinessDay {
        businessDayCalls++
        businessDayError?.let { throw it }
        return businessDay
    }

    override suspend fun reservationVersion(id: String): ReservationVersion {
        versionCalls += id
        val error = versionError
        if (holdVersion) CompletableDeferred<Unit>().also { heldVersions += it }.await()
        error?.let { throw it }
        return checkNotNull(versions[id]) { "Versão de $id fora do servidor de mentira" }
    }

    override suspend fun quote(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse {
        quoteCalls += QuoteCall(arrival, departure, adults, childAges)
        if (holdQuote) CompletableDeferred<Unit>().also { heldQuotes += it }.await()
        quoteError?.let { throw it }
        return quote
    }

    override fun changeReservation(
        id: String,
        input: ReservationChangeInput,
        etag: String,
    ): ApprovableAction<ReservationDetailResponse> {
        require(input.segment in CHANGE_SEGMENTS) { "segmento inválido" }
        require(etag.isNotBlank()) { "etag inválido" }
        changeCalls += ChangeCall(id, input, etag)
        return ApprovableAction { token ->
            changeRuns += ChangeRun(etag, token)
            val approval = changeRequiresApproval
            if (holdChange) CompletableDeferred<Unit>().also { heldChanges += it }.await()
            if (token == null && approval != null) throw approval
            changeOutcomes.removeFirstOrNull()?.let { throw it }
            ApiResponse(checkNotNull(changeResponse) { "Resposta da alteração fora do servidor de mentira" }, null, 200)
        }
    }
}
