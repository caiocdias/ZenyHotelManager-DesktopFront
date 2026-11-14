package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.client.GuestsApi
import com.zeny.desktop.sdk.model.CreateGuestRequest
import com.zeny.desktop.sdk.model.GuestDocumentRequest
import com.zeny.desktop.sdk.model.GuestDocumentSearchRequest
import com.zeny.desktop.sdk.model.GuestProfileRequest
import com.zeny.desktop.sdk.model.GuestResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Tipos de documento que dispensam o país emissor (o servidor os trata como brasileiros). */
private val TYPES_WITHOUT_ISSUING_COUNTRY = setOf("CPF", "RG", "CNH", "RNM")

/**
 * Dados mínimos para cadastrar um hóspede. É dado pessoal: o `toString` não mostra nada, e o número do documento só
 * vai no corpo da requisição.
 */
data class NewGuest(
    val fullName: String,
    val documentType: String,
    val documentNumber: String,
    val issuingCountry: String?,
    val nationality: String,
    val birthDate: BusinessDate,
) {
    override fun toString(): String = "NewGuest(***)"
}

/**
 * A leitura do perfil de um hóspede. O documento vem sempre mascarado pelo servidor. Um `id` que não é UUID lança
 * `IllegalArgumentException` antes de ir à rede, sem o `id` na mensagem.
 */
class GuestsFacade internal constructor(
    private val guests: GuestsApi,
    private val executor: ApiExecutor,
) {
    suspend fun guest(id: String): GuestResponse {
        val guestId = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de hóspede inválido")
        return executor.execute { guests.getGuest(guestId, it.headers) }.value
    }

    /** Uma página da busca por nome (o nome aparado); o servidor recusa nome curto demais. */
    suspend fun searchByName(
        name: String,
        limit: Int,
    ): List<GuestResponse> {
        val trimmed = name.trim()
        return executor.execute { guests.listGuests(name = trimmed, limit = limit, block = it.headers) }.value.items
    }

    /** Busca por documento: o número vai só no corpo (`POST`), nunca na URL. */
    suspend fun searchByDocument(
        type: String,
        number: String,
        issuingCountry: String?,
    ): List<GuestResponse> {
        val request =
            GuestDocumentSearchRequest(
                issuingCountry = issuingCountry?.let { Option.Some(it) } ?: Option.Undefined,
                number = number,
                type = GuestDocumentSearchRequest.Type.fromSerialOrNull(type) ?: throw invalidDocumentType(),
            )
        return executor.execute { guests.searchGuestsByDocument(request, it.headers) }.value.items
    }

    /** Cadastro mínimo: nome, nacionalidade, nascimento e um documento; sem consentimentos nem dados sensíveis. */
    suspend fun create(input: NewGuest): GuestResponse {
        val country = input.issuingCountry?.takeIf { input.documentType !in TYPES_WITHOUT_ISSUING_COUNTRY }
        val request =
            CreateGuestRequest(
                documents =
                    listOf(
                        GuestDocumentRequest(
                            issuingCountry = country?.let { Option.Some(it) } ?: Option.Undefined,
                            number = input.documentNumber,
                            type =
                                GuestDocumentRequest.Type.fromSerialOrNull(input.documentType)
                                    ?: throw invalidDocumentType(),
                        ),
                    ),
                profile =
                    GuestProfileRequest(
                        fullName = input.fullName,
                        vip = false,
                        nationality = Option.Some(input.nationality),
                        birthDate = Option.Some(input.birthDate.toApi()),
                    ),
            )
        return executor.execute { guests.createGuest(request, it.headers) }.value
    }

    private fun invalidDocumentType() = IllegalArgumentException("tipo de documento inválido")
}
