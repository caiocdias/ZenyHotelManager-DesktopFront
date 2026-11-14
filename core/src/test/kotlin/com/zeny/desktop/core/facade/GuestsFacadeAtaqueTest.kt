package com.zeny.desktop.core.facade

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ErrorCatalog
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.sdk.model.GuestResponse
import de.quati.kotlin.util.Option
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val G_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val G_DOC1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val G_DOC2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val G_SURVIVOR = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"
private const val NAME = "Beltrana Fictícia Sigilosa"
private const val MAIL = "beltrana.ficticia@exemplo.invalid"
private const val PHONE = "+55 11 90000-1234"
private const val FULL_DOC = "123.456.789-09"

private val PERSONAL = listOf(NAME, MAIL, PHONE, FULL_DOC, "Sigilosa")

/**
 * Ataque à leitura do hóspede (dado pessoal): nenhum dado do perfil em mensagem, causa ou `toString` de exceção em
 * qualquer caminho de falha; `401`, `403`, `404`, `409`; perfil mesclado; id inválido sem rede e sem eco; o
 * `maskedNumber` chega como o servidor mandou. Só dados fictícios.
 */
class GuestsFacadeAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private var server = FakeZenyServer()

    private suspend fun core(routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore {
        server = FakeZenyServer()
        server.routes = routes
        val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings(), server.engine)
        core.session.login("maria", "segredo")
        return core
    }

    private fun doc(
        id: String,
        masked: String,
        type: String = "CPF",
    ) =
        """{"createdAt":"2026-11-12T10:00:00Z","id":"$id","issuingCountry":"BR","maskedNumber":"$masked","type":"$type"}"""

    private fun guest(
        status: String = "ACTIVE",
        docs: List<String> = listOf(doc(G_DOC1, "***.456.789-**")),
        extra: String = "",
    ) = """{"createdAt":"2026-11-12T10:00:00Z","documents":[${docs.joinToString(",")}],"fullName":"$NAME",
        "email":"$MAIL","phone":"$PHONE","notes":"$NAME","hasSensitiveData":false,"id":"$G_ID","preferences":{},
        "status":"$status","updatedAt":"2026-11-12T10:00:00Z","vip":false$extra}"""

    private fun guestRequests() = server.requests.filter { it.url.encodedPath.startsWith("/api/v1/guests") }

    /** Tudo que uma pessoa ou um log poderia ver da exceção: `toString`, mensagem, causas, suprimidas e a pilha. */
    private fun everythingVisible(error: Throwable): String {
        val seen = mutableSetOf<Throwable>()
        val out = StringBuilder()

        fun walk(t: Throwable?) {
            if (t == null || !seen.add(t)) return
            out
                .append(t.toString())
                .append('\n')
                .append(t.message.orEmpty())
                .append('\n')
            t.suppressed.forEach(::walk)
            walk(t.cause)
        }
        walk(error)
        out.append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
        if (error is ApiError) out.append(error.userMessage)
        return out.toString()
    }

    private fun assertNoPersonalData(
        error: Throwable,
        label: String,
    ) {
        val visible = everythingVisible(error)
        for (secret in PERSONAL) assertFalse(secret in visible, "$label vaza '$secret'")
    }

    @Test
    fun maskedNumbersArriveExactlyAsTheServerSentThemInOrder() =
        runTest(timeout = 10.seconds) {
            val docs = listOf(doc(G_DOC1, "***.456.789-**"), doc(G_DOC2, "••••1234", type = "PASSPORT"))
            core { json(guest(docs = docs)) }.use { core ->
                val result = core.guests.guest(G_ID)
                assertEquals(listOf("***.456.789-**", "••••1234"), result.documents.map { it.maskedNumber })
                assertEquals(listOf("CPF", "PASSPORT"), result.documents.map { it.type.value })
            }
        }

    @Test
    fun guestWithoutDocumentsHasEmptyListAndNoInventedNumber() =
        runTest(timeout = 10.seconds) {
            core { json(guest(docs = emptyList())) }.use { core ->
                assertEquals(emptyList(), core.guests.guest(G_ID).documents)
            }
        }

    @Test
    fun readNeverAsksTheServerToRevealADocument() =
        runTest(timeout = 10.seconds) {
            core { json(guest()) }.use { core -> core.guests.guest(G_ID) }
            val sent = guestRequests().single()
            assertEquals("GET", sent.method.value)
            assertEquals("/api/v1/guests/$G_ID", sent.url.encodedPath)
            assertEquals(
                0,
                sent.url.parameters
                    .names()
                    .size,
                "sem query",
            )
            assertFalse("reveal" in sent.url.toString().lowercase())
        }

    @Test
    fun uppercaseIdIsSentCanonicalLowercase() =
        runTest(timeout = 10.seconds) {
            core { json(guest()) }.use { core -> core.guests.guest(G_ID.uppercase()) }
            assertEquals("/api/v1/guests/$G_ID", guestRequests().single().url.encodedPath)
        }

    @Test
    fun mergedProfileIsAReadableOkWithSurvivorNotAnError() =
        runTest(timeout = 10.seconds) {
            val extra = ""","mergedInto":"$G_SURVIVOR","mergedAt":"2026-11-12T11:00:00Z""""
            core { json(guest(status = "MERGED", extra = extra)) }.use { core ->
                val result = core.guests.guest(G_ID)
                assertEquals(GuestResponse.Status.MERGED, result.status)
                assertEquals(Option.Some<Uuid?>(Uuid.parse(G_SURVIVOR)), result.mergedInto)
                assertIs<SessionState.Active>(core.session.state.value)
            }
        }

    @Test
    fun anonymizedProfileIsReadable() =
        runTest(timeout = 10.seconds) {
            core { json(guest(status = "ANONYMIZED", docs = emptyList())) }.use { core ->
                assertEquals(GuestResponse.Status.ANONYMIZED, core.guests.guest(G_ID).status)
            }
        }

    @Test
    fun forbiddenNotFoundAndConflictAreProblemsKeepTheSessionAndLeakNothing() =
        runTest(timeout = 10.seconds) {
            val cases =
                listOf(
                    Triple(HttpStatusCode.Forbidden, "PERMISSION_DENIED", 403),
                    Triple(HttpStatusCode.NotFound, "GUEST_NOT_FOUND", 404),
                    Triple(HttpStatusCode.Conflict, "GUEST_ALREADY_MERGED", 409),
                    Triple(HttpStatusCode.Conflict, "GUEST_ANONYMIZED", 409),
                )
            for ((http, code, status) in cases) {
                core { problem(http, code) }.use { core ->
                    val error = assertFailsWith<ApiError.Problem>(code) { core.guests.guest(G_ID) }
                    assertEquals(status, error.status)
                    assertEquals(code, error.code)
                    assertFalse(G_ID in everythingVisible(error), "$code ecoa o id")
                    assertIs<SessionState.Active>(core.session.state.value, "$code não encerra a sessão")
                }
            }
        }

    @Test
    fun unauthorizedIsSessionEndedAndEndsTheSession() =
        runTest(timeout = 10.seconds) {
            core { json(guest()) }.use { core ->
                server.rejectAllWith = "SESSION_REVOKED"
                val ended = assertFailsWith<ApiError.SessionEnded> { core.guests.guest(G_ID) }
                assertEquals("SESSION_REVOKED", ended.code)
                assertIs<SessionState.Ended>(core.session.state.value)
                assertNoPersonalData(ended, "SessionEnded")
            }
        }

    @Test
    fun everyIncompatibleBodyHidesTheProfileFromMessageCauseAndStack() =
        runTest(timeout = 10.seconds) {
            val bodies =
                mapOf(
                    "status fechado desconhecido" to guest(status = "QUARANTINE"),
                    "status com o nome" to guest(status = NAME),
                    "tipo de documento desconhecido" to guest(docs = listOf(doc(G_DOC1, "***", type = "VISTO"))),
                    "genero desconhecido" to guest(extra = ""","gender":"$NAME""""),
                    "campo obrigatorio faltando" to guest().replace("\"fullName\":\"$NAME\",", ""),
                    "documentos faltando" to guest().replace(Regex("\"documents\":\\[.*?\\],"), ""),
                    "id do perfil nao e uuid" to guest().replace("\"id\":\"$G_ID\"", "\"id\":\"$NAME\""),
                    "id do documento nao e uuid" to guest(docs = listOf(doc(NAME, "***"))),
                    "tipo errado no nome" to guest().replace("\"fullName\":\"$NAME\"", "\"fullName\":[\"$NAME\"]"),
                    "vip como texto" to guest().replace("\"vip\":false", "\"vip\":\"$NAME\""),
                    "corpo html" to "<html>$NAME $MAIL $PHONE $FULL_DOC</html>",
                    "json truncado" to
                        """{"fullName":"$NAME","email":"$MAIL","documents":[{"maskedNumber":"$FULL_DOC"""",
                    "array no lugar do objeto" to """["$NAME","$MAIL"]""",
                    "corpo vazio" to "",
                )
            for ((label, body) in bodies) {
                core { json(body) }.use { core ->
                    val error = assertFailsWith<ApiError.IncompatibleResponse>(label) { core.guests.guest(G_ID) }
                    assertNoPersonalData(error, label)
                    assertIs<SessionState.Active>(core.session.state.value, "$label não encerra a sessão")
                }
            }
        }

    @Test
    fun unmappedCodeShowsTheServerDetailAndNeverRawJsonOrCode() =
        runTest(timeout = 10.seconds) {
            // Design 6.2: um `code` desconhecido mostra o `detail` do servidor; JSON cru e código cru não aparecem.
            val cases =
                mapOf(
                    """{"code":"GUEST_NEW_RULE","detail":"Perfil em revisão manual."}""" to "Perfil em revisão manual.",
                    """{"code":"GUEST_NEW_RULE","detail":"GUEST_NEW_RULE"}""" to ErrorCatalog.GENERIC_MESSAGE,
                    """{"code":"GUEST_NEW_RULE","detail":"{\"x\":1}"}""" to ErrorCatalog.GENERIC_MESSAGE,
                )
            for ((body, expected) in cases) {
                core { json(body, HttpStatusCode.Conflict) }.use { core ->
                    val error = assertFailsWith<ApiError.Problem>(body) { core.guests.guest(G_ID) }
                    assertEquals(expected, error.userMessage, body)
                    assertEquals(expected, error.message)
                    assertFalse("GUEST_NEW_RULE" in error.userMessage)
                }
            }
        }

    @Test
    fun invalidIdsFailBeforeNetworkWithoutEchoingTheId() =
        runTest(timeout = 10.seconds) {
            core { json(guest()) }.use { core ->
                val before = server.requests.size
                val bad =
                    listOf(
                        " $G_ID",
                        "$G_ID ",
                        "$G_ID\n",
                        G_ID.dropLast(1),
                        "${G_ID}0",
                        "{$G_ID}",
                        "%20$G_ID",
                        "$G_ID/documents",
                        "$G_ID?reveal=true",
                        "id-$NAME",
                        "   ",
                    )
                for (id in bad) {
                    val error = assertFailsWith<IllegalArgumentException>("id '$id'") { core.guests.guest(id) }
                    val visible = everythingVisible(error)
                    assertFalse(id.isNotBlank() && id.trim() in visible, "mensagem ecoa '$id'")
                    assertFalse(NAME in visible)
                    assertFalse(G_ID in visible, "mensagem ecoa parte de um UUID")
                }
                assertEquals(before, server.requests.size, "nenhuma requisição para id inválido")
            }
            assertTrue(guestRequests().isEmpty())
        }
}
