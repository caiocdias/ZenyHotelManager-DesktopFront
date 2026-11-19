package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.pending.PendingPlanSpec
import com.zeny.desktop.seed.pending.PendingPriceSpec
import com.zeny.desktop.seed.pending.PendingReservationSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MARKED = "DETALHE-MARCADO-DO-SERVIDOR"
private const val L_CPF = "52998224725"
private const val L_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d11"
private const val L_ID2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d12"
private val L_DAY = LocalDate.of(2026, 11, 19)

/** O que o servidor responde numa falha, com o marcador no `detail`, no corpo ou numa mensagem de campo. */
private class Reply(
    val label: String,
    val status: HttpStatusCode,
    val body: String,
)

private val REPLIES =
    listOf(
        Reply(
            "412 com detail marcado",
            HttpStatusCode.PreconditionFailed,
            """{"code":"PRECONDITION_FAILED","title":"T","status":412,"detail":"$MARKED $L_CPF"}""",
        ),
        Reply(
            "200 com corpo malformado",
            HttpStatusCode.OK,
            """{"items":[{"id":"$MARKED $L_CPF""",
        ),
        Reply(
            "200 com tipo errado no corpo",
            HttpStatusCode.OK,
            """{"items":"$MARKED $L_CPF"}""",
        ),
        Reply(
            "422 com o marcador só em fieldErrors[].message",
            HttpStatusCode.UnprocessableEntity,
            """{"code":"ALGUM_CODIGO","title":"T","status":422,"detail":"d",
              |"errors":[{"field":"f","code":"c","message":"$MARKED $L_CPF"}]}
            """.trimMargin(),
        ),
        Reply(
            "422 com marcador em detail e em fieldErrors",
            HttpStatusCode.UnprocessableEntity,
            """{"code":"ALGUM_CODIGO","title":"T","status":422,"detail":"$MARKED",
              |"errors":[{"field":"$MARKED","code":"c","message":"$MARKED $L_CPF"}]}
            """.trimMargin(),
        ),
    )

/** Tudo o que um operador (ou um log) poderia ler de um erro: mensagens, `toString`, causas e campos tipados. */
private fun readableTexts(error: Throwable): List<String> {
    val texts = mutableListOf<String>()
    generateSequence(error) { it.cause }.forEach {
        texts += it.message.orEmpty()
        texts += it.toString()
        when (it) {
            is ApiError.Problem -> {
                texts += it.detail.orEmpty()
                texts += it.code.orEmpty()
                it.fieldErrors.forEach { f -> texts += listOf(f.field, f.code, f.message) }
            }

            is ApiError.PreconditionFailed -> {
                texts += it.detail.orEmpty()
            }

            is ApiError.ApprovalRequired -> {
                texts += it.detail.orEmpty()
            }

            else -> {
                texts += ""
            }
        }
    }
    return texts
}

private fun leaks(error: Throwable) = readableTexts(error).filter { MARKED in it || L_CPF in it }

private fun pendingOperations(): Map<String, suspend SdkPendingGateway.() -> Unit> =
    mapOf(
        "guaranteePolicyId" to { guaranteePolicyId("x") },
        "createCardGuaranteePolicy" to { createCardGuaranteePolicy("x") },
        "cancellationPolicyId" to { cancellationPolicyId("x") },
        "ratePlanId" to { ratePlanId("x") },
        "createWebsiteRatePlan" to { createWebsiteRatePlan(PendingPlanSpec("C", "N", L_ID, L_ID, L_ID)) },
        "writePrices" to {
            writePrices(L_ID, PendingPriceSpec(L_ID, BigDecimal("300"), BigDecimal("320"), L_DAY, L_DAY))
        },
        "guestId" to { guestId(L_CPF) },
        "findReservation" to { findReservation("DEMO-PEND-R1") },
        "createReservation" to {
            createReservation(
                PendingReservationSpec(
                    "DEMO-PEND-P1",
                    L_ID,
                    L_ID,
                    L_ID,
                    "WEBSITE",
                    L_DAY.plusDays(5),
                    L_DAY.plusDays(7),
                    2,
                ),
            )
        },
        "assignRoom" to { assignRoom(L_ID, L_ID2) },
        "cancelReservation" to { cancelReservation(L_ID, "GUEST_REQUEST") },
        "openOutOfOrder" to { openOutOfOrder(L_ID, L_DAY, L_DAY, "x") },
    )

