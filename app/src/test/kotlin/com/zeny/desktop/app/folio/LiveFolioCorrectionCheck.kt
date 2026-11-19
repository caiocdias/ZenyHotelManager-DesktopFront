package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.zeny.desktop.core.facade.PostingInput
import com.zeny.desktop.core.facade.SplitPart
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import com.zeny.desktop.sdk.model.TransferResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
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
private const val UNIT_PRICE = "10.00"
private const val ADJUSTED = "1,00"
private const val SCREEN_ADJUSTMENT = "-R$ 1,00"
private const val ADJUSTMENT_REASON = "Verificação ao vivo da M4 E08"
private const val APPROVAL_REASON = "Ajuste autorizado pelo Gerente na verificação ao vivo"
private const val POSTINGS_ADJUST = "billing.postings.adjust"
private const val SERVICE_CHARGE_REMOVE = "billing.servicecharge.remove"
private const val NO_FOLIO = "NEED_SEED: menos de duas contas abertas de reserva; rode o demo-seed"
private const val HALF = "50"
private const val MAX_TARGET_PAGES = 20
private const val POSTINGS_TRANSFER = "billing.postings.transfer"
private const val NO_CODE = "NEED_SEED: nenhum código ativo de consumo com taxa de serviço; rode o demo-seed"
private const val NO_CHARGE = "NEED_SEED: o consumo lançado não gerou taxa de serviço; confira o código no demo-seed"

