package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f14"
private const val PRE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f21"
private const val PRE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f22"

/** As cauções a vencer da `PaymentsFacade`, contra o `MockEngine`. */
class PaymentsFacadeExpiringTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(body: String): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                json(body)
            },
        )

    private fun preAuthorization(id: String) =
        """{"amount":"300.00","authorizationCode":"AUT123","businessDate":"2026-11-18","capturableAmount":"300.00",
        "capturePaymentIds":[],"capturedAmount":"0.00","expiresAt":"2026-11-20T02:59:59Z","folioId":"$FOLIO",
        "id":"$id","registeredAt":"2026-11-18T10:00:00Z","registeredBy":"$USER","status":"ACTIVE"}"""

    @Test
    fun expiringSendsToWithoutFromAndTheCursor() =
        runTest {
            core("""{"items":[]}""").use { core ->
                core.payments.expiringPreAuthorizationPage(BusinessDate.parse("2026-11-25"), "c-2", 20)
                val request = requests.single()
                assertTrue(request.url.encodedPath.endsWith("/card-pre-authorizations/expiring"))
                assertEquals("2026-11-25", request.url.parameters["to"])
                assertNull(request.url.parameters["from"])
                assertEquals("c-2", request.url.parameters["cursor"])
                assertEquals("20", request.url.parameters["limit"])
            }
        }

    @Test
    fun expiringKeepsTheServerOrderAndTheNextCursor() =
        runTest {
            core("""{"items":[${preAuthorization(PRE_2)},${preAuthorization(PRE)}],"nextCursor":"c-3"}""").use { core ->
                val page = core.payments.expiringPreAuthorizationPage(BusinessDate.parse("2026-11-25"), null)
                assertEquals(listOf(PRE_2, PRE), page.items.map { it.id.toString() })
                assertEquals("c-3", page.nextCursor)
                assertNull(requests.single().url.parameters["cursor"])
                assertEquals("50", requests.single().url.parameters["limit"])
            }
        }

    @Test
    fun blankNextCursorEndsThePages() =
        runTest {
            core("""{"items":[${preAuthorization(PRE)}],"nextCursor":" "}""").use { core ->
                val page = core.payments.expiringPreAuthorizationPage(BusinessDate.parse("2026-11-25"), null)
                assertNull(page.nextCursor)
                assertEquals(1, page.items.size)
            }
        }
}
