package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1280
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val PAGE_SIZE = 50
private const val MAX_PAGES = 20
private const val STEP = "100.00"
private const val STALE_STEP = "1.00"
private const val FIRST_LIMIT = "500.00"
private const val CHECKED_OUT = "CHECKED_OUT"
private const val REOPEN_REASON = "Verificação ao vivo da M4 E13"
private const val APPROVAL_REASON = "Reabertura autorizada pelo Gerente na verificação ao vivo"
private const val NO_OPEN = "NEED_SEED: nenhuma conta aberta de reserva; rode o demo-seed"
private const val NO_COMPANY = "NEED_SEED: nenhuma empresa ativa; rode o demo-seed"
private const val NO_CLOSED = "NEED_SEED: nenhuma conta encerrada; rode a LiveCheckOutCheck"

/**
 * Verificação ao vivo do pagador e limite (M4 E12) e da reabertura da conta (M4 E13) contra um servidor real (o
 * ambiente `zeny-front`), com a Recepcionista e a aprovação do Gerente, pelo `CoreConnection` real, pelos estados
 * reais (`FolioState`, `FolioAccountFormState`, `ReopenState`), pelo painel real e pelo `ApprovalCoordinator` real.
 *
 * 1. `changesPayerAndCreditLimit`: numa conta aberta de reserva, troca o limite (o atual mais R$ 100,00, ou R$ 500,00
 *    sem limite) e o pagador (uma empresa ativa que o servidor lista) pelo "Pagador e limite"; exige a conta relida
 *    pela porta e o diálogo reaberto com os valores novos; depois grava de novo pela porta (que muda a versão) e salva
 *    o formulário, que ainda guarda a versão antiga: exige o `412` em pt-BR e a conta relida com os valores da porta.
 * 2. `reopensAClosedFolioWithTheManagerApproval`: numa conta encerrada de reserva (a que a `LiveCheckOutCheck`
 *    deixa), pede "Reabrir conta" com um motivo, exige o pedido de aprovação no coordenador da sessão, aprova com o
 *    PIN do Gerente e exige a conta relida aberta e o aviso "Conta reaberta.". Se a Recepcionista reabre sem
 *    aprovação, registra isso na saída e confere só a reabertura.
 *
 * Nenhuma resposta do `sdk` é impressa, interpolada nem comparada inteira; só valores formatados e textos da tela.
 *
 * **Escreve na demo e não desfaz:** a primeira deixa a conta com o limite e o pagador novos; a segunda deixa a conta
 * encerrada reaberta. Por isso tem opt-in próprio: pulada sem `ZENY_LIVE_ACCOUNT=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`, `ZENY_LIVE_APPROVER_LOGIN` e `ZENY_LIVE_APPROVER_PIN` (só do
 * ambiente, nunca impressas). Sem conta aberta, sem empresa ativa ou sem conta encerrada, falha com `NEED_SEED`. Grava
 * `build/live/account.png`, `build/live/account-stale.png` e `build/live/reopen.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_ACCOUNT=1 ./gradlew :app:test --tests '*LiveFolioAccountCheck' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveFolioAccountCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** O Gerente que aprova a reabertura; o `toString` não mostra nada. */
    private class Approver(
        val login: String,
        val pin: String,
    ) {
        override fun toString(): String = "Approver(***)"
    }

    /** O que os passos usam: as portas, o painel montado e o coordenador de aprovação da sessão. */
    private class Screen(
        val source: FolioSource,
        val account: FolioAccountSource,
        val folioId: String,
    ) {
        lateinit var scope: CoroutineScope
        lateinit var approvals: ApprovalCoordinator
        var shown: FolioState? = null
        val folio: FolioState get() = checkNotNull(shown) { "o painel da conta não foi montado" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_ACCOUNT") == "1", "sem ZENY_LIVE_ACCOUNT=1: verificação real pulada")
    }

    @Test
    fun changesPayerAndCreditLimit() =
        withScreen({ source, _ -> openReservationFolio(source) }) { screen, _ ->
            val before = limitOf(screen)
            val newLimit = before?.let { it + BigDecimal(STEP) } ?: BigDecimal(FIRST_LIMIT)
            val company = changeThroughTheForm(screen, newLimit)
            requireSavedEverywhere(screen, newLimit, company)
            capture("account")
            requireStaleVersionRefused(screen, newLimit + BigDecimal(STALE_STEP), company)
            capture("account-stale")
        }

    @Test
    fun reopensAClosedFolioWithTheManagerApproval() =
        withScreen({ _, connection -> closedReservationFolio(connection) }) { screen, approver ->
            val holder = screen.folio
            assertTrue(holder.canReopen, "a Recepcionista não vê \"Reabrir conta\" (sem a permissão de reabrir)")
            runOnUiThread { holder.startReopen() }
            val form = assertNotNull(holder.reopen, "o diálogo de reabertura não abriu")
            runOnUiThread {
                form.reason = REOPEN_REASON
                form.confirm()
            }
            waitUntil("o pedido de aprovação da reabertura", LOAD_TIMEOUT_MILLIS) {
                screen.approvals.pending != null || holder.reopen == null || (!form.busy && form.message != null)
            }
            if (screen.approvals.pending != null) {
                approveAsTheManager(screen, approver, form)
            } else {
                println("Conta ao vivo: a Recepcionista reabre sem aprovação; confere só a reabertura")
            }
            assertNull(holder.reopen, "a reabertura falhou: ${form.message}")
            val reread = runBlocking { screen.account.versionedFolio(screen.folioId) }
            assertEquals(FolioResponse.Status.OPEN, reread.folio.status, "a conta relida não está aberta")
            assertEquals(FolioText.REOPEN_DONE, holder.notice, "o aviso da reabertura")
            waitUntil("a conta aberta na tela", LOAD_TIMEOUT_MILLIS) { holder.view?.closed == false }
            println("Conta ao vivo: conta encerrada reaberta com o aviso \"${FolioText.REOPEN_DONE}\"")
            capture("reopen")
        }

    /** O Gerente aprova o pedido do coordenador; a reabertura termina com o diálogo fechado. */
    private fun DesktopComposeUiTest.approveAsTheManager(
        screen: Screen,
        approver: Approver,
        form: ReopenState,
    ) {
        println("Conta ao vivo: a reabertura pediu a aprovação de um superior")
        runOnUiThread {
            screen.approvals.login = approver.login
            screen.approvals.pin = approver.pin
            screen.approvals.reason = APPROVAL_REASON
        }
        assertTrue(screen.approvals.dialog?.canConfirm == true, "o diálogo de aprovação não aceita o Gerente")
        runOnUiThread { screen.approvals.confirm() }
        waitUntil("a reabertura aprovada", LOAD_TIMEOUT_MILLIS) {
            screen.approvals.dialog?.message != null || screen.folio.reopen == null ||
                (!form.busy && form.message != null)
        }
        assertNull(screen.approvals.dialog?.message, "o Gerente foi recusado: ${screen.approvals.dialog?.message}")
    }

    /** Abre o diálogo, troca o limite e o pagador e salva; devolve a empresa escolhida. */
    private fun DesktopComposeUiTest.changeThroughTheForm(
        screen: Screen,
        newLimit: BigDecimal,
    ): CompanyChoice {
        val holder = screen.folio
        assertTrue(holder.canEditAccount, "a Recepcionista não vê \"Pagador e limite\" (sem gerir contas)")
        val form = openAccountForm(screen)
        val companies = assertNotNull(form.companies, "o diálogo não oferece as empresas (sem a permissão de lê-las)")
        waitUntil("as empresas ativas", LOAD_TIMEOUT_MILLIS) { companies.loaded || companies.error != null }
        val company =
            companies.rows.firstOrNull { it.id != form.payer?.id } ?: companies.rows.firstOrNull() ?: fail(NO_COMPANY)
        runOnUiThread {
            form.limitText = MoneyInput.format(Money.parse(newLimit.toPlainString()))
            form.choose(company)
            form.save()
        }
        waitUntil("o salvamento", LOAD_TIMEOUT_MILLIS) {
            holder.account == null || (!form.busy && form.message != null)
        }
        assertNull(holder.account, "o salvamento falhou: ${form.message}")
        assertEquals(FolioText.ACCOUNT_SAVED, holder.notice, "o aviso do salvamento")
        println("Conta ao vivo: limite e pagador trocados pelo formulário")
        return company
    }

    /** A conta relida pela porta e a tela (o cabeçalho e o diálogo reaberto) trazem o limite e o pagador novos. */
    private fun DesktopComposeUiTest.requireSavedEverywhere(
        screen: Screen,
        newLimit: BigDecimal,
        company: CompanyChoice,
    ) {
        val reread = runBlocking { screen.account.versionedFolio(screen.folioId) }.folio
        assertEquals(0, newLimit.compareTo(assertNotNull(limitIn(reread))), "o limite relido não é o novo")
        assertEquals(company.id, payerIn(reread), "o pagador relido não é o novo")
        val shownLimit = FolioText.money(newLimit.toPlainString())
        waitUntil("o limite novo na tela", LOAD_TIMEOUT_MILLIS) { screen.folio.view?.creditLimit == shownLimit }
        val form = openAccountForm(screen)
        assertEquals(company.name, form.payerName, "o diálogo não mostra o pagador novo")
        assertEquals(MoneyInput.format(Money.parse(newLimit.toPlainString())), form.limitText, "o limite do diálogo")
        println("Conta ao vivo: a conta relida e a tela mostram o limite $shownLimit e o pagador novos")
    }

    /**
     * Com o diálogo aberto (e a versão dele lida), grava de novo pela porta, o que muda a versão; salvar o formulário
     * leva a versão antiga e dá o `412`: o formulário mostra o texto em pt-BR e relê a conta com os valores da porta.
     */
    private fun DesktopComposeUiTest.requireStaleVersionRefused(
        screen: Screen,
        portLimit: BigDecimal,
        company: CompanyChoice,
    ) {
        val form = assertNotNull(screen.folio.account, "o diálogo não está aberto")
        runBlocking {
            val current = screen.account.versionedFolio(screen.folioId)
            screen.account.updateFolio(
                screen.folioId,
                current.etag,
                company.id,
                Money.parse(portLimit.toPlainString()),
            )
        }
        runOnUiThread { form.save() }
        val expected = FolioText.ACCOUNT_REREAD
        waitUntil("o 412 do formulário", LOAD_TIMEOUT_MILLIS) {
            !form.busy && form.message?.contains(expected) == true
        }
        assertEquals(MoneyInput.format(Money.parse(portLimit.toPlainString())), form.limitText, "o limite relido")
        assertEquals(company.name, form.payerName, "o pagador relido")
        assertTrue(!form.saved, "o formulário diz salvo depois do 412")
        val reread = runBlocking { screen.account.versionedFolio(screen.folioId) }.folio
        assertEquals(0, portLimit.compareTo(assertNotNull(limitIn(reread))), "o servidor perdeu a gravação da porta")
        println("Conta ao vivo: a versão antiga deu 412 e o formulário releu os valores do servidor")
    }

    /** Abre "Pagador e limite" e espera a leitura da conta; fecha o que estava aberto antes. */
    private fun DesktopComposeUiTest.openAccountForm(screen: Screen): FolioAccountFormState {
        val holder = screen.folio
        runOnUiThread { holder.dismissAccount() }
        runOnUiThread { holder.startAccount() }
        val form = assertNotNull(holder.account, "o diálogo \"Pagador e limite\" não abriu")
        waitUntil("a leitura da conta no diálogo", LOAD_TIMEOUT_MILLIS) { !form.loading && form.payerKnown }
        return form
    }

    private fun limitOf(screen: Screen): BigDecimal? =
        limitIn(runBlocking { screen.account.versionedFolio(screen.folioId) }.folio)

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        // o diálogo é uma raiz própria; a do setContent pode ficar com tamanho zero. Captura a raiz com área.
        val roots = onAllNodes(isRoot())
        val sized = roots.fetchSemanticsNodes().indexOfFirst { it.size.width > 0 && it.size.height > 0 }
        check(sized >= 0) { "nenhuma raiz com tamanho para capturar" }
        ImageIO.write(roots[sized].captureToImage().toAwtImage(), "png", file)
    }

    @Suppress("LongMethod") // as portas, os estados reais e o coordenador de aprovação, montados juntos
    private fun withScreen(
        pick: (FolioSource, CoreConnection) -> String,
        block: DesktopComposeUiTest.(Screen, Approver) -> Unit,
    ) = withViewer { connection, approver ->
        val source = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
        val account = checkNotNull(connection.folioAccountSource()) { "a conexão não tem a porta da conta" }
        val permissions = runBlocking { connection.me() }.permissions
        val screen = Screen(source, account, pick(source, connection))
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            setContent {
                screen.scope = rememberCoroutineScope()
                screen.approvals = remember { ApprovalCoordinator(connection::grant, emptyFlow(), screen.scope) }
                val state =
                    remember {
                        val wiring =
                            FolioWiring(
                                source,
                                permissions,
                                { screen.approvals.run(it) },
                                null,
                                null,
                                account = account,
                                approveReopen = { screen.approvals.run(it) },
                            )
                        FolioState(wiring, screen.scope, screen.folioId).also { it.start() }
                    }
                DisposableEffect(state) { onDispose { state.close() } }
                screen.shown = state
                ZenyTheme {
                    FolioPanel(state)
                    ApprovalHost(screen.approvals)
                }
            }
            try {
                waitUntil("a conta", LOAD_TIMEOUT_MILLIS) { screen.shown?.view != null }
                block(screen, approver)
            } finally {
                screen.shown?.close()
                screen.approvals.close()
            }
        }
    }

    private fun withViewer(block: (CoreConnection, Approver) -> Unit) {
        val url = ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
        val approver = Approver(env("ZENY_LIVE_APPROVER_LOGIN"), env("ZENY_LIVE_APPROVER_PIN"))
        val core = ZenyCore(url, MemorySettings())
        val connection = CoreConnection(core)
        try {
            runBlocking { connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD")) }
            block(connection, approver)
            assertIs<SessionState.Active>(connection.state.value, "a sessão da Recepcionista caiu")
        } finally {
            runCatching { runBlocking { connection.logout() } }
            connection.close()
        }
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")
}

/** A primeira conta aberta de reserva, percorrendo as páginas das abertas; sem nenhuma, `NEED_SEED`. */
private fun openReservationFolio(source: FolioSource): String =
    runBlocking {
        val pager = CursorPager(PAGE_SIZE) { cursor, limit -> source.openFolioPage(cursor, limit) }
        while (pager.hasMore) {
            pager.next().firstOrNull { it.owner.type == FolioOwnerDto.Type.RESERVATION }?.let {
                return@runBlocking it.id.toString()
            }
        }
        fail(NO_OPEN)
    }

/**
 * Uma conta encerrada de reserva, nas reservas com saída registrada (a `LiveCheckOutCheck` deixa uma); sem nenhuma,
 * `NEED_SEED`.
 */
private fun closedReservationFolio(connection: CoreConnection): String {
    val search = checkNotNull(connection.reservationSearchSource()) { "a conexão não tem a busca de reservas" }
    val folios = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
    return runBlocking {
        var cursor: String? = null
        repeat(MAX_PAGES) { _ ->
            val page = search.search(ReservationFilter(statuses = setOf(CHECKED_OUT)), cursor, PAGE_SIZE)
            for (item in page.items) {
                folios
                    .reservationFolios(item.id.toString())
                    .firstOrNull { folio -> folio.status == FolioResponse.Status.CLOSED }
                    ?.let { closed -> return@runBlocking closed.id.toString() }
            }
            cursor = page.nextCursor ?: fail(NO_CLOSED)
        }
        fail(NO_CLOSED)
    }
}

/** O limite de crédito da conta, nulo sem limite. */
private fun limitIn(folio: FolioResponse): BigDecimal? =
    (folio.creditLimit as? Option.Some)?.value?.let { BigDecimal(it) }

/** O id da empresa pagadora da conta, nulo sem pagador. */
private fun payerIn(folio: FolioResponse): String? = (folio.payerCompanyId as? Option.Some)?.value?.toString()
