package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PRE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f21"
private const val USER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f14"

/** Ataque às operações de caução da `PaymentsFacade` (M4 E09): a chave amarrada à intenção e as entradas sem rede. */
class PreAuthorizationFacadeAtaqueTest {
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

    private val preAuthorization =
        """{"amount":"300.00","authorizationCode":"AUT123","businessDate":"2026-11-18","capturableAmount":"300.00",
        "capturePaymentIds":[],"capturedAmount":"0.00","expiresAt":"2026-11-20T02:59:59Z","folioId":"$FOLIO",
        "id":"$PRE","registeredAt":"2026-11-18T10:00:00Z","registeredBy":"$USER","status":"ACTIVE"}"""

    private fun register(
        authorizationCode: String = "AUT123",
        cardLast4: String? = null,
    ) = PreAuthorizationInput(
        Money.parse("300.00"),
        Instant.parse("2026-11-20T02:59:59Z"),
        authorizationCode,
        cardLast4 = cardLast4,
    )

    @Test
    fun registerSendsTheKeyOfTheIntentAndRepeatsItOnRetry() =
        runTest {
            core { json(preAuthorization, HttpStatusCode.Created) }.use { core ->
                val intent = Intent()
                core.payments.registerPreAuthorization(FOLIO, register(), intent)
                core.payments.registerPreAuthorization(FOLIO, register(), intent)
                core.payments.registerPreAuthorization(FOLIO, register(), Intent())

                val (first, retry, other) = requests.map { it.headers["Idempotency-Key"] }
                assertEquals(intent.key, first, "a chave é a da intenção")
                assertEquals(intent.key, retry, "a repetição com a mesma intenção leva a mesma chave")
                assertNotEquals(intent.key, other, "outra intenção, outra chave")
            }
        }

    @Test
    fun invalidPreAuthorizationInputFailsWithoutTheTypedValue() =
        runTest {
            core { json(preAuthorization, HttpStatusCode.Created) }.use { core ->
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        core.payments.registerPreAuthorization(
                            FOLIO,
                            register(authorizationCode = "4111111111111111", cardLast4 = "41x1"),
                            Intent(),
                        )
                    }
                val text = failure.message.orEmpty() + failure.toString()
                assertFalse("4111" in text, "a mensagem não repete o digitado")
                assertFalse("41x1" in text, "a mensagem não repete os últimos dígitos")
                assertTrue(requests.isEmpty(), "nada vai à rede")
            }
        }

    @Test
    fun invalidIdsFailBeforeTheNetworkWithoutTheId() =
        runTest {
            core { json(preAuthorization) }.use { core ->
                val secret = "4111111111111111"
                listOf(
                    suspend { core.payments.releasePreAuthorization(secret, PRE, Intent()) },
                    suspend { core.payments.releasePreAuthorization(FOLIO, secret, Intent()) },
                    suspend { core.payments.registerPreAuthorization(secret, register(), Intent()) },
                    suspend { core.payments.preAuthorizations(secret) },
                ).forEach { call ->
                    val failure = assertFailsWith<IllegalArgumentException> { call() }
                    assertFalse(secret in failure.message.orEmpty(), "a mensagem não repete o id")
                }
                assertTrue(requests.isEmpty(), "nada vai à rede")
            }
        }
}
