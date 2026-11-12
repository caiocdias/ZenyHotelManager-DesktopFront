package com.zeny.desktop.core.facade

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ApprovalLimit
import com.zeny.desktop.core.http.Intent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
private const val GRANTED =
    """{"approvalId":"$ID","approvalToken":"tok-1","approverId":"$ID","expiresAt":"2026-11-11T10:05:00Z"}"""

/** As fachadas do `core` contra o `MockEngine`, sem `@RawSdkAccess` (a não ser no `approvable`, que é do `core`). */
class FacadesTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        vararg extra: Pair<String, String>,
    ): HttpResponseData =
        respond(
            body,
            status,
            headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                *extra.map { it.first to listOf(it.second) }.toTypedArray(),
            ),
        )

    private fun hotelBody(
        configured: Boolean,
        extra: String = "",
    ) =
        """{"configured":$configured,"id":"$ID","timezone":"America/Sao_Paulo","updatedAt":"2026-11-11T10:00:00Z"$extra}"""

    @Test
    fun meMapsPermissionCodes() =
        runTest {
            val body =
                """{"login":"maria","name":"Maria Silva","permissions":[
                    {"code":"reservation.cancel","limits":[],"scope":"ALL"},
                    {"code":"folio.discount","limits":[],"scope":"OWN"}],"roles":[],
                    "sessionId":"$ID","userId":"$ID"}"""
            core { json(body) }.use { core ->
                val me = core.account.me()
                assertEquals(Me(ID, "maria", "Maria Silva", setOf("reservation.cancel", "folio.discount")), me)
            }
        }

    @Test
    fun unconfiguredHotelHasNullName() =
        runTest {
            core { json(hotelBody(false)) }.use { assertEquals(HotelInfo(null, false), it.hotel.hotel()) }
            core { json(hotelBody(true, ""","tradeName":null""")) }.use {
                assertEquals(HotelInfo(null, true), it.hotel.hotel())
            }
            core { json(hotelBody(true, ""","tradeName":"Pousada Sol"""")) }.use {
                assertEquals(HotelInfo("Pousada Sol", true), it.hotel.hotel())
            }
        }

    @Test
    fun blankTradeNameBecomesNull() =
        runTest {
            for (name in listOf("", "   ")) {
                core { json(hotelBody(true, ""","tradeName":"$name"""")) }.use {
                    assertEquals(HotelInfo(null, true), it.hotel.hotel())
                }
            }
        }

    @Test
    fun businessDateWithoutZoneConversion() =
        runTest {
            core { json("""{"businessDate":"2026-03-01","timezone":"Pacific/Kiritimati"}""") }.use { core ->
                assertEquals("2026-03-01", core.hotel.businessDate().toApi())
            }
        }

    @Test
    fun forbiddenHotelDoesNotAffectBusinessDate() =
        runTest {
            core { request ->
                if (request.url.encodedPath.endsWith("/business-date")) {
                    json("""{"businessDate":"2026-11-11","timezone":"America/Sao_Paulo"}""")
                } else {
                    json("""{"code":"FORBIDDEN","detail":"sem permissão"}""", HttpStatusCode.Forbidden)
                }
            }.use { core ->
                assertFailsWith<ApiError.Problem> { core.hotel.hotel() }
                assertEquals("2026-11-11", core.hotel.businessDate().toApi())
            }
        }

    @Test
    fun grantCopiesProblemFieldsVerbatim() =
        runTest {
            var sent = ""
            core { request ->
                sent = request.body.toByteArray().decodeToString()
                json(GRANTED)
            }.use { core ->
                val required =
                    ApiError.ApprovalRequired(
                        "reservation.cancel",
                        "reservation.cancel.approve",
                        "reservation/$ID",
                        ApprovalLimit("discount.percent", "10.50"),
                        null,
                    )
                val token = core.approvals.grant(required, ApprovalInput("gerente", "1234", "hóspede pediu"))
                assertEquals("tok-1", token)
                val body = Json.parseToJsonElement(sent).jsonObject
                assertEquals("reservation.cancel", body.getValue("action").jsonPrimitive.content)
                assertEquals("reservation.cancel.approve", body.getValue("permission").jsonPrimitive.content)
                assertEquals("reservation/$ID", body.getValue("resourceRef").jsonPrimitive.content)
                assertEquals("gerente", body.getValue("approverLogin").jsonPrimitive.content)
                assertEquals("1234", body.getValue("approverPin").jsonPrimitive.content)
                assertEquals("hóspede pediu", body.getValue("reason").jsonPrimitive.content)
                val limit = body.getValue("limit").jsonObject
                assertEquals("discount.percent", limit.getValue("key").jsonPrimitive.content)
                assertEquals("10.50", limit.getValue("amount").jsonPrimitive.content)
            }
        }

    @Test
    fun grantWithoutLimitSendsNoLimit() =
        runTest {
            var sent = ""
            core { request ->
                sent = request.body.toByteArray().decodeToString()
                json(GRANTED)
            }.use { core ->
                val required = ApiError.ApprovalRequired("a", "p", "r", null, null)
                core.approvals.grant(required, ApprovalInput("g", "1", "m"))
                val limit = Json.parseToJsonElement(sent).jsonObject["limit"]
                assertTrue(limit == null || limit == JsonNull, sent)
            }
        }

    @OptIn(RawSdkAccess::class)
    @Test
    fun approvableRetriesWithNewKeyAndToken() =
        runTest {
            val problem =
                """{"code":"APPROVAL_REQUIRED","detail":"d","action":"a","permission":"p","resourceRef":"r"}"""
            val date = """{"businessDate":"2026-11-11","timezone":"America/Sao_Paulo"}"""
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(problem, HttpStatusCode.Forbidden)
                } else {
                    json(date, HttpStatusCode.OK, HttpHeaders.ETag to "\"v7\"")
                }
            }.use { core ->
                val action = core.approvable(Intent()) { core.apis.hotel.getBusinessDate(it.headers) }
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                val retried = action.run("tok-9")
                assertEquals("\"v7\"", retried.etag)
                assertEquals("2026-11-11", retried.value.businessDate)
                assertEquals(2, requests.size)
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals("tok-9", requests[1].headers["X-Approval-Token"])
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun approvalInputToStringHidesPin() {
        val text = ApprovalInput("gerente", "987654", "motivo").toString()
        assertTrue("987654" !in text, text)
        assertTrue("gerente" in text)
    }

    /** Compila sem `@OptIn(RawSdkAccess::class)`: o opt-in é erro, então o build deste arquivo prova a exposição. */
    @Test
    fun facadesNeedNoRawSdkAccess() {
        core { json("{}") }.use { core ->
            assertEquals(3, listOf(core.account, core.hotel, core.approvals).size)
        }
    }
}
