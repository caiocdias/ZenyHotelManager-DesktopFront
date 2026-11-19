package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.client.GuestsApi
import com.zeny.desktop.sdk.model.CompanyResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

private const val DEFAULT_PAGE_SIZE = 50

/** O contrato só aceita o filtro `name` com 2 caracteres ou mais. */
private const val MIN_NAME_LENGTH = 2

/** A empresa como a tela a escolhe: o id e o nome (o fantasia ou, sem ele, a razão social). O CNPJ não é guardado. */
data class CompanyChoice(
    val id: String,
    val name: String,
)

/** As empresas, só leitura, para escolher o pagador da conta (`guests.companies.read`). */
class CompaniesFacade internal constructor(
    private val guests: GuestsApi,
    private val executor: ApiExecutor,
) {
    /** Uma página das empresas ativas; `name` com menos de 2 caracteres não vai (lista sem filtro). */
    suspend fun activeCompanyPage(
        name: String?,
        cursor: String?,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): Page<CompanyChoice> {
        val filter = name?.trim()?.takeIf { it.length >= MIN_NAME_LENGTH }
        val page =
            executor
                .execute {
                    guests.listCompanies(
                        name = filter,
                        active = true,
                        cursor = cursor,
                        limit = limit,
                        block = it.headers,
                    )
                }.value
        val next = (page.nextCursor as? Option.Some)?.value?.takeIf { it.isNotBlank() }
        return Page(page.items.map { it.toChoice() }, next)
    }

    /** A empresa pelo id (`getCompany`); um id que não é UUID lança `IllegalArgumentException` antes da rede. */
    suspend fun company(companyId: String): CompanyChoice {
        val id = Uuid.parseOrNull(companyId) ?: throw IllegalArgumentException("id de empresa inválido")
        return executor.execute { guests.getCompany(id, it.headers) }.value.toChoice()
    }

    private fun CompanyResponse.toChoice(): CompanyChoice {
        val trade = (tradeName as? Option.Some)?.value?.takeIf { it.isNotBlank() }
        return CompanyChoice(id.toString(), trade ?: legalName)
    }
}
