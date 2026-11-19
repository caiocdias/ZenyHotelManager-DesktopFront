package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STALE_TEXT =
    "Alguém alterou este registro. Recarregue e tente de novo. Os valores foram relidos; confira e salve de novo."
private const val UNKNOWN_TEXT =
    "Não foi possível confirmar a alteração. A conta foi relida; confira antes de salvar de novo."

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class FolioAccountFormStateTest {
    private var savedCalls = 0
    private var rereadCalls = 0

    private fun TestScope.form(
        source: FakeFolioAccountSource,
        canChoosePayer: Boolean = true,
    ) = FolioAccountFormState(
        source,
        backgroundScope,
        FOLIO_ID.toString(),
        canChoosePayer,
        onSaved = { savedCalls++ },
        onReread = { rereadCalls++ },
    ).also {
        it.start()
        runCurrent()
    }

    private fun money(text: String) = Money(BigDecimal(text))

    @Test
    fun startReadsTheFolioWithItsVersionAndThePayerName() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            assertEquals(1, source.folioCalls)
            assertEquals(listOf(PAYER_ID.toString()), source.companyCalls)
            assertEquals("Acme Turismo", form.payerName)
            assertEquals(CompanyChoice(PAYER_ID.toString(), "Acme Turismo"), form.payer)
            assertEquals("R$ 1.500,00", form.limitText)
            assertTrue(form.canSave)

            form.save()
            runCurrent()
            assertEquals("\"v1\"", source.updates.single().etag)
        }

    @Test
    fun payerNameFailureShowsTheGenericText() =
        runTest {
            val source =
                FakeFolioAccountSource().apply {
                    companyById = { throw ApiError.Problem(404, null, null, emptyList()) }
                }
            val form = form(source)

            assertEquals("Empresa pagadora definida", form.payerName)
            assertEquals(PAYER_ID.toString(), form.payer?.id)
            assertNull(form.message)
            assertTrue(form.canSave)
        }

    @Test
    fun changingOnlyTheLimitKeepsThePayer() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.limitText = "2.000,00"
            form.save()
            runCurrent()

            val sent = source.updates.single()
            assertEquals(PAYER_ID.toString(), sent.payerCompanyId)
            assertEquals(money("2000.00"), sent.creditLimit)
        }

    @Test
    fun emptyLimitIsNoLimit() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.limitText = ""
            form.save()
            runCurrent()

            assertNull(source.updates.single().creditLimit)
        }

    @Test
    fun negativeLimitIsRefusedLocally() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.limitText = "-10,00"
            form.save()
            runCurrent()

            assertTrue(source.updates.isEmpty())
            assertEquals("O limite não pode ser negativo.", form.message)
        }

    @Test
    fun choosingACompanySendsItsId() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)
            val companies = checkNotNull(form.companies)
            assertEquals(listOf("Viagens Sul"), companies.rows.map { it.name })

            form.choose(companies.rows.single())
            form.save()
            runCurrent()

            assertEquals(OTHER_PAYER_ID.toString(), source.updates.single().payerCompanyId)
        }

    @Test
    fun noPayerSendsNull() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.choose(null)
            form.save()
            runCurrent()

            assertNull(source.updates.single().payerCompanyId)
        }

    @Test
    fun searchSendsTheNameOnlyFromTwoCharactersAndCutsAt200() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)
            val companies = checkNotNull(form.companies)

            companies.query = "a"
            companies.search()
            runCurrent()
            companies.query = "x".repeat(250)
            companies.search()
            runCurrent()

            val names = source.companyPageCalls.map { it.first }
            assertEquals(listOf(null, null, "x".repeat(200)), names)
            assertTrue(source.companyPageCalls.all { it.third == 50 })
        }

    @Test
    fun loadMoreAppendsTheNextPage() =
        runTest {
            val source =
                FakeFolioAccountSource().apply {
                    companies = { _, cursor ->
                        if (cursor == null) {
                            Page(listOf(CompanyChoice("$PAYER_ID", "Acme Turismo")), "c2")
                        } else {
                            Page(listOf(CompanyChoice("$OTHER_PAYER_ID", "Viagens Sul")), null)
                        }
                    }
                }
            val form = form(source)
            val companies = checkNotNull(form.companies)
            assertTrue(companies.hasMore)

            companies.loadMore()
            runCurrent()

            assertEquals(listOf("Acme Turismo", "Viagens Sul"), companies.rows.map { it.name })
            assertFalse(companies.hasMore)
            assertEquals("c2", source.companyPageCalls.last().second)
        }

    @Test
    fun withoutCompanyReadThePayerStaysAndSaysSo() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source, canChoosePayer = false)

            assertNull(form.companies)
            assertEquals("Seu perfil não pode escolher a empresa pagadora.", form.payerHint)
            assertEquals("Empresa pagadora definida", form.payerName)
            form.choose(null)
            form.limitText = "300,00"
            form.save()
            runCurrent()

            assertTrue(source.companyCalls.isEmpty())
            assertTrue(source.companyPageCalls.isEmpty())
            assertEquals(PAYER_ID.toString(), source.updates.single().payerCompanyId)
            assertEquals(money("300.00"), source.updates.single().creditLimit)
        }

    @Test
    fun staleVersionTakesTheOtherStationsValues() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = {
                source.folio = { VersionedFolio(accountFolioOf(OTHER_PAYER_ID, "800.00"), "\"v9\"") }
                throw ApiError.PreconditionFailed(null)
            }
            source.companyById = { CompanyChoice(it, if (it == "$OTHER_PAYER_ID") "Viagens Sul" else "Acme Turismo") }
            val form = form(source)

            form.choose(null)
            form.limitText = "100,00"
            form.save()
            runCurrent()

            assertEquals(STALE_TEXT, form.message)
            assertEquals(2, source.folioCalls)
            assertEquals(1, rereadCalls)
            assertEquals(1, source.updates.size)
            assertEquals(0, savedCalls)
            assertEquals(CompanyChoice("$OTHER_PAYER_ID", "Viagens Sul"), form.payer)
            assertEquals("Viagens Sul", form.payerName)
            assertEquals("R$ 800,00", form.limitText)
            assertTrue(form.canSave)

            source.update = { VersionedFolio(accountFolioOf(OTHER_PAYER_ID, "800.00"), "\"v10\"") }
            form.save()
            runCurrent()
            assertEquals("\"v9\"", source.updates.last().etag)
            assertEquals("$OTHER_PAYER_ID", source.updates.last().payerCompanyId)
        }

    @Test
    fun staleVersionWithTheFolioClosedSaysItIsClosed() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = {
                val closedFolio = accountFolioOf(status = FolioResponse.Status.CLOSED)
                source.folio = { VersionedFolio(closedFolio, "\"v9\"") }
                throw ApiError.PreconditionFailed(null)
            }
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals("A conta já está fechada.", form.message)
            assertFalse(form.canSave)
            assertEquals(1, rereadCalls)
            assertEquals(1, source.updates.size)
        }

    @Test
    fun doubleSaveSendsOnce() =
        runTest {
            val source = FakeFolioAccountSource()
            val late = LateAnswer<VersionedFolio>()
            source.update = { late.await() }
            val form = form(source)

            form.save()
            form.save()
            runCurrent()
            form.save()
            runCurrent()

            assertEquals(1, source.updates.size)
            assertTrue(form.busy)
            assertFalse(form.canSave)
            late.answer(VersionedFolio(accountFolioOf(), "\"v2\""))
            runCurrent()
            assertEquals(1, savedCalls)
            assertEquals(1, source.updates.size)
        }

    @Test
    fun unknownResultRereadsAndSaysToCheck() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = {
                source.folio = { VersionedFolio(accountFolioOf(PAYER_ID, "2000.00"), "\"v2\"") }
                throw ApiError.Network(IOException("caiu"))
            }
            val form = form(source)

            form.limitText = "2.000,00"
            form.save()
            runCurrent()

            assertEquals(UNKNOWN_TEXT, form.message)
            assertEquals(2, source.folioCalls)
            assertEquals(1, rereadCalls)
            assertEquals(0, savedCalls)
            assertEquals("R$ 2.000,00", form.limitText)
            assertTrue(form.canSave)

            form.save()
            runCurrent()
            assertEquals("\"v2\"", source.updates.last().etag)
        }

    @Test
    fun companyInactiveShowsTheCatalogText() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = { throw ApiError.Problem(422, "COMPANY_INACTIVE", null, emptyList()) }
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals("A empresa está inativa.", form.message)
            assertEquals(0, savedCalls)
            assertEquals(0, rereadCalls)
            assertTrue(form.canSave)
        }

    @Test
    fun folioClosedShowsTheCatalogTextAndRereads() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = { throw ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList()) }
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals("A conta já está fechada.", form.message)
            assertEquals(1, rereadCalls)
            assertEquals(0, savedCalls)
        }

    @Test
    fun successCallsOnSavedAndCloses() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals(1, savedCalls)
            assertEquals(0, rereadCalls)
            assertFalse(form.canSave)
            form.save()
            runCurrent()
            assertEquals(1, source.updates.size)
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val source = FakeFolioAccountSource()
            val late = LateAnswer<VersionedFolio>()
            source.update = { late.await() }
            val form = form(source)

            form.save()
            runCurrent()
            form.close()
            late.answer(VersionedFolio(accountFolioOf(), "\"v2\""))
            runCurrent()

            assertEquals(0, savedCalls)
            assertEquals(0, rereadCalls)
            assertNull(form.message)
            assertFalse(form.canSave)

            val reading = LateAnswer<VersionedFolio>()
            val slow = FakeFolioAccountSource().apply { folio = { reading.await() } }
            val other = form(slow)
            other.close()
            reading.answer(VersionedFolio(accountFolioOf(OTHER_PAYER_ID, "9.00"), "\"v7\""))
            runCurrent()
            assertNull(other.payer)
            assertEquals("", other.limitText)
            assertFalse(other.canSave)
        }

    @Test
    fun toStringHidesTheValues() =
        runTest {
            val form = form(FakeFolioAccountSource())

            assertFalse("Acme" in form.toString())
            assertFalse("1.500" in form.toString())
        }
}
