package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money

/**
 * A porta do pagador e do limite da conta: a leitura da conta com o `ETag`, o `PUT` com `If-Match` (que substitui os
 * dois campos) e as empresas (a lista das ativas e uma por id). Um id que não é UUID, `etag` em branco ou limite
 * negativo lançam `IllegalArgumentException` antes da rede. O CNPJ das empresas não chega ao `app`.
 */
interface FolioAccountSource {
    suspend fun versionedFolio(folioId: String): VersionedFolio

    suspend fun updateFolio(
        folioId: String,
        etag: String,
        payerCompanyId: String?,
        creditLimit: Money?,
    ): VersionedFolio

    suspend fun activeCompanyPage(
        name: String?,
        cursor: String?,
        limit: Int = 50,
    ): Page<CompanyChoice>

    suspend fun company(companyId: String): CompanyChoice
}
