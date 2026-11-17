package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationGuaranteeResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlin.uuid.Uuid

/** Uma chamada de `confirmReservation` registrada pelo falso; a chave da `Intent` é a que iria ao servidor. */
data class ConfirmCall(
    val id: String,
    val guarantee: String,
    val intentKey: String,
)

/**
 * Servidor de mentira para a confirmação da pré-reserva.
 *
 * - [details] (por id) e [confirmResponse] são programáveis; cada chamada fica registrada ([readCalls],
 *   [confirmCalls]).
 * - [confirmOutcomes] é o resultado de cada confirmação, na ordem (`null` = sucesso); sem fila, sucesso.
 * - [holdRead] e [holdConfirm] prendem a próxima resposta até o teste completar o portão.
 * - Como a porta real, garantia fora de `CARD` e `DEPOSIT` lança `IllegalArgumentException`.
 */
class FakeReservationConfirmSource : ReservationConfirmSource {
    val details = mutableMapOf<String, ReservationDetailResponse>()
    var confirmResponse: ReservationDetailResponse? = null
    val confirmOutcomes = ArrayDeque<Throwable?>()
    var readError: Exception? = null

    var holdRead = false
    val heldReads = mutableListOf<CompletableDeferred<Unit>>()
    var holdConfirm = false
    val heldConfirms = mutableListOf<CompletableDeferred<Unit>>()

    val readCalls = mutableListOf<String>()
    val confirmCalls = mutableListOf<ConfirmCall>()

    override suspend fun reservation(id: String): ReservationDetailResponse {
        readCalls += id
        val error = readError
        if (holdRead) CompletableDeferred<Unit>().also { heldReads += it }.await()
        error?.let { throw it }
        return checkNotNull(details[id]) { "Reserva $id fora do servidor de mentira" }
    }

    override suspend fun confirmReservation(
        id: String,
        guarantee: String,
        intent: Intent,
    ): ReservationDetailResponse {
        require(guarantee in setOf("CARD", "DEPOSIT")) { "garantia inválida" }
        confirmCalls += ConfirmCall(id, guarantee, intent.key)
        if (holdConfirm) CompletableDeferred<Unit>().also { heldConfirms += it }.await()
        confirmOutcomes.removeFirstOrNull()?.let { throw it }
        return checkNotNull(confirmResponse) { "Resposta da confirmação fora do servidor de mentira" }
    }
}

/** A reserva como o servidor a devolve para a confirmação: com a política de garantia de [guaranteeType]. */
@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun confirmDetail(
    id: Uuid,
    code: String = "R-1",
    status: String = "PROVISIONAL",
    guaranteeType: String = "CARD",
    requiredDeposit: String? = null,
    depositReceived: String? = null,
) = ReservationDetailResponse(
    bookingCode = "B-1",
    holderGuestId = Uuid.random(),
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = "2026-11-20",
            bookingId = Uuid.random(),
            cancellationTerms =
                CancellationTermsResponse(
                    24,
                    "Flexível",
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = code,
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = "2026-11-23",
            folioId = Uuid.random(),
            guarantee =
                if (depositReceived == null) {
                    Option.Undefined
                } else {
                    Option.Some(
                        ReservationGuaranteeResponse(
                            confirmedAt = "2026-11-11T12:00:00Z",
                            depositReceived = Option.Some(depositReceived),
                            method = "DEPOSIT",
                        ),
                    )
                },
            guaranteeTerms =
                GuaranteeTermsResponse(
                    depositPercent = if (guaranteeType == "DEPOSIT") Option.Some("30.00") else Option.Undefined,
                    dueDaysBefore = if (guaranteeType == "DEPOSIT") Option.Some(7) else Option.Undefined,
                    name = "Política",
                    policyId = Uuid.random(),
                    type = guaranteeType,
                    version = 1,
                ),
            guests = emptyList(),
            id = id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = Uuid.random(),
            requiredDeposit = if (requiredDeposit == null) Option.Undefined else Option.Some(requiredDeposit),
            roomTotal = "720.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = status,
            total = "720.00",
            version = 3,
        ),
    restrictionAlerts = emptyList(),
)
