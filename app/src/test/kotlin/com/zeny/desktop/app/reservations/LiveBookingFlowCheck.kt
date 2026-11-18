package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.reception.PdfKind
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.app.reception.RegistrationCardDialog
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.PrintOutcome
import com.zeny.desktop.design.pdf.SaveLocationChooser
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
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

private const val WIDTH = 1400
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L

// O teto do `limit` do servidor nas listas por cursor é 200 (`PageParams.MAX_LIMIT`).
private const val PAGE_LIMIT = 200
private const val LIFECYCLE_ARRIVAL_DAYS = 30L
private const val LIFECYCLE_DEPARTURE_DAYS = 33L
private const val CHANGED_DEPARTURE_DAYS = 34L
private const val DISCOUNT_ARRIVAL_DAYS = 35L
private const val DISCOUNT_DEPARTURE_DAYS = 36L
private const val ADULTS = 2
private const val STD = "STD"
private const val BALCAO = "BALCAO"
private const val CONFIRMED = "CONFIRMED"
private const val IN_HOUSE = "IN_HOUSE"
private const val REHEARSAL = "ENSAIO E03"
private const val REHEARSAL_A = "ENSAIO E03 A"
private const val NEGOTIATED_RATE = "1,00"
private const val DISCOUNT_PERMISSION = "rates.discounts.apply"
private const val MAX_PERCENT = "maxPercent"
private const val AVAILABILITY_READ = "reservations.availability.read"
private const val APPROVAL_REASON = "Verificação ao vivo do desconto da E03"
private const val STALE_NOTICE =
    "Alguém alterou esta reserva. Os dados foram recarregados; confira e tente de novo."
private const val CONFIRMATION_TITLE = "Confirmação da reserva — "
private const val G1_CPF_BASE = "202600101"
private const val CPF_FIRST_WEIGHT = 10
private const val CPF_MODULUS = 11
private const val NO_OFFER = "NEED_SEED: sem preço ou sem unidade nas datas do ensaio; recrie a demo"
private const val NO_DISCOUNT_APPROVAL = "NEED_SEED: o desconto deveria exceder o teto de 10% da Recepcionista"
private val RECEPTIONIST_CEILING = BigDecimal("10")
private val ANY_SPACE = Regex("[\\s\\u00A0]+")
private val MONEY_DIGITS = Regex("""^\d{1,3}(\.\d{3})*,\d{2}$""")

