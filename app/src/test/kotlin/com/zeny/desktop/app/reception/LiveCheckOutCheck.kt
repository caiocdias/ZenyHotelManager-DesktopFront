package com.zeny.desktop.app.reception

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.folio.FolioSource
import com.zeny.desktop.app.folio.FolioWiring
import com.zeny.desktop.app.folio.PaymentSource
import com.zeny.desktop.app.folio.isCapturable
import com.zeny.desktop.app.folio.moneyOrNull
import com.zeny.desktop.app.rooms.RoomDisplayStatus
import com.zeny.desktop.app.rooms.RoomMapState
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.PreAuthorizationInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

private const val WIDTH = 1280
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val ROOM_LIMIT_MILLIS = 2_000L
private const val PAGE_LIMIT = 50
private const val VALIDITY_DAYS = 2L
private const val DEPOSIT_API = "50.00"
private const val TEST_CODE = "VERIFE04"
private const val ZERO = "0.00"
private const val IN_HOUSE = "IN_HOUSE"
private const val DONE = "Check-out concluído."
private const val CAPTURE_FIRST = CheckOutText.CAPTURE_FIRST
private const val NO_DEPARTURE = "NEED_SEED: nenhuma saída do dia hospedada, com UH e com conta; rode o demo-seed"
private const val CREDIT_DUE = "conta credora no devido: o check-out depois do recebimento voltou com saldo devido"
private const val NO_POINT = "NEED_SEED: sem o ponto de caixa \"Recepção\" ativo; rode o demo-seed"

