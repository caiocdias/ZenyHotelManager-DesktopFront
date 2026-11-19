package com.zeny.desktop.seed.folio

import com.zeny.desktop.core.error.ApiError

data class PostedConsumption(
    val folioId: String,
    val codeId: String,
    val unitPrice: String,
    val description: String,
)

/** Falso em memória da porta de contas: reservas por `externalId`, uma conta por reserva e os lançamentos feitos. */
class FakeFolioGateway(
    val reservations: MutableMap<String, String> = mutableMapOf(),
    val folios: MutableMap<String, String> = mutableMapOf(),
    var codeId: String? = "code-ab",
) : FolioGateway {
    val postings = mutableListOf<PostedConsumption>()
    val failures = ArrayDeque<ApiError>()
    var acceptedNames: List<String> = emptyList()
        private set

    /** Planta uma reserva com a sua conta, como o servidor deixa após o check-in. */
    fun plant(
        externalId: String,
        withFolio: Boolean = true,
    ) {
        val reservationId = "res-$externalId"
        reservations[externalId] = reservationId
        if (withFolio) folios[reservationId] = "folio-$externalId"
    }

    override suspend fun reservationId(externalId: String): String? = reservations[externalId]

    override suspend fun reservationFolioId(reservationId: String): String? = folios[reservationId]

    override suspend fun postingDescriptions(folioId: String): List<String> =
        postings.filter { it.folioId == folioId }.map { it.description }

    override suspend fun chargeableCodeId(names: List<String>): String? {
        acceptedNames = names
        return codeId
    }

    override suspend fun post(
        folioId: String,
        codeId: String,
        unitPrice: String,
        description: String,
    ) {
        failures.removeFirstOrNull()?.let { throw it }
        postings += PostedConsumption(folioId, codeId, unitPrice, description)
    }
}