/**
 * Verificação do ciclo da reserva da M3 E03 contra um servidor real (o ambiente `zeny-front`), com a Recepcionista e a
 * aprovação do Gerente, pelo `CoreConnection` real, pelos estados reais (`NewReservationState`,
 * `ReservationChangeState`, `RegistrationCardState` com `PdfKind.CONFIRMATION`) e pelo `ApprovalCoordinator` real.
 * **Escreve na demo e não desfaz a criação** (ruling 9): cada execução deixa duas reservas futuras do G1, com os
 * pedidos especiais começando por "ENSAIO E03" para a limpeza da E04; a saída do hospedado estendido volta à original.
 *
 * Pulada sem o opt-in `ZENY_LIVE_BOOKING=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`,
 * `ZENY_LIVE_VIEWER_PASSWORD`, `ZENY_LIVE_APPROVER_LOGIN` e `ZENY_LIVE_APPROVER_PIN` (só do ambiente, nunca
 * impressas). O texto da confirmação é extraído com o `PDFTextStripper` só aqui, em memória. Grava
 * `build/live/new-reservation.png`, `build/live/reservation-change.png` e `build/live/reservation-confirmation.png`.
 * Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_BOOKING=1 ./gradlew :app:test --tests '*LiveBookingFlowCheck*' --rerun
 * --max-workers=2)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveBookingFlowCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** O Gerente que aprova o desconto; o `toString` não mostra nada. */
    private class Approver(
        val login: String,
        val pin: String,
    ) {
        override fun toString(): String = "Approver(***)"
    }

    private class Screen {
        lateinit var scope: CoroutineScope
        lateinit var approvals: ApprovalCoordinator
        var newReservation by mutableStateOf<NewReservationState?>(null)
        var change by mutableStateOf<ReservationChangeState?>(null)
        var confirmation by mutableStateOf<RegistrationCardState?>(null)

        /** Os bytes da última confirmação baixada, vistos só pelo teste. */
        @Volatile var bytes: ByteArray? = null
    }

    /** A reserva criada no início do ciclo, que os passos seguintes alteram e confirmam. */
    private class Created(
        val id: String,
        val code: String,
    )

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_BOOKING") == "1", "sem ZENY_LIVE_BOOKING=1: verificação real pulada")
    }

    /**
     * Critérios 1, 4 e 5 e o passo 23.1 do roteiro, sobre a mesma reserva: cria pela cotação, altera em concorrência,
     * abre a confirmação e muda a saída com o total novo do servidor.
     */
    @Test
    fun bookingLifecycle() =
        withScreen { screen, ports, _ ->
            val today = runBlocking { ports.availability.businessDay().date }
            val arrival = today.plusDays(LIFECYCLE_ARRIVAL_DAYS)
            val departure = today.plusDays(LIFECYCLE_DEPARTURE_DAYS)
            val created = createFromQuote(screen, ports, arrival, departure)
            concurrentChangeReloads(screen, ports, created)
            confirmationOpens(screen, ports, created)
            departureChangeRaisesTotal(screen, ports, created, today.plusDays(CHANGED_DEPARTURE_DAYS))
        }

    /** Critério 2: a diária negociada abaixo do teto da Recepcionista pede o Gerente e cria uma reserva só. */
    @Test
    @Suppress("LongMethod") // um roteiro só: titular, contagem, pendência, aprovação e releitura
    fun discountNeedsApprovalAndCreatesOnce() =
        withScreen { screen, ports, approver ->
            val today = runBlocking { ports.availability.businessDay().date }
            val arrival = today.plusDays(DISCOUNT_ARRIVAL_DAYS)
            val departure = today.plusDays(DISCOUNT_DEPARTURE_DAYS)
            val offer = stdOffer(ports, arrival, departure)
            val state = newReservation(screen, ports, offer, QuotedStay(arrival, departure, ADULTS, emptyList()))
            val holderId = pickG1(state)
            val before = holderReservations(ports, holderId, arrival, departure)
            fill(state, NEGOTIATED_RATE)
            runOnUiThread { state.confirm() }
            awaitCreation(screen, state)
            if (screen.approvals.pending == null && state.done != null) {
                fail("$NO_DISCOUNT_APPROVAL: a reserva ${state.done?.code} foi criada sem pedir aprovação")
            }
            assertNull(state.rateError, "a diária $NEGOTIATED_RATE foi recusada no cliente: ${state.rateError}")
            assertNull(state.error, "a criação com a diária negociada falhou antes da aprovação: ${state.error}")
            val required = assertNotNull(screen.approvals.pending, "a criação com desconto não pediu aprovação")
            assertEquals(DISCOUNT_PERMISSION, required.permission, "a permissão pedida no APPROVAL_REQUIRED")
            val limit = assertNotNull(required.limit, "o APPROVAL_REQUIRED veio sem o limit do desconto")
            assertEquals(MAX_PERCENT, limit.key, "a chave do limit no APPROVAL_REQUIRED")
            val percent = limit.amount.toBigDecimalOrNull() ?: fail("o limit pedido não é número: ${limit.amount}")
            assertTrue(
                percent > RECEPTIONIST_CEILING,
                "$NO_DISCOUNT_APPROVAL: o desconto pedido é de $percent%, dentro do teto",
            )
            runOnUiThread {
                screen.approvals.login = approver.login
                screen.approvals.pin = approver.pin
                screen.approvals.reason = APPROVAL_REASON
            }
            assertTrue(
                screen.approvals.dialog?.canConfirm == true,
                "o diálogo de aprovação não aceita o login, o PIN (4 a 8 dígitos) ou o motivo do Gerente",
            )
            runOnUiThread { screen.approvals.confirm() }
            waitUntil("a criação aprovada pelo Gerente", LOAD_TIMEOUT_MILLIS) {
                screen.approvals.dialog?.message != null ||
                    (!state.busy && (state.done != null || state.error != null))
            }
            assertNull(screen.approvals.dialog?.message, "o Gerente foi recusado: ${screen.approvals.dialog?.message}")
            assertNull(state.error, "a criação aprovada falhou: ${state.error}")
            val done = assertNotNull(state.done, "a criação aprovada não concluiu")
            assertEquals(CONFIRMED, done.status, "a reserva com desconto nasceu ${done.status}, esperava CONFIRMED")
            val after = holderReservations(ports, holderId, arrival, departure)
            assertEquals(
                before.size + 1,
                after.size,
                "reservas do G1 em ${arrival.toApi()}..${departure.toApi()}: " +
                    "antes ${before.size}, depois ${after.size}",
            )
            assertEquals(setOf(done.reservationId), after - before, "a reserva nova da busca não é a criada")
            val reservation = runBlocking { ports.reception.reservation(done.reservationId) }.reservation
            assertTrue(
                (reservation.approvalId as? Option.Some)?.value != null,
                "a reserva ${done.code} não registra a aprovação do Gerente",
            )
            assertTrue(reservation.nightlyRates.isNotEmpty(), "a reserva ${done.code} veio sem noites")
            reservation.nightlyRates.forEach { night ->
                assertTrue(night.overridden, "a noite ${night.date} não ficou com a diária negociada")
                assertEquals(
                    0,
                    BigDecimal(night.roomAmount).compareTo(BigDecimal.ONE),
                    "a diária da noite ${night.date} é ${night.roomAmount}, esperava 1.00",
                )
            }
            println(
                "LiveBookingFlowCheck: desconto de $percent% pediu $DISCOUNT_PERMISSION; aprovado, reserva " +
                    "${done.code} criada uma vez (G1 no período: ${before.size} -> ${after.size})",
            )
            runOnUiThread {
                screen.newReservation = null
                state.close()
            }
        }

    /** Critério 3: um hospedado ganha um dia na saída, a reserva e o histórico mostram, e a saída volta à original. */
    @Test
    fun extendInHouseUpdatesPeriodAndHistory() =
        withScreen { screen, ports, _ ->
            val today = runBlocking { ports.reception.businessDay().date }
            val inHouse = allRows(ports, ReservationFilter(statuses = setOf(IN_HOUSE)))
            val candidates =
                inHouse
                    .filter { BusinessDate.parse(it.departure).value > today.value }
                    .sortedBy { it.code }
            if (candidates.isEmpty()) {
                fail(
                    "NEED_SEED: nenhum hospedado com saída depois de ${today.toApi()} " +
                        "(${inHouse.size} hospedado(s) no total); recrie a demo",
                )
            }
            val refusals = mutableListOf<String>()
            for (row in candidates) {
                val refusal = extendOne(screen, ports, row) ?: return@withScreen
                refusals += "${row.code}: $refusal"
            }
            fail("NEED_SEED: nenhum hospedado aceitou mais um dia na saída (recusas: $refusals); recrie a demo")
        }

    // --- Step 1 -------------------------------------------------------------------------------------------------

    /** Critério 1: cria a reserva STD/BALCAO pela cotação, com o G1, e confere total, busca e disponibilidade. */
    private fun DesktopComposeUiTest.createFromQuote(
        screen: Screen,
        ports: Ports,
        arrival: BusinessDate,
        departure: BusinessDate,
    ): Created {
        val availableBefore = stdAvailable(ports, arrival)
        val offer = stdOffer(ports, arrival, departure)
        val state = newReservation(screen, ports, offer, QuotedStay(arrival, departure, ADULTS, emptyList()))
        pickG1(state)
        fill(state, "")
        runOnUiThread { state.confirm() }
        awaitCreation(screen, state)
        assertNull(screen.approvals.pending, "a reserva sem diária negociada pediu aprovação de superior")
        assertNull(state.error, "a criação pela cotação falhou: ${state.error}")
        val done = assertNotNull(state.done, "a criação pela cotação não concluiu")
        assertEquals(CONFIRMED, done.status, "a reserva nasceu ${done.status}; a garantia NONE do BALCAO dá CONFIRMED")
        assertEquals(ReceptionText.money(offer.total), done.total, "o total da reserva criada não é o da oferta")
        waitForIdle()
        assertTrue(
            onAllNodesWithTag("new-reservation-done").fetchSemanticsNodes().isNotEmpty(),
            "o diálogo da nova reserva não mostra a reserva criada",
        )
        capture("new-reservation", "new-reservation-dialog")
        runOnUiThread {
            screen.newReservation = null
            state.close()
        }
        val found = runBlocking { ports.search.search(ReservationFilter(code = done.code), null, PAGE_LIMIT) }.items
        assertEquals(1, found.size, "a busca pelo código ${done.code} achou ${found.size} linha(s)")
        assertEquals(done.reservationId, found.single().id.toString(), "a linha do código ${done.code} é outra reserva")
        val availableAfter = stdAvailable(ports, arrival)
        assertEquals(
            availableBefore - 1,
            availableAfter,
            "o STD em ${arrival.toApi()} devia ter uma unidade a menos: antes $availableBefore, depois $availableAfter",
        )
        println(
            "LiveBookingFlowCheck: reserva ${done.code} criada pela cotação, ${done.status}, total ${done.total}; " +
                "STD em ${arrival.toApi()}: $availableBefore -> $availableAfter",
        )
        return Created(done.reservationId, done.code)
    }

    // --- Step 4 -------------------------------------------------------------------------------------------------

    /** Critério 4: duas alterações sobre a mesma versão; a segunda recebe o `412`, recarrega, e a refeita passa. */
    @Suppress("LongMethod") // um roteiro só: duas leituras, a 1ª alteração, o 412, a captura e a refeita
    private fun DesktopComposeUiTest.concurrentChangeReloads(
        screen: Screen,
        ports: Ports,
        created: Created,
    ) {
        val first = newChange(screen, ports, created.id)
        val second = newChange(screen, ports, created.id)
        try {
            // A 2ª fica no diálogo, que a inicia; as duas leem a mesma versão antes de qualquer alteração.
            runOnUiThread {
                screen.change = second
                first.start()
            }
            awaitLoaded(first, "1ª")
            awaitLoaded(second, "2ª")
            val segment = assertNotNull(second.segment, "o segmento da reserva ${created.code} não é dos oferecidos")
            assertTrue(first.specialRequests == REHEARSAL, "a 1ª leitura não traz os pedidos especiais da criação")
            val newSegment = BookingText.SEGMENTS.first { it != segment }

            runOnUiThread { first.specialRequests = REHEARSAL_A }
            assertTrue(first.specialRequests == REHEARSAL_A, "a 1ª alteração ignorou a troca dos pedidos especiais")
            runOnUiThread { first.confirm() }
            awaitSettled(screen, first)
            assertSettledDone(first, "a 1ª alteração (pedidos especiais)")

            runOnUiThread { second.segment = newSegment }
            assertEquals(newSegment, second.segment, "a 2ª alteração ignorou a troca do segmento")
            runOnUiThread { second.confirm() }
            awaitSettled(screen, second)
            assertNull(second.validation, "a 2ª alteração foi barrada no cliente: ${second.validation}")
            assertNull(second.done, "a 2ª alteração, com a versão velha, foi aceita: o If-Match não barrou")
            assertNull(second.error, "a 2ª alteração falhou em vez de recarregar: ${second.error}")
            assertEquals(STALE_NOTICE, second.notice, "o aviso do 412")
            assertEquals(segment, second.segment, "depois do 412 o segmento devia voltar ao do servidor")
            assertTrue(
                second.specialRequests == REHEARSAL_A,
                "depois do 412 os pedidos especiais deviam ser os da 1ª alteração",
            )
            waitForIdle()
            capture("reservation-change", "change-dialog")

            runOnUiThread { second.segment = newSegment }
            assertEquals(newSegment, second.segment, "a edição refeita depois do 412 foi ignorada")
            runOnUiThread { second.confirm() }
            awaitSettled(screen, second)
            assertNull(second.notice, "a confirmação refeita recebeu outro 412: não usou o ETag novo")
            assertSettledDone(second, "a alteração refeita (segmento)")
            assertTrue(second.done.orEmpty().contains(created.code), "o resumo da alteração não cita ${created.code}")

            val reservation = runBlocking { ports.reception.reservation(created.id) }.reservation
            assertEquals(newSegment, reservation.segment, "o segmento relido da reserva ${created.code}")
            assertTrue(
                (reservation.specialRequests as? Option.Some)?.value == REHEARSAL_A,
                "os pedidos especiais relidos da reserva ${created.code} não são os da 1ª alteração",
            )
            println(
                "LiveBookingFlowCheck: alteração concorrente da reserva ${created.code}: 412 recarregou; " +
                    "segmento $segment -> $newSegment na refeita, pedidos especiais da 1ª mantidos",
            )
        } finally {
            runOnUiThread {
                screen.change = null
                first.close()
                second.close()
            }
        }
    }

    // --- Passo 23.1 do roteiro ----------------------------------------------------------------------------------

    /**
     * Passo 23.1 do roteiro: a saída de uma reserva confirmada ganha um dia pela tela; o total novo que a tela mostra
     * é o que o servidor devolve (maior que o anterior) e o histórico ganha uma entrada. **Escreve** na reserva criada.
     */
    @Suppress("LongMethod") // um roteiro só: leitura, edição, espera, releitura e conferência
    private fun DesktopComposeUiTest.departureChangeRaisesTotal(
        screen: Screen,
        ports: Ports,
        created: Created,
        newDeparture: BusinessDate,
    ) {
        val before = runBlocking { ports.reception.reservation(created.id) }.reservation
        val historyBefore = runBlocking { ports.reception.history(created.id) }.size
        val totalBefore = BigDecimal(before.total)
        val change = newChange(screen, ports, created.id)
        try {
            runOnUiThread { change.start() }
            awaitLoaded(change, "${created.code}, na mudança de saída")
            assertTrue(!change.inHouse, "a reserva ${created.code} abriu como hospedada; esperava confirmada")
            runOnUiThread { change.departureText = DateInput.format(newDeparture) }
            assertEquals(DateInput.format(newDeparture), change.departureText, "a nova saída de ${created.code}")
            runOnUiThread { change.confirm() }
            awaitSettled(screen, change)
            assertSettledDone(change, "a mudança de saída da ${created.code}")
            val after = runBlocking { ports.reception.reservation(created.id) }.reservation
            assertEquals(newDeparture.toApi(), after.departure, "a saída relida da ${created.code}")
            val totalAfter = BigDecimal(after.total)
            assertTrue(
                totalAfter > totalBefore,
                "o total relido da ${created.code} devia crescer com a noite a mais: antes $totalBefore, " +
                    "depois $totalAfter",
            )
            assertEquals(
                "Reserva ${created.code} alterada. Novo total: ${ReceptionText.money(after.total)}.",
                change.done,
                "o total novo que a tela mostra da ${created.code} não é o relido do servidor ($totalAfter)",
            )
            val historyAfter = runBlocking { ports.reception.history(created.id) }.size
            assertEquals(
                historyBefore + 1,
                historyAfter,
                "entradas do histórico da ${created.code}: antes $historyBefore, depois $historyAfter",
            )
            println(
                "LiveBookingFlowCheck: reserva ${created.code} com a saída em ${newDeparture.toApi()}: total " +
                    "$totalBefore -> $totalAfter, igual ao da tela; histórico $historyBefore -> $historyAfter",
            )
        } finally {
            runOnUiThread { change.close() }
        }
    }

    // --- Step 5 -------------------------------------------------------------------------------------------------

    /** Critério 5: a confirmação em PDF abre no visualizador e traz o código, as datas e o total relido. */
    private fun DesktopComposeUiTest.confirmationOpens(
        screen: Screen,
        ports: Ports,
        created: Created,
    ) {
        val reservation = runBlocking { ports.reception.reservation(created.id) }.reservation
        val real = ports.confirmations.asCards()
        val cards = RegistrationCards { id -> real.registrationCard(id).also { screen.bytes = it.bytes } }

        @Suppress("InjectDispatcher") // as ações (salvar, imprimir) ficam inertes aqui; nada roda em outra thread
        val inline = Dispatchers.Unconfined
        lateinit var card: RegistrationCardState
        runOnUiThread {
            card =
                RegistrationCardState(
                    source = cards,
                    scope = screen.scope,
                    reservationId = created.id,
                    reservationCode = created.code,
                    newDocument = { bytes, scope -> PdfDocumentState(bytes, scope) },
                    newActions = { bytes, name ->
                        PdfActions(
                            bytes,
                            name,
                            SaveLocationChooser { null },
                            { PrintOutcome.CANCELLED },
                            inline,
                            inline,
                        )
                    },
                    kind = PdfKind.CONFIRMATION,
                )
            screen.confirmation = card
        }
        try {
            waitUntil("a confirmação em PDF de ${created.code}", LOAD_TIMEOUT_MILLIS) {
                card.viewer != null || card.error != null
            }
            assertNull(card.error, "a confirmação de ${created.code} mostrou erro: ${card.error}")
            assertEquals("$CONFIRMATION_TITLE${created.code}", card.title, "o título do visualizador")
            waitUntil("a página do visualizador", LOAD_TIMEOUT_MILLIS) {
                onAllNodesWithTag("pdf-page").fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            capture("reservation-confirmation", "registration-card-dialog")
            val bytes = assertNotNull(screen.bytes, "a confirmação não foi baixada")
            val found = assertConfirmationText(bytes, created.code, reservation)
            println("LiveBookingFlowCheck: confirmação de ${created.code} abriu: ${bytes.size} bytes, com $found")
        } finally {
            runOnUiThread {
                screen.confirmation = null
                card.close()
            }
        }
    }

    // --- Step 3 -------------------------------------------------------------------------------------------------

    /**
     * Estende a saída de [row] em um dia; `null` se estendeu (e conferiu e devolveu), ou a recusa definitiva do
     * servidor, para tentar o hospedado seguinte.
     */
    private fun DesktopComposeUiTest.extendOne(
        screen: Screen,
        ports: Ports,
        row: ReservationListItemResponse,
    ): String? {
        val id = row.id.toString()
        val original = BusinessDate.parse(row.departure)
        val extended = original.plusDays(1)
        val historyBefore = runBlocking { ports.reception.history(id) }
        val change = newChange(screen, ports, id)
        try {
            runOnUiThread { change.start() }
            awaitLoaded(change, row.code)
            assertTrue(change.inHouse, "a reserva ${row.code} da busca IN_HOUSE não abriu como hospedada")
            assertEquals(DateInput.format(original), change.departureText, "a saída lida de ${row.code}")
            runOnUiThread { change.departureText = DateInput.format(extended) }
            assertEquals(DateInput.format(extended), change.departureText, "a extensão de ${row.code} foi ignorada")
            runOnUiThread { change.confirm() }
            awaitSettled(screen, change)
            assertNull(change.validation, "a extensão de ${row.code} foi barrada no cliente: ${change.validation}")
            assertNull(change.notice, "a extensão de ${row.code} recebeu 412 sem alteração concorrente")
            if (change.done == null) return assertNotNull(change.error, "a extensão de ${row.code} não terminou")
        } finally {
            runOnUiThread { change.close() }
        }
        // A devolução roda sempre; a falha dela entra como suprimida na do corpo, sem mascará-la.
        val extension = Extension(row.code, id, original, extended)
        val body = runCatching { extension.assertApplied(ports, historyBefore.map { it.id.toString() }.toSet()) }
        val restore = runCatching { restoreDeparture(screen, ports, row.code, id, original) }
        body.exceptionOrNull()?.let { failure ->
            restore.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        restore.getOrThrow()
        return null
    }

    /** A reserva relida tem a saída nova, e o histórico, uma entrada a mais, da saída original para a nova. */
    private fun Extension.assertApplied(
        ports: Ports,
        historyBefore: Set<String>,
    ) {
        val reservation = runBlocking { ports.reception.reservation(id) }.reservation
        assertEquals(extended.toApi(), reservation.departure, "a saída relida de $code depois da extensão")
        val history = runBlocking { ports.reception.history(id) }
        assertEquals(
            historyBefore.size + 1,
            history.size,
            "entradas do histórico de $code: antes ${historyBefore.size}, depois ${history.size}",
        )
        val added = history.filter { it.id.toString() !in historyBefore }
        assertEquals(1, added.size, "o histórico de $code ganhou ${added.size} entrada(s) nova(s)")
        assertEquals(original.toApi(), added.single().before.departure, "a saída antes, na entrada nova de $code")
        assertEquals(extended.toApi(), added.single().after.departure, "a saída depois, na entrada nova de $code")
        println(
            "LiveBookingFlowCheck: hospedado $code estendido de ${original.toApi()} para ${extended.toApi()}; " +
                "histórico ${historyBefore.size} -> ${history.size}",
        )
    }

    /** Devolve a saída original pelo mesmo estado de alteração (uma instância nova: a que concluiu fica travada). */
    private fun DesktopComposeUiTest.restoreDeparture(
        screen: Screen,
        ports: Ports,
        code: String,
        id: String,
        original: BusinessDate,
    ) {
        val back = newChange(screen, ports, id)
        try {
            runOnUiThread { back.start() }
            awaitLoaded(back, "$code, na devolução")
            runOnUiThread { back.departureText = DateInput.format(original) }
            assertEquals(DateInput.format(original), back.departureText, "a devolução de $code foi ignorada")
            runOnUiThread { back.confirm() }
            awaitSettled(screen, back)
            assertSettledDone(back, "a devolução da saída original de $code")
            val reservation = runBlocking { ports.reception.reservation(id) }.reservation
            assertEquals(original.toApi(), reservation.departure, "a saída de $code não voltou à original")
            println("LiveBookingFlowCheck: saída de $code devolvida a ${original.toApi()}")
        } finally {
            runOnUiThread { back.close() }
        }
    }

    // --- Ajudantes com a tela -----------------------------------------------------------------------------------

    /** A nova reserva da oferta, no diálogo, com o `ApprovalCoordinator` real e as permissões da Recepcionista. */
    private fun ComposeUiTest.newReservation(
        screen: Screen,
        ports: Ports,
        offer: QuoteOfferResponse,
        stay: QuotedStay,
    ): NewReservationState {
        val approve: suspend (ApprovableAction<BookingResponse>) -> ApiResponse<BookingResponse>? =
            { screen.approvals.run(it) }
        lateinit var state: NewReservationState
        runOnUiThread {
            state =
                NewReservationState(
                    source = ports.booking,
                    guests = ports.reception,
                    scope = screen.scope,
                    offer = offer,
                    stay = stay,
                    permissions = ports.permissions,
                    approve = approve,
                    onChanged = {},
                )
            screen.newReservation = state
        }
        return state
    }

    /** Acha o G1 pelo CPF fictício no `GuestPickerState` real e o escolhe como titular; devolve o id dele. */
    private fun ComposeUiTest.pickG1(state: NewReservationState): String {
        val picker = state.picker
        assertTrue(picker.canSearch, "a Recepcionista não pode buscar hóspedes (guests.profiles.read)")
        runOnUiThread {
            picker.documentType = "CPF"
            picker.documentNumber = g1Cpf()
            picker.searchByDocument()
        }
        waitUntil("a busca do G1 pelo documento", LOAD_TIMEOUT_MILLIS) { !picker.searching }
        assertNull(picker.error, "a busca do G1 pelo documento falhou: ${picker.error}")
        val found = picker.results
        if (found.size != 1) {
            fail("NEED_SEED: o CPF fictício do G1 achou ${found.size} hóspede(s), esperava 1; recrie a demo")
        }
        runOnUiThread { picker.pick(found.single()) }
        assertEquals(found.single().id, state.holder?.id, "o G1 achado não virou o titular")
        return found.single().id
    }

    /** Os pedidos especiais do ensaio e a diária negociada ([rate] vazio = nenhuma). */
    private fun ComposeUiTest.fill(
        state: NewReservationState,
        rate: String,
    ) {
        runOnUiThread {
            state.specialRequests = REHEARSAL
            state.nightlyRateText = rate
        }
        assertTrue(state.specialRequests == REHEARSAL, "os pedidos especiais do ensaio não entraram")
        assertEquals(rate, state.nightlyRateText, "a diária negociada não entrou")
        assertTrue(state.canConfirm, "a nova reserva não pode ser confirmada: ${state.error}")
    }

    /** Espera a criação terminar ou parar na aprovação de superior. */
    private fun ComposeUiTest.awaitCreation(
        screen: Screen,
        state: NewReservationState,
    ) {
        waitUntil("a criação da reserva", LOAD_TIMEOUT_MILLIS) {
            screen.approvals.pending != null ||
                (!state.busy && (state.done != null || state.error != null || state.rateError != null))
        }
    }

    /** Uma alteração da reserva [id], com o `ApprovalCoordinator` real. */
    private fun ComposeUiTest.newChange(
        screen: Screen,
        ports: Ports,
        id: String,
    ): ReservationChangeState {
        val approve:
            suspend (ApprovableAction<ReservationDetailResponse>) -> ApiResponse<ReservationDetailResponse>? =
            { screen.approvals.run(it) }
        lateinit var state: ReservationChangeState
        runOnUiThread {
            state =
                ReservationChangeState(
                    source = ports.change,
                    scope = screen.scope,
                    reservationId = id,
                    canQuote = AVAILABILITY_READ in ports.permissions,
                    approve = approve,
                    onChanged = {},
                )
        }
        return state
    }

    /** Espera a primeira leitura da alteração; os campos ignoram escrita enquanto `loading`. */
    private fun ComposeUiTest.awaitLoaded(
        change: ReservationChangeState,
        which: String,
    ) {
        waitUntil("a leitura da reserva ($which)", LOAD_TIMEOUT_MILLIS) {
            !change.loading && (change.code != null || change.error != null)
        }
        assertNull(change.error, "a leitura da reserva para alterar ($which) falhou: ${change.error}")
    }

    /** Espera a confirmação da alteração assentar: concluída, recusada, barrada, recarregada (`412`) ou aprovação. */
    private fun ComposeUiTest.awaitSettled(
        screen: Screen,
        change: ReservationChangeState,
    ) {
        waitUntil("a alteração da reserva ${change.code}", LOAD_TIMEOUT_MILLIS) {
            val answered =
                change.done != null || change.notice != null || change.error != null || change.validation != null
            screen.approvals.pending != null || (!change.busy && !change.loading && answered)
        }
        assertNull(screen.approvals.pending, "a alteração da reserva ${change.code} pediu aprovação de superior")
    }

    private fun DesktopComposeUiTest.capture(
        name: String,
        tag: String,
    ) {
        val file = File("build/live/$name.png")
        file.parentFile.mkdirs()
        val image = onNodeWithTag(tag).captureToImage().toAwtImage()
        assertTrue(ImageIO.write(image, "png", file), "não gravou a captura $name.png")
        assertTrue(file.length() > 0, "a captura $name.png saiu vazia")
    }

    private fun withScreen(block: DesktopComposeUiTest.(Screen, Ports, Approver) -> Unit) =
        withViewer { connection, approver ->
            val ports = Ports(connection)
            val screen = Screen()
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.approvals = remember { ApprovalCoordinator(connection::grant, emptyFlow(), screen.scope) }
                    ZenyTheme {
                        screen.newReservation?.let { NewReservationDialog(it, onConfirmation = null) {} }
                        screen.change?.let { ReservationChangeDialog(it) {} }
                        screen.confirmation?.let { RegistrationCardDialog(it) {} }
                        ApprovalHost(screen.approvals)
                    }
                }
                try {
                    waitForIdle()
                    block(screen, ports, approver)
                } finally {
                    screen.newReservation?.close()
                    screen.change?.close()
                    screen.confirmation?.close()
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

/** As portas reais do `CoreConnection` que a verificação usa. */
private class Ports(
    connection: CoreConnection,
) {
    val reception = connection.receptionSource()
    val availability = connection.availabilitySource()
    val search = connection.reservationSearchSource()
    val booking = connection.newReservationSource()
    val change = connection.reservationChangeSource()
    val confirmations = connection.reservationConfirmations()
    val permissions: Set<String> = runBlocking { connection.me().permissions }
}

/** A extensão de um hospedado: o código, o id e a saída antes e depois. */
private class Extension(
    val code: String,
    val id: String,
    val original: BusinessDate,
    val extended: BusinessDate,
)

/** O `available` do STD em [date], pela `AvailabilitySource`. */
private fun stdAvailable(
    ports: Ports,
    date: BusinessDate,
): Int {
    val api = runBlocking { ports.availability.availability(date, date) }
    val std = api.roomTypes.firstOrNull { it.code == STD } ?: fail("NEED_SEED: o tipo $STD sumiu; recrie a demo")
    return std.days.firstOrNull { it.date == date.toApi() }?.available
        ?: fail("$NO_OFFER (a disponibilidade não trouxe o $STD em ${date.toApi()})")
}

/** A oferta STD do BALCAO na estadia, com unidade; sem ela, `NEED_SEED`. */
private fun stdOffer(
    ports: Ports,
    arrival: BusinessDate,
    departure: BusinessDate,
): QuoteOfferResponse {
    val quote = runBlocking { ports.availability.quote(arrival, departure, ADULTS, emptyList()) }
    val offer = quote.offers.firstOrNull { it.roomTypeCode == STD && it.ratePlanCode == BALCAO }
    if (offer == null || offer.availableUnits <= 0) {
        fail(
            "$NO_OFFER (${arrival.toApi()} a ${departure.toApi()}: ${quote.offers.size} oferta(s), " +
                "${quote.refused().size} recusa(s), unidades do $STD/$BALCAO: ${offer?.availableUnits})",
        )
    }
    return offer
}

private fun assertSettledDone(
    change: ReservationChangeState,
    what: String,
) {
    assertNull(change.validation, "$what foi barrada no cliente: ${change.validation}")
    assertNull(change.error, "$what falhou: ${change.error}")
    assertNull(change.notice, "$what recebeu 412: ${change.notice}")
    assertNotNull(change.done, "$what não concluiu")
}

/**
 * O texto da confirmação (só em memória, espaços normalizados) traz o código, as duas datas em `dd/MM/aaaa` e a parte
 * numérica do total relido; devolve o resumo do que achou, sem dado de hóspede.
 */
private fun assertConfirmationText(
    bytes: ByteArray,
    code: String,
    reservation: ReservationResponse,
): String {
    val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }.replace(ANY_SPACE, " ")
    val arrival = DateInput.format(BusinessDate.parse(reservation.arrival))
    val departure = DateInput.format(BusinessDate.parse(reservation.departure))
    val money = ReceptionText.money(reservation.total)
    val digits = money.removePrefix("R$ ")
    assertTrue(MONEY_DIGITS.matches(digits), "a parte numérica de $money não tem o formato 1.234,56")
    assertTrue(text.contains(code), "o texto da confirmação não traz o código $code")
    assertTrue(text.contains(arrival), "o texto da confirmação não traz a chegada $arrival")
    assertTrue(text.contains(departure), "o texto da confirmação não traz a saída $departure")
    assertTrue(text.contains(digits), "o texto da confirmação não traz o total relido $digits")
    return "${text.length} caracteres de texto, o código, $arrival, $departure e $digits"
}

/** As reservas do titular [holderId] que tocam o período, pela busca da Recepcionista. */
private fun holderReservations(
    ports: Ports,
    holderId: String,
    from: BusinessDate,
    to: BusinessDate,
): Set<String> =
    allRows(ports, ReservationFilter(from = from, to = to))
        .filter { it.holderGuestId.toString() == holderId }
        .map { it.id.toString() }
        .toSet()

/** Todas as páginas da busca. */
private fun allRows(
    ports: Ports,
    filter: ReservationFilter,
): List<ReservationListItemResponse> =
    runBlocking {
        val rows = mutableListOf<ReservationListItemResponse>()
        var cursor: String? = null
        do {
            val page = ports.search.search(filter, cursor, PAGE_LIMIT)
            rows += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        rows
    }

/** O CPF fictício do G1: a base mais os dois dígitos verificadores, a mesma regra de `FictitiousDocuments`. */
private fun g1Cpf(): String {
    fun digit(
        base: String,
        firstWeight: Int,
    ): Int {
        val sum = base.mapIndexed { i, c -> (c - '0') * (firstWeight - i) }.sum()
        return (sum % CPF_MODULUS).let { if (it < 2) 0 else CPF_MODULUS - it }
    }
    val d1 = digit(G1_CPF_BASE, CPF_FIRST_WEIGHT)
    val d2 = digit(G1_CPF_BASE + d1, CPF_FIRST_WEIGHT + 1)
    return "$G1_CPF_BASE$d1$d2"
}

private fun BusinessDate.plusDays(days: Long) = BusinessDate(value.plusDays(days))
