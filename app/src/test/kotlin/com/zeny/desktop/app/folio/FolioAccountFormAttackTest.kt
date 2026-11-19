package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STALE_SERVER_TEXT = "Alguém alterou este registro. Recarregue e tente de novo."
private const val UNCONFIRMED_START = "Não foi possível confirmar a alteração."

/**
 * Ataque do testador de etapa (M4 E12) ao `FolioAccountFormState`: recusas que só mostram o catálogo, a releitura que
 * falha depois do `412` e do resultado desconhecido, a conta ilegível ou encerrada, o limite zero ou ilegível, a lista
 * de empresas vazia, com falha e com resposta atrasada, e a troca de sessão com o formulário aberto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por ataque
class FolioAccountFormAttackTest {
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

    @Test
    fun unknownCompanyShowsTheCatalogTextWithoutRereading() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = { throw ApiError.Problem(422, "UNKNOWN_COMPANY", null, emptyList()) }
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals("A empresa informada não existe.", form.message)
            assertEquals(0, savedCalls)
            assertEquals(0, rereadCalls)
            assertEquals(1, source.folioCalls)
            assertTrue(form.canSave)
            assertEquals(1, source.updates.size)
        }

    /** Critério 1: se a releitura depois do `412` falha, o aviso de que outra estação mudou a conta não some. */
    @Test
    fun failedRereadAfterStaleVersionKeepsTheStaleNotice() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = {
                source.folio = { throw ApiError.Network(IOException("caiu")) }
                throw ApiError.PreconditionFailed(null)
            }
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals(1, rereadCalls)
            assertEquals(0, savedCalls)
            assertFalse(form.canSave, "sem a versão relida, salvar de novo repetiria a versão velha")
            assertTrue(
                form.message.orEmpty().contains(STALE_SERVER_TEXT),
                "o aviso do 412 sumiu; a mensagem é: ${form.message}",
            )
        }

    /** Sem `Idempotency-Key`: se a releitura depois do resultado desconhecido falha, o aviso de conferir não some. */
    @Test
    fun failedRereadAfterUnknownResultKeepsTheWarning() =
        runTest {
            val source = FakeFolioAccountSource()
            source.update = {
                source.folio = { throw ApiError.Network(IOException("caiu de novo")) }
                throw ApiError.Network(IOException("caiu"))
            }
            val form = form(source)

            form.save()
            runCurrent()

            assertEquals(1, rereadCalls)
            assertEquals(0, savedCalls)
            assertFalse(form.canSave, "sem a versão relida, salvar de novo repetiria a versão velha")
            assertTrue(
                form.message.orEmpty().contains(UNCONFIRMED_START),
                "o aviso do resultado desconhecido sumiu; a mensagem é: ${form.message}",
            )
        }

    @Test
    fun failedStartBlocksSavingAndShowsTheError() =
        runTest {
            val source = FakeFolioAccountSource().apply { folio = { throw ApiError.Network(IOException("caiu")) } }
            val form = form(source)

            assertFalse(form.canSave)
            assertNotNull(form.message)
            assertNull(form.payer)
            form.save()
            runCurrent()
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun closedFolioReadBlocksSaving() =
        runTest {
            val source =
                FakeFolioAccountSource().apply {
                    folio = { VersionedFolio(accountFolioOf(status = FolioResponse.Status.CLOSED), "\"v1\"") }
                }
            val form = form(source)

            assertFalse(form.canSave)
            form.save()
            runCurrent()
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun zeroLimitIsSentAsZeroNotAsNoLimit() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.limitText = "0,00"
            form.save()
            runCurrent()

            val sent = assertNotNull(source.updates.single().creditLimit, "limite zero virou \"Sem limite\"")
            assertEquals(0, sent.amount.signum())
            assertEquals(1, savedCalls)
        }

    @Test
    fun invalidLimitTextGoesNowhere() =
        runTest {
            val source = FakeFolioAccountSource()
            val form = form(source)

            form.limitText = "mil reais"
            form.save()
            runCurrent()

            assertTrue(source.updates.isEmpty())
            assertNotNull(form.message)
            assertEquals(0, savedCalls)
        }

    @Test
    fun unreadableServerLimitIsShownAsIsAndRefusedOnSave() =
        runTest {
            val source =
                FakeFolioAccountSource().apply {
                    folio = { VersionedFolio(accountFolioOf(creditLimit = "mil"), "\"v1\"") }
                }
            val form = form(source)
            assertEquals("mil", form.limitText)

            form.save()
            runCurrent()

            assertTrue(source.updates.isEmpty(), "o limite ilegível do servidor foi reenviado como outro valor")
            assertNotNull(form.message)
        }

    @Test
    fun emptyCompanyListIsLoadedWithoutRows() =
        runTest {
            val source = FakeFolioAccountSource().apply { companies = { _, _ -> Page(emptyList(), null) } }
            val companies = checkNotNull(form(source).companies)

            assertTrue(companies.loaded)
            assertTrue(companies.rows.isEmpty())
            assertFalse(companies.hasMore)
            assertNull(companies.error)
        }

    @Test
    fun companyListFailureRetriesTheSameCursorWithoutDuplicates() =
        runTest {
            var fail = true
            val source =
                FakeFolioAccountSource().apply {
                    companies = { _, cursor ->
                        when {
                            cursor == null -> Page(listOf(CompanyChoice("$PAYER_ID", "Acme Turismo")), "c2")
                            fail -> throw ApiError.Network(IOException("caiu"))
                            else -> Page(listOf(CompanyChoice("$OTHER_PAYER_ID", "Viagens Sul")), null)
                        }
                    }
                }
            val companies = checkNotNull(form(source).companies)

            companies.loadMore()
            runCurrent()
            assertNotNull(companies.error)
            assertEquals(listOf("Acme Turismo"), companies.rows.map { it.name })

            fail = false
            companies.loadMore()
            runCurrent()

            assertNull(companies.error)
            assertEquals(listOf("Acme Turismo", "Viagens Sul"), companies.rows.map { it.name })
            assertEquals(listOf(null, "c2", "c2"), source.companyPageCalls.map { it.second })
        }

    @Test
    fun lateAnswerOfAnOldSearchIsDropped() =
        runTest {
            val late = LateAnswer<Page<CompanyChoice>>()
            val source =
                FakeFolioAccountSource().apply {
                    companies = { name, _ ->
                        if (name == null) {
                            late.await()
                        } else {
                            Page(listOf(CompanyChoice("$OTHER_PAYER_ID", "Viagens Sul")), null)
                        }
                    }
                }
            val companies = checkNotNull(form(source).companies)
            assertTrue(late.asked)

            companies.query = "viagens"
            companies.search()
            runCurrent()
            late.answer(Page(listOf(CompanyChoice("$PAYER_ID", "Acme Turismo")), "c2"))
            runCurrent()

            assertEquals(listOf("Viagens Sul"), companies.rows.map { it.name })
            assertFalse(companies.hasMore)
            assertFalse(companies.loading)
        }

    @Test
    fun choosingWhileTheFolioIsBeingReadIsIgnored() =
        runTest {
            val reading = LateAnswer<VersionedFolio>()
            val source = FakeFolioAccountSource().apply { folio = { reading.await() } }
            val form = form(source)

            form.choose(CompanyChoice("$OTHER_PAYER_ID", "Viagens Sul"))
            form.limitText = "1,00"
            reading.answer(VersionedFolio(accountFolioOf(), "\"v1\""))
            runCurrent()

            assertEquals(PAYER_ID.toString(), form.payer?.id)
            assertEquals("R$ 1.500,00", form.limitText)
        }

    @Test
    fun sessionChangeWithTheFormOpenDropsTheLateAnswer() =
        runTest {
            val folios = FakeFolioSource().apply { folio = { accountFolioOf() } }
            val late = LateAnswer<VersionedFolio>()
            val account = FakeFolioAccountSource().apply { update = { late.await() } }
            val wiring =
                FolioWiring(
                    folios,
                    setOf("billing.folios.read", "billing.folios.manage", "guests.companies.read"),
                    { it.run(null) },
                    null,
                    null,
                    account = account,
                )
            val panel = FolioState(wiring, backgroundScope, "$FOLIO_ID").also { it.start() }
            runCurrent()
            panel.startAccount()
            runCurrent()
            val open = checkNotNull(panel.account)
            open.save()
            runCurrent()
            val readsBefore = folios.folioCalls.size

            panel.close()
            late.answer(VersionedFolio(accountFolioOf(), "\"v2\""))
            runCurrent()

            assertNull(panel.notice)
            assertEquals(readsBefore, folios.folioCalls.size)
            assertFalse(open.saved)
            assertEquals(1, account.updates.size)
        }
}
