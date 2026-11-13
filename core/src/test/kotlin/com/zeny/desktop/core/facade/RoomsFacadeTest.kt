package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.sdk.model.RoomStatusResponse
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

private const val ROOM_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ROOM_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ROOM_3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val TYPE_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"

/** A fachada das UHs contra o `MockEngine`, pelo `ZenyCore` real. */
class RoomsFacadeTest {
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

    private fun status(
        id: String,
        number: String,
        housekeeping: String = "CLEAN",
    ) = """{"active":true,"changedAt":"2026-11-12T10:00:00Z","housekeepingStatus":"$housekeeping",
        "inspectionPending":false,"number":"$number","occupancy":"VACANT","readyForCheckIn":true,
        "roomId":"$id","roomTypeId":"$TYPE_1","serviceFlag":"NONE","version":3}"""

    private fun roomType(
        id: String,
        code: String,
    ) = """{"active":true,"code":"$code","createdAt":"2026-11-12T10:00:00Z","displayOrder":1,"featureIds":[],
        "id":"$id","maxAdults":2,"maxChildren":1,"name":"Tipo $code","standardAdults":2,"standardChildren":0,
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun room(id: String) =
        """{"active":true,"createdAt":"2026-11-12T10:00:00Z","featureIds":[],"id":"$id","number":"101",
        "roomTypeId":"$TYPE_1","updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun page(
        items: List<String>,
        next: String?,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    @Test
    fun statusesReadAllPages() =
        runTest {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(status(ROOM_1, "101"), status(ROOM_2, "102")), "p2"))
                    "p2" -> json(page(listOf(status(ROOM_3, "103")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                val statuses = core.rooms.statuses()
                assertEquals(listOf("101", "102", "103"), statuses.map { it.number })
                assertEquals(listOf(ROOM_1, ROOM_2, ROOM_3), statuses.map { it.roomId.toString() })
            }
            assertEquals(listOf("/api/v1/rooms/status", "/api/v1/rooms/status"), requests.map { it.url.encodedPath })
            assertEquals(listOf(null, "p2"), requests.map { it.url.parameters["cursor"] })
        }

    @Test
    fun roomTypesReadAllPages() =
        runTest {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(roomType(TYPE_1, "STD")), "p2"))
                    "p2" -> json(page(listOf(roomType(TYPE_2, "LUX")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("STD", "LUX"), core.rooms.roomTypes().map { it.code })
            }
            assertEquals(listOf("/api/v1/room-types", "/api/v1/room-types"), requests.map { it.url.encodedPath })
            assertEquals(listOf(null, "p2"), requests.map { it.url.parameters["cursor"] })
        }

    @Test
    fun roomAndStatusById() =
        runTest {
            core { request ->
                when (request.url.encodedPath) {
                    "/api/v1/rooms/$ROOM_1" -> json(room(ROOM_1))
                    "/api/v1/rooms/$ROOM_1/status" -> json(status(ROOM_1, "101", "DIRTY"))
                    else -> problem(HttpStatusCode.NotFound, "ROOM_NOT_FOUND")
                }
            }.use { core ->
                val room = core.rooms.room(ROOM_1)
                assertEquals(ROOM_1, room.id.toString())
                val status = core.rooms.status(ROOM_1)
                assertEquals(RoomStatusResponse.HousekeepingStatus.DIRTY, status.housekeepingStatus)
                assertEquals(2, requests.size)
                // Um id que não é UUID nem sai para a rede.
                for (id in listOf("", "101", "../rooms", "$ROOM_1/status")) {
                    assertFailsWith<IllegalArgumentException>("id $id") { core.rooms.room(id) }
                    assertFailsWith<IllegalArgumentException>("id $id") { core.rooms.status(id) }
                }
                assertEquals(2, requests.size)
            }
        }

    @Test
    fun problemsAreApiErrors() =
        runTest {
            core { request ->
                when (request.url.encodedPath) {
                    "/api/v1/rooms/status" -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                    else -> problem(HttpStatusCode.NotFound, "ROOM_NOT_FOUND")
                }
            }.use { core ->
                val forbidden = assertFailsWith<ApiError.Problem> { core.rooms.statuses() }
                assertEquals(403, forbidden.status)
                assertEquals("PERMISSION_DENIED", forbidden.code)
                val missing = assertFailsWith<ApiError.Problem> { core.rooms.room(ROOM_2) }
                assertEquals(404, missing.status)
                assertEquals("ROOM_NOT_FOUND", missing.code)
                assertEquals("ROOM_NOT_FOUND", assertFailsWith<ApiError.Problem> { core.rooms.status(ROOM_2) }.code)
                assertEquals("ROOM_NOT_FOUND", assertFailsWith<ApiError.Problem> { core.rooms.roomTypes() }.code)
            }
        }

    @Test
    fun incompatibleEnumIsIncompatibleResponse() =
        runTest {
            core { json(page(listOf(status(ROOM_1, "101", "QUARANTINE")), null)) }.use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.rooms.statuses() }
                // A causa não ecoa o corpo da resposta.
                assertNull(generateSequence(error as Throwable) { it.cause }.firstOrNull { "QUARANTINE" in "$it" })
            }
        }
}
