package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
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
private const val TAG_LENGTH = 8
private const val OPEN_PAGE_SIZE = 50
private const val UNIT_PRICE = "10,00"
private const val REVERSAL_REASON = "Verificação ao vivo da M4 E02"
private const val APPROVAL_REASON = "Estorno autorizado pelo Gerente na verificação ao vivo"
private const val POSTINGS_CREATE = "billing.postings.create"
private const val POSTINGS_REVERSE = "billing.postings.reverse"
private const val NO_FOLIO = "NEED_SEED: nenhuma conta aberta de reserva; rode o demo-seed"
private const val NO_CODE = "NEED_SEED: nenhum código ativo de consumo com taxa de serviço; rode o demo-seed"
private val BETWEEN_DIGITS = Regex("(?<=\\d)[.,](?=\\d)")

/**
 * Verificação da conta da hospedagem contra um servidor real (o ambiente `zeny-front`), com a Recepcionista e a
 * aprovação do Gerente, pelo `CoreConnection` real, pelos estados reais (`FolioState`, `PostingFormState`,
 * `ReversalState`), pelo painel real e pelo `ApprovalCoordinator` real. Numa conta aberta de reserva do seed:
 * - lança um consumo com um código que tem taxa de serviço, com dois "Lançar" seguidos, e exige um item e uma taxa a
 *   mais, com o saldo e a taxa da tela iguais aos de `folio()` e `postings()` relidos;
 * - estorna o item como a Recepcionista, exige que a aprovação foi pedida, aprova como o Gerente e exige o estorno
 *   como lançamento novo, com o original intacto;
 * - lê o extrato `GUEST` (nenhuma linha com o bloco interno) e o `INTERNAL`, com o saldo igual ao da conta;
 * - baixa o extrato em PDF das duas audiências e exige, pelo texto do PDFBox (só em memória, nunca em log), os
 *   dígitos do saldo da conta.
 *
 * **Escreve na demo e não desfaz:** cada execução deixa na conta um consumo de R$ 10,00 com a taxa de serviço dele e
 * o estorno do consumo (a taxa fica como o servidor decidir). Por isso tem opt-in próprio: pulada sem
 * `ZENY_LIVE_FOLIO=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`,
 * `ZENY_LIVE_APPROVER_LOGIN` e `ZENY_LIVE_APPROVER_PIN` (só do ambiente, nunca impressas). Grava
 * `build/live/folio.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_FOLIO=1 ./gradlew :app:test --tests '*LiveFolioCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveFolioCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** O Gerente que aprova o estorno; o `toString` não mostra nada. */
    private class Approver(
        val login: String,
        val pin: String,
    ) {
        override fun toString(): String = "Approver(***)"
    }

    private class Screen(
        val source: FolioSource,
        val folioId: String,
    ) {
        lateinit var scope: CoroutineScope
        lateinit var approvals: ApprovalCoordinator
        var shown by mutableStateOf<FolioState?>(null)
        val folio: FolioState get() = checkNotNull(shown) { "o painel da conta não foi montado" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_FOLIO") == "1", "sem ZENY_LIVE_FOLIO=1: verificação real pulada")
    }

    @Test
    fun postReverseStatementAndPdf() =
        withScreen { screen, approver ->
            val item = postTwice(screen)
            reverseWithApproval(screen, approver, item)
            statements(screen)
            pdfs(screen)
            capture("folio")
        }

    /** Dois "Lançar" seguidos: um item e a taxa dele a mais; saldo e taxa da tela iguais aos relidos. */
    private fun DesktopComposeUiTest.postTwice(screen: Screen): PostingResponse {
        val folio = screen.folio
        val before = runBlocking { screen.source.postings(screen.folioId) }
        val code = serviceChargeCode(screen.source)
        val description = "Verificação ao vivo E02 ${UUID.randomUUID().toString().take(TAG_LENGTH)}"
        assertTrue(folio.canPost, "a Recepcionista não vê \"Lançar\" (sem $POSTINGS_CREATE ou conta encerrada)")
        runOnUiThread { folio.startPosting() }
        val form = assertNotNull(folio.posting, "o diálogo de lançamento não abriu")
        waitUntil("os códigos de lançamento", LOAD_TIMEOUT_MILLIS) { !form.codesLoading }
        assertNull(form.codesError, "a leitura dos códigos falhou: ${form.codesError}")
        runOnUiThread {
            form.codeId = code.id.toString()
            form.unitPrice = UNIT_PRICE
            form.description = description
        }
        assertEquals(code.id.toString(), form.codeId, "o código com taxa de serviço não está entre os oferecidos")
        runOnUiThread {
            form.confirm()
            form.confirm()
        }
        waitUntil("o lançamento", LOAD_TIMEOUT_MILLIS) { folio.posting == null || (!form.busy && form.message != null) }
        assertNull(folio.posting, "o lançamento falhou: ${form.message}")
        waitUntil("a conta relida", LOAD_TIMEOUT_MILLIS) {
            !folio.loading && folio.postings.any { it.description == description }
        }

        val after = runBlocking { screen.source.postings(screen.folioId) }
        val reread = runBlocking { screen.source.folio(screen.folioId) }
        assertEquals(before.size + 2, after.size, "dois \"Lançar\" seguidos não deram um item e uma taxa")
        val item = after.single { it.description == description }
        val charge =
            after.singleOrNull { (it.serviceChargeForPostingId as? Option.Some)?.value == item.id }
                ?: fail("o consumo lançado não trouxe a taxa de serviço")
        assertEquals(FolioText.money(reread.balance), folio.view?.balance, "o saldo da tela não é o do servidor")
        val chargeRow = folio.postings.single { it.id == charge.id.toString() }
        assertEquals(FolioText.money(charge.amount), chargeRow.amount, "a taxa da tela não é a do servidor")
        assertTrue(chargeRow.serviceCharge, "a taxa não aparece como taxa de serviço")
        println(
            "LiveFolioCheck: conta ${reread.number}: item ${FolioText.money(item.amount)} e taxa " +
                "${chargeRow.amount}; saldo ${FolioText.money(reread.balance)}",
        )
        return item
    }

    /** O estorno pela Recepcionista pede aprovação; o Gerente aprova; o original fica e o estorno é um lançamento. */
    private fun DesktopComposeUiTest.reverseWithApproval(
        screen: Screen,
        approver: Approver,
        item: PostingResponse,
    ) {
        val folio = screen.folio
        val row = folio.postings.single { it.id == item.id.toString() }
        assertTrue(folio.canReverse(row), "a Recepcionista não vê \"Estornar\" no consumo lançado")
        runOnUiThread { folio.startReversal(row.id) }
        val form = assertNotNull(folio.reversal, "o diálogo de estorno não abriu")
        runOnUiThread {
            form.reason = REVERSAL_REASON
            form.confirm()
        }
        waitUntil("o pedido de aprovação do estorno", LOAD_TIMEOUT_MILLIS) {
            screen.approvals.pending != null || folio.reversal == null || (!form.busy && form.message != null)
        }
        val required =
            screen.approvals.pending
                ?: fail(
                    "o estorno não pediu aprovação (${form.message}); a Recepcionista não devia ter $POSTINGS_REVERSE",
                )
        assertEquals(POSTINGS_REVERSE, required.permission, "a permissão pedida no APPROVAL_REQUIRED")
        runOnUiThread {
            screen.approvals.login = approver.login
            screen.approvals.pin = approver.pin
            screen.approvals.reason = APPROVAL_REASON
        }
        assertTrue(screen.approvals.dialog?.canConfirm == true, "o diálogo de aprovação não aceita o Gerente")
        runOnUiThread { screen.approvals.confirm() }
        waitUntil("o estorno aprovado", LOAD_TIMEOUT_MILLIS) {
            screen.approvals.dialog?.message != null || folio.reversal == null || (!form.busy && form.message != null)
        }
        assertNull(screen.approvals.dialog?.message, "o Gerente foi recusado: ${screen.approvals.dialog?.message}")
        assertNull(folio.reversal, "o estorno aprovado falhou: ${form.message}")
        waitUntil("a conta relida depois do estorno", LOAD_TIMEOUT_MILLIS) {
            !folio.loading && folio.postings.any { it.id == item.id.toString() && it.reversed }
        }

        val after = runBlocking { screen.source.postings(screen.folioId) }
        val original = after.single { it.id == item.id }
        assertEquals(item.amount, original.amount, "o valor do original mudou")
        assertEquals(item.description, original.description, "a descrição do original mudou")
        val reversalId = (original.reversedBy as? Option.Some)?.value ?: fail("o original não aponta o estorno")
        val reversal = after.single { it.id == reversalId }
        assertEquals("REVERSAL", reversal.type, "o estorno não é um lançamento REVERSAL")
        assertTrue(folio.postings.any { it.id == reversalId.toString() }, "o estorno não aparece na tela")
        println("LiveFolioCheck: estorno aprovado pelo Gerente: ${FolioText.money(reversal.amount)}, original intacto")
    }

    /** O extrato `GUEST` sem o bloco interno e o `INTERNAL`, os dois com o saldo da conta, na tela e no servidor. */
    private fun DesktopComposeUiTest.statements(screen: Screen) {
        val folio = screen.folio
        val balance = runBlocking { screen.source.folio(screen.folioId) }.balance
        val guest = runBlocking { screen.source.statement(screen.folioId, StatementAudience.GUEST) }
        val withInternal = guest.days.flatMap { it.lines }.count { (it.`internal` as? Option.Some)?.value != null }
        assertEquals(0, withInternal, "o extrato do hóspede traz o bloco interno")
        assertEquals(balance, guest.balance, "o saldo do extrato do hóspede não é o da conta")
        val internal = runBlocking { screen.source.statement(screen.folioId, StatementAudience.INTERNAL) }
        assertEquals(balance, internal.balance, "o saldo do extrato interno não é o da conta")

        listOf(StatementAudience.GUEST, StatementAudience.INTERNAL).forEach { audience ->
            runOnUiThread {
                folio.audience = audience
                folio.showStatement()
            }
            waitUntil("o extrato ${audience.name} na tela", LOAD_TIMEOUT_MILLIS) {
                !folio.statementLoading && (folio.statement != null || folio.statementError != null)
            }
            val shown = assertNotNull(folio.statement, "o extrato não abriu: ${folio.statementError}")
            assertEquals(FolioText.money(balance), shown.balance, "o saldo do extrato na tela (${audience.name})")
            if (audience == StatementAudience.GUEST) {
                assertTrue(shown.days.flatMap { it.lines }.all { it.reason == null }, "o extrato do hóspede tem motivo")
            }
        }
        println("LiveFolioCheck: extratos GUEST e INTERNAL com o saldo da conta ${FolioText.money(balance)}")
    }

    /** O PDF das duas audiências (pelo adaptador do portador) traz os dígitos do saldo da conta. */
    private fun pdfs(screen: Screen) {
        val balance = runBlocking { screen.source.folio(screen.folioId) }.balance
        val digits = balance.filter(Char::isDigit)
        listOf(StatementAudience.GUEST, StatementAudience.INTERNAL).forEach { audience ->
            val pdf = runBlocking { screen.source.statementCards(audience).registrationCard(screen.folioId) }
            val text = Loader.loadPDF(pdf.bytes).use { PDFTextStripper().getText(it) }
            val joined = text.replace(BETWEEN_DIGITS, "")
            assertTrue(joined.contains(digits), "o PDF ${audience.name} não traz os dígitos do saldo da conta")
        }
        println("LiveFolioCheck: PDFs GUEST e INTERNAL com os dígitos do saldo ${FolioText.money(balance)}")
    }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen, Approver) -> Unit) =
        withViewer { connection, approver ->
            val source = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
            val permissions = runBlocking { connection.me() }.permissions
            val screen = Screen(source, openReservationFolio(source).id.toString())
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.approvals = remember { ApprovalCoordinator(connection::grant, emptyFlow(), screen.scope) }
                    val state =
                        remember {
                            val wiring = FolioWiring(source, permissions, { screen.approvals.run(it) }, null, null)
                            FolioState(wiring, screen.scope, screen.folioId).also { it.start() }
                        }
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

/** A primeira conta aberta de reserva, percorrendo as páginas das abertas pelo `CursorPager`. */
private fun openReservationFolio(source: FolioSource): FolioResponse =
    runBlocking {
        val pager = CursorPager(OPEN_PAGE_SIZE) { cursor, limit -> source.openFolioPage(cursor, limit) }
        while (pager.hasMore) {
            pager.next().firstOrNull { it.owner.type == FolioOwnerDto.Type.RESERVATION }?.let { return@runBlocking it }
        }
        fail(NO_FOLIO)
    }

/** Um código ativo de consumo (oferecido em "Lançar") que leva taxa de serviço. */
private fun serviceChargeCode(source: FolioSource): TransactionCodeResponse =
    runBlocking { source.transactionCodes() }.firstOrNull {
        it.active && it.serviceChargeApplies && it.kind == TransactionCodeResponse.Kind.CHARGE
    } ?: fail(NO_CODE)
