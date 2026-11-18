package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

private const val GUEST_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val DOC_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val PLAN_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val POLICY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b02"
private const val USER_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val SESSION_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"

/**
 * O adaptador `CoreConnection.receptionSource()` contra um servidor HTTP local de verdade (JDK), pelo `ZenyCore` real:
 * o `app` não tem o motor de mock do Ktor. Só dados fictícios; nenhum teste dorme.
 */
class CoreReceptionSourceAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val paths = CopyOnWriteArrayList<String>()

    private fun withConnection(
        guest: String,
        block: suspend (ReceptionSource, CoreConnection) -> Unit,
    ) = runTest {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.path
            val body =
                when (exchange.requestURI.path) {
                    "/api/v1/auth/me" -> me()
                    "/api/v1/guests/$GUEST_ID" -> guest
                    "/api/v1/rate-plans/$PLAN_ID" -> plan()
                    else -> null
                }
            exchange.reply(body)
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                block(assertNotNull(connection.receptionSource()), connection)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(body: String?) {
        val bytes = (body ?: """{"code":"NOT_FOUND","detail":"x"}""").toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun me() =
        """{"login":"maria.silva","name":"Maria Silva","permissions":[
        {"code":"reservations.reservations.read","limits":[],"scope":"ALL"},
        {"code":"guests.profiles.read","limits":[],"scope":"OWN"}],"roles":[],
        "sessionId":"$SESSION_ID","userId":"$USER_ID"}"""

    private fun plan() =
        """{"active":true,"cancellationPolicyId":"$POLICY_ID","category":"NORMAL","channels":["DIRECT"],
        "code":"BAR","createdAt":"2026-11-12T10:00:00Z","guaranteePolicyId":"$POLICY_ID","id":"$PLAN_ID",
        "mealPlan":"BREAKFAST","name":"Tarifa Padrão","packageIds":[],"roomTypeIds":[],
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun guest(
        social: String?,
        documents: String =
            """[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC_ID","issuingCountry":"BR",
            "maskedNumber":"***.456.789-**","type":"CPF"}]""",
    ) = """{"createdAt":"2026-11-12T10:00:00Z","documents":$documents,"fullName":"Fulana Fictícia de Teste",
        "hasSensitiveData":false,"id":"$GUEST_ID","preferences":{},"status":"ACTIVE",
        ${social?.let { """"socialName":"$it",""" }.orEmpty()}"updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    @Test
    fun receptionSourceIsTheSameInstanceOnEveryCall() =
        withConnection(guest(null)) { source, connection ->
            assertSame(source, connection.receptionSource())
        }

    @Test
    fun permissionsAreTheCodesOfMeOnly() =
        withConnection(guest(null)) { _, connection ->
            // A porta não lê mais permissões (ADR 0013): o conjunto das áreas é o `me` que a casca lê da conexão.
            assertEquals(setOf("reservations.reservations.read", "guests.profiles.read"), connection.me().permissions)
            assertEquals(listOf("/api/v1/auth/me"), paths.toList())
        }

    @Test
    fun ratePlanNameIsThePlanNameNotItsCodeOrId() =
        withConnection(guest(null)) { source, _ ->
            assertEquals("Tarifa Padrão", source.ratePlanName(PLAN_ID))
            assertEquals(listOf("/api/v1/rate-plans/$PLAN_ID"), paths.toList())
        }

    @Test
    fun holderUsesSocialNameAndMaskedDocumentFromTheServer() =
        withConnection(guest("Fulana Social")) { source, _ ->
            val holder = source.holder(GUEST_ID)

            assertEquals(HolderSummary("Fulana Social", "CPF ***.456.789-**"), holder)
            assertEquals(listOf("/api/v1/guests/$GUEST_ID"), paths.toList())
        }

    @Test
    fun holderWithoutSocialNameFieldFallsBackToFullName() =
        withConnection(guest(null)) { source, _ ->
            assertEquals("Fulana Fictícia de Teste", source.holder(GUEST_ID).name)
        }

    @Test
    fun holderWithBlankSocialNameFromTheWireFallsBackToFullName() =
        withConnection(guest("   ")) { source, _ ->
            assertEquals("Fulana Fictícia de Teste", source.holder(GUEST_ID).name)
        }

    @Test
    fun holderWithoutDocumentsHasNullDocument() =
        withConnection(guest(null, documents = "[]")) { source, _ ->
            assertNull(source.holder(GUEST_ID).document)
        }

    @Test
    fun holderResultNeverExposesPersonalDataInToString() =
        withConnection(guest("Fulana Social")) { source, _ ->
            val text = source.holder(GUEST_ID).toString()

            assertFalse("Fulana" in text || "456" in text || "CPF" in text, text)
        }

    @Test
    fun eventsDelegateToTheCoreRealtimeValidationOfTopics() =
        withConnection(guest(null)) { source, _ ->
            // O `core` recusa assinatura sem tópicos ou com vírgula: só falha aqui se `events` repassar os tópicos.
            assertFailsWith<IllegalArgumentException> { source.events(emptySet()) }
            assertFailsWith<IllegalArgumentException> { source.events(setOf("a,b")) }
            assertEquals(emptyList(), paths.toList())
        }

    @Test
    fun invalidIdsAreRejectedByTheFacadeBeforeAnyRequest() =
        withConnection(guest(null)) { source, _ ->
            assertFailsWith<IllegalArgumentException> { source.history("não-é-uuid") }
            assertFailsWith<IllegalArgumentException> { source.roomAssignments("não-é-uuid") }
            assertFailsWith<IllegalArgumentException> { source.ratePlanName("não-é-uuid") }
            assertEquals(emptyList(), paths.toList())
        }
}
