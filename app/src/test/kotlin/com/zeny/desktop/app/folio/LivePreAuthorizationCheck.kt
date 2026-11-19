package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PreAuthorizationInput
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
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
private const val OPEN_PAGE_SIZE = 50
private const val VALIDITY_DAYS = 7L
private const val AUTHORIZED = "100,00"
private const val CAPTURED = "30,00"
private const val CAPTURED_API = "30.00"
private const val SCREEN_AUTHORIZED = "R$ 100,00"
private const val SCREEN_LEFT = "R$ 70,00"
private const val TEST_CODE = "VERIFE09"
private const val ZERO = "0.00"
private const val SHIFT_TEXT = "É preciso um turno de caixa aberto."
private const val NO_FOLIO = "NEED_SEED: nenhuma conta aberta de reserva; rode o demo-seed"
private const val NO_POINT = "NEED_SEED: sem o ponto de caixa \"Recepção\" ativo; rode o demo-seed"

/**
 * Verificação das cauções contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real, pelos estados reais (`FolioState`, `PreAuthorizationsState`, `PreAuthorizationFormState`,
 * `CaptureState`, `ReleaseState`) e pelo painel real da conta. Numa conta aberta de reserva do seed:
 * 1. registra uma caução de R$ 100,00 com validade de 7 dias (no fuso do hotel) e uma autorização de teste; a primeira
 *    resposta se perde (a porta do check a descarta depois de o servidor atender), o "Registrar" de novo leva a mesma
 *    `Intent`, e o check exige uma caução só a mais;
 * 2. se a Recepcionista não tem turno, "Capturar" mostra o texto de `CASHIER_SHIFT_REQUIRED` com o atalho, e o check
 *    abre o turno no ponto "Recepção" do seed com fundo zero (como a `LivePaymentCheck`); captura R$ 30,00 e exige a
 *    situação "Capturada em parte", o capturável R$ 70,00 e o saldo relido igual ao de antes da captura menos R$ 30,00;
 * 3. libera a sobra e exige a situação "Liberada", o liberado R$ 70,00 e o saldo igual ao do passo 2.
 *
 * As respostas de caução do `sdk` imprimem o cartão no `toString`: nenhuma é impressa, interpolada nem comparada
 * inteira; só valores formatados, situações e o número do turno.
 *
 * **Escreve na demo e não desfaz:** cada execução deixa na conta uma caução capturada em R$ 30,00 e liberada, o
 * pagamento da captura (a conta pode ficar credora) e, se a Recepcionista não tinha turno, o turno aberto no ponto
 * "Recepção" com fundo zero. Por isso tem opt-in próprio: pulada sem `ZENY_LIVE_PREAUTH=1`; com ele, exige
 * `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Grava
 * `build/live/preauth.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_PREAUTH=1 ./gradlew :app:test --tests '*LivePreAuthorizationCheck' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LivePreAuthorizationCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** A porta real com um desvio de teste: o próximo registro chega ao servidor e a resposta se perde. */
    private class ProbePayments(
        private val real: PaymentSource,
    ) : PaymentSource by real {
        var loseNextAnswer = false
        val intents = mutableListOf<Intent>()

        override suspend fun registerPreAuthorization(
            folioId: String,
            input: PreAuthorizationInput,
            intent: Intent,
        ): CardPreAuthorizationResponse {
            intents += intent
            val registered = real.registerPreAuthorization(folioId, input, intent)
            if (!loseNextAnswer) return registered
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
        val holder: PreAuthorizationsState
            get() = checkNotNull(folio.preAuthorizations) { "o painel não tem a seção das cauções" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_PREAUTH") == "1", "sem ZENY_LIVE_PREAUTH=1: verificação real pulada")
    }

    @Test
    fun registerCaptureAndReleaseOnceEach() =
        withScreen { screen ->
            val id = registerTwice(screen)
            val balance = captureInTheCurrentShift(screen, id)
            releaseTheRest(screen, id, balance)
            capture("preauth")
        }

    /** Passo 1: R$ 100,00 por 7 dias; a primeira resposta se perde e o "Registrar" de novo leva a mesma `Intent`. */
    private fun DesktopComposeUiTest.registerTwice(screen: Screen): String {
        val holder = screen.holder
        waitUntil("as cauções", LOAD_TIMEOUT_MILLIS) { holder.loaded }
        val before = preAuthorizationsOf(screen).map { it.id }.toSet()
        val zone = runBlocking { screen.payments.businessDay() }.zone ?: fail("o fuso do hotel não foi lido")
        assertTrue(holder.canRegister, "a Recepcionista não vê \"Registrar caução\" (sem permissão ou conta encerrada)")
        screen.payments.loseNextAnswer = true
        runOnUiThread { holder.startRegister() }
        val form = assertNotNull(holder.register, "o diálogo de registro não abriu")
        runOnUiThread {
            form.amount = AUTHORIZED
            form.expiresOn = DateInput.format(BusinessDate(LocalDate.now(zone).plusDays(VALIDITY_DAYS)))
            form.authorizationCode = TEST_CODE
            form.confirm()
        }
        waitUntil("a resposta perdida", LOAD_TIMEOUT_MILLIS) { !form.busy && form.message != null }
        assertTrue(form.unconfirmed, "a resposta perdida não deixou o registro sem confirmação: ${form.message}")
        runOnUiThread { form.confirm() }
        waitUntil("a repetição", LOAD_TIMEOUT_MILLIS) { holder.register == null || (!form.busy && !form.unconfirmed) }
        assertNull(holder.register, "a repetição com a mesma chave falhou: ${form.message}")
        val intents = screen.payments.intents.takeLast(2)
        assertEquals(intents[0].key, intents[1].key, "a repetição não levou a mesma chave")

        val added = preAuthorizationsOf(screen).filter { it.id !in before }
        assertEquals(1, added.size, "a repetição com a mesma chave não deu uma caução só")
        val registered = added.single()
        assertEquals(SCREEN_AUTHORIZED, FolioText.money(registered.amount), "o valor autorizado não é R$ 100,00")
        assertEquals("ACTIVE", registered.status, "a caução nova não está ativa")
        println("Cauções ao vivo: caução de $SCREEN_AUTHORIZED registrada uma vez só, com a resposta perdida")
        return registered.id.toString()
    }

    /** Passo 2: o turno (aberto pelo check quando falta) e a captura de R$ 30,00; o saldo cai R$ 30,00. */
    private fun DesktopComposeUiTest.captureInTheCurrentShift(
        screen: Screen,
        id: String,
    ): String {
        val holder = screen.holder
        if (runBlocking { screen.cashier.currentShift() } == null) shiftRequiredThenOpen(screen, id)
        val before = BigDecimal(screen.balance())
        val form = openCapture(screen, id)
        runOnUiThread {
            form.amount = CAPTURED
            form.confirm()
        }
        waitUntil("a captura", LOAD_TIMEOUT_MILLIS) { holder.capture == null || (!form.busy && form.message != null) }
        assertNull(holder.capture, "a captura falhou: ${form.message}")

        val captured = preAuthorizationOf(screen, id)
        assertEquals("PARTIALLY_CAPTURED", captured.status, "a caução não ficou capturada em parte")
        assertEquals(SCREEN_LEFT, FolioText.money(captured.capturableAmount), "o capturável não é R$ 70,00")
        val balance = screen.balance()
        val expected = MoneyInput.format(Money(before.subtract(BigDecimal(CAPTURED_API))))
        assertEquals(expected, FolioText.money(balance), "o saldo não caiu R$ 30,00 com a captura")
        waitUntil("a linha capturada em parte", LOAD_TIMEOUT_MILLIS) {
            holder.rows.any { it.id == id && it.status == "Capturada em parte" }
        }
        println("Cauções ao vivo: R$ $CAPTURED capturados; capturável $SCREEN_LEFT; saldo $expected")
        return balance
    }

    /** Sem turno, "Capturar" mostra o texto e o atalho; o check abre o turno na "Recepção" com fundo zero. */
    private fun DesktopComposeUiTest.shiftRequiredThenOpen(
        screen: Screen,
        id: String,
    ) {
        val holder = screen.holder
        val form = openCapture(screen, id)
        runOnUiThread {
            form.amount = CAPTURED
            form.confirm()
        }
        waitUntil("a recusa sem turno", LOAD_TIMEOUT_MILLIS) { !form.busy && form.message != null }
        assertEquals(SHIFT_TEXT, form.message, "sem turno, a captura não mostrou o texto do catálogo")
        assertTrue(form.offersOpenShift, "sem turno, o diálogo não ofereceu \"Abrir turno\"")
        runOnUiThread { form.openShiftAndClose() }
        assertNull(holder.capture, "\"Abrir turno\" não fechou o diálogo")
        assertEquals(1, screen.shortcuts, "\"Abrir turno\" não chamou o atalho")

        val point = runBlocking { screen.cashier.cashPoints() }.firstOrNull { it.name == "Recepção" } ?: fail(NO_POINT)
        val shift = runBlocking { screen.cashier.openShift(point.id.toString(), Money.parse(ZERO), Intent()) }
        println(
            "Cauções ao vivo: sem turno, o texto e o atalho apareceram; turno ${shift.number} aberto",
        )
    }

    /** Passo 3: libera a sobra; a caução fica "Liberada" com R$ 70,00 liberados, e o saldo não muda. */
    private fun DesktopComposeUiTest.releaseTheRest(
        screen: Screen,
        id: String,
        balance: String,
    ) {
        val holder = screen.holder
        val row = rowIn(holder, id)
        assertTrue(holder.canRelease(row), "a Recepcionista não vê \"Liberar\"")
        runOnUiThread { holder.startRelease(row) }
        val form = assertNotNull(holder.release, "a confirmação de liberação não abriu")
        runOnUiThread { form.confirm() }
        waitUntil("a liberação", LOAD_TIMEOUT_MILLIS) { holder.release == null || (!form.busy && form.message != null) }
        assertNull(holder.release, "a liberação falhou: ${form.message}")

        val released = preAuthorizationOf(screen, id)
        assertEquals("RELEASED", released.status, "a caução não ficou liberada")
        val left = (released.releasedAmount as? Option.Some)?.value ?: fail("o servidor não devolveu o liberado")
        assertEquals(SCREEN_LEFT, FolioText.money(left), "o liberado não é R$ 70,00")
        assertEquals(FolioText.money(balance), FolioText.money(screen.balance()), "a liberação mudou o saldo")
        waitUntil("a linha liberada", LOAD_TIMEOUT_MILLIS) {
            holder.rows.any { it.id == id && it.status == "Liberada" }
        }
        println("Cauções ao vivo: sobra de $SCREEN_LEFT liberada; o saldo ficou igual")
    }

    /** Abre "Capturar" na linha da caução [id], esperando a seção relida com ela. */
    private fun DesktopComposeUiTest.openCapture(
        screen: Screen,
        id: String,
    ): CaptureState {
        val holder = screen.holder
        val row = rowIn(holder, id)
        assertTrue(holder.canCapture(row), "a Recepcionista não vê \"Capturar\"")
        runOnUiThread { holder.startCapture(row) }
        return assertNotNull(holder.capture, "o diálogo de captura não abriu")
    }

    /** A linha da caução [id] na seção, depois da releitura. */
    private fun DesktopComposeUiTest.rowIn(
        holder: PreAuthorizationsState,
        id: String,
    ): PreAuthorizationRow {
        waitUntil("a caução na seção", LOAD_TIMEOUT_MILLIS) { holder.rows.any { it.id == id } }
        return holder.rows.first { it.id == id }
    }

    private fun preAuthorizationsOf(screen: Screen): List<CardPreAuthorizationResponse> =
        runBlocking { screen.payments.preAuthorizations(screen.folioId) }

    private fun preAuthorizationOf(
        screen: Screen,
        id: String,
    ): CardPreAuthorizationResponse =
        preAuthorizationsOf(screen).firstOrNull { it.id.toString() == id } ?: fail("a caução sumiu da conta")

    private fun Screen.balance(): String = runBlocking { folios.folio(folioId) }.balance

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
