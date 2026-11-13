package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GUEST_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val DOC_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val FICTIONAL_NAME = "Fulana Fictícia de Teste"

/** A leitura do hóspede contra o `MockEngine`, pelo `ZenyCore` real; só dados fictícios. */
class GuestsFacadeTest {
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

    private fun guest(status: String = "ACTIVE") =
        """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC_ID",
        "issuingCountry":"BR","maskedNumber":"***.456.789-**","type":"CPF"}],"fullName":"$FICTIONAL_NAME",
        "hasSensitiveData":false,"id":"$GUEST_ID","preferences":{},"status":"$status",
        "updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    @Test
    fun guestReturnsMaskedDocuments() =
        runTest {
            core { json(guest()) }.use { core ->
                val guest = core.guests.guest(GUEST_ID)
                assertEquals(FICTIONAL_NAME, guest.fullName)
                assertEquals("***.456.789-**", guest.documents.single().maskedNumber)
            }
            assertEquals("/api/v1/guests/$GUEST_ID", requests.single().url.encodedPath)
        }

    @Test
    fun guestNotFoundIsProblem() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "GUEST_NOT_FOUND") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.guests.guest(GUEST_ID) }
                assertEquals(404, error.status)
                assertEquals("GUEST_NOT_FOUND", error.code)
                assertEquals("O hóspede não foi encontrado.", error.userMessage)
            }
        }

    @Test
    fun guestForbiddenIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED") }.use { core ->
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.guests.guest(GUEST_ID) }.status)
            }
        }

    @Test
    fun incompatibleGuestBodyHidesBody() =
        runTest {
            core { json(guest(status = "QUARANTINE")) }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.guests.guest(GUEST_ID) }
                val chain = generateSequence(error as Throwable) { it.cause }.toList()
                assertTrue(chain.none { FICTIONAL_NAME in "$it" || FICTIONAL_NAME in it.message.orEmpty() })
                assertNull(chain.firstOrNull { "QUARANTINE" in "$it" })
            }
        }

    @Test
    fun invalidIdFailsBeforeNetworkWithoutEchoingTheId() =
        runTest {
            core { json(guest()) }.use { core ->
                for (id in listOf("", "segredo-123", "../guests", "$GUEST_ID/x")) {
                    val error = assertFailsWith<IllegalArgumentException>("id $id") { core.guests.guest(id) }
                    assertTrue(id.isEmpty() || id !in error.message.orEmpty(), "mensagem ecoa '$id'")
                }
            }
            assertEquals(0, requests.size)
        }
}
