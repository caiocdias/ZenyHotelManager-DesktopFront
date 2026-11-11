package com.zeny.desktop.core

import com.zeny.desktop.core.config.PreferencesSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ApprovalLimit
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.CallOptions
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.sdk.model.MeResponse
import com.zeny.desktop.sdk.model.OperationalSettingsDto
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.ogen.client.ktor.HttpResponseTyped
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val ME = "/api/v1/auth/me"
private const val REFRESH = "/api/v1/auth/refresh"
private const val SETTINGS = "/api/v1/hotel/operational-settings"

/** Critérios 1 a 4 da E02 de ponta a ponta: o `ZenyCore` montado, contra o servidor falso. */
@OptIn(RawSdkAccess::class)
@Suppress("InjectDispatcher")
class CoreCriteriaTest {
    private val node: Preferences = Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}")
    private val server = FakeZenyServer()
    private val core =
        ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), PreferencesSettings(node), server.engine)
    private val reservationId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"

    @AfterTest
    fun tearDown() {
        core.close()
        node.removeNode()
        Preferences.userRoot().flush()
    }

    private suspend fun me(): ApiResponse<MeResponse> = core.executor.execute { core.apis.iam.getMe(it.headers) }

    private fun HttpRequestData.path() = url.encodedPath

    @Test
    fun fiveConcurrentRequestsWithExpiredTokenRefreshOnce() =
        runTest(timeout = 5.seconds) {
            core.session.login("maria.silva", "senha-longa-123")
            assertEquals(200, me().status)
            server.expireAccess()
            // As 5 recebem o 401 do A1 juntas: a barreira só solta quando as 5 chegaram com A1.
            val arrived = AtomicInteger()
            val allArrived = CompletableDeferred<Unit>()
            server.beforeSecured = { request ->
                if (request.path() == ME && server.authorizationOf(request) == listOf("Bearer A1")) {
                    if (arrived.incrementAndGet() == 5) allArrived.complete(Unit)
                    allArrived.await()
                }
            }

            val statuses = List(5) { async(Dispatchers.Default) { me().status } }.awaitAll()

            assertEquals(List(5) { 200 }, statuses)
            assertEquals(1, server.count(REFRESH))
            assertEquals(5, server.authorizations(ME).count { it == listOf("Bearer A2") })
            assertIs<SessionState.Active>(core.session.state.value)
        }

    @Test
    fun unauthenticatedRefreshesOtherUnauthorizedEndsSession() =
        runTest(timeout = 5.seconds) {
            core.session.login("maria.silva", "senha-longa-123")
            server.expireAccess()

            assertEquals(200, me().status)
            assertEquals(1, server.count(REFRESH))
            assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), server.authorizations(ME))

            // Noutra sessão, a conta é desativada: o 401 encerra a sessão, sem refresh e sem repetição.
            core.session.logout()
            val second = core.session.login("maria.silva", "senha-longa-123")
            assertEquals(server.sessionId, second.sessionId)
            server.requests.clear()
            server.rejectAllWith = "ACCOUNT_DISABLED"

            val ended = assertFailsWith<ApiError.SessionEnded> { me() }

            assertEquals("ACCOUNT_DISABLED", ended.code)
            assertIs<SessionState.Ended>(core.session.state.value)
            assertEquals(listOf(ME), server.requests.map { it.path() })
        }

    @Test
    fun approvalRequiredEndToEnd() =
        runTest(timeout = 5.seconds) {
            core.session.login("maria.silva", "senha-longa-123")
            val cancels = mutableListOf<HttpRequestData>()
            server.routes = { request ->
                cancels += request
                when {
                    request.headers["X-Approval-Token"] == null -> json(approvalJson, HttpStatusCode.Forbidden)
                    cancels.size == 2 -> throw IOException("conexão caiu")
                    else -> json(reservationJson)
                }
            }
            val intent = Intent()
            val firstKey = intent.key

            val approval = assertFailsWith<ApiError.ApprovalRequired> { core.executor.execute(intent, call = cancel()) }
            assertEquals("reservations.cancel", approval.action)
            assertEquals("reservations.penalties.waive", approval.permission)
            assertEquals("reservation:$reservationId", approval.resourceRef)
            assertEquals(ApprovalLimit("reservations.penalties.waive.amount", "150.00"), approval.limit)

            assertFailsWith<ApiError.Network> { core.executor.executeApproved(intent, "tok-1", cancel()) }
            val done = core.executor.execute(intent, call = cancel())

            assertEquals(200, done.status)
            assertEquals(listOf(firstKey), cancels[0].headers.getAll("Idempotency-Key"))
            assertNull(cancels[0].headers["X-Approval-Token"])
            assertNotEquals(firstKey, intent.key)
            assertEquals(listOf(intent.key), cancels[1].headers.getAll("Idempotency-Key"))
            assertEquals(listOf("tok-1"), cancels[1].headers.getAll("X-Approval-Token"))
            assertEquals(listOf(intent.key), cancels[2].headers.getAll("Idempotency-Key"))
            assertEquals(listOf("tok-1"), cancels[2].headers.getAll("X-Approval-Token"))
        }

    @Test
    fun preconditionFailedEndToEnd() =
        runTest(timeout = 5.seconds) {
            core.session.login("maria.silva", "senha-longa-123")
            var version = 7
            val writes = mutableListOf<HttpRequestData>()
            server.routes = { request ->
                if (request.method == HttpMethod.Put) writes += request
                when {
                    request.path() != SETTINGS -> {
                        json("{}", HttpStatusCode.NotFound)
                    }

                    request.method == HttpMethod.Get -> {
                        json(settingsJson, extra = arrayOf("ETag" to "\"$version\""))
                    }

                    request.headers["If-Match"] != "\"$version\"" -> {
                        problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED")
                    }

                    else -> {
                        json(settingsJson, extra = arrayOf("ETag" to "\"${++version}\""))
                    }
                }
            }
            val read = loadSettings()
            version++ // outro usuário salvou depois da leitura

            assertFailsWith<ApiError.PreconditionFailed> { saveSettings(read) }
            val saved = saveSettings(loadSettings())

            assertEquals("\"7\"", read.etag)
            assertEquals("\"9\"", saved.etag)
            assertEquals(listOf(listOf("\"7\""), listOf("\"8\"")), writes.map { it.headers.getAll("If-Match") })
            assertIs<SessionState.Active>(core.session.state.value)
        }

    private suspend fun loadSettings(): ApiResponse<OperationalSettingsDto> =
        core.executor.execute { core.apis.hotel.getHotelOperationalSettings(it.headers) }

    /** Salva o que foi lido, com o `If-Match` da leitura. */
    private suspend fun saveSettings(read: ApiResponse<OperationalSettingsDto>): ApiResponse<OperationalSettingsDto> =
        core.executor.execute { core.apis.hotel.updateHotelOperationalSettings(read.etag, read.value, it.headers) }

    private fun cancel(): suspend (CallOptions) -> HttpResponseTyped<ReservationDetailResponse> =
        { o ->
            core.apis.reservations.cancelReservation(
                id = Uuid.parse(reservationId),
                idempotencyKey = o.requireIdempotencyKey(),
                xApprovalToken = o.approvalToken,
                reservationCancelRequest =
                    ReservationCancelRequest(
                        reason = ReservationCancelRequest.Reason.OTHER,
                        waivePenalty = true,
                    ),
                block = o.headers,
            )
        }

    private val settingsJson =
        """{"endOfDayTime":"03:00","lostAndFoundRetentionDays":90,""" +
            """"preReservationAlertLeadHours":24,"preReservationExpiryHours":48}"""

    private val approvalJson =
        """{"code":"APPROVAL_REQUIRED","detail":"Precisa de aprovação","action":"reservations.cancel",""" +
            """"permission":"reservations.penalties.waive","resourceRef":"reservation:$reservationId",""" +
            """"limit":{"key":"reservations.penalties.waive.amount","amount":"150.00"}}"""

    private val policyId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c10"

    private val reservationJson =
        """
        {"bookingCode":"B-1","holderGuestId":"$policyId","restrictionAlerts":[],
         "reservation":{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-20","bookingId":"$policyId",
          "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$policyId","version":1},
          "childAges":[],"code":"R-1","createdAt":"2026-11-11T13:00:00Z","dayUse":false,"departure":"2026-11-22",
          "folioId":"$policyId",
          "guaranteeTerms":{"name":"Sem garantia","policyId":"$policyId","type":"NONE","version":1},
          "guests":[],"id":"$reservationId","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
          "ratePlanId":"$policyId","roomTotal":"400.00","roomTypeId":"$policyId","segment":"LEISURE",
          "status":"CANCELLED","total":"400.00","version":2}}
        """.trimIndent()
}
