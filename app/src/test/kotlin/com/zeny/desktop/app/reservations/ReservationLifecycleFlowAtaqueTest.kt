package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.CancelInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FA_RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e71"
private const val FA_OTHER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f71"
private const val FA_WAIT_MILLIS = 10_000L
private const val FA_NOTES = "hóspede internado no hospital São Lucas"

/**
 * Ataque à M3 E04 de ponta a ponta: os estados de cancelar e reativar sobre o `CoreConnection` real (fachada,
 * `ApiExecutor`, `ProblemMapper`), contra um servidor HTTP local, com o `ApprovalCoordinator` real. O que se prova é o
 * que só aparece com as peças juntas: a mesma `Idempotency-Key` (e o token aprovado) na repetição depois de uma
 * resposta perdida, a aprovação pedida uma vez só e as recusas do servidor como texto do catálogo.
 */
class ReservationLifecycleFlowAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-ataque"
    }

    /** Um pedido que chegou ao servidor: método, caminho, chave, token e corpo. */
    private class Seen(
        val method: String,
        val path: String,
        val key: String?,
        val token: String?,
        val body: String,
    )

    /** Uma resposta programada: status e corpo (JSON ou não). */
    private class Answer(
        val status: Int,
        val body: String,
        val json: Boolean = true,
    )

    private val seen = CopyOnWriteArrayList<Seen>()
    private val answers = ConcurrentLinkedQueue<Answer>()
    private val grants = AtomicInteger()

    /** Com o portão, o servidor só responde ao POST depois de o teste o abrir. */
    @Volatile private var gate: CountDownLatch? = null

    private fun reservationJson(
        status: String,
        cancellation: String?,
    ) = """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-12-10","bookingId":"$FA_OTHER",
        ${cancellation?.let { "\"cancellation\":$it," }.orEmpty()}
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyNights":1,"penaltyType":"NIGHTS",
        "policyId":"$FA_OTHER","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-16T10:00:00Z","dayUse":false,"departure":"2026-12-12",
        "folioId":"$FA_OTHER",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$FA_OTHER","type":"NONE","version":1},
        "guests":[],"id":"$FA_RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$FA_OTHER","roomTotal":"640.00","roomTypeId":"$FA_OTHER","segment":"LEISURE",
        "status":"$status","total":"640.00","version":1}"""

    private fun detailJson(
        status: String = "CONFIRMED",
        cancellation: String? = null,
    ) = """{"bookingCode":"B-1","holderGuestId":"$FA_OTHER","reservation":${reservationJson(status, cancellation)},
        "restrictionAlerts":[]}"""

    private fun cancellationJson(
        amount: String,
        waived: Boolean,
    ) = """{"cancelledAt":"2026-11-16T12:00:00Z","penalty":{"amount":"$amount","deadline":"2026-12-09T17:00:00Z",
        "waived":$waived},"previousStatus":"CONFIRMED","reason":"GUEST_REQUEST"}"""

    private fun approvalJson(permission: String) =
        """{"code":"APPROVAL_REQUIRED","detail":"Esta ação precisa da aprovação de um superior.",
        "action":"SENSITIVE","permission":"$permission","resourceRef":"reservation:$FA_RES"}"""

    private fun problemJson(
        code: String,
        detail: String,
    ) = """{"code":"$code","detail":"$detail"}"""

    private fun cancelled(
        amount: String,
        waived: Boolean,
    ) = Answer(200, detailJson("CANCELLED", cancellationJson(amount, waived)))

    private val badGateway = Answer(502, "<html>bad gateway</html>", json = false)

    /** Os POSTs (cancelar ou reativar) que chegaram, na ordem. */
    private fun posts() = seen.filter { it.method == "POST" }

    private class FlowRig(
        val connection: CoreConnection,
        val scope: CoroutineScope,
        val approvals: ApprovalCoordinator,
    )

    private fun withFlow(block: suspend FlowRig.() -> Unit) =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                val headers = exchange.requestHeaders
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        headers.getFirst("Idempotency-Key"),
                        headers.getFirst("X-Approval-Token"),
                        body,
                    )
                exchange.answer()
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val approvals = ApprovalCoordinator({ _, _ -> "tok-${grants.incrementAndGet()}" }, emptyFlow(), scope)
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use {
                    FlowRig(it, scope, approvals).block()
                }
            } finally {
                gate?.countDown()
                approvals.close()
                scope.cancel()
                server.stop(0)
            }
        }

    private fun HttpExchange.answer() {
        if (requestMethod == "GET") {
            reply(Answer(200, detailJson()))
            return
        }
        gate?.await(FA_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        reply(answers.poll() ?: Answer(500, problemJson("INTERNAL_ERROR", "sem resposta programada")))
    }

    private fun HttpExchange.reply(answer: Answer) {
        val bytes = answer.body.toByteArray()
        responseHeaders.add("Content-Type", if (answer.json) "application/json" else "text/html")
        sendResponseHeaders(answer.status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** Espera a condição com teto de tempo; nunca dorme um tempo fixo. */
    private suspend fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(FA_WAIT_MILLIS) { while (!condition()) delay(2) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("esperava: $what", e)
        }
    }

    private suspend fun FlowRig.approve() {
        awaitUntil("o pedido de aprovação") { approvals.pending != null }
        approvals.login = "gerente"
        approvals.pin = "1234"
        approvals.reason = "Autorizado pelo gerente"
        approvals.confirm()
    }

    private suspend fun FlowRig.startedCancel(onChanged: () -> Unit = {}): ReservationCancelState {
        val source = assertNotNull(connection.reservationCancelSource())
        val state = ReservationCancelState(source, scope, FA_RES, { approvals.run(it) }, onChanged)
        state.start()
        awaitUntil("os termos lidos") { state.canConfirm }
        return state
    }

    private suspend fun settled(state: ReservationCancelState) = awaitUntil("o cancelamento assentado") { !state.busy }

    private suspend fun settled(state: ReservationReactivateState) =
        awaitUntil("a reativação assentada") { !state.busy }

    private fun FlowRig.reactivate(onChanged: () -> Unit = {}): ReservationReactivateState {
        val source = assertNotNull(connection.reservationReactivateSource())
        return ReservationReactivateState(source, scope, FA_RES, "R-1", { approvals.run(it) }, onChanged)
    }

    // --- Cancelar ------------------------------------------------------------------------------------------------

    @Test
    fun approvedWaiverWhoseAnswerIsLostIsRetriedWithTheApprovedKeyAndTokenWithoutAskingAgain() =
        withFlow {
            answers += Answer(403, approvalJson("reservations.penalties.waive"))
            answers += badGateway
            answers += cancelled("320.00", waived = true)
            var changes = 0
            val state = startedCancel { changes++ }
            state.waivePenalty = true

            state.confirm()
            approve()
            awaitUntil("o resultado desconhecido") { state.unconfirmed && !state.busy }
            assertNull(state.done)
            assertEquals(0, changes)
            state.confirm()
            awaitUntil("o cancelamento concluído") { state.done != null }

            val (first, approved, retried) = posts()
            assertNull(first.token, "a primeira tentativa vai sem token")
            assertEquals("tok-1", approved.token)
            assertNotEquals(first.key, approved.key, "a repetição aprovada leva chave nova (D2)")
            assertEquals(approved.key, retried.key, "a repetição da resposta perdida é a mesma intenção aprovada")
            assertEquals("tok-1", retried.token, "a repetição leva o token já aprovado")
            assertEquals(approved.body, retried.body)
            assertEquals(1, grants.get(), "a aprovação foi pedida uma vez só")
            assertNull(approvals.pending)
            assertEquals(3, posts().size)
            assertEquals("Reserva R-1 cancelada. Multa isenta: R$ 320,00.", state.done)
            assertEquals(1, changes)
        }

    @Test
    fun cancelledApprovalSendsNothingMoreAndTheRetryIsTheSameIntent() =
        withFlow {
            answers += Answer(403, approvalJson("reservations.penalties.waive"))
            answers += Answer(403, approvalJson("reservations.penalties.waive"))
            answers += cancelled("320.00", waived = true)
            val state = startedCancel()
            state.waivePenalty = true

            state.confirm()
            awaitUntil("o pedido de aprovação") { approvals.pending != null }
            approvals.cancel()
            settled(state)
            assertEquals(1, posts().size, "aprovação cancelada não manda nada")
            assertNull(state.done)
            assertNull(state.error)
            assertFalse(state.unconfirmed)
            assertTrue(state.canConfirm)

            state.confirm()
            approve()
            awaitUntil("o cancelamento concluído") { state.done != null }

            val (first, again, approved) = posts()
            assertEquals(first.key, again.key, "a aprovação cancelada mantém a intenção")
            assertNull(again.token)
            assertEquals("tok-1", approved.token)
            assertEquals("Reserva R-1 cancelada. Multa isenta: R$ 320,00.", state.done)
        }

    @Test
    fun doubleConfirmSendsOneCancellation() =
        withFlow {
            gate = CountDownLatch(1)
            answers += cancelled("320.00", waived = false)
            val state = startedCancel()

            state.confirm()
            state.confirm()
            awaitUntil("o POST no servidor") { posts().isNotEmpty() }
            state.confirm()
            gate?.countDown()
            awaitUntil("o cancelamento concluído") { state.done != null }
            state.confirm()
            delay(1)

            assertEquals(1, posts().size)
            assertEquals("Reserva R-1 cancelada. Multa lançada na conta: R$ 320,00.", state.done)
        }

    @Test
    fun doneTextFollowsTheServerJsonAndNeverInventsAPenalty() =
        withFlow {
            val cases =
                listOf(
                    Answer(200, detailJson("CANCELLED", null)) to "Reserva R-1 cancelada.",
                    cancelled("0.00", waived = false) to "Reserva R-1 cancelada. Sem multa.",
                    cancelled("150.5", waived = false) to "Reserva R-1 cancelada. Multa lançada na conta: R$ 150,50.",
                )
            for ((answer, expected) in cases) {
                answers += answer
                val state = startedCancel()
                state.waivePenalty = true
                state.confirm()
                awaitUntil("o cancelamento concluído") { state.done != null || state.error != null }
                assertEquals(expected, state.done)
                assertFalse(state.done.orEmpty().contains("isenta"), "o servidor não isentou: $expected")
                state.close()
            }
        }

    @Test
    fun notesGoOnlyInTheBodyNeverInErrorOrToString() =
        withFlow {
            answers += Answer(400, problemJson("RESERVATION_CANCELLATION_INVALID", "A observação é longa demais."))
            val state = startedCancel()
            state.reason = "OTHER"
            state.notes = FA_NOTES

            state.confirm()
            settled(state)

            val post = posts().single()
            assertTrue("\"notes\":\"$FA_NOTES\"" in post.body, post.body)
            assertTrue("\"reason\":\"OTHER\"" in post.body, post.body)
            assertEquals("A observação é longa demais.", state.error, "o detail do servidor, sem o código")
            assertFalse(state.error.orEmpty().contains("RESERVATION_CANCELLATION_INVALID"))
            assertFalse(state.unconfirmed, "400 é resposta definitiva")
            listOf(state.error.orEmpty(), state.toString(), CancelInput("OTHER", false, FA_NOTES).toString())
                .forEach { assertFalse(it.contains("hospital"), "a observação vazou em: $it") }
        }

    @Test
    fun blankNotesAreNotSentAndExpiredIsNeverOffered() =
        withFlow {
            answers += cancelled("0.00", waived = false)
            val state = startedCancel()
            state.reason = "EXPIRED"
            state.notes = "   "

            state.confirm()
            awaitUntil("o cancelamento concluído") { state.done != null }

            val body = posts().single().body
            assertFalse("notes" in body, body)
            assertTrue("\"reason\":\"GUEST_REQUEST\"" in body, body)
            assertFalse("EXPIRED" in body, body)
        }

    // --- Reativar ------------------------------------------------------------------------------------------------

    @Test
    fun approvedReactivationWhoseAnswerIsLostIsRetriedWithTheApprovedKeyAndToken() =
        withFlow {
            answers += Answer(403, approvalJson("reservations.reservations.reactivate"))
            answers += badGateway
            answers += Answer(200, detailJson("CONFIRMED"))
            var changes = 0
            val state = reactivate { changes++ }

            state.confirm()
            approve()
            awaitUntil("o resultado desconhecido") { state.unconfirmed && !state.busy }
            assertEquals(0, changes)
            state.confirm()
            awaitUntil("a reativação concluída") { state.done != null }

            val (first, approved, retried) = posts()
            assertTrue(posts().all { it.path == "/api/v1/reservations/$FA_RES/reactivate" })
            assertTrue(posts().all { it.body.isEmpty() }, "a reativação não tem corpo")
            assertNull(first.token)
            assertNotEquals(first.key, approved.key)
            assertEquals(approved.key, retried.key)
            assertEquals("tok-1", retried.token)
            assertEquals(1, grants.get())
            assertEquals("Reserva R-1 reativada.", state.done)
            assertEquals(1, changes)
        }

    @Test
    fun reactivationRefusalsShowCatalogTextAndTakeANewIntent() =
        withFlow {
            val refusals =
                listOf(
                    Answer(422, problemJson("RESERVATION_STAY_ENDED", "stay ended at 2026-11-01")) to
                        "A estadia desta reserva já terminou.",
                    Answer(409, problemJson("ROOM_TYPE_UNAVAILABLE", "no units for 0199d6b8")) to
                        "Não há UH disponível deste tipo em todas as noites.",
                )
            val state = reactivate()
            for ((answer, expected) in refusals) {
                answers += answer
                state.confirm()
                settled(state)
                assertEquals(expected, state.error)
                assertFalse(state.unconfirmed, "recusa é definitiva")
                assertNull(state.done)
            }
            val (first, second) = posts()
            assertNotEquals(first.key, second.key, "depois da recusa definitiva, outra intenção")
        }

    @Test
    fun closeWhileReactivationIsInFlightDropsTheAnswer() =
        withFlow {
            gate = CountDownLatch(1)
            answers += Answer(200, detailJson("CONFIRMED"))
            var changes = 0
            val state = reactivate { changes++ }

            state.confirm()
            state.confirm()
            awaitUntil("o POST no servidor") { posts().isNotEmpty() }
            state.close()
            gate?.countDown()
            awaitUntil("a resposta servida") { answers.isEmpty() }
            settled(state)
            state.confirm()
            delay(1)

            assertEquals(1, posts().size)
            assertNull(state.done)
            assertEquals(0, changes)
        }
}
