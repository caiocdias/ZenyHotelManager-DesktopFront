package com.zeny.desktop.seed.folio

/** Contas das reservas e lançamentos no servidor. Erros sobem como `ApiError`; as listas vêm inteiras. */
interface FolioGateway {
    /** O id da reserva de exemplo pelo `externalId`, ou `null` se ela não existe. */
    suspend fun reservationId(externalId: String): String?

    /** O id da conta aberta da reserva, ou `null` se ela não tem uma. */
    suspend fun reservationFolioId(reservationId: String): String?

    /** As descrições de todos os lançamentos da conta. */
    suspend fun postingDescriptions(folioId: String): List<String>

    /** O id do código de lançamento ativo, com taxa de serviço, de um dos nomes dados; `null` se nenhum serve. */
    suspend fun chargeableCodeId(names: List<String>): String?

    /** Lança um consumo de quantidade 1 na conta, com `Idempotency-Key` própria. */
    suspend fun post(
        folioId: String,
        codeId: String,
        unitPrice: String,
        description: String,
    )
}