/**
 * Verificação das correções da conta contra um servidor real (o ambiente `zeny-front`), com a Recepcionista e a
 * aprovação do Gerente, pelo `CoreConnection` real, pelos estados reais (`FolioState`, `FolioCorrectionsState`,
 * `AdjustmentState`, `ServiceChargeRemovalState`), pelo painel real e pelo `ApprovalCoordinator` real. Numa conta
 * aberta de reserva do seed:
 * 1. lança um consumo de R$ 10,00 com um código que tem taxa de serviço (se o servidor o rotear, o resto segue na
 *    conta de destino) e exige a taxa dele logo ali, antes de escrever mais (sem ela, `NEED_SEED`);
 * 2. ajusta R$ 1,00 do consumo pelo `AdjustmentState`: exige o `APPROVAL_REQUIRED` (a Recepcionista não tem
 *    `billing.postings.adjust`), aprova como o Gerente e exige o ajuste com o `correctsId` do consumo, a linha
 *    "Corrige: <descrição>" na tela e o consumo intacto, com o valor dele (critério 1);
 * 3. exige que o estado oferece "Remover a taxa" na linha real da taxa (`canRemoveServiceCharge`), remove-a pelo
 *    `ServiceChargeRemovalState` e exige o estorno da taxa com o motivo que o servidor registrou, o mesmo do aviso da
 *    tela (critério 3);
 * 4. lança um segundo consumo com taxa na conta da tela, divide-o 50% / 50% com uma segunda conta aberta de reserva
 *    pelo `SplitState` (a segunda escolhida entre as contas de destino do diálogo) e exige, nas duas contas relidas, a
 *    saída, a entrada e as taxas que a API devolveu (critério 2);
 * 5. transfere o que resta dele para a segunda conta pelo `TransferState` e exige o mesmo, com a taxa sob o item na
 *    tabela de um `FolioState` real da segunda conta (critério 2).
 *
 * Nenhuma resposta do `sdk` é impressa, interpolada nem comparada inteira; só valores formatados, tipos e textos da
 * tela.
 *
 * **Escreve na demo e não desfaz:** cada execução deixa na conta um consumo de R$ 10,00 com a taxa de serviço dele, o
 * ajuste de R$ 1,00 (com o que acompanha a taxa), a remoção da taxa e um segundo consumo de R$ 10,00 dividido e
 * transferido para a segunda conta. Por isso tem opt-in próprio: pulada sem `ZENY_LIVE_CORRECTION=1`; com ele, exige
 * `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`, `ZENY_LIVE_APPROVER_LOGIN` e
 * `ZENY_LIVE_APPROVER_PIN` (só do ambiente, nunca impressas). Sem duas contas abertas de reserva ou sem o código com
 * taxa, falha com `NEED_SEED` antes de escrever. Grava `build/live/correction.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_CORRECTION=1 ./gradlew :app:test --tests '*LiveFolioCorrectionCheck'
 * --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveFolioCorrectionCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** O Gerente que aprova o ajuste; o `toString` não mostra nada. */
    private class Approver(
        val login: String,
        val pin: String,
    ) {
        override fun toString(): String = "Approver(***)"
    }

    /**
     * O consumo lançado no passo 1: a conta dele (a de destino, se o servidor rotear), o id, a descrição e a taxa de
     * serviço que ele gerou.
     */
    private class Item(
        val folioId: String,
        val id: String,
        val description: String,
        val amount: String,
        val chargeId: String,
    )

    /** A porta real das correções, guardando a última resposta de transferir e de dividir. */
    private class RecordingCorrections(
        private val inner: FolioCorrectionSource,
    ) : FolioCorrectionSource by inner {
        @Volatile
        var last: TransferResponse? = null

        override suspend fun transfer(
            folioId: String,
            postingIds: List<String>,
            targetFolioId: String,
            reason: String?,
            intent: Intent,
        ): TransferResponse = inner.transfer(folioId, postingIds, targetFolioId, reason, intent).also { last = it }

        override suspend fun split(
            folioId: String,
            postingId: String,
            parts: List<SplitPart>,
            reason: String?,
            intent: Intent,
        ): TransferResponse = inner.split(folioId, postingId, parts, reason, intent).also { last = it }
    }

    /** O que os passos usam: as portas, o consumo do passo 1, o código com taxa e a segunda conta de reserva. */
    private class Screen(
        val source: FolioSource,
        val corrections: RecordingCorrections,
        val item: Item,
        val code: TransactionCodeResponse,
        val secondId: String,
    ) {
        lateinit var scope: CoroutineScope
        lateinit var approvals: ApprovalCoordinator
        lateinit var wiring: FolioWiring
        var shown: FolioState? = null
        val folio: FolioState get() = checkNotNull(shown) { "o painel da conta não foi montado" }
        val holder: FolioCorrectionsState
            get() = checkNotNull(folio.corrections) { "o painel não tem as correções da conta" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_CORRECTION") == "1", "sem ZENY_LIVE_CORRECTION=1: verificação real pulada")
    }

    @Test
    fun adjustWithApprovalAndRemoveTheServiceCharge() =
        withScreen { screen, approver ->
            adjustWithApproval(screen, approver)
            removeTheServiceCharge(screen)
            val split = splitHalfWithTheSecond(screen)
            transferTheRestToTheSecond(screen, split)
            capture("correction")
        }

    /** Passo 2: R$ 1,00 do consumo pede aprovação; o Gerente aprova; o ajuste cita o consumo, que fica intacto. */
    private fun DesktopComposeUiTest.adjustWithApproval(
        screen: Screen,
        approver: Approver,
    ) {
        val holder = screen.holder
        val item = screen.item
        val row = rowIn(screen, item.id)
        assertTrue(holder.canAdjust(row), "a Recepcionista não vê \"Ajustar\" no consumo lançado")
        runOnUiThread { holder.startAdjustment(row) }
        val form = assertNotNull(holder.adjustment, "o diálogo de ajuste não abriu")
        assertNotNull(form.remaining, "o diálogo de ajuste não mostra o restante do servidor")
        runOnUiThread {
            form.amount = ADJUSTED
            form.reason = ADJUSTMENT_REASON
            form.confirm()
        }
        waitUntil("o pedido de aprovação do ajuste", LOAD_TIMEOUT_MILLIS) {
            screen.approvals.pending != null || holder.adjustment == null || (!form.busy && form.message != null)
        }
        val required =
            screen.approvals.pending
                ?: fail(
                    "o ajuste não pediu aprovação (${form.message}); a Recepcionista não devia ter $POSTINGS_ADJUST",
                )
        assertEquals(POSTINGS_ADJUST, required.permission, "a permissão pedida no APPROVAL_REQUIRED")
        runOnUiThread {
            screen.approvals.login = approver.login
            screen.approvals.pin = approver.pin
            screen.approvals.reason = APPROVAL_REASON
        }
        assertTrue(screen.approvals.dialog?.canConfirm == true, "o diálogo de aprovação não aceita o Gerente")
        runOnUiThread { screen.approvals.confirm() }
        waitUntil("o ajuste aprovado", LOAD_TIMEOUT_MILLIS) {
            screen.approvals.dialog?.message != null || holder.adjustment == null ||
                (!form.busy && form.message != null)
        }
        assertNull(screen.approvals.dialog?.message, "o Gerente foi recusado: ${screen.approvals.dialog?.message}")
        assertNull(holder.adjustment, "o ajuste aprovado falhou: ${form.message}")
        assertEquals(FolioText.ADJUSTMENT_DONE, screen.folio.notice, "o aviso do ajuste")

        val after = postingsOf(screen)
        val original = after.single { it.id.toString() == item.id }
        assertEquals(item.amount, original.amount, "o valor do consumo mudou")
        assertEquals(item.description, original.description, "a descrição do consumo mudou")
        val adjustment =
            after.singleOrNull { it.type == "ADJUSTMENT" && it.corrects() == item.id }
                ?: fail("o ajuste não cita o consumo")
        assertEquals(SCREEN_ADJUSTMENT, FolioText.money(adjustment.amount), "o ajuste não é de R$ 1,00")
        val adjustmentId = adjustment.id.toString()
        waitUntil("a linha do ajuste na tela", LOAD_TIMEOUT_MILLIS) {
            screen.folio.postings.any { it.id == adjustmentId && it.corrects == item.description }
        }
        println("Correções ao vivo: ajuste de R$ $ADJUSTED aprovado pelo Gerente; o consumo ficou intacto")
    }

    /** Passo 3: "Remover a taxa" na linha real da taxa; o estorno dela leva o motivo do servidor, o mesmo do aviso. */
    private fun DesktopComposeUiTest.removeTheServiceCharge(screen: Screen) {
        val holder = screen.holder
        val item = screen.item
        val chargeId = item.chargeId
        val row = rowIn(screen, chargeId)
        assertTrue(
            holder.canRemoveServiceCharge(row),
            "a Recepcionista não vê \"Remover a taxa\" na taxa (sem $SERVICE_CHARGE_REMOVE ou sem restante)",
        )
        runOnUiThread { holder.startRemoval(row) }
        val form = assertNotNull(holder.removal, "a confirmação da remoção não abriu")
        runOnUiThread { form.confirm() }
        waitUntil("a remoção", LOAD_TIMEOUT_MILLIS) { holder.removal == null || (!form.busy && form.message != null) }
        assertNull(holder.removal, "a remoção falhou: ${form.message}")

        val removal =
            postingsOf(screen).singleOrNull { it.type == "REVERSAL" && it.corrects() == chargeId }
                ?: fail("a taxa não ficou estornada")
        val reason =
            (removal.reason as? Option.Some)?.value?.takeIf { it.isNotBlank() }
                ?: fail("o estorno da taxa veio sem o motivo do servidor")
        assertEquals(FolioText.serviceChargeRemoved(reason), screen.folio.notice, "o aviso não traz o motivo")
        println("Correções ao vivo: taxa removida com o motivo do servidor no aviso")
    }

    /**
     * Passo 4: um segundo consumo com taxa na conta da tela, dividido 50% / 50% com a segunda conta pelo `SplitState`;
     * a saída, a entrada e as taxas que a API devolveu estão nas duas contas relidas. Devolve o id do consumo.
     */
    private fun DesktopComposeUiTest.splitHalfWithTheSecond(screen: Screen): String {
        val holder = screen.holder
        val folioId = screen.item.folioId
        val description = "Verificação ao vivo E08 divisão ${UUID.randomUUID().toString().take(TAG_LENGTH)}"
        val input = PostingInput(screen.code.id.toString(), 1, Money.parse(UNIT_PRICE), description)
        val posted = runBlocking { screen.source.post(folioId, input, Intent()) }
        if (posted.folioId.toString() !=
            folioId
        ) {
            fail("o segundo consumo foi roteado; a divisão o pede na conta da tela")
        }
        val itemId = posted.id.toString()
        runBlocking { screen.source.postings(folioId) }
            .singleOrNull { it.type == "SERVICE_CHARGE" && it.chargedItem() == itemId }
            ?: fail(NO_CHARGE)
        runOnUiThread { screen.folio.reload() }
        val row = rowIn(screen, itemId)
        assertTrue(holder.canTransfer(row), "a Recepcionista não vê \"Dividir\" no consumo (sem $POSTINGS_TRANSFER?)")
        runOnUiThread { holder.startSplit(row) }
        val form = assertNotNull(holder.split, "o diálogo de divisão não abriu")
        assertNotNull(form.remaining, "o diálogo de divisão não mostra o restante do servidor")
        val target = targetIn(form.targets, screen.secondId)
        screen.corrections.last = null
        runOnUiThread {
            form.addPart(target)
            form.setPart(0, HALF)
            form.setPart(1, HALF)
            form.confirm()
        }
        waitUntil("a divisão", LOAD_TIMEOUT_MILLIS) { holder.split == null || (!form.busy && form.message != null) }
        assertNull(holder.split, "a divisão falhou: ${form.message}")
        assertEquals(FolioText.splitAmong(2), screen.folio.notice, "o aviso da divisão")
        val response = screen.corrections.last ?: fail("a divisão não devolveu a resposta")
        requireMoved(screen, response, folioId, screen.secondId)
        println("Correções ao vivo: consumo dividido 50% / 50% com a segunda conta; saída, entrada e taxas nas duas")
        return itemId
    }

    /**
     * Passo 5: o que resta do consumo dividido vai para a segunda conta pelo `TransferState`; a saída, a entrada e as
     * taxas estão nas duas contas relidas, e a taxa vem sob o item na tabela da segunda conta.
     */
    private fun DesktopComposeUiTest.transferTheRestToTheSecond(
        screen: Screen,
        itemId: String,
    ) {
        val holder = screen.holder
        val row = rowIn(screen, itemId)
        assertTrue(holder.canTransfer(row), "o resto do consumo dividido não é transferível")
        runOnUiThread { holder.startTransfer(row) }
        val form = assertNotNull(holder.transfer, "o diálogo de transferência não abriu")
        assertEquals(setOf(itemId), form.checked, "o consumo da linha não veio marcado")
        val target = targetIn(form.targets, screen.secondId)
        screen.corrections.last = null
        runOnUiThread {
            form.chooseTarget(target)
            form.confirm()
        }
        waitUntil("a transferência", LOAD_TIMEOUT_MILLIS) {
            holder.transfer == null || (!form.busy && form.message != null)
        }
        assertNull(holder.transfer, "a transferência falhou: ${form.message}")
        assertEquals(FolioText.transferredTo(target.number), screen.folio.notice, "o aviso da transferência")
        val response = screen.corrections.last ?: fail("a transferência não devolveu a resposta")
        requireMoved(screen, response, screen.item.folioId, screen.secondId)
        val moved =
            response.transfers.singleOrNull { it.originalId.toString() == itemId }
                ?: fail("a resposta não traz o consumo transferido")
        val chargeId =
            (moved.serviceChargeIncoming as? Option.Some)?.value?.id?.toString()
                ?: fail("a resposta veio sem a taxa")
        requireChargeUnderTheItem(screen, moved.incoming.id.toString(), chargeId)
        println("Correções ao vivo: o resto foi transferido; a taxa veio sob o item na segunda conta")
    }

    /** A tabela real da segunda conta mostra a taxa recebida logo abaixo do item recebido, recuada. */
    private fun DesktopComposeUiTest.requireChargeUnderTheItem(
        screen: Screen,
        incomingId: String,
        chargeId: String,
    ) {
        var second: FolioState? = null
        runOnUiThread { second = FolioState(screen.wiring, screen.scope, screen.secondId).also { it.start() } }
        val state = checkNotNull(second) { "a segunda conta não foi montada" }
        try {
            waitUntil("a segunda conta", LOAD_TIMEOUT_MILLIS) {
                !state.loading && state.postings.any { it.id == chargeId }
            }
            val rows = state.postings
            val item = rows.indexOfFirst { it.id == incomingId }
            assertTrue(item >= 0, "a entrada do item não está na tabela da segunda conta")
            val charge = rows.getOrNull(item + 1)
            assertEquals(chargeId, charge?.id, "a taxa recebida não veio logo abaixo do item")
            assertTrue(charge?.serviceCharge == true, "a taxa recebida não veio recuada sob o item")
        } finally {
            runOnUiThread { state.close() }
        }
    }

    /**
     * Cada transferência da [response]: a saída e a saída da taxa na conta [from] relida, a entrada e a entrada da taxa
     * na conta [to] relida. A resposta sem a taxa falha.
     */
    private fun requireMoved(
        screen: Screen,
        response: TransferResponse,
        from: String,
        to: String,
    ) {
        assertTrue(response.transfers.isNotEmpty(), "a resposta não trouxe nenhuma transferência")
        val here = runBlocking { screen.source.postings(from) }.map { it.id }.toSet()
        val there = runBlocking { screen.source.postings(to) }.map { it.id }.toSet()
        response.transfers.forEach { moved ->
            val chargeOut = (moved.serviceChargeOutgoing as? Option.Some)?.value ?: fail("a resposta veio sem a taxa")
            val chargeIn = (moved.serviceChargeIncoming as? Option.Some)?.value ?: fail("a resposta veio sem a taxa")
            assertTrue(moved.outgoing.id in here, "a saída não está na conta de origem relida")
            assertTrue(chargeOut.id in here, "a saída da taxa não está na conta de origem relida")
            assertTrue(moved.incoming.id in there, "a entrada não está na conta de destino relida")
            assertTrue(chargeIn.id in there, "a entrada da taxa não está na conta de destino relida")
        }
    }

    /** A conta [id] entre as contas de destino do diálogo, carregando mais páginas se preciso. */
    private fun DesktopComposeUiTest.targetIn(
        targets: FolioTargets,
        id: String,
    ): FolioRow {
        repeat(MAX_TARGET_PAGES) {
            waitUntil("as contas de destino", LOAD_TIMEOUT_MILLIS) { !targets.loading }
            targets.rows.firstOrNull { folio -> folio.id == id }?.let { found -> return found }
            if (!targets.hasMore) fail("a segunda conta não está entre as contas de destino (${targets.error})")
            runOnUiThread { targets.loadMore() }
        }
        fail("a segunda conta não apareceu em $MAX_TARGET_PAGES páginas")
    }

    /** A linha [id] na tabela da tela, depois da releitura. */
    private fun DesktopComposeUiTest.rowIn(
        screen: Screen,
        id: String,
    ): PostingRow {
        waitUntil("o lançamento na tela", LOAD_TIMEOUT_MILLIS) {
            !screen.folio.loading && screen.folio.postings.any { it.id == id }
        }
        return screen.folio.postings.first { it.id == id }
    }

    private fun postingsOf(screen: Screen): List<PostingResponse> =
        runBlocking { screen.source.postings(screen.item.folioId) }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen, Approver) -> Unit) =
        withViewer { connection, approver ->
            val source = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
            val corrections =
                RecordingCorrections(
                    checkNotNull(connection.folioCorrectionSource()) { "a conexão não tem as correções" },
                )
            val permissions = runBlocking { connection.me() }.permissions
            // As duas contas e o código são exigidos antes de qualquer escrita.
            val folios = openReservationFolios(source)
            val code = serviceChargeCode(source)
            val item = postItem(source, folios.first(), code)
            val second = folios.firstOrNull { it.id.toString() != item.folioId } ?: fail(NO_FOLIO)
            val screen = Screen(source, corrections, item, code, second.id.toString())
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.approvals = remember { ApprovalCoordinator(connection::grant, emptyFlow(), screen.scope) }
                    val state =
                        remember {
                            screen.wiring =
                                FolioWiring(
                                    source,
                                    permissions,
                                    { screen.approvals.run(it) },
                                    null,
                                    null,
                                    corrections = corrections,
                                )
                            FolioState(screen.wiring, screen.scope, screen.item.folioId).also { it.start() }
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

    /**
     * Passo 1: um consumo de R$ 10,00 com o [code] que tem taxa de serviço, na primeira conta aberta de reserva; o
     * servidor pode roteá-lo para outra conta, e o resto segue nela. Sem a taxa dele, falha com `NEED_SEED` antes do
     * passo 2.
     */
    private fun postItem(
        source: FolioSource,
        folio: FolioResponse,
        code: TransactionCodeResponse,
    ): Item {
        val description = "Verificação ao vivo E08 ${UUID.randomUUID().toString().take(TAG_LENGTH)}"
        val input = PostingInput(code.id.toString(), 1, Money.parse(UNIT_PRICE), description)
        val posted = runBlocking { source.post(folio.id.toString(), input, Intent()) }
        val folioId = posted.folioId.toString()
        val itemId = posted.id.toString()
        // A taxa é exigida aqui, antes de o ajuste do passo 2 escrever na demo.
        val chargeId =
            runBlocking { source.postings(folioId) }
                .singleOrNull { it.type == "SERVICE_CHARGE" && it.chargedItem() == itemId }
                ?.id
                ?.toString()
                ?: fail(NO_CHARGE)
        val item = Item(folioId, itemId, posted.description, posted.amount, chargeId)
        if (item.folioId != folio.id.toString()) println("Correções ao vivo: o consumo foi roteado pelo servidor")
        println("Correções ao vivo: consumo de ${FolioText.money(item.amount)} lançado")
        return item
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")
}

/**
 * As contas abertas de reserva, percorrendo as páginas das abertas pelo `CursorPager`; menos de duas falha com
 * `NEED_SEED`, antes de qualquer escrita.
 */
private fun openReservationFolios(source: FolioSource): List<FolioResponse> =
    runBlocking {
        val pager = CursorPager(OPEN_PAGE_SIZE) { cursor, limit -> source.openFolioPage(cursor, limit) }
        val found = mutableListOf<FolioResponse>()
        while (pager.hasMore) found += pager.next().filter { it.owner.type == FolioOwnerDto.Type.RESERVATION }
        found.takeIf { it.size >= 2 } ?: fail(NO_FOLIO)
    }

/** Um código ativo de consumo que leva taxa de serviço. */
private fun serviceChargeCode(source: FolioSource): TransactionCodeResponse =
    runBlocking { source.transactionCodes() }.firstOrNull {
        it.active && it.serviceChargeApplies && it.kind == TransactionCodeResponse.Kind.CHARGE
    } ?: fail(NO_CODE)

/** O lançamento que este ajuste ou estorno corrige, como texto. */
private fun PostingResponse.corrects(): String? = (correctsId as? Option.Some)?.value?.toString()

/** O item desta taxa de serviço, como texto. */
private fun PostingResponse.chargedItem(): String? = (serviceChargeForPostingId as? Option.Some)?.value?.toString()