private fun folioOperations(): Map<String, suspend SdkFolioGateway.() -> Unit> =
    mapOf(
        "reservationId" to { reservationId("DEMO-SEED-H1") },
        "reservationFolioId" to { reservationFolioId(L_ID) },
        "postingDescriptions" to { postingDescriptions(L_ID) },
        "chargeableCodeId" to { chargeableCodeId(listOf("Alimentos e bebidas")) },
        "post" to { post(L_ID, L_ID2, "32.00", "Seed M4: Frigobar") },
    )

private fun core(reply: Reply) =
    SeedCore(
        SERVER_A,
        MockEngine {
            respond(reply.body, reply.status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

/**
 * `B-M4`: o `detail` do servidor pode ecoar dado do hóspede; nenhum erro que sai do adaptador o carrega, seja
 * qual for a forma da recusa (412, 2xx ilegível, mensagem de campo).
 */
class SdkDetailLeakAtaqueTest {
    @Test
    fun pendingGatewayNeverLeaksTheMarkedBodyOnAnyFailureShape() =
        runTest {
            val found = mutableListOf<String>()
            for (reply in REPLIES) {
                for ((name, operation) in pendingOperations()) {
                    val failure = runCatching { SdkPendingGateway(core(reply)).operation() }.exceptionOrNull()
                    if (failure == null) {
                        found += "[${reply.label}] $name: não falhou"
                    } else {
                        val kind = failure::class.simpleName
                        leaks(failure).forEach { found += "[${reply.label}] $name: $kind vaza: $it" }
                    }
                }
            }
            assertEquals(emptyList(), found, found.joinToString("\n"))
        }

    private suspend fun folioLeaks(only: (String) -> Boolean): List<String> {
        val found = mutableListOf<String>()
        for (reply in REPLIES) {
            for ((name, operation) in folioOperations().filterKeys(only)) {
                val failure = runCatching { SdkFolioGateway(core(reply)).operation() }.exceptionOrNull()
                if (failure == null) {
                    found += "[${reply.label}] $name: não falhou"
                } else {
                    leaks(failure).forEach { found += "[${reply.label}] $name: ${failure::class.simpleName} vaza: $it" }
                }
            }
        }
        return found
    }

    @Test
    fun folioPostNeverLeaksTheMarkedBodyOnAnyFailureShape() =
        runTest {
            val found = folioLeaks { it == "post" }
            assertEquals(emptyList(), found, found.joinToString("\n"))
        }

    @Test
    fun folioReadsNeverLeakTheMarkedBodyOnAnyFailureShape() =
        runTest {
            val found = folioLeaks { it != "post" }
            assertEquals(emptyList(), found, found.joinToString("\n"))
        }

    @Test
    fun folioPostKeepsStatusAndCodeOfA412AndOfAFieldRefusal() =
        runTest {
            val precondition =
                runCatching { SdkFolioGateway(core(REPLIES[0])).post(L_ID, L_ID2, "1.00", "Seed M4: X") }
                    .exceptionOrNull()
            assertTrue(precondition is ApiError.PreconditionFailed, precondition.toString())
            val fields =
                runCatching { SdkFolioGateway(core(REPLIES[3])).post(L_ID, L_ID2, "1.00", "Seed M4: X") }
                    .exceptionOrNull()
            assertTrue(fields is ApiError.Problem, fields.toString())
            assertEquals(422, fields.status)
            assertEquals("ALGUM_CODIGO", fields.code)
            assertEquals(emptyList(), fields.fieldErrors)
        }
}
