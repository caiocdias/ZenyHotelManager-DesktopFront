package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse

/**
 * A porta da conta em si: a leitura com o `ETag`, o `PUT` do pagador e do limite com `If-Match` (que substitui os
 * dois campos), as empresas (a lista das ativas e uma por id) e a reabertura da conta encerrada, que pode pedir
 * aprovação e não leva `Idempotency-Key`. Um id que não é UUID, `etag` em branco, limite negativo ou motivo da
 * reabertura vazio ou acima de 500 caracteres lançam `IllegalArgumentException` antes da rede. O CNPJ das
 * empresas não chega ao `app`.
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

    /** A ação que reabre a conta encerrada com o [reason] aparado; `run(null)` tenta e `run(token)` repete aprovada. */
    fun reopen(
        folioId: String,
        reason: String,
    ): ApprovableAction<FolioResponse>
}
