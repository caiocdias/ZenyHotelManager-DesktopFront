package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SHIFT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val POINT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val POINT_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val MOVEMENT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"
private const val SECRET_REASON = "motivo-secreto-da-sangria"

/** O turno de caixa da `CashierFacade`, contra o `MockEngine`. */
class CashierFacadeTest {
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

    private fun point(
        id: String,
        name: String,
    ) = """{"active":true,"blindClose":true,"createdAt":"2026-11-01T10:00:00Z","id":"$id","name":"$name",
        "updatedAt":"2026-11-01T10:00:00Z"}"""

    private fun pointPage(
        id: String,
        next: String? = null,
    ): String {
        val cursor = if (next == null) "" else ""","nextCursor":"$next""""
        return """{"items":[${point(id, "Caixa")}]$cursor}"""
    }

    private fun shift(status: String = "OPEN") =
        """{"blindClose":true,"cashPointId":"$POINT","cashPointName":"Caixa","expectedVisible":false,"id":"$SHIFT",
        "movements":[],"number":"CX-2026-1","openedAt":"2026-11-18T10:00:00Z","openedOn":"2026-11-18",
        "openingFloat":"150.00","operatorId":"$USER","status":"$status"}"""

    private fun report() = """{"generatedAt":"2026-11-18T20:00:00Z","shift":${shift("CLOSED")}}"""

