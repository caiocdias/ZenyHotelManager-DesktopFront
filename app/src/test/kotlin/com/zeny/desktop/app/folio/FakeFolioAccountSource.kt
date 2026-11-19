package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

val PAYER_ID: Uuid = Uuid.parse("90000000-0000-0000-0000-000000000001")
val OTHER_PAYER_ID: Uuid = Uuid.parse("90000000-0000-0000-0000-000000000002")

/** A conta com o pagador [payer] e o limite [creditLimit], como o servidor a devolve. */
fun accountFolioOf(
    payer: Uuid? = PAYER_ID,
    creditLimit: String? = "1500.00",
    status: FolioResponse.Status = FolioResponse.Status.OPEN,
): FolioResponse =
    folioOf(creditLimit = creditLimit, status = status)
        .copy(payerCompanyId = payer?.let { Option.Some(it) } ?: Option.Some(null))

/** Uma chamada do `PUT`: a versão enviada e os dois campos. */
data class AccountUpdate(
    val folioId: String,
    val etag: String,
    val payerCompanyId: String?,
    val creditLimit: Money?,
)

/** Servidor de mentira do pagador e do limite: as respostas são lambdas trocáveis, e cada chamada é registrada. */
class FakeFolioAccountSource : FolioAccountSource {
    var folio: suspend () -> VersionedFolio = { VersionedFolio(accountFolioOf(), "\"v1\"") }
    var update: suspend (AccountUpdate) -> VersionedFolio = {
        VersionedFolio(accountFolioOf(it.payerCompanyId?.let(Uuid::parse), it.creditLimit?.toApi()), "\"v2\"")
    }
    var companies: suspend (name: String?, cursor: String?) -> Page<CompanyChoice> = { _, _ ->
        Page(listOf(CompanyChoice(OTHER_PAYER_ID.toString(), "Viagens Sul")), null)
    }
    var companyById: suspend (String) -> CompanyChoice = { CompanyChoice(it, "Acme Turismo") }

    var folioCalls = 0
    val updates = mutableListOf<AccountUpdate>()
    val companyPageCalls = mutableListOf<Triple<String?, String?, Int>>()
    val companyCalls = mutableListOf<String>()

    override suspend fun versionedFolio(folioId: String): VersionedFolio {
        folioCalls++
        return folio()
    }

    override suspend fun updateFolio(
        folioId: String,
        etag: String,
        payerCompanyId: String?,
        creditLimit: Money?,
    ): VersionedFolio {
        val call = AccountUpdate(folioId, etag, payerCompanyId, creditLimit)
        updates += call
        return update(call)
    }

    override suspend fun activeCompanyPage(
        name: String?,
        cursor: String?,
        limit: Int,
    ): Page<CompanyChoice> {
        companyPageCalls += Triple(name, cursor, limit)
        return companies(name, cursor)
    }

    override fun reopen(
        folioId: String,
        reason: String,
    ): ApprovableAction<FolioResponse> = error("não usado")

    override suspend fun company(companyId: String): CompanyChoice {
        companyCalls += companyId
        return companyById(companyId)
    }
}
