package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.BusinessDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NF_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e61"
private const val NF_WAIT_MILLIS = 10_000L

/**
 * Ataque da M3 E06 (Task 6) de ponta a ponta para a nova reserva: a `NewReservationState` sobre o `CoreConnection` real
 * (fachada, `ApiExecutor`), contra um servidor HTTP local e com o `ApprovalCoordinator` real. A sequência "resultado
 * desconhecido, aprovação cancelada, edição, reenvio" leva a mesma `Idempotency-Key` até a edição e uma chave nova
 * depois dela, e nada sai sem o usuário pedir.
 */
class NewReservationFlowAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-ataque"
    }

    private class Seen(
        val method: String,
        val key: String?,
        val token: String?,
        val body: String,
    )

    private class Answer(
        val status: Int,
        val body: String,
        val json: Boolean = true,
    )

    private val seen = CopyOnWriteArrayList<Seen>()
    private val answers = ConcurrentLinkedQueue<Answer>()
    private val guests = FakeReceptionSource().apply { businessDay = BusinessDay(etStay.arrival, ZoneId.of("UTC")) }

    private fun reservationJson() =
        """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","bookingId":"$NF_ID",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyNights":1,"penaltyType":"NIGHTS",
        "policyId":"$NF_ID","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-12T10:00:00Z","dayUse":false,"departure":"2026-11-16",
        "folioId":"$NF_ID",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$NF_ID","type":"NONE","version":1},
        "guests":[],"id":"$NF_ID","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$NF_ID","roomTotal":"655.37","roomTypeId":"$NF_ID","segment":"LEISURE",
        "status":"CONFIRMED","total":"655.37","version":1}"""

    private fun bookingJson() =
        """{"channel":"DIRECT","code":"B-1","createdAt":"2026-11-12T10:00:00Z","holderGuestId":"$NF_ID","id":"$NF_ID",
        "origin":"FRONT_DESK","reservations":[${reservationJson()}],"restrictionAlerts":[],"version":1}"""

    private val approvalJson =
        """{"code":"APPROVAL_REQUIRED","detail":"Esta ação precisa da aprovação de um superior.",
        "action":"SENSITIVE","permission":"rates.discounts.apply","resourceRef":"booking:novo"}"""

    private val badGateway = Answer(502, "<html>bad gateway</html>", json = false)

    private class Rig(
        val connection: CoreConnection,
        val scope: CoroutineScope,
        val approvals: ApprovalCoordinator,
    )

    private fun withFlow(block: suspend Rig.() -> Unit) =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                val headers = exchange.requestHeaders
                seen +=
                    Seen(
                        exchange.requestMethod,
                        headers.getFirst("Idempotency-Key"),
                        headers.getFirst("X-Approval-Token"),
                        body,
                    )
                exchange.reply(answers.poll() ?: Answer(500, """{"code":"INTERNAL_ERROR","detail":"sem resposta"}"""))
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val approvals = ApprovalCoordinator({ _, _ -> "tok" }, emptyFlow(), scope)
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use {
                    Rig(it, scope, approvals).block()
                }
            } finally {
                approvals.close()
                scope.cancel()
                server.stop(0)
            }
        }

    private fun HttpExchange.reply(answer: Answer) {
        val bytes = answer.body.toByteArray()
        responseHeaders.add("Content-Type", if (answer.json) "application/json" else "text/html")
        sendResponseHeaders(answer.status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private suspend fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(NF_WAIT_MILLIS) { while (!condition()) delay(2) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("esperava: $what", e)
        }
    }

    private fun Rig.state(): NewReservationState {
        val source = assertNotNull(connection.newReservationSource())
        return NewReservationState(
            source,
            guests,
            scope,
            etOffer(),
            etStay,
            setOf("guests.profiles.read"),
            { approvals.run(it) },
        ) {}.also { it.picker.pick(etGuest) }
    }

    private suspend fun settled(state: NewReservationState) = awaitUntil("a criação assentada") { !state.busy }

    private fun posts() = seen.filter { it.method == "POST" }

    @Test
    fun unknownResultCancelledApprovalEditAndResendTakeANewKeyOnlyAfterTheEdit() =
        withFlow {
            answers += badGateway
            answers += Answer(403, approvalJson)
            answers += Answer(201, bookingJson())
            val state = state()

            state.confirm()
            settled(state)
            assertTrue(state.unconfirmed)
            assertEquals(1, posts().size)

            state.confirm()
            awaitUntil("o pedido de aprovação") { approvals.pending != null }
            approvals.cancel()
            settled(state)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertNull(state.done)
            assertEquals(2, posts().size, "aprovação cancelada não manda nada")

            state.specialRequests = "Andar alto"
            assertEquals("Andar alto", state.specialRequests)
            state.confirm()
            awaitUntil("a reserva criada") { state.done != null }

            val (first, again, edited) = posts()
            assertEquals(first.key, again.key, "a aprovação cancelada mantém a intenção")
            assertNotEquals(again.key, edited.key, "a edição depois de destravar é outra intenção")
            assertNull(first.token)
            assertNull(again.token)
            assertNull(edited.token)
            assertFalse(again.body.contains("Andar alto"))
            assertTrue(edited.body.contains("Andar alto"))
            assertEquals(3, posts().size)
            assertNull(approvals.pending)
        }

    @Test
    fun unknownResultCancelledApprovalAndResendWithoutEditKeepsTheKeyAndAsksForApprovalOnce() =
        withFlow {
            answers += badGateway
            answers += Answer(403, approvalJson)
            answers += Answer(403, approvalJson)
            answers += Answer(201, bookingJson())
            val state = state()

            state.confirm()
            settled(state)
            state.confirm()
            awaitUntil("o pedido de aprovação") { approvals.pending != null }
            approvals.cancel()
            settled(state)
            assertFalse(state.unconfirmed)

            state.confirm()
            awaitUntil("o pedido de aprovação de novo") { approvals.pending != null }
            approvals.login = "gerente"
            approvals.pin = "1234"
            approvals.reason = "Autorizado pelo gerente"
            approvals.confirm()
            awaitUntil("a reserva criada") { state.done != null }

            val sent = posts()
            assertEquals(4, sent.size)
            assertEquals(
                1,
                sent
                    .take(3)
                    .map { it.key }
                    .toSet()
                    .size,
                "os três envios sem token levam a mesma chave",
            )
            assertEquals("tok", sent.last().token)
            assertNotNull(state.done)
        }
}