    private fun movement() =
        """{"amount":"20.50","businessDate":"2026-11-18","id":"$MOVEMENT","reason":"troco","recordedAt":
        "2026-11-18T11:00:00Z","recordedBy":"$USER","shiftId":"$SHIFT","type":"SUPPLY"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    @Test
    fun cashPointsReadsAllPagesOfActive() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json(pointPage(POINT, "next-2"))
                } else {
                    json(pointPage(POINT_2))
                }
            }.use { core ->
                val points = core.cashier.cashPoints()
                assertEquals(listOf(POINT, POINT_2), points.map { it.id.toString() })
                assertEquals(2, requests.size)
                requests.forEach { assertEquals("true", it.url.parameters["active"]) }
                assertEquals("next-2", requests[1].url.parameters["cursor"])
            }
        }

    @Test
    fun currentShiftReturnsTheShift() =
        runTest {
            core { json(shift()) }.use { core ->
                assertEquals(
                    SHIFT,
                    core.cashier
                        .currentShift()
                        ?.id
                        .toString(),
                )
            }
        }

    @Test
    fun currentShiftIsNullOnNotFound() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "CASHIER_SHIFT_NOT_FOUND") }.use { core ->
                assertNull(core.cashier.currentShift())
            }
        }

    @Test
    fun currentShiftOtherErrorThrows() =
        runTest {
            core { problem(HttpStatusCode.Forbidden, "ACCESS_DENIED") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.cashier.currentShift() }
                assertEquals("ACCESS_DENIED", error.code)
            }
        }

    @Test
    fun currentShiftNotFoundWithOtherCodeThrows() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "NOT_FOUND") }.use { core ->
                assertFailsWith<ApiError.Problem> { core.cashier.currentShift() }
            }
        }

    @Test
    fun openSendsExactDecimalAndIdempotencyKey() =
        runTest {
            core { json(shift(), HttpStatusCode.Created) }.use { core ->
                val intent = Intent()
                core.cashier.openShift(POINT, Money.parse("150.00"), intent)
                val request = requests.single()
                assertEquals(intent.key, request.headers["Idempotency-Key"])
                val body = bodyOf(request)
                assertEquals("150.00", body.getValue("openingFloat").jsonPrimitive.content)
                assertTrue(body.getValue("openingFloat").jsonPrimitive.isString)
                assertEquals(POINT, body.getValue("cashPointId").jsonPrimitive.content)
            }
        }

    @Test
    fun sameIntentKeepsTheKey() =
        runTest {
            core { request ->
                when {
                    request.url.encodedPath.endsWith("/close") -> json(report())
                    request.url.encodedPath.endsWith("/movements") -> json(movement(), HttpStatusCode.Created)
                    else -> json(shift(), HttpStatusCode.Created)
                }
            }.use { core ->
                val open = Intent()
                core.cashier.openShift(POINT, Money.parse("10.00"), open)
                core.cashier.openShift(POINT, Money.parse("10.00"), open)
                val move = Intent()
                core.cashier.recordMovement(SHIFT, CashMovementType.SUPPLY, Money.parse("20.50"), "troco", move)
                core.cashier.recordMovement(SHIFT, CashMovementType.SUPPLY, Money.parse("20.50"), "troco", move)
                val close = Intent()
                core.cashier.closeShift(SHIFT, listOf(CashCount(PaymentMethod.CASH, Money.parse("1.00"))), close)
                core.cashier.closeShift(SHIFT, listOf(CashCount(PaymentMethod.CASH, Money.parse("1.00"))), close)
                val keys = requests.map { it.headers["Idempotency-Key"] }
                assertEquals(listOf(open.key, open.key, move.key, move.key, close.key, close.key), keys)
                assertEquals(3, keys.toSet().size)
            }
        }

    @Test
    fun recordMovementSendsTypeAmountAndReason() =
        runTest {
            core { json(movement(), HttpStatusCode.Created) }.use { core ->
                val result =
                    core.cashier.recordMovement(
                        SHIFT,
                        CashMovementType.WITHDRAWAL,
                        Money.parse("20.50"),
                        SECRET_REASON,
                        Intent(),
                    )
                assertEquals(MOVEMENT, result.id.toString())
                val body = bodyOf(requests.single())
                assertEquals("WITHDRAWAL", body.getValue("type").jsonPrimitive.content)
                assertEquals("20.50", body.getValue("amount").jsonPrimitive.content)
                assertEquals(SECRET_REASON, body.getValue("reason").jsonPrimitive.content)
            }
        }

    @Test
    fun closeSendsCountsByMethod() =
        runTest {
            core { json(report()) }.use { core ->
                val counts =
                    listOf(
                        CashCount(PaymentMethod.CASH, Money.parse("100.00")),
                        CashCount(PaymentMethod.PIX, Money.parse("55.50")),
                    )
                core.cashier.closeShift(SHIFT, counts, Intent())
                val sent: JsonArray = bodyOf(requests.single()).getValue("counts").jsonArray
                assertEquals(
                    listOf("CASH" to "100.00", "PIX" to "55.50"),
                    sent.map {
                        it.jsonObject
                            .getValue("method")
                            .jsonPrimitive.content to
                            it.jsonObject
                                .getValue("amount")
                                .jsonPrimitive.content
                    },
                )
                assertFalse(
                    sent.any {
                        it.jsonObject
                            .getValue("method")
                            .jsonPrimitive.content == "DEBIT_CARD"
                    },
                )
            }
        }

    @Test
    fun reportReadsTheShiftReport() =
        runTest {
            core { json(report()) }.use { core ->
                assertNotNull(core.cashier.report(SHIFT))
                assertTrue(
                    requests
                        .single()
                        .url.encodedPath
                        .endsWith("/$SHIFT/report"),
                )
            }
        }

    @Test
    fun nonUuidIdFailsBeforeTheNetwork() =
        runTest {
            core { json("{}") }.use { core ->
                val bad = "nao-e-uuid-segredo"
                val errors =
                    listOf(
                        assertFailsWith<IllegalArgumentException> {
                            core.cashier.openShift(bad, Money.parse("1.00"), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            val amount = Money.parse("1.00")
                            core.cashier.recordMovement(bad, CashMovementType.SUPPLY, amount, "x", Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.cashier.closeShift(bad, emptyList(), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> { core.cashier.report(bad) },
                    )
                errors.forEach { assertFalse(it.message.orEmpty().contains(bad)) }
                assertTrue(requests.isEmpty())
            }
        }
}