/**
 * Verificação do check-out contra um servidor real (o ambiente `zeny-front`), com a Recepcionista, pelo
 * `CoreConnection` real e pelos estados reais (`CheckOutState` no `CheckOutDialog`, `PaymentFormState` do devido,
 * `DayPanelState` e `RoomMapState` assinado em paralelo). Numa saída do dia do seed, hospedada, com UH e com conta:
 * 1. abre o turno no ponto "Recepção" do seed com fundo zero, se a Recepcionista não tem turno (o recebimento pede);
 * 2. com uma caução capturável na conta (registra uma de R$ 50,00 se não há), exige "Concluir" desabilitado com o
 *    texto da caução; libera as capturáveis pela porta (o que o "Abrir conta" faria), relê o assistente e exige
 *    "Concluir" habilitado (critério 2);
 * 3. se a cotação oferece late, liga a caixa; conclui; com o saldo devido, recebe o devido de cada conta pelo
 *    assistente (o valor sugerido é o do servidor) e conclui de novo, que tem de levar outra chave; a resposta do
 *    check-out que deu certo se perde (a porta do check a descarta depois de o servidor atender), e "Tentar de novo"
 *    leva a mesma chave; exige "Check-out concluído." e, com late, um lançamento com o valor do late numa conta
 *    relida da reserva (critério 4);
 * 4. exige a UH da reserva "Suja" (vaga e suja) no mapa em até 2 s depois da resposta do servidor (critério 1);
 * 5. relê o painel do dia e exige a reserva fora de "Hospedados".
 *
 * O prefixo "Check-out ao vivo:" marca as linhas desta verificação na saída do Gradle (`--info`). As respostas de
 * caução do `sdk` imprimem o cartão no `toString`: nenhuma resposta do `sdk` é impressa, interpolada nem comparada
 * inteira; só valores formatados, o código da reserva e o número da UH.
 *
 * **Escreve na demo e não desfaz:** cada execução faz o check-out de uma saída do dia (a reserva sai, as contas são
 * encerradas e a UH fica vaga e suja), deixa as cauções da conta liberadas (e uma de R$ 50,00 registrada, se não havia
 * capturável), os pagamentos do devido e, se a Recepcionista não tinha turno, o turno aberto no ponto "Recepção" com
 * fundo zero. Por isso tem opt-in próprio: pulada sem `ZENY_LIVE_CHECKOUT=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Sem saída do dia com conta,
 * falha com `NEED_SEED` (rode o demo-seed de novo). Grava `build/live/check-out.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_CHECKOUT=1 ./gradlew :app:test --tests '*LiveCheckOutCheck' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveCheckOutCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /**
     * A porta real com um desvio de teste: o próximo check-out que dá certo chega ao servidor e a resposta se perde.
     * Guarda a chave de cada envio, as chaves que deram saldo devido e quando o servidor respondeu o sucesso.
     */
    private class ProbeCheckOut(
        private val real: CheckOutSource,
    ) : CheckOutSource by real {
        var loseNextDone = false
        val keys = mutableListOf<String>()
        val dueKeys = mutableListOf<String>()
        var answeredAt: TimeSource.Monotonic.ValueTimeMark? = null

        override suspend fun checkOut(
            reservationId: String,
            lateFee: Money?,
            intent: Intent,
        ): CheckOutResult {
            keys += intent.key
            val result = real.checkOut(reservationId, lateFee, intent)
            if (result is CheckOutResult.BalanceDue) {
                dueKeys += intent.key
                return result
            }
            if (answeredAt == null) answeredAt = TimeSource.Monotonic.markNow()
            if (!loseNextDone) return result
            loseNextDone = false
            throw ApiError.Network(IOException("resposta perdida pela verificação"))
        }

        /** O servidor devolveu saldo devido mais de uma vez: o check-out depois do recebimento ainda tinha devido. */
        fun dueAgain(): Boolean = dueKeys.size > 1

        /** A repetição depois da resposta perdida levou a mesma chave do envio que deu certo. */
        fun retriedWithTheSameKey(): Boolean = keys.size >= 2 && keys[keys.size - 1] == keys[keys.size - 2]

        /** O envio que deu certo levou outra chave que a do último saldo devido (sem devido, nada a conferir). */
        fun rotatedAfterTheDue(): Boolean = dueKeys.isEmpty() || keys.last() != dueKeys.last()
    }

    private class Screen(
        val departure: Departure,
        val folios: FolioSource,
        val payments: PaymentSource,
        val probe: ProbeCheckOut,
    ) {
        var shown: CheckOutState? = null
        var panelShown: DayPanelState? = null
        var mapShown: RoomMapState? = null
        var dirtyAt: TimeSource.Monotonic.ValueTimeMark? = null
        var postingsBefore: Set<String> = emptySet()
        var closings = 0
        var notice: String? = null
        var reloadPanel = false
        val state: CheckOutState get() = checkNotNull(shown) { "o assistente não foi montado" }
        val panel: DayPanelState get() = checkNotNull(panelShown) { "o painel do dia não foi montado" }
        val map: RoomMapState get() = checkNotNull(mapShown) { "o mapa não foi montado" }
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_CHECKOUT") == "1", "sem ZENY_LIVE_CHECKOUT=1: verificação real pulada")
    }

    @Test
    fun checksOutADepartureOfTheDay() =
        withScreen { screen ->
            cautionBlocksThenReleaseUnlocks(screen)
            capture("check-out")
            val fee = concludeReceivingTheDue(screen)
            lateWasPosted(screen, fee)
            roomTurnsVacantAndDirty(screen)
            leavesInHouse(screen)
        }

    /** Passo 2: a caução capturável bloqueia "Concluir"; liberada (pela porta) e relido o assistente, destrava. */
    private fun DesktopComposeUiTest.cautionBlocksThenReleaseUnlocks(screen: Screen) {
        val state = screen.state
        waitUntil("a conferência", LOAD_TIMEOUT_MILLIS) { state.loaded && state.pending.isNotEmpty() }
        assertNull(state.error, "a conferência das contas e das cauções falhou")
        assertEquals(CAPTURE_FIRST, state.blocked, "a caução capturável não bloqueou \"Concluir\"")
        check(!state.canConclude) { "com caução capturável, \"Concluir\" ficou habilitado" }
        val freed = releaseCapturable(screen)
        runOnUiThread { state.reload() }
        waitUntil("a conferência relida", LOAD_TIMEOUT_MILLIS) { state.canConclude || state.error != null }
        assertNull(state.error, "a conferência relida falhou")
        assertNull(state.blocked, "a caução liberada ainda bloqueia \"Concluir\"")
        println("Check-out ao vivo: $freed caução(ões) liberada(s); \"Concluir\" destravou")
    }

    /**
     * Passo 3: liga o late oferecido, conclui, recebe o devido de cada conta e conclui de novo; a resposta do sucesso
     * se perde e a repetição leva a mesma chave. Devolve o late cobrado (nulo sem oferta).
     */
    private fun DesktopComposeUiTest.concludeReceivingTheDue(screen: Screen): Money? {
        val state = screen.state
        val fee = state.quote?.fee
        if (fee != null) runOnUiThread { state.chargeLate = true }
        check(fee == null || state.chargeLate) { "a caixa do late não ligou" }
        waitUntil("o mapa ao vivo", LOAD_TIMEOUT_MILLIS) {
            !screen.map.loading && screen.map.live == RealtimeStatus.Live
        }
        screen.postingsBefore = postingIds(screen)
        screen.probe.loseNextDone = true
        conclude(screen)
        if (state.due != null) {
            receiveEachDue(screen)
            conclude(screen)
            // Pelo que o servidor respondeu: o devido da tela fica até a próxima resposta, e a do sucesso se perde.
            if (screen.probe.dueAgain()) fail(CREDIT_DUE)
        }
        assertTrue(state.unconfirmed, "a resposta perdida não deixou o check-out sem confirmação")
        runOnUiThread { state.conclude() }
        waitUntil("a repetição", LOAD_TIMEOUT_MILLIS) { screen.closings > 0 || !state.busy }
        assertEquals(1, screen.closings, "a repetição com a mesma chave não fechou o assistente")
        assertEquals(DONE, screen.notice, "o assistente não fechou com o check-out concluído")
        assertTrue(screen.reloadPanel, "o sucesso não pediu a releitura do painel")
        check(screen.probe.retriedWithTheSameKey()) { "a repetição não levou a mesma chave" }
        check(screen.probe.rotatedAfterTheDue()) { "o check-out depois do devido não levou outra chave" }
        println("Check-out ao vivo: reserva ${screen.departure.code} com check-out concluído, com a resposta perdida")
        return fee
    }

    /** Recebe pelo assistente o devido de cada conta que oferece "Receber", com o valor que o servidor mandou. */
    private fun DesktopComposeUiTest.receiveEachDue(screen: Screen) {
        val state = screen.state
        val lines = state.dueLines.filter { it.receive != null }
        check(lines.isNotEmpty()) { "o saldo devido não oferece \"Receber\" (conta credora?)" }
        for (line in lines) {
            val owed =
                state.due
                    ?.folios
                    ?.firstOrNull { it.folioId == line.folioId }
                    ?.balance
            runOnUiThread { state.receiveDue(line.folioId) }
            val form = checkNotNull(state.payment) { "o recebimento do devido não abriu" }
            val suggested = owed != null && form.amount == MoneyInput.format(owed)
            check(suggested) { "o valor sugerido não é o devido do servidor" }
            runOnUiThread { form.confirm() }
            waitUntil("o recebimento", LOAD_TIMEOUT_MILLIS) {
                state.payment == null || (!form.busy && form.message != null)
            }
            assertNull(state.payment, "o recebimento do devido falhou: ${form.message}")
            waitUntil("o assistente relido", LOAD_TIMEOUT_MILLIS) { state.canConclude }
        }
        println("Check-out ao vivo: devido recebido em ${lines.size} conta(s); total ${state.dueTotal}")
    }

    /** Envia o check-out e espera a resposta (ou o fechamento do assistente). */
    private fun DesktopComposeUiTest.conclude(screen: Screen) {
        val state = screen.state
        check(state.canConclude) { "\"Concluir\" não está habilitado" }
        runOnUiThread { state.conclude() }
        waitUntil("o check-out", LOAD_TIMEOUT_MILLIS) { screen.closings > 0 || !state.busy }
        if (!state.unconfirmed) assertNull(state.message, "o check-out foi recusado: ${state.message}")
    }

    /**
     * Critério 4: com late, uma conta relida da reserva tem um lançamento novo (que não existia antes do check-out) com
     * o valor do late, comparado sem a escala (o `Money` guarda a do servidor).
     */
    private fun lateWasPosted(
        screen: Screen,
        fee: Money?,
    ) {
        if (fee == null) {
            println("Check-out ao vivo: sem late oferecido pela política vigente")
            return
        }
        val posted =
            screen.departure.folioIds.any { id ->
                runBlocking { screen.folios.postings(id) }.any {
                    val isNew = it.id.toString() !in screen.postingsBefore
                    isNew && moneyOrNull(it.amount)?.amount?.compareTo(fee.amount) == 0
                }
            }
        check(posted) { "nenhuma conta relida tem um lançamento novo com o valor do late" }
        println("Check-out ao vivo: late de ${MoneyInput.format(fee)} lançado na conta")
    }

    /** Passo 4: a UH da reserva fica vaga e suja no mapa em até 2 s depois da resposta do servidor. */
    private fun DesktopComposeUiTest.roomTurnsVacantAndDirty(screen: Screen) {
        waitUntil("a UH vaga e suja no mapa", LOAD_TIMEOUT_MILLIS) { screen.dirtyAt != null }
        val answered = checkNotNull(screen.probe.answeredAt) { "o servidor não respondeu o sucesso" }
        val dirty = checkNotNull(screen.dirtyAt) { "o mapa não mostrou a UH vaga e suja" }
        val elapsed = (dirty - answered).inWholeMilliseconds
        println("Check-out ao vivo: UH ${screen.departure.room} vaga e suja no mapa em $elapsed ms")
        check(elapsed <= ROOM_LIMIT_MILLIS) { "a UH levou mais de 2 s para ficar vaga e suja no mapa" }
    }

    /** Passo 5: o painel do dia relido não tem mais a reserva em "Hospedados". */
    private fun DesktopComposeUiTest.leavesInHouse(screen: Screen) {
        val panel = screen.panel
        runOnUiThread { panel.refresh() }
        waitUntil("a reserva fora de \"Hospedados\"", LOAD_TIMEOUT_MILLIS) {
            val inHouse = panel.tabs.getValue(DayList.IN_HOUSE)
            !inHouse.loading && inHouse.rows.none { it.id == screen.departure.id }
        }
        println("Check-out ao vivo: a reserva saiu de \"Hospedados\" no painel do dia")
    }

    /** Os ids dos lançamentos de todas as contas da reserva (para achar o lançamento novo do late). */
    private fun postingIds(screen: Screen): Set<String> =
        screen.departure.folioIds
            .flatMap { id -> runBlocking { screen.folios.postings(id) }.map { it.id.toString() } }
            .toSet()

    /** Libera as cauções capturáveis das contas da reserva (registra uma de R$ 50,00 antes, se não há nenhuma). */
    private fun releaseCapturable(screen: Screen): Int =
        runBlocking {
            var freed = 0
            for (folioId in screen.departure.folioIds) {
                screen.payments
                    .preAuthorizations(folioId)
                    .filter { isCapturable(it.status, moneyOrNull(it.capturableAmount)) }
                    .forEach {
                        screen.payments.releasePreAuthorization(folioId, it.id.toString(), Intent())
                        freed++
                    }
            }
            freed
        }

    /** Registra uma caução de R$ 50,00 na primeira conta, se nenhuma conta da reserva tem caução capturável. */
    private fun ensureCapturable(
        payments: PaymentSource,
        departure: Departure,
    ) = runBlocking {
        val capturable =
            departure.folioIds.any { id ->
                payments.preAuthorizations(id).any { isCapturable(it.status, moneyOrNull(it.capturableAmount)) }
            }
        if (capturable) return@runBlocking
        val zone = payments.businessDay().zone ?: fail("o fuso do hotel não foi lido")
        val expires = ZonedDateTime.now(zone).plusDays(VALIDITY_DAYS).toInstant()
        val input = PreAuthorizationInput(Money.parse(DEPOSIT_API), expires, TEST_CODE)
        payments.registerPreAuthorization(departure.folioIds.first(), input, Intent())
        println("Check-out ao vivo: caução de R$ 50,00 registrada na conta")
    }

    /** Passo 1: sem turno, abre o turno no ponto "Recepção" do seed com fundo zero. */
    private fun ensureShift(cashier: CashierSource) =
        runBlocking {
            if (cashier.currentShift() != null) return@runBlocking
            val point = cashier.cashPoints().firstOrNull { it.name == "Recepção" } ?: fail(NO_POINT)
            val shift = cashier.openShift(point.id.toString(), Money.parse(ZERO), Intent())
            println("Check-out ao vivo: turno ${shift.number} aberto no ponto \"Recepção\"")
        }

    private fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        // o diálogo do assistente é uma raiz própria (a janela de 1280x900); a raiz do setContent fica vazia, com
        // tamanho zero. Captura a raiz com área, não a de posição fixa na lista.
        val roots = onAllNodes(isRoot())
        val sized = roots.fetchSemanticsNodes().indexOfFirst { it.size.width > 0 && it.size.height > 0 }
        check(sized >= 0) { "nenhuma raiz com tamanho para capturar" }
        ImageIO.write(roots[sized].captureToImage().toAwtImage(), "png", file)
    }

    @Suppress("LongMethod") // as portas, os estados reais e o observador do mapa, montados juntos
    private fun withScreen(block: DesktopComposeUiTest.(Screen) -> Unit) =
        withViewer { connection ->
            val reception = connection.receptionSource()
            val folios = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
            val payments = checkNotNull(connection.paymentSource()) { "a conexão não tem pagamentos" }
            val cashier = checkNotNull(connection.cashierSource()) { "a conexão não tem caixa" }
            val rooms: RoomsSource = connection.roomsSource()
            val probe = ProbeCheckOut(checkNotNull(connection.checkOutSource()) { "a conexão não tem check-out" })
            val permissions = runBlocking { connection.me() }.permissions
            val departure = departureOfTheDay(reception, folios)
            ensureShift(cashier)
            ensureCapturable(payments, departure)
            val screen = Screen(departure, folios, payments, probe)
            val wiring = FolioWiring(folios, permissions, { it.run(null) }, null, null, payments = payments)
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    val scope = rememberCoroutineScope()
                    val panel = remember { DayPanelState(reception, scope, permissions) }
                    val map = remember { RoomMapState(rooms, scope) }
                    val state =
                        remember {
                            CheckOutState(probe, wiring, scope, departure.id) { notice, reload ->
                                screen.closings++
                                screen.notice = notice
                                screen.reloadPanel = reload
                            }
                        }
                    LaunchedEffect(map) {
                        snapshotFlow { map.floors.flatMap { it.second }.firstOrNull { it.roomId == departure.roomId } }
                            .first { it?.status == RoomDisplayStatus.DIRTY }
                        screen.dirtyAt = TimeSource.Monotonic.markNow()
                    }
                    screen.panelShown = panel
                    screen.mapShown = map
                    screen.shown = state
                    ZenyTheme { CheckOutDialog(state) }
                }
                try {
                    waitUntil("o assistente", LOAD_TIMEOUT_MILLIS) { screen.shown != null }
                    waitUntil("o mapa", LOAD_TIMEOUT_MILLIS) { !screen.map.loading && screen.map.hasRooms }
                    check(screen.dirtyAt == null) { "a UH da reserva hospedada já aparece vaga e suja" }
                    block(screen)
                } finally {
                    screen.shown?.close()
                    screen.panelShown?.close()
                    screen.mapShown?.close()
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

/** A saída do dia escolhida: o id, o código, a UH e as contas dela. */
private class Departure(
    val id: String,
    val code: String,
    val roomId: String,
    val room: String,
    val folioIds: List<String>,
)

/** A primeira saída do dia hospedada, com UH e com conta, percorrendo as páginas de "Saídas". */
private fun departureOfTheDay(
    reception: ReceptionSource,
    folios: FolioSource,
): Departure =
    runBlocking {
        val date = reception.businessDay().date
        var cursor: String? = null
        do {
            val page = reception.dayPage(DayList.DEPARTURES, date, cursor, PAGE_LIMIT)
            page.items.firstNotNullOfOrNull { departureOf(it, folios) }?.let { return@runBlocking it }
            cursor = page.nextCursor
        } while (cursor != null)
        fail(NO_DEPARTURE)
    }

/** A saída [item], se hospedada, com UH e com conta; senão nula. */
private suspend fun departureOf(
    item: ReservationListItemResponse,
    folios: FolioSource,
): Departure? {
    val room = (item.roomAssignment as? Option.Some)?.value
    if (item.status != IN_HOUSE || room == null) return null
    val folioIds = folios.reservationFolios(item.id.toString()).map { it.id.toString() }
    val number = (room.roomNumber as? Option.Some)?.value ?: "?"
    return Departure(item.id.toString(), item.code, room.roomId.toString(), number, folioIds).takeIf {
        folioIds.isNotEmpty()
    }
}
