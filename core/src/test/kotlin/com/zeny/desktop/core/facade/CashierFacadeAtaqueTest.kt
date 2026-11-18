package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SHIFT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val POINT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val MOVEMENT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"

/** Ataque à `CashierFacade`: bordas de valor, leituras sem chave e o cursor em branco. */
class CashierFacadeAtaqueTest {
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

    private fun point() =
        """{"active":true,"blindClose":true,"createdAt":"2026-11-01T10:00:00Z","id":"$POINT","name":"Caixa",
        "updatedAt":"2026-11-01T10:00:00Z"}"""

    private fun shift(status: String = "OPEN") =
        """{"blindClose":true,"cashPointId":"$POINT","cashPointName":"Caixa","expectedVisible":false,"id":"$SHIFT",
        "movements":[],"number":"CX-2026-1","openedAt":"2026-11-18T10:00:00Z","openedOn":"2026-11-18",
        "openingFloat":"150.00","operatorId":"$USER","status":"$status"}"""

    private fun report() = """{"generatedAt":"2026-11-18T20:00:00Z","shift":${shift("CLOSED")}}"""

    private fun movement() =
        """{"amount":"0.01","businessDate":"2026-11-18","id":"$MOVEMENT","reason":"troco","recordedAt":
        "2026-11-18T11:00:00Z","recordedBy":"$USER","shiftId":"$SHIFT","type":"SUPPLY"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    @Test
    fun readsGoWithoutIdempotencyKey() =
        runTest {
            core { request ->
                when {
                    request.url.encodedPath.endsWith("/report") -> json(report())
                    request.url.encodedPath.endsWith("/current") -> json(shift())
                    else -> json("""{"items":[${point()}]}""")
                }
            }.use { core ->
                core.cashier.cashPoints()
                core.cashier.currentShift()
                core.cashier.report(SHIFT)

                assertEquals(3, requests.size)
                requests.forEach {
                    assertNull(it.headers["Idempotency-Key"], "a leitura ${it.url.encodedPath} levou a chave")
                }
            }
        }

    @Test
    fun blankNextCursorEndsTheList() =
        runTest {
            listOf("\"\"", "\"   \"", "null").forEach { cursor ->
                requests.clear()
                core { json("""{"items":[${point()}],"nextCursor":$cursor}""") }.use { core ->
                    val points = core.cashier.cashPoints()

                    assertEquals(1, points.size, "cursor $cursor")
                    assertEquals(1, requests.size, "o cursor $cursor pediu outra página")
                }
            }
        }

    @Test
    fun openingFloatBordersGoAsExactDecimalStrings() =
        runTest {
            core { json(shift(), HttpStatusCode.Created) }.use { core ->
                listOf("0.00", "0.01", "1000000.00", "1000000.01").forEach {
                    core.cashier.openShift(POINT, Money.parse(it), Intent())
                }

                val sent = requests.map { bodyOf(it).getValue("openingFloat").jsonPrimitive }
                assertEquals(listOf("0.00", "0.01", "1000000.00", "1000000.01"), sent.map { it.content })
                assertTrue(sent.all { it.isString }, "o fundo saiu como número")
            }
        }

    @Test
    fun movementBordersGoAsExactDecimalStrings() =
        runTest {
            core { json(movement(), HttpStatusCode.Created) }.use { core ->
                listOf("0.01", "1000000.00").forEach {
                    core.cashier.recordMovement(SHIFT, CashMovementType.SUPPLY, Money.parse(it), "troco", Intent())
                }

                val sent = requests.map { bodyOf(it).getValue("amount").jsonPrimitive }
                assertEquals(listOf("0.01", "1000000.00"), sent.map { it.content })
                assertTrue(sent.all { it.isString })
            }
        }

    @Test
    fun negativeCountOfNonCashMethodGoesExact() =
        runTest {
            core { json(report()) }.use { core ->
                core.cashier.closeShift(
                    SHIFT,
                    listOf(
                        CashCount(PaymentMethod.CASH, Money.parse("0.00")),
                        CashCount(PaymentMethod.PIX, Money.parse("-1000000.00")),
                        CashCount(PaymentMethod.OTHER, Money.parse("-0.01")),
                    ),
                    Intent(),
                )

                val counts = bodyOf(requests.single()).getValue("counts").jsonArray.map { it.jsonObject }
                assertEquals(
                    listOf("CASH" to "0.00", "PIX" to "-1000000.00", "OTHER" to "-0.01"),
                    counts.map {
                        it.getValue("method").jsonPrimitive.content to it.getValue("amount").jsonPrimitive.content
                    },
                )
                assertTrue(counts.all { it.getValue("amount").jsonPrimitive.isString })
            }
        }
}
