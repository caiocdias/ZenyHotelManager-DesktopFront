package com.zeny.desktop.core.facade

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.session.SessionState
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val R1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val R2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val R3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val R4 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"
private const val T1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val T2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val T3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"
private const val STATUSES = "/api/v1/rooms/status"
private const val TYPES = "/api/v1/room-types"

/**
 * Ataque à fachada das UHs, de ponta a ponta pelo `ZenyCore` com sessão aberta contra o `FakeZenyServer`:
 * paginação longa, páginas vazias, cursor que não avança, falha no meio da lista, sessão (refresh e 401),
 * `id` inválido e enum fechado desconhecido.
 */
class RoomsFacadeAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val server = FakeZenyServer()

    private suspend fun core(routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore {
        server.routes = routes
        val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings(), server.engine)
        core.session.login("maria", "segredo")
        return core
    }

    private fun status(
        id: String,
        number: String,
        housekeeping: String = "CLEAN",
        occupancy: String = "VACANT",
        serviceFlag: String = "NONE",
    ) = """{"active":true,"changedAt":"2026-11-12T10:00:00Z","housekeepingStatus":"$housekeeping",
        "inspectionPending":false,"number":"$number","occupancy":"$occupancy","readyForCheckIn":true,
        "roomId":"$id","roomTypeId":"$T1","serviceFlag":"$serviceFlag","version":3}"""

    private fun roomType(
        id: String,
        code: String,
    ) = """{"active":true,"code":"$code","createdAt":"2026-11-12T10:00:00Z","displayOrder":1,"featureIds":[],
        "id":"$id","maxAdults":2,"maxChildren":1,"name":"Tipo $code","standardAdults":2,"standardChildren":0,
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun room(id: String) =
        """{"active":true,"createdAt":"2026-11-12T10:00:00Z","featureIds":[],"id":"$id","number":"101",
        "roomTypeId":"$T1","updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun page(
        items: List<String>,
        next: String?,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun requestsTo(path: String) = server.requests.filter { it.url.encodedPath == path }

    @Test
    fun statusesJoinThreePagesInOrderAskingTheContractMaximumWithTheSessionBearer() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(status(R1, "101"), status(R2, "102")), "p2"))
                    "p2" -> json(page(listOf(status(R3, "103")), "p3"))
                    "p3" -> json(page(listOf(status(R4, "104")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("101", "102", "103", "104"), core.rooms.statuses().map { it.number })
            }
            val sent = requestsTo(STATUSES)
            assertEquals(listOf(null, "p2", "p3"), sent.map { it.url.parameters["cursor"] })
            assertEquals(listOf("200", "200", "200"), sent.map { it.url.parameters["limit"] })
            assertEquals(List(3) { listOf("Bearer A1") }, sent.map(server::authorizationOf))
        }

    @Test
    fun roomTypesJoinThreePagesInOrder() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(roomType(T1, "STD")), "c2"))
                    "c2" -> json(page(listOf(roomType(T2, "LUX")), "c3"))
                    "c3" -> json(page(listOf(roomType(T3, "SUI")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("STD", "LUX", "SUI"), core.rooms.roomTypes().map { it.code })
            }
            assertEquals(listOf(null, "c2", "c3"), requestsTo(TYPES).map { it.url.parameters["cursor"] })
            assertEquals(listOf("200", "200", "200"), requestsTo(TYPES).map { it.url.parameters["limit"] })
        }

    @Test
    fun emptyOnlyPageIsAnEmptyListAfterOneRequest() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                assertEquals(emptyList(), core.rooms.statuses())
                assertEquals(emptyList(), core.rooms.roomTypes())
            }
            assertEquals(1, requestsTo(STATUSES).size)
            assertEquals(1, requestsTo(TYPES).size)
        }

    @Test
    fun emptyMiddlePageWithCursorKeepsReading() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(status(R1, "101")), "p2"))
                    "p2" -> json(page(emptyList(), "p3"))
                    "p3" -> json(page(listOf(status(R2, "102")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("101", "102"), core.rooms.statuses().map { it.number })
            }
            assertEquals(3, requestsTo(STATUSES).size)
        }

    @Test
    fun cursorThatDoesNotAdvanceOrIsBlankEndsTheListInsteadOfLooping() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.encodedPath to request.url.parameters["cursor"]) {
                    STATUSES to null -> json(page(listOf(status(R1, "101")), "p2"))

                    // O servidor devolve o mesmo cursor: a fachada não pode girar para sempre.
                    STATUSES to "p2" -> json(page(listOf(status(R2, "102")), "p2"))

                    TYPES to null -> json(page(listOf(roomType(T1, "STD")), ""))

                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("101", "102"), core.rooms.statuses().map { it.number })
                assertEquals(listOf("STD"), core.rooms.roomTypes().map { it.code })
            }
            assertEquals(2, requestsTo(STATUSES).size)
            assertEquals(1, requestsTo(TYPES).size)
        }

    @Test
    fun forbiddenLaterPageFailsTheWholeListWithoutPartialResult() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(status(R1, "101")), "p2"))
                    else -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                }
            }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.rooms.statuses() }
                assertEquals(403, error.status)
                assertEquals("PERMISSION_DENIED", error.code)
                assertIs<SessionState.Active>(core.session.state.value, "403 não encerra a sessão")
            }
        }

    @Test
    fun expiredAccessBetweenPagesRefreshesOnceAndTheListCompletes() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(status(R1, "101")), "p2")).also { server.expireAccess() }
                    "p2" -> json(page(listOf(status(R2, "102")), "p3"))
                    "p3" -> json(page(listOf(status(R3, "103")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("101", "102", "103"), core.rooms.statuses().map { it.number })
                assertIs<SessionState.Active>(core.session.state.value)
            }
            assertEquals(1, server.count("/api/v1/auth/refresh"))
            assertEquals(
                listOf(listOf("Bearer A1"), listOf("Bearer A1"), listOf("Bearer A2"), listOf("Bearer A2")),
                requestsTo(STATUSES).map(server::authorizationOf),
            )
            assertEquals(listOf(null, "p2", "p2", "p3"), requestsTo(STATUSES).map { it.url.parameters["cursor"] })
        }

    @Test
    fun revokedSessionIsSessionEndedOnEveryOperationAndEndsTheSession() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                server.rejectAllWith = "SESSION_REVOKED"
                val ended = assertFailsWith<ApiError.SessionEnded> { core.rooms.statuses() }
                assertEquals("SESSION_REVOKED", ended.code)
                assertIs<SessionState.Ended>(core.session.state.value)
                // Sem sessão, as demais chamadas também terminam em 401, sem token.
                assertFailsWith<ApiError.SessionEnded> { core.rooms.roomTypes() }
                assertFailsWith<ApiError.SessionEnded> { core.rooms.room(R1) }
                assertFailsWith<ApiError.SessionEnded> { core.rooms.status(R1) }
            }
            assertEquals(0, server.count("/api/v1/auth/refresh"), "SESSION_REVOKED não pede refresh")
        }

    @Test
    fun notFoundAndForbiddenByIdAreProblemsAndKeepTheSession() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.encodedPath) {
                    "/api/v1/rooms/$R1" -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                    "/api/v1/rooms/$R1/status" -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                    else -> problem(HttpStatusCode.NotFound, "ROOM_NOT_FOUND")
                }
            }.use { core ->
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.rooms.room(R1) }.status)
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.rooms.status(R1) }.status)
                val missing = assertFailsWith<ApiError.Problem> { core.rooms.status(R2) }
                assertEquals(404, missing.status)
                assertEquals("ROOM_NOT_FOUND", missing.code)
                assertIs<SessionState.Active>(core.session.state.value)
            }
            assertEquals(
                listOf("/api/v1/rooms/$R1", "/api/v1/rooms/$R1/status", "/api/v1/rooms/$R2/status"),
                server.requests.map { it.url.encodedPath }.filter { it.startsWith("/api/v1/rooms") },
            )
        }

    @Test
    fun invalidIdFailsBeforeTheNetworkWithoutEchoingTheId() =
        runTest(timeout = 10.seconds) {
            core { json(room(R1)) }.use { core ->
                val before = server.requests.size
                val invalid =
                    listOf(
                        " $R1",
                        "$R1 ",
                        "{$R1}",
                        "${R1}0",
                        R1.dropLast(1),
                        "zz99d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01",
                        "0199d6b8_1f2e_7a3b_8c4d_5e6f7a8b9c01",
                        "%2e%2e",
                        "null",
                        "segredo-do-usuario",
                    )
                for (id in invalid) {
                    val onRoom = assertFailsWith<IllegalArgumentException>("room($id)") { core.rooms.room(id) }
                    val onStatus = assertFailsWith<IllegalArgumentException>("status($id)") { core.rooms.status(id) }
                    for (error in listOf(onRoom, onStatus)) {
                        assertFalse(id.isNotBlank() && id in error.message.orEmpty(), "a mensagem ecoa o id $id")
                    }
                }
                assertEquals(before, server.requests.size, "um id inválido saiu para a rede")
            }
        }

    @Test
    fun uppercaseUuidIsAcceptedAndSentInCanonicalForm() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.encodedPath) {
                    "/api/v1/rooms/$R1" -> json(room(R1))
                    else -> problem(HttpStatusCode.NotFound, "ROOM_NOT_FOUND")
                }
            }.use { core ->
                assertEquals(
                    R1,
                    core.rooms
                        .room(R1.uppercase())
                        .id
                        .toString(),
                )
            }
        }

    @Test
    fun unknownClosedEnumIsIncompatibleResponseInStatusesAndStatusById() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.encodedPath to request.url.parameters["cursor"]) {
                    STATUSES to null -> json(page(listOf(status(R1, "101")), "p2"))

                    // O valor desconhecido só aparece na segunda página: a lista não sai pela metade.
                    STATUSES to "p2" -> json(page(listOf(status(R2, "102", occupancy = "BLOCKED")), null))

                    "/api/v1/rooms/$R1/status" to null -> json(status(R1, "101", housekeeping = "QUARANTINE"))

                    "/api/v1/rooms/$R2/status" to null -> json(status(R2, "102", serviceFlag = "PRIVACY"))

                    else -> problem(HttpStatusCode.NotFound, "ROOM_NOT_FOUND")
                }
            }.use { core ->
                val inList = assertFailsWith<ApiError.IncompatibleResponse> { core.rooms.statuses() }
                val byId = assertFailsWith<ApiError.IncompatibleResponse> { core.rooms.status(R1) }
                val flag = assertFailsWith<ApiError.IncompatibleResponse> { core.rooms.status(R2) }
                for ((error, value) in listOf(inList to "BLOCKED", byId to "QUARANTINE", flag to "PRIVACY")) {
                    val echoes = generateSequence(error as Throwable) { it.cause }.any { value in "$it" }
                    assertFalse(echoes, "a causa ecoa o corpo ($value)")
                }
                assertIs<SessionState.Active>(core.session.state.value, "resposta incompatível não encerra a sessão")
            }
            assertEquals(2, requestsTo(STATUSES).size)
        }

    @Test
    fun missingRequiredFieldIsIncompatibleAndUnknownExtraFieldIsTolerated() =
        runTest(timeout = 10.seconds) {
            val withoutNumber = status(R1, "101").replace(""""number":"101",""", "")
            val withExtra = status(R2, "102").replace("{", """{"campoNovoDoServidor":{"x":1},""")
            core { request ->
                when (request.url.encodedPath) {
                    "/api/v1/rooms/$R1/status" -> json(withoutNumber)
                    "/api/v1/rooms/$R2/status" -> json(withExtra)
                    else -> problem(HttpStatusCode.NotFound, "ROOM_NOT_FOUND")
                }
            }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.rooms.status(R1) }
                assertEquals("102", core.rooms.status(R2).number)
            }
        }

    @Test
    fun networkFailureOnALaterPageIsNetworkError() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(status(R1, "101")), "p2"))
                    else -> throw IOException("conexão caiu")
                }
            }.use { core ->
                assertIs<ApiError.Network>(assertFails { core.rooms.statuses() })
                assertTrue(core.session.state.value is SessionState.Active)
            }
        }

    private suspend fun assertFails(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            return e
        }
        throw AssertionError("deveria ter falhado")
    }
}
