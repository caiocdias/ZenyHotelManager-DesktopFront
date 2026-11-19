@file:OptIn(ExperimentalTestApi::class)

package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.app.reception.ReservationDetailPanel
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.table.TableState
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationPenaltyResponse
import com.zeny.desktop.sdk.model.CancellationResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1400
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L

// A marca de realocação é posta por um listener assíncrono do servidor: a lista é relida por até 30 s.
private const val REALLOCATION_TIMEOUT_MILLIS = 30_000L
private const val REALLOCATION_RETRY_NANOS = 2_000_000_000L

// O teto do `limit` do servidor nas listas por cursor é 200 (`PageParams.MAX_LIMIT`).
private const val PAGE_LIMIT = 200
private const val ADULTS = 2
private const val STD = "STD"
private const val BALCAO = "BALCAO"
private const val CONFIRMED = "CONFIRMED"
private const val CANCELLED = "CANCELLED"
private const val GUEST_REQUEST = "GUEST_REQUEST"
private const val OTHER = "OTHER"
private const val REHEARSAL = "ENSAIO E04"
private const val REHEARSAL_MARK = "ENSAIO E0"
private const val SEED_PREFIX = "DEMO-"
private const val PRE_BOOKING = "DEMO-PEND-P1"
private const val REALLOCATED = "DEMO-PEND-R1"
private const val REALLOCATED_DETAIL = "UH 208 fora de ordem na estadia"
private const val PRE_BOOKING_KIND = "Pré-reserva"
private const val ONE_NIGHT_PENALTY = "multa de 1 diária(s)"
private const val PENALTY_POSTED = "Multa lançada na conta: "
private const val PENALTY_WAIVED = "Multa isenta: "
private const val NO_NEW_PENALTY = "Sem multa nova. A conta já tem "
private const val PREVIOUS_PENALTY = " de multa de cancelamento anterior."
private const val REACTIVATE_PERMISSION = "reservations.reservations.reactivate"
private const val APPROVAL_REASON = "Verificação ao vivo da reativação da E04"
private const val WAIVE_PERMISSION = "reservations.penalties.waive"
private const val WAIVER_REASON = "Verificação ao vivo da isenção da multa da E12"
private const val G1_CPF_BASE = "202600101"
private const val CPF_FIRST_WEIGHT = 10
private const val CPF_MODULUS = 11
private const val NO_OFFER = "NEED_SEED: sem preço ou sem unidade do STD/BALCAO para hoje; recrie a demo"
private const val NO_PRE_BOOKING =
    "NEED_SEED: rode o demo-seed desta etapa; a pré-reserva vence 48 h depois de criada"
private const val NO_REALLOCATION =
    "NEED_SEED: rode o demo-seed desta etapa (a DEMO-PEND-R1, alocada na UH 208, que entra em fora de ordem)"

/**
 * Verificação do cancelamento, da reativação e das pendências da M3 E04, e da multa de cancelamento da M4 E12 (o
 * texto do recancelamento e a isenção), contra um servidor real (o ambiente `zeny-front`), com a Recepcionista e a
 * aprovação do Gerente, pelo `CoreConnection` real, pelos estados reais (`NewReservationState`,
 * `ReservationCancelState`, `ReservationReactivateState`, `PendingListsState`, `ReservationDetailState`), pelos
 * diálogos e pelo painel reais e pelo `ApprovalCoordinator` real; a conta da reserva é lida pela `FolioSource` real.
 * **Escreve na demo e não desfaz** (ruling 6): cada execução deixa duas reservas canceladas do G1 com a chegada hoje,
 * com os pedidos especiais "ENSAIO E04" (uma com a multa lançada na conta, outra com a multa isenta pelo Gerente), e
 * cancela as reservas confirmadas do G1 cujos pedidos especiais começam com "ENSAIO E0" (nunca uma do seed, de
 * `externalId` "DEMO-…").
 *
 * Pulada sem o opt-in `ZENY_LIVE_LIFECYCLE=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`,
 * `ZENY_LIVE_VIEWER_PASSWORD`, `ZENY_LIVE_APPROVER_LOGIN` e `ZENY_LIVE_APPROVER_PIN` (só do ambiente, nunca
 * impressas). Depende do seed desta etapa (`DEMO-PEND-P1` e `DEMO-PEND-R1`). Grava `build/live/reservation-cancel.png`
 * e `build/live/pending.png`. Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_LIFECYCLE=1 ./gradlew :app:test --tests '*LiveReservationLifecycleCheck*'
 * --rerun --max-workers=2)`
 */
class LiveReservationLifecycleCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** O Gerente que aprova a reativação; o `toString` não mostra nada. */
    private class Approver(
        val login: String,
        val pin: String,
    ) {
        override fun toString(): String = "Approver(***)"
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_LIFECYCLE") == "1", "sem ZENY_LIVE_LIFECYCLE=1: verificação real pulada")
    }

    /**
     * Critérios 1 e 2 da E04, sobre a mesma reserva de chegada hoje: cancela com a multa lançada, reativa com o
     * Gerente, acha a reativada nos no-shows previstos e cancela de novo, sem outra multa (e, desde a E12, com o texto
     * "Sem multa nova" e a multa do 1º cancelamento intacta na conta). Se ela ficar confirmada no fim (o teste falhou
     * no meio), a limpeza a cancela ([withRehearsal]).
     */
    @Test
    fun cancelAndReactivate() =
        withScreen { screen, ports, approver ->
            withRehearsal(screen, ports) { created ->
                val penalty = cancelWithPenalty(screen, ports, created)
                reactivateWithApproval(screen, ports, approver, created)
                listedAsExpectedNoShow(screen, ports, created)
                cancelAgainWithoutPenalty(screen, ports, created, penalty)
            }
        }

    /**
     * Critério 3 da E12: uma reserva de ensaio de chegada hoje (o prazo da política vencido, a multa devida) cancelada
     * com "Isentar a multa", aprovada pelo Gerente se o servidor pedir: o resumo inteiro é "Reserva <código>
     * cancelada. Multa isenta: <valor>." e a conta da reserva fica sem cobrança de multa
     * ([noPenaltyChargedOnTheFolio]). A limpeza é a do [withRehearsal].
     */
    @Test
    fun cancelWithWaiver() =
        withScreen { screen, ports, approver ->
            withRehearsal(screen, ports) { created -> cancelWaived(screen, ports, approver, created) }
        }

    /**
     * Critério 3: as quatro listas carregam, cada pendência do seed está nelas uma vez e a linha abre o detalhe.
     * Sustenta também o passo 26 do roteiro: "Depósitos em falta" está vazia. A lista só ganha linha com operações de
     * `billing`, que são do M4; quando o M4 as trouxer, esta asserção e o texto do roteiro mudam juntos.
     */
    @Test
    fun pendingListsLoad() =
        withScreen { screen, ports, _ ->
            val preBooking = singleSeedReservation(ports, PRE_BOOKING, NO_PRE_BOOKING)
            val reallocated = singleSeedReservation(ports, REALLOCATED, NO_REALLOCATION)
            lateinit var pending: PendingListsState
            lateinit var detail: ReservationDetailState
            runOnUiThread {
                pending = PendingListsState(ports.pending, screen.scope, { Instant.now() }, PAGE_LIMIT)
                detail = ReservationDetailState(ports.reception, screen.scope)
                screen.detail = detail
                screen.pending = pending
                pending.start()
            }
            val counts = PendingList.entries.associateWith { loadAll(screen, pending.table(it), it.label).size }
            assertEquals(
                0,
                counts[PendingList.DEPOSITS],
                "\"${PendingList.DEPOSITS.label}\" devia estar vazia (o depósito é do M4); veio com " +
                    "${counts[PendingList.DEPOSITS]} linha(s)",
            )
            val guarantees = pending.table(PendingList.GUARANTEES).rows
            val guarantee =
                guarantees.firstOrNull { it.code == preBooking.code }
                    ?: fail(
                        "$NO_PRE_BOOKING (a $PRE_BOOKING é a ${preBooking.code}, situação ${preBooking.status}, e " +
                            "não está em \"${PendingList.GUARANTEES.label}\", que tem ${guarantees.size} linha(s))",
                    )
            assertEquals(
                preBooking.id.toString(),
                guarantee.reservationId,
                "a linha da ${preBooking.code} é outra reserva",
            )
            assertTrue(
                guarantee.detail.startsWith(PRE_BOOKING_KIND),
                "a ${preBooking.code} aparece como \"${guarantee.detail}\"; esperava começar com \"$PRE_BOOKING_KIND\"",
            )
            val reallocation = awaitReallocation(screen, pending, reallocated)
            assertEquals(
                reallocated.id.toString(),
                reallocation.reservationId,
                "a linha da ${reallocated.code} é outra reserva",
            )
            assertEquals(REALLOCATED_DETAIL, reallocation.detail, "a situação da ${reallocated.code} na realocação")
            println(
                "LiveReservationLifecycleCheck: pendências carregadas: garantias ${counts[PendingList.GUARANTEES]}, " +
                    "depósitos ${counts[PendingList.DEPOSITS]}, no-shows previstos ${counts[PendingList.NO_SHOWS]}, " +
                    "realocação ${pending.table(PendingList.REALLOCATION).rows.size}; ${preBooking.code} " +
                    "($PRE_BOOKING) nas garantias e ${reallocated.code} ($REALLOCATED) na realocação, uma de cada",
            )
            openFromPanel(pending, detail, preBooking)
        }

    /**
     * Limpeza prometida pela E03: cancela, com o motivo `OTHER`, as reservas confirmadas do G1 cujos pedidos especiais
     * começam com "ENSAIO E0"; nenhuma do seed (`externalId` "DEMO-…") nem nenhuma sem a marca é tocada.
     */
    @Test
    @Suppress("LongMethod") // um roteiro só: a lista do G1, a triagem, os cancelamentos e as duas conferências
    fun rehearsalCleanup() =
        withScreen { screen, ports, _ ->
            val before = g1Rows(ports)
            val seed = before.filter { externalId(it)?.startsWith(SEED_PREFIX) == true }
            val candidates = before - seed.toSet()
            val marked = mutableListOf<LifecycleReservation>()
            val untouched = seed.map { it.id.toString() }.toMutableSet()
            for (row in candidates) {
                val reservation = runBlocking { ports.reception.reservation(row.id.toString()) }.reservation
                if (reservation.status == CONFIRMED && requestsOf(reservation).startsWith(REHEARSAL_MARK)) {
                    marked += LifecycleReservation(row.id.toString(), row.code)
                } else {
                    untouched += row.id.toString()
                }
            }
            val cancelled = mutableListOf<String>()
            for (rehearsal in marked) {
                cancelRehearsal(screen, ports, rehearsal)
                cancelled += rehearsal.code
            }
            // A prova pela lista dos cancelados: cada um, relido do servidor, tem a marca e não é do seed.
            val byCode = before.associateBy { it.code }
            cancelled.forEach { code ->
                val row = assertNotNull(byCode[code], "o cancelado $code não estava na lista do G1")
                val reservation = runBlocking { ports.reception.reservation(row.id.toString()) }.reservation
                assertTrue(requestsOf(reservation).startsWith(REHEARSAL_MARK), "o cancelado $code não tinha a marca")
                assertFalse(externalId(row)?.startsWith(SEED_PREFIX) == true, "o cancelado $code é do seed")
                assertEquals(CANCELLED, reservation.status, "a situação relida do cancelado $code")
            }
            val after = g1Rows(ports)
            val afterIds = after.map { it.id.toString() }.toSet()
            val touched = untouched - afterIds
            assertTrue(
                touched.isEmpty(),
                "${touched.size} reserva(s) confirmada(s) do G1 sem a marca (ou do seed) saíram das confirmadas: " +
                    before.filter { it.id.toString() in touched }.map { it.code },
            )
            val leftover =
                after.filter { row ->
                    externalId(row)?.startsWith(SEED_PREFIX) != true &&
                        requestsOf(runBlocking { ports.reception.reservation(row.id.toString()) }.reservation)
                            .startsWith(REHEARSAL_MARK)
                }
            assertTrue(
                leftover.isEmpty(),
                "ensaios confirmados do G1 sobraram depois da limpeza: ${leftover.map { it.code }}",
            )
            println(
                "LiveReservationLifecycleCheck: limpeza dos ensaios: ${cancelled.size} reserva(s) do G1 cancelada(s) " +
                    "$cancelled; ${untouched.size} confirmada(s) sem a marca ou do seed intocada(s) " +
                    "(${seed.size} do seed)",
            )
        }

    // --- Step 1 -------------------------------------------------------------------------------------------------

    /**
     * Cria o ensaio de hoje ([createToday]) e roda [steps] sobre ele; se o ensaio ficar confirmado no fim (o teste
     * falhou no meio), a limpeza o cancela, e a falha da limpeza entra como suprimida na do corpo.
     */
    private fun DesktopComposeUiTest.withRehearsal(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        steps: DesktopComposeUiTest.(LifecycleReservation) -> Unit,
    ) {
        val today = runBlocking { ports.availability.businessDay().date }
        val created = createToday(screen, ports, today)
        val body = runCatching { steps(created) }
        val cleanup = runCatching { cancelIfStillConfirmed(screen, ports, created) }
        body.exceptionOrNull()?.let { failure ->
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
    }

    /** Cria pela `NewReservationState` a reserva STD/BALCAO do G1 de hoje a amanhã, com os pedidos do ensaio. */
    private fun DesktopComposeUiTest.createToday(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        today: BusinessDate,
    ): LifecycleReservation {
        val departure = today.plusDays(1)
        val offer = stdOffer(ports, today, departure)
        val state = newReservation(screen, ports, offer, QuotedStay(today, departure, ADULTS, emptyList()))
        try {
            pickG1(state)
            runOnUiThread { state.specialRequests = REHEARSAL }
            assertTrue(state.specialRequests == REHEARSAL, "os pedidos especiais do ensaio não entraram")
            assertTrue(state.canConfirm, "a reserva do ensaio não pode ser confirmada: ${state.error}")
            runOnUiThread { state.confirm() }
            waitUntil("a criação da reserva do ensaio", LOAD_TIMEOUT_MILLIS) {
                screen.approvals.pending != null ||
                    (!state.busy && (state.done != null || state.error != null || state.rateError != null))
            }
            assertNull(screen.approvals.pending, "a reserva do ensaio, sem diária negociada, pediu aprovação")
            assertNull(state.rateError, "a diária da reserva do ensaio foi recusada no cliente: ${state.rateError}")
            assertNull(state.error, "a criação da reserva do ensaio falhou: ${state.error}")
            val done = assertNotNull(state.done, "a criação da reserva do ensaio não concluiu")
            assertEquals(CONFIRMED, done.status, "a reserva do ensaio nasceu ${done.status}, esperava CONFIRMED")
            println(
                "LiveReservationLifecycleCheck: reserva ${done.code} criada para ${today.toApi()} a " +
                    "${departure.toApi()}, ${done.status}, total ${done.total}",
            )
            return LifecycleReservation(done.reservationId, done.code)
        } finally {
            runOnUiThread {
                screen.newReservation = null
                state.close()
            }
        }
    }

    /**
     * Critério 1: os termos antes de confirmar, e depois a multa do servidor lançada na conta. Devolve a multa e o
     * lançamento dela.
     */
    @Suppress("LongMethod") // um roteiro só: termos, captura, confirmação, releitura e o texto da multa
    private fun DesktopComposeUiTest.cancelWithPenalty(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        created: LifecycleReservation,
    ): ChargedPenalty {
        val state = openCancel(screen, ports, created)
        try {
            val terms = assertNotNull(state.terms, "o diálogo de ${created.code} não leu os termos")
            assertTrue(
                terms.contains(ONE_NIGHT_PENALTY),
                "os termos de ${created.code} são \"$terms\"; esperava \"$ONE_NIGHT_PENALTY\" (a política " +
                    "Flexível (demo) do BALCAO; se mudou, recrie a demo)",
            )
            assertEquals(created.code, state.code, "o diálogo de cancelamento leu outra reserva")
            waitForIdle()
            assertTrue(
                onAllNodesWithTag("cancel-terms").fetchSemanticsNodes().isNotEmpty(),
                "o diálogo de cancelamento não mostra os termos antes de confirmar",
            )
            capture("reservation-cancel", "cancel-dialog")
            runOnUiThread { state.reason = GUEST_REQUEST }
            assertEquals(GUEST_REQUEST, state.reason, "o motivo do cancelamento")
            assertFalse(state.waivePenalty, "o cancelamento do ensaio não pode isentar a multa")
            val done = confirmCancel(screen, state, created.code)
            val reservation = runBlocking { ports.reception.reservation(created.id) }.reservation
            assertEquals(CANCELLED, reservation.status, "a situação relida de ${created.code} depois do cancelamento")
            val cancellation = cancellationOf(reservation)
            assertEquals(GUEST_REQUEST, cancellation.reason, "o motivo gravado no cancelamento de ${created.code}")
            assertEquals(CONFIRMED, cancellation.previousStatus, "a situação anterior gravada de ${created.code}")
            val penalty = cancellation.penalty
            val amount = amountOf(penalty.amount, "a multa de ${created.code}")
            assertTrue(
                amount.signum() > 0,
                "a multa de ${created.code} é ${penalty.amount}; esperava maior que zero (a chegada é hoje: o " +
                    "prazo da política já passou)",
            )
            assertFalse(penalty.waived, "a multa de ${created.code} veio isenta sem pedir isenção")
            assertTrue(
                (penalty.postingId as? Option.Some)?.value != null,
                "a multa de ${created.code} não tem lançamento na conta (postingId vazio)",
            )
            val postingId = (penalty.postingId as? Option.Some)?.value?.toString().orEmpty()
            assertEquals(
                0,
                amountOf(reservation.penaltiesCharged, "as multas cobradas de ${created.code}").compareTo(amount),
                "as multas cobradas de ${created.code} são ${reservation.penaltiesCharged}; esperava ${penalty.amount}",
            )
            val expected = "$PENALTY_POSTED${ReceptionText.money(penalty.amount)}."
            assertTrue(done.contains(expected), "o resumo do cancelamento é \"$done\"; esperava conter \"$expected\"")
            println(
                "LiveReservationLifecycleCheck: reserva ${created.code} cancelada (GUEST_REQUEST) com multa " +
                    "${ReceptionText.money(penalty.amount)} lançada; resumo: $done",
            )
            return ChargedPenalty(amount, postingId)
        } finally {
            closeCancel(screen, state)
        }
    }

    // --- Step 2 -------------------------------------------------------------------------------------------------

    /** Critério 2: a reativação pede o Gerente (`reservations.reservations.reactivate`) e volta a CONFIRMED. */
    @Suppress("LongMethod") // um roteiro só: pendência, permissão, aprovação, resumo e releitura
    private fun ComposeUiTest.reactivateWithApproval(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        approver: Approver,
        created: LifecycleReservation,
    ) {
        val approve:
            suspend (ApprovableAction<ReservationDetailResponse>) -> ApiResponse<ReservationDetailResponse>? =
            { screen.approvals.run(it) }
        lateinit var state: ReservationReactivateState
        runOnUiThread {
            state = ReservationReactivateState(ports.reactivate, screen.scope, created.id, created.code, approve) {}
            screen.reactivate = state
        }
        try {
            runOnUiThread { state.confirm() }
            waitUntil("o pedido de aprovação da reativação de ${created.code}", LOAD_TIMEOUT_MILLIS) {
                screen.approvals.pending != null || (!state.busy && (state.done != null || state.error != null))
            }
            val required =
                screen.approvals.pending
                    ?: fail(
                        "a reativação de ${created.code} não pediu aprovação de superior (resumo: ${state.done}, " +
                            "erro: ${state.error}); a Recepcionista não devia ter $REACTIVATE_PERMISSION",
                    )
            assertEquals(REACTIVATE_PERMISSION, required.permission, "a permissão pedida no APPROVAL_REQUIRED")
            approveAsManager(screen, approver, APPROVAL_REASON, "a reativação aprovada pelo Gerente") {
                !state.busy && (state.done != null || state.error != null)
            }
            assertNull(state.error, "a reativação aprovada de ${created.code} falhou: ${state.error}")
            assertEquals("Reserva ${created.code} reativada.", state.done, "o resumo da reativação")
            val reservation = runBlocking { ports.reception.reservation(created.id) }.reservation
            assertEquals(CONFIRMED, reservation.status, "a situação relida de ${created.code} depois da reativação")
            assertNull(
                (reservation.cancellation as? Option.Some)?.value,
                "a reserva ${created.code} reativada ainda traz o cancelamento",
            )
            println(
                "LiveReservationLifecycleCheck: reserva ${created.code} reativada com a aprovação do Gerente " +
                    "($REACTIVATE_PERMISSION); relida CONFIRMED",
            )
        } finally {
            runOnUiThread {
                screen.reactivate = null
                state.close()
            }
        }
    }

    /**
     * Aprova como o Gerente o pedido pendente do `ApprovalCoordinator`: o único caminho do PIN, que só vai ao diálogo
     * de aprovação. Espera a recusa do Gerente ou a ação [settled] e exige que ele não tenha sido recusado.
     */
    private fun ComposeUiTest.approveAsManager(
        screen: LifecycleScreen,
        approver: Approver,
        reason: String,
        what: String,
        settled: () -> Boolean,
    ) {
        runOnUiThread {
            screen.approvals.login = approver.login
            screen.approvals.pin = approver.pin
            screen.approvals.reason = reason
        }
        assertTrue(
            screen.approvals.dialog?.canConfirm == true,
            "o diálogo de aprovação não aceita o login, o PIN (4 a 8 dígitos) ou o motivo do Gerente",
        )
        runOnUiThread { screen.approvals.confirm() }
        waitUntil(what, LOAD_TIMEOUT_MILLIS) { screen.approvals.dialog?.message != null || settled() }
        assertNull(screen.approvals.dialog?.message, "o Gerente foi recusado: ${screen.approvals.dialog?.message}")
    }

    /** Confirmada, de chegada hoje e sem check-in: a reserva está em "No-shows previstos". */
    private fun ComposeUiTest.listedAsExpectedNoShow(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        created: LifecycleReservation,
    ) {
        lateinit var pending: PendingListsState
        runOnUiThread {
            pending = PendingListsState(ports.pending, screen.scope, { Instant.now() }, PAGE_LIMIT)
            pending.start()
        }
        try {
            val label = PendingList.NO_SHOWS.label
            val rows = loadAll(screen, pending.table(PendingList.NO_SHOWS), label)
            val row =
                rows.firstOrNull { it.code == created.code }
                    ?: fail(
                        "a reserva ${created.code}, confirmada com a chegada hoje e sem check-in, não está em " +
                            "\"$label\" (${rows.size} linha(s))",
                    )
            assertEquals(created.id, row.reservationId, "a linha de ${created.code} nos no-shows é outra reserva")
            println(
                "LiveReservationLifecycleCheck: ${created.code} reativada está em \"$label\" " +
                    "(${rows.size} linha(s))",
            )
        } finally {
            runOnUiThread { pending.close() }
        }
    }

    /**
     * Cancela de novo a reativada: CANCELLED, e a multa já cobrada não é lançada outra vez. O resumo diz "Sem multa
     * nova" com o `penaltiesCharged` relido, e o lançamento da multa do 1º cancelamento continua na conta da reserva,
     * sem estorno, com o valor do `penaltiesCharged` e sem outro lançamento no mesmo código (critério 2 da E12).
     */
    @Suppress("LongMethod") // um roteiro só: releitura, multa, conta e o texto da multa anterior
    private fun DesktopComposeUiTest.cancelAgainWithoutPenalty(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        created: LifecycleReservation,
        charged: ChargedPenalty,
    ) {
        val state = openCancel(screen, ports, created)
        try {
            runOnUiThread { state.reason = GUEST_REQUEST }
            val done = confirmCancel(screen, state, created.code)
            val reservation = runBlocking { ports.reception.reservation(created.id) }.reservation
            assertEquals(CANCELLED, reservation.status, "a situação relida de ${created.code} no 2º cancelamento")
            val penalty = cancellationOf(reservation).penalty
            assertEquals(
                0,
                amountOf(penalty.amount, "a multa do 2º cancelamento de ${created.code}").signum(),
                "o 2º cancelamento de ${created.code} devia ter multa zero (já cobrada); veio ${penalty.amount}",
            )
            assertNull(
                (penalty.postingId as? Option.Some)?.value,
                "o 2º cancelamento de ${created.code} lançou outra multa na conta",
            )
            assertEquals(
                0,
                amountOf(reservation.penaltiesCharged, "as multas cobradas de ${created.code}")
                    .compareTo(charged.amount),
                "as multas cobradas de ${created.code} mudaram no 2º cancelamento: ${reservation.penaltiesCharged}",
            )
            val previousText = "$NO_NEW_PENALTY${ReceptionText.money(reservation.penaltiesCharged)}$PREVIOUS_PENALTY"
            assertEquals("Reserva ${created.code} cancelada. $previousText", done, "o resumo do 2º cancelamento")
            firstPenaltyStaysOnTheFolio(ports, created, reservation, charged)
            println("LiveReservationLifecycleCheck: reserva ${created.code} cancelada de novo, sem outra multa: $done")
        } finally {
            closeCancel(screen, state)
        }
    }

    /**
     * A multa do 1º cancelamento ([charged]) continua na conta da reserva relida: o mesmo lançamento, sem estorno, com
     * o valor do `penaltiesCharged`, e nenhum outro lançamento no mesmo código de transação.
     */
    private fun firstPenaltyStaysOnTheFolio(
        ports: LifecyclePorts,
        created: LifecycleReservation,
        reservation: ReservationResponse,
        charged: ChargedPenalty,
    ) {
        val postings = postingsOf(ports, reservation)
        val first =
            postings.firstOrNull { it.id.toString() == charged.postingId }
                ?: fail("o lançamento da multa do 1º cancelamento de ${created.code} não está na conta da reserva")
        assertNull(
            (first.reversedBy as? Option.Some)?.value,
            "a multa do 1º cancelamento de ${created.code} foi estornada",
        )
        assertEquals(
            0,
            amountOf(first.amount, "o lançamento da multa de ${created.code}")
                .compareTo(amountOf(reservation.penaltiesCharged, "as multas cobradas de ${created.code}")),
            "o lançamento da multa de ${created.code} é ${first.amount}; as multas cobradas são " +
                reservation.penaltiesCharged,
        )
        val others = postings.filter { it.transactionCodeId == first.transactionCodeId && it.id != first.id }
        assertTrue(
            others.isEmpty(),
            "a conta de ${created.code} tem ${others.size} lançamento(s) a mais no código da multa",
        )
    }

    /**
     * Critério 3 da E12: cancela com "Isentar a multa", aprova como o Gerente se o servidor pedir e exige a multa
     * devida isenta, o resumo inteiro "Reserva <código> cancelada. Multa isenta: <valor do servidor>." e a conta sem
     * cobrança de multa ([noPenaltyChargedOnTheFolio]).
     */
    @Suppress("LongMethod") // um roteiro só: isenção, aprovação, releitura, conta e o texto da multa isenta
    private fun DesktopComposeUiTest.cancelWaived(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        approver: Approver,
        created: LifecycleReservation,
    ) {
        val state = openCancel(screen, ports, created)
        try {
            runOnUiThread {
                state.reason = GUEST_REQUEST
                state.waivePenalty = true
            }
            assertTrue(state.waivePenalty, "a isenção da multa de ${created.code} não foi marcada")
            runOnUiThread { state.confirm() }
            val settled = { !state.busy && (state.done != null || state.error != null) }
            waitUntil("o cancelamento com isenção de ${created.code}", LOAD_TIMEOUT_MILLIS) {
                screen.approvals.pending != null || settled()
            }
            if (WAIVE_PERMISSION !in ports.permissions && screen.approvals.pending == null) {
                fail(
                    "o cancelamento com isenção de ${created.code} não pediu aprovação de superior (resumo: " +
                        "${state.done}, erro: ${state.error}); a Recepcionista não tem $WAIVE_PERMISSION",
                )
            }
            screen.approvals.pending?.let { asked ->
                assertEquals(WAIVE_PERMISSION, asked.permission, "a permissão pedida na isenção da multa")
                approveAsManager(screen, approver, WAIVER_REASON, "a isenção aprovada pelo Gerente", settled)
            }
            assertNull(state.error, "o cancelamento com isenção de ${created.code} falhou: ${state.error}")
            val done = assertNotNull(state.done, "o cancelamento com isenção de ${created.code} não concluiu")
            val reservation = runBlocking { ports.reception.reservation(created.id) }.reservation
            assertEquals(CANCELLED, reservation.status, "a situação relida de ${created.code} depois da isenção")
            val penalty = cancellationOf(reservation).penalty
            assertTrue(penalty.waived, "a multa de ${created.code} não veio isenta")
            assertTrue(
                amountOf(penalty.amount, "a multa isenta de ${created.code}").signum() > 0,
                "a multa isenta de ${created.code} é ${penalty.amount}; esperava a multa devida, maior que zero (a " +
                    "chegada é hoje: o prazo da política já passou)",
            )
            assertEquals(
                0,
                amountOf(reservation.penaltiesCharged, "as multas cobradas de ${created.code}").signum(),
                "a isenção de ${created.code} cobrou ${reservation.penaltiesCharged}",
            )
            noPenaltyChargedOnTheFolio(ports, created, reservation, penalty)
            val waivedText = "$PENALTY_WAIVED${ReceptionText.money(penalty.amount)}."
            assertEquals("Reserva ${created.code} cancelada. $waivedText", done, "o resumo da isenção")
            println(
                "LiveReservationLifecycleCheck: reserva ${created.code} cancelada com a multa " +
                    "${ReceptionText.money(penalty.amount)} isenta, sem lançamento de multa na conta; resumo: $done",
            )
        } finally {
            closeCancel(screen, state)
        }
    }

    /**
     * A isenção não cobra na conta da reserva relida: o contrato atual devolve o `postingId` nulo, e então não há
     * lançamento no código da multa (`CANCELLATION_FEE`). Se a API devolver um lançamento, ele tem valor zero ou está
     * estornado, e o único outro lançamento no código da multa é o estorno dele.
     */
    private fun noPenaltyChargedOnTheFolio(
        ports: LifecyclePorts,
        created: LifecycleReservation,
        reservation: ReservationResponse,
        penalty: CancellationPenaltyResponse,
    ) {
        val feeCode = cancellationFeeCode(ports)
        val fees = postingsOf(ports, reservation).filter { it.transactionCodeId.toString() == feeCode }
        val returned = (penalty.postingId as? Option.Some)?.value?.toString()
        if (returned == null) {
            assertTrue(
                fees.isEmpty(),
                "a conta de ${created.code} tem ${fees.size} lançamento(s) de multa depois da isenção",
            )
            return
        }
        val posting =
            fees.firstOrNull { it.id.toString() == returned }
                ?: fail("o lançamento que a isenção de ${created.code} devolveu não está na conta da reserva")
        val reversal = (posting.reversedBy as? Option.Some)?.value?.toString()
        assertTrue(
            amountOf(posting.amount, "o lançamento da isenção de ${created.code}").signum() == 0 || reversal != null,
            "a isenção de ${created.code} lançou ${posting.amount} na conta, sem estorno",
        )
        val others = fees.map { it.id.toString() } - setOfNotNull(returned, reversal)
        assertTrue(
            others.isEmpty(),
            "a conta de ${created.code} tem ${others.size} lançamento(s) de multa além do da isenção",
        )
    }

    /** A limpeza do [withRehearsal]: a reserva do ensaio não fica confirmada na demo. */
    private fun DesktopComposeUiTest.cancelIfStillConfirmed(
        screen: LifecycleScreen,
        ports: LifecyclePorts,
        created: LifecycleReservation,
    ) {
        runOnUiThread {
            screen.approvals.cancel()
            screen.reactivate?.close()
            screen.reactivate = null
            screen.cancel?.close()
            screen.cancel = null
        }
        val status = runBlocking { ports.reception.reservation(created.id) }.reservation.status
        if (status == CONFIRMED) {
            cancelRehearsal(screen, ports, created)
            println("LiveReservationLifecycleCheck: limpeza: ${created.code} ficou confirmada e foi cancelada")
        }
    }

    private fun withScreen(block: DesktopComposeUiTest.(LifecycleScreen, LifecyclePorts, Approver) -> Unit) =
        withViewer { connection, approver ->
            val ports = LifecyclePorts(connection)
            val screen = LifecycleScreen()
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent {
                    screen.scope = rememberCoroutineScope()
                    screen.approvals = remember { ApprovalCoordinator(connection::grant, emptyFlow(), screen.scope) }
                    ZenyTheme {
                        screen.pending?.let { pending ->
                            Row(Modifier.fillMaxSize().testTag("pending-screen")) {
                                PendingPanel(pending, { screen.detail?.open(it) }, Modifier.weight(1f).fillMaxHeight())
                                screen.detail?.takeIf { it.openId != null }?.let { ReservationDetailPanel(it) }
                            }
                        }
                        screen.newReservation?.let { NewReservationDialog(it, onConfirmation = null) {} }
                        screen.cancel?.let { ReservationCancelDialog(it) {} }
                        screen.reactivate?.let { ReservationReactivateDialog(it) {} }
                        ApprovalHost(screen.approvals)
                    }
                }
                try {
                    waitForIdle()
                    block(screen, ports, approver)
                } finally {
                    screen.newReservation?.close()
                    screen.cancel?.close()
                    screen.reactivate?.close()
                    screen.pending?.close()
                    screen.detail?.close()
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

private class LifecycleScreen {
    lateinit var scope: CoroutineScope
    lateinit var approvals: ApprovalCoordinator
    var newReservation by mutableStateOf<NewReservationState?>(null)
    var cancel by mutableStateOf<ReservationCancelState?>(null)
    var reactivate by mutableStateOf<ReservationReactivateState?>(null)
    var pending by mutableStateOf<PendingListsState?>(null)
    var detail by mutableStateOf<ReservationDetailState?>(null)
}

/** A reserva que o ensaio cancela, reativa e cancela de novo. */
private class LifecycleReservation(
    val id: String,
    val code: String,
)

/** A multa do 1º cancelamento: o valor do servidor e o lançamento dela na conta da reserva. */
private class ChargedPenalty(
    val amount: BigDecimal,
    val postingId: String,
)

// --- Step 3 -------------------------------------------------------------------------------------------------

/** A reserva do seed de `externalId` [externalId]: exatamente uma (rodar o seed de novo não duplica). */
private fun singleSeedReservation(
    ports: LifecyclePorts,
    externalId: String,
    needSeed: String,
): ReservationListItemResponse {
    val found = allRows(ports, ReservationFilter(externalId = externalId))
    if (found.isEmpty()) fail("$needSeed (a busca pelo externalId $externalId não achou reserva)")
    assertEquals(
        1,
        found.size,
        "a busca pelo externalId $externalId achou ${found.size} reservas " +
            "(${found.map { "${it.code} ${it.status}" }}); esperava exatamente uma: o seed duplicou a pendência " +
            "(recrie a demo)",
    )
    return found.single()
}

/** Relê "Realocação necessária" por até 30 s até a [reallocated] aparecer (a marca do servidor é assíncrona). */
private fun ComposeUiTest.awaitReallocation(
    screen: LifecycleScreen,
    pending: PendingListsState,
    reallocated: ReservationListItemResponse,
): PendingRow {
    val table = pending.table(PendingList.REALLOCATION)
    val label = PendingList.REALLOCATION.label
    var lastTry = System.nanoTime()
    runOnUiThread { pending.select(PendingList.REALLOCATION) }
    val waited =
        runCatching {
            waitUntil("a ${reallocated.code} em \"$label\"", REALLOCATION_TIMEOUT_MILLIS) {
                val idle = !table.loading && table.error == null
                when {
                    table.error != null || table.rows.any { it.code == reallocated.code } -> {
                        true
                    }

                    idle && table.hasMore -> {
                        screen.scope.launch { table.loadMore() }
                        false
                    }

                    idle && System.nanoTime() - lastTry > REALLOCATION_RETRY_NANOS -> {
                        lastTry = System.nanoTime()
                        screen.scope.launch { pending.refresh() }
                        false
                    }

                    else -> {
                        false
                    }
                }
            }
        }
    waited.exceptionOrNull()?.let { if (it !is ComposeTimeoutException) throw it }
    assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")
    return table.rows.firstOrNull { it.code == reallocated.code }
        ?: fail(
            "$NO_REALLOCATION (a $REALLOCATED é a ${reallocated.code}, situação ${reallocated.status}, e não " +
                "apareceu em 30 s; a lista tem ${table.rows.size} linha(s))",
        )
}

/** O clique na linha da pré-reserva, no painel, abre o detalhe dela; grava a captura do painel. */
private fun DesktopComposeUiTest.openFromPanel(
    pending: PendingListsState,
    detail: ReservationDetailState,
    preBooking: ReservationListItemResponse,
) {
    val id = preBooking.id.toString()
    runOnUiThread { pending.select(PendingList.GUARANTEES) }
    waitForIdle()
    onNodeWithTag("table-list").performScrollToNode(hasTestTag("row-$id"))
    onNodeWithTag("row-$id").performClick()
    waitUntil("o detalhe da ${preBooking.code}", LOAD_TIMEOUT_MILLIS) {
        detail.openId == id && !detail.loading && (detail.view != null || detail.error != null)
    }
    assertNull(detail.error, "o detalhe da ${preBooking.code} falhou: ${detail.error?.userMessage}")
    val view = assertNotNull(detail.view, "o detalhe da ${preBooking.code} não carregou")
    assertEquals(preBooking.code, view.code, "o clique na linha da ${preBooking.code} abriu outra reserva")
    waitForIdle()
    assertTrue(
        onAllNodesWithTag("reservation-detail").fetchSemanticsNodes().isNotEmpty(),
        "o detalhe da ${preBooking.code} não aparece ao lado do painel",
    )
    capture("pending", "pending-screen")
    println("LiveReservationLifecycleCheck: a linha da ${preBooking.code} abriu o detalhe ao lado do painel")
}

// --- Step 4 -------------------------------------------------------------------------------------------------

/** Cancela um ensaio com o motivo `OTHER`, sem isenção, e confere a situação relida. */
private fun DesktopComposeUiTest.cancelRehearsal(
    screen: LifecycleScreen,
    ports: LifecyclePorts,
    rehearsal: LifecycleReservation,
) {
    val state = openCancel(screen, ports, rehearsal)
    try {
        runOnUiThread { state.reason = OTHER }
        assertEquals(OTHER, state.reason, "o motivo do cancelamento de ${rehearsal.code}")
        assertFalse(state.waivePenalty, "o cancelamento de ${rehearsal.code} não pode isentar a multa")
        confirmCancel(screen, state, rehearsal.code)
        val reservation = runBlocking { ports.reception.reservation(rehearsal.id) }.reservation
        assertEquals(CANCELLED, reservation.status, "a situação relida de ${rehearsal.code} na limpeza")
        assertEquals(OTHER, cancellationOf(reservation).reason, "o motivo gravado de ${rehearsal.code}")
    } finally {
        closeCancel(screen, state)
    }
}

// --- Ajudantes com a tela -----------------------------------------------------------------------------------

/** O diálogo de cancelamento de [created], que o inicia; espera os termos (o `canConfirm` os exige). */
private fun ComposeUiTest.openCancel(
    screen: LifecycleScreen,
    ports: LifecyclePorts,
    created: LifecycleReservation,
): ReservationCancelState {
    val approve:
        suspend (ApprovableAction<ReservationDetailResponse>) -> ApiResponse<ReservationDetailResponse>? =
        { screen.approvals.run(it) }
    lateinit var state: ReservationCancelState
    runOnUiThread {
        state = ReservationCancelState(ports.cancel, screen.scope, created.id, approve) {}
        screen.cancel = state
    }
    waitUntil("os termos do cancelamento de ${created.code}", LOAD_TIMEOUT_MILLIS) {
        !state.loading && (state.terms != null || state.error != null)
    }
    assertNull(state.error, "a leitura de ${created.code} para cancelar falhou: ${state.error}")
    assertTrue(state.canConfirm, "o cancelamento de ${created.code} não pode ser confirmado com os termos lidos")
    return state
}

/** Confirma o cancelamento sem aprovação e devolve o resumo. */
private fun ComposeUiTest.confirmCancel(
    screen: LifecycleScreen,
    state: ReservationCancelState,
    code: String,
): String {
    runOnUiThread { state.confirm() }
    waitUntil("o cancelamento de $code", LOAD_TIMEOUT_MILLIS) {
        screen.approvals.pending != null || (!state.busy && (state.done != null || state.error != null))
    }
    assertNull(screen.approvals.pending, "o cancelamento de $code, sem isenção, pediu aprovação de superior")
    assertNull(state.error, "o cancelamento de $code falhou: ${state.error}")
    return assertNotNull(state.done, "o cancelamento de $code não concluiu")
}

private fun ComposeUiTest.closeCancel(
    screen: LifecycleScreen,
    state: ReservationCancelState,
) {
    runOnUiThread {
        screen.cancel = null
        state.close()
    }
}

/** Todas as páginas de [table]; a lista não pode terminar com erro. */
private fun ComposeUiTest.loadAll(
    screen: LifecycleScreen,
    table: TableState<PendingRow>,
    label: String,
): List<PendingRow> {
    waitUntil("todas as páginas de \"$label\"", LOAD_TIMEOUT_MILLIS) {
        when {
            table.error != null -> {
                true
            }

            table.loading -> {
                false
            }

            !table.hasMore -> {
                true
            }

            else -> {
                screen.scope.launch { table.loadMore() }
                false
            }
        }
    }
    assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")
    return table.rows
}

/** A nova reserva da oferta, no diálogo, com o `ApprovalCoordinator` real e as permissões da Recepcionista. */
private fun ComposeUiTest.newReservation(
    screen: LifecycleScreen,
    ports: LifecyclePorts,
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

/** Acha o G1 pelo CPF fictício no `GuestPickerState` real e o escolhe como titular. */
private fun ComposeUiTest.pickG1(state: NewReservationState) {
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

/** As portas reais do `CoreConnection` que a verificação usa. */
private class LifecyclePorts(
    connection: CoreConnection,
) {
    val reception = connection.receptionSource()
    val availability = connection.availabilitySource()
    val search = connection.reservationSearchSource()
    val booking = connection.newReservationSource()
    val cancel = connection.reservationCancelSource()
    val reactivate = connection.reservationReactivateSource()
    val pending = connection.pendingSource()
    val folios = connection.folioSource()
    val permissions: Set<String> = runBlocking { connection.me().permissions }
}

/** A oferta STD do BALCAO na estadia, com unidade; sem ela, `NEED_SEED`. */
private fun stdOffer(
    ports: LifecyclePorts,
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

/** O cancelamento gravado na reserva relida; sem ele, falha. */
private fun cancellationOf(reservation: ReservationResponse): CancellationResponse =
    (reservation.cancellation as? Option.Some)?.value
        ?: fail("a reserva ${reservation.code} (${reservation.status}) veio sem o cancelamento")

/** Os lançamentos da conta da reserva relida. */
private fun postingsOf(
    ports: LifecyclePorts,
    reservation: ReservationResponse,
): List<PostingResponse> = runBlocking { ports.folios.postings(reservation.folioId.toString()) }

/** O id do código de lançamento da multa de cancelamento (`CANCELLATION_FEE`); sem ele, `NEED_SEED`. */
private fun cancellationFeeCode(ports: LifecyclePorts): String =
    runBlocking { ports.folios.transactionCodes() }
        .firstOrNull { (it.key as? Option.Some)?.value == TransactionCodeResponse.Key.CANCELLATION_FEE }
        ?.id
        ?.toString()
        ?: fail("NEED_SEED: sem o código de lançamento CANCELLATION_FEE")

/** Um decimal do servidor; ilegível, falha citando [what]. */
private fun amountOf(
    serverValue: String,
    what: String,
): BigDecimal = serverValue.toBigDecimalOrNull() ?: fail("$what não é número: $serverValue")

/** Os pedidos especiais da reserva relida, ou vazio. */
private fun requestsOf(reservation: ReservationResponse): String =
    (reservation.specialRequests as? Option.Some)?.value.orEmpty()

private fun externalId(row: ReservationListItemResponse): String? = (row.externalId as? Option.Some)?.value

/** As reservas confirmadas do G1, pela busca por documento (todas as páginas). */
private fun g1Rows(ports: LifecyclePorts): List<ReservationListItemResponse> =
    runBlocking {
        val query = DocumentQuery("CPF", g1Cpf(), null)
        val rows = mutableListOf<ReservationListItemResponse>()
        var cursor: String? = null
        do {
            val page = ports.search.searchByDocument(query, setOf(CONFIRMED), cursor, PAGE_LIMIT)
            rows += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        rows.forEach { row ->
            assertEquals(CONFIRMED, row.status, "a busca das confirmadas do G1 trouxe ${row.code} ${row.status}")
        }
        rows
    }

/** Todas as páginas da busca. */
private fun allRows(
    ports: LifecyclePorts,
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
