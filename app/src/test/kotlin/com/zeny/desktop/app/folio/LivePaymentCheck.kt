package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
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
private const val CONSUMPTION = "10,00"
private const val SHIFT_TEXT = "É preciso um turno de caixa aberto."
private const val ZERO = "0.00"
private const val SCREEN_ZERO = "R$ 0,00"
private const val NO_FOLIO = "NEED_SEED: nenhuma conta aberta de reserva; rode o demo-seed"
private const val NO_CODE = "NEED_SEED: nenhum código ativo de consumo; rode o demo-seed"
private const val NO_BALANCE = "NEED_SEED: a conta continua sem saldo positivo depois do consumo; rode o demo-seed"
private const val NO_POINT = "NEED_SEED: sem o ponto de caixa \"Recepção\" ativo; rode o demo-seed"

/**
 * Verificação do recebimento contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real, pelos estados reais (`FolioState`, `PostingFormState`, `PaymentFormState`) e pelo painel
 * real da conta. Numa conta aberta de reserva do seed:
 * 1. se a Recepcionista não tem turno, "Receber" mostra o texto de `CASHIER_SHIFT_REQUIRED` com o atalho
 *    (`offersOpenShift`), e o check abre o turno no ponto "Recepção" do seed com fundo zero; com o turno já aberto,
 *    segue com ele e diz no relatório que o passo 1 ficou com os testes;
 * 2. se a conta não tem saldo positivo, lança um consumo de R$ 10,00 (falha com `NEED_SEED` se o saldo continuar sem
 *    ser positivo);
 * 3. recebe o saldo em dinheiro; a primeira resposta se perde (a porta do check a descarta depois de o servidor
 *    atender), o "Receber" de novo leva a mesma `Intent`, e o check exige um pagamento só a mais;
 * 4. exige o saldo relido `0.00`, a tela em "R$ 0,00" e o pagamento no turno corrente.
 *
 * O reembolso fica nos testes de estado e de UI: desfaria o saldo zero e pede o Gerente. O `PaymentResponse` nunca é
 * impresso (o `toString` gerado mostra os dados do cartão): só valores formatados e o número da conta.
 *
 * **Escreve na demo e não desfaz:** cada execução deixa na conta o pagamento em dinheiro, o consumo de R$ 10,00
 * quando foi preciso lançá-lo e, se a Recepcionista não tinha turno, o turno aberto no ponto "Recepção" com fundo
 * zero. Por isso tem opt-in próprio: pulada sem `ZENY_LIVE_PAYMENT=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/payment.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_PAYMENT=1 ./gradlew :app:test --tests '*LivePaymentCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LivePaymentCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** A porta real com um desvio de teste: o próximo recebimento chega ao servidor e a resposta se perde. */
    private class ProbePayments(
        private val real: PaymentSource,
    ) : PaymentSource by real {
        var loseNextAnswer = false
        val intents = mutableListOf<Intent>()

        override suspend fun receive(
            folioId: String,
            input: PaymentInput,
            intent: Intent,
        ): PaymentResponse {
            intents += intent
            val received = real.receive(folioId, input, intent)
            if (!loseNextAnswer) return received
            loseNextAnswer = false
            throw ApiError.Network(IOException("resposta perdida pela verificação"))
        }
    }

    private class Screen(
        val folios: FolioSource,
        val payments: ProbePayments,
        val cashier: CashierSource,
        val folioId: String,
    ) {
        var shown: FolioState? = null
        var shortcuts = 0
        val folio: FolioState get() = checkNotNull(shown) { "o painel da conta não foi montado" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_PAYMENT") == "1", "sem ZENY_LIVE_PAYMENT=1: verificação real pulada")
    }

    @Test
    fun receiveTheBalanceInCashOnceInTheCurrentShift() =
        withScreen { screen ->
            shiftRequiredThenOpen(screen)
            ensurePositiveBalance(screen)
            val payment = receiveTwice(screen)
            zeroBalanceInTheCurrentShift(screen, payment)
            capture("payment")
        }

    /** Passo 1: sem turno, "Receber" mostra o texto e o atalho; o check abre o turno na "Recepção" com fundo zero. */
    private fun DesktopComposeUiTest.shiftRequiredThenOpen(screen: Screen) {
        if (runBlocking { screen.cashier.currentShift() } != null) {
            println("LivePaymentCheck: a Recepcionista já tem turno; o passo 1 foi conferido só pelos testes")
            return
        }
        val folio = screen.folio
        assertTrue(folio.canReceive, "a Recepcionista não vê \"Receber\" (sem a permissão ou conta encerrada)")
        runOnUiThread { folio.startPayment() }
        val form = assertNotNull(folio.payment, "o diálogo de recebimento não abriu")
        runOnUiThread {
            form.amount = CONSUMPTION
            form.confirm()
        }
        waitUntil("a recusa sem turno", LOAD_TIMEOUT_MILLIS) { !form.busy && form.message != null }
        assertEquals(SHIFT_TEXT, form.message, "sem turno, o recebimento não mostrou o texto do catálogo")
        assertTrue(form.offersOpenShift, "sem turno, o diálogo não ofereceu \"Abrir turno\"")
        runOnUiThread { form.openShiftAndClose() }
        assertNull(folio.payment, "\"Abrir turno\" não fechou o diálogo")
        assertEquals(1, screen.shortcuts, "\"Abrir turno\" não chamou o atalho")

        val point = runBlocking { screen.cashier.cashPoints() }.firstOrNull { it.name == "Recepção" } ?: fail(NO_POINT)
        val shift = runBlocking { screen.cashier.openShift(point.id.toString(), Money.parse(ZERO), Intent()) }
        println(
            "LivePaymentCheck: sem turno, o texto e o atalho apareceram; turno ${shift.number} aberto com fundo zero",
        )
    }

    /** Passo 2: sem saldo positivo, um consumo de R$ 10,00 pelo diálogo real. */
    private fun DesktopComposeUiTest.ensurePositiveBalance(screen: Screen) {
        if (balanceOf(screen).amount.signum() > 0) return
        val folio = screen.folio
        val code = consumptionCode(screen.folios)
        assertTrue(folio.canPost, "a Recepcionista não vê \"Lançar\" (sem a permissão ou conta encerrada)")
        runOnUiThread { folio.startPosting() }
        val form = assertNotNull(folio.posting, "o diálogo de lançamento não abriu")
        waitUntil("os códigos de lançamento", LOAD_TIMEOUT_MILLIS) { !form.codesLoading }
        assertNull(form.codesError, "a leitura dos códigos falhou: ${form.codesError}")
        runOnUiThread {
            form.codeId = code.id.toString()
            form.unitPrice = CONSUMPTION
            form.description = "Verificação ao vivo E03 ${UUID.randomUUID().toString().take(TAG_LENGTH)}"
            form.confirm()
        }
        waitUntil("o lançamento", LOAD_TIMEOUT_MILLIS) { folio.posting == null || (!form.busy && form.message != null) }
        assertNull(folio.posting, "o lançamento falhou: ${form.message}")
        waitUntil("a conta relida", LOAD_TIMEOUT_MILLIS) { !folio.loading }
        if (balanceOf(screen).amount.signum() <= 0) fail(NO_BALANCE)
        println("LivePaymentCheck: consumo de R$ $CONSUMPTION lançado para a conta ter saldo")
    }

    /** Passo 3: o saldo em dinheiro; a primeira resposta se perde e o "Receber" de novo leva a mesma `Intent`. */
    private fun DesktopComposeUiTest.receiveTwice(screen: Screen): PaymentResponse {
        val folio = screen.folio
        val balance = balanceOf(screen)
        val shown = FolioText.money(balance.toApi())
        waitUntil("a conta relida com o saldo", LOAD_TIMEOUT_MILLIS) { !folio.loading && folio.view?.balance == shown }
        val before = runBlocking { screen.payments.payments(screen.folioId) }.map { it.id }.toSet()
        assertTrue(folio.canReceive, "a Recepcionista não vê \"Receber\"")
        screen.payments.loseNextAnswer = true
        runOnUiThread { folio.startPayment() }
        val form = assertNotNull(folio.payment, "o diálogo de recebimento não abriu")
        assertEquals(PaymentMethod.CASH, form.method, "a forma padrão não é Dinheiro")
        runOnUiThread { form.confirm() }
        waitUntil("a resposta perdida", LOAD_TIMEOUT_MILLIS) { !form.busy && form.message != null }
        assertTrue(form.unconfirmed, "a resposta perdida não deixou o recebimento sem confirmação: ${form.message}")
        runOnUiThread { form.confirm() }
        waitUntil("a repetição", LOAD_TIMEOUT_MILLIS) { folio.payment == null || (!form.busy && !form.unconfirmed) }
        assertNull(folio.payment, "a repetição com a mesma chave falhou: ${form.message}")
        val intents = screen.payments.intents.takeLast(2)
        assertEquals(intents[0].key, intents[1].key, "a repetição não levou a mesma chave")

        val added = runBlocking { screen.payments.payments(screen.folioId) }.filter { it.id !in before }
        assertEquals(1, added.size, "a repetição com a mesma chave não deu um pagamento só")
        val payment = added.single()
        assertEquals(balance, Money.parse(payment.amount), "o valor recebido não é o saldo sugerido")
        assertEquals(PaymentMethod.CASH.name, payment.method, "o pagamento não foi em dinheiro")
        println("LivePaymentCheck: ${FolioText.money(payment.amount)} recebidos uma vez só, com a resposta perdida")
        return payment
    }

    /** Passo 4: o saldo relido zero, na tela "R$ 0,00", e o pagamento no turno corrente. */
    private fun DesktopComposeUiTest.zeroBalanceInTheCurrentShift(
        screen: Screen,
        payment: PaymentResponse,
    ) {
        val folio = screen.folio
        val reread = runBlocking { screen.folios.folio(screen.folioId) }
        assertEquals(ZERO, reread.balance, "o saldo relido não é zero")
        waitUntil(
            "a tela com o saldo zero",
            LOAD_TIMEOUT_MILLIS,
        ) { !folio.loading && folio.view?.balance == SCREEN_ZERO }
        waitUntil("o saldo zero desenhado", LOAD_TIMEOUT_MILLIS) {
            onAllNodesWithText("Saldo: $SCREEN_ZERO").fetchSemanticsNodes().isNotEmpty()
        }
        val shift = runBlocking { screen.cashier.currentShift() } ?: fail("a Recepcionista ficou sem turno")
        assertEquals(shift.id, payment.cashierShiftId, "o pagamento não está no turno corrente")
        println("LivePaymentCheck: conta ${reread.number} com saldo $SCREEN_ZERO; pagamento no turno ${shift.number}")
    }

    private fun balanceOf(screen: Screen): Money =
        Money.parse(runBlocking { screen.folios.folio(screen.folioId) }.balance)

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen) -> Unit) =
        withViewer { connection ->
            val folios = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
            val payments = ProbePayments(checkNotNull(connection.paymentSource()) { "a conexão não tem pagamentos" })
            val cashier = checkNotNull(connection.cashierSource()) { "a conexão não tem caixa" }
            val permissions = runBlocking { connection.me() }.permissions
            val screen = Screen(folios, payments, cashier, openReservationFolio(folios).id.toString())
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    val scope = rememberCoroutineScope()
                    val state =
                        remember {
                            val wiring =
                                FolioWiring(
                                    folios,
                                    permissions,
                                    { it.run(null) },
                                    null,
                                    null,
                                    payments = payments,
                                    openShift = { screen.shortcuts++ },
                                )
                            FolioState(wiring, scope, screen.folioId).also { it.start() }
                        }
                    DisposableEffect(state) { onDispose { state.close() } }
                    screen.shown = state
                    ZenyTheme { FolioPanel(state) }
                }
                try {
                    waitUntil("a conta", LOAD_TIMEOUT_MILLIS) { screen.shown?.view != null }
                    block(screen)
                } finally {
                    screen.shown?.close()
                }
            }
        }

    private fun withViewer(block: (CoreConnection) -> Unit) {
        val url = ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
        val core = ZenyCore(url, MemorySettings())
        val connection = CoreConnection(core)
        try {
            runBlocking { connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD")) }
            block(connection)
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

/** Um código ativo de consumo (oferecido em "Lançar"). */
private fun consumptionCode(source: FolioSource): TransactionCodeResponse =
    runBlocking { source.transactionCodes() }.firstOrNull {
        it.active && it.kind == TransactionCodeResponse.Kind.CHARGE
    } ?: fail(NO_CODE)
