package com.zeny.desktop.app.traces

import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.sdk.model.TraceResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private val SERVER_DEPT = Department("40000000-0000-0000-0000-000000000001", "Governança")
private const val SERVER_RESERVATION = "20000000-0000-0000-0000-000000000001"

/** Recados decodificados do JSON no formato real do servidor: o que não tem valor vem com `null` explícito. */
private fun serverTrace(
    id: String,
    reservationId: String?,
    roomId: String?,
    roomNumber: String?,
): TraceResponse {
    fun json(value: String?) = value?.let { "\"$it\"" } ?: "null"
    return Json { ignoreUnknownKeys = true }.decodeFromString(
        TraceResponse.serializer(),
        """
        {
          "assigneeName": null,
          "assigneeUserId": null,
          "createdAt": "2026-11-14T10:00:00Z",
          "createdBy": "10000000-0000-0000-0000-000000000001",
          "departmentId": "${SERVER_DEPT.id}",
          "departmentName": "Governança",
          "dueOn": "2026-11-14",
          "id": "$id",
          "reservationId": ${json(reservationId)},
          "resolvedAt": null,
          "resolvedBy": null,
          "roomId": ${json(roomId)},
          "roomNumber": ${json(roomNumber)},
          "status": "OPEN",
          "text": "texto",
          "version": 1
        }
        """.trimIndent(),
    )
}

private const val ROOM_ID = "30000000-0000-0000-0000-000000000001"
private const val ROOM_TRACE_ID = "50000000-0000-0000-0000-000000000001"
private const val RESERVATION_TRACE_ID = "50000000-0000-0000-0000-000000000002"
private const val FREE_TRACE_ID = "50000000-0000-0000-0000-000000000003"

@OptIn(ExperimentalCoroutinesApi::class)
class TraceLinkServerJsonTest {
    private val roomTrace = serverTrace(ROOM_TRACE_ID, null, ROOM_ID, "101")
    private val reservationTrace = serverTrace(RESERVATION_TRACE_ID, SERVER_RESERVATION, null, null)
    private val freeTrace = serverTrace(FREE_TRACE_ID, null, null, null)

    private fun source() =
        FakeTracesSource().apply {
            departments = listOf(SERVER_DEPT)
            reservationCodes[SERVER_RESERVATION] = "R-77"
            seed(roomTrace)
            seed(reservationTrace)
            seed(freeTrace)
        }

    @Test
    fun roomTraceWithNullReservationShowsRoom() {
        assertEquals("UH 101", TraceText.link(roomTrace, null))
    }

    @Test
    fun reservationTraceShowsReservationCode() {
        assertEquals("Reserva R-77", TraceText.link(reservationTrace, "R-77"))
        assertEquals("Reserva", TraceText.link(reservationTrace, null))
    }

    @Test
    fun traceWithoutLinkShowsNoLink() {
        assertEquals("Sem vínculo", TraceText.link(freeTrace, null))
    }

    @Test
    fun tableShowsRoomForRoomTraceWithNullReservation() =
        runTest {
            val source = source()
            val state = TracesListState(source, backgroundScope, source.permissions, null)
            state.start()
            runCurrent()
            val links = state.table.rows.associate { it.id to it.link }
            assertEquals("UH 101", links[ROOM_TRACE_ID])
            assertEquals("Reserva R-77", links[RESERVATION_TRACE_ID])
            assertEquals("Sem vínculo", links[FREE_TRACE_ID])
        }

    @Test
    fun editFormShowsRoomForRoomTraceWithNullReservation() =
        runTest {
            val source = source()
            assertEquals("UH 101", edit(source, roomTrace).linkText)
            assertEquals("Reserva R-77", edit(source, reservationTrace).linkText)
        }

    private fun TestScope.edit(
        source: FakeTracesSource,
        trace: TraceResponse,
    ) = TraceFormState(source, backgroundScope, TraceTarget.Existing(trace.id.toString()), source.departments, false) {}
        .also {
            it.start()
            runCurrent()
        }
}
