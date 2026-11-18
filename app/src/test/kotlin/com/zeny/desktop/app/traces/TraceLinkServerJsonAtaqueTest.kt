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
import kotlin.test.assertFalse

private val ATK_DEPT = Department("40000000-0000-0000-0000-000000000001", "Governança")
private const val ATK_RES = "20000000-0000-0000-0000-000000000001"
private const val ATK_ROOM = "30000000-0000-0000-0000-000000000001"
private const val ATK_ID = "50000000-0000-0000-0000-000000000009"
private const val ATK_TEXT = "TEXTO-SECRETO-DO-RECADO"

/** [vinculo] é o trecho JSON dos campos de vínculo, escrito à mão para variar nulo explícito, ausente e extras. */
private fun decode(
    vinculo: String,
    id: String = ATK_ID,
): TraceResponse =
    Json { ignoreUnknownKeys = true }.decodeFromString(
        TraceResponse.serializer(),
        """
        {
          "assigneeName": null,
          "assigneeUserId": null,
          "createdAt": "2026-11-14T10:00:00Z",
          "createdBy": "10000000-0000-0000-0000-000000000001",
          "departmentId": "${ATK_DEPT.id}",
          "departmentName": "Governança",
          "dueOn": "2026-11-14",
          "id": "$id",
          $vinculo
          "resolvedAt": null,
          "resolvedBy": null,
          "status": "OPEN",
          "text": "$ATK_TEXT",
          "version": 1
        }
        """.trimIndent(),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class TraceLinkServerJsonAtaqueTest {
    private val both = """"reservationId": "$ATK_RES", "roomId": "$ATK_ROOM", "roomNumber": "101","""
    private val roomIdOnly = """"reservationId": null, "roomId": "$ATK_ROOM", "roomNumber": null,"""
    private val absentAll = ""
    private val absentRoomFields = """"reservationId": null,"""
    private val extras =
        """"reservationId": null, "roomId": "$ATK_ROOM", "roomNumber": "101", "campoNovo": {"a": null},"""

    @Test
    fun reservationWinsOverRoomWhenBothArePresent() {
        assertEquals("Reserva R-77", TraceText.link(decode(both), "R-77"))
    }

    @Test
    fun reservationWithFailedCodeLookupStaysReservationNotRoom() {
        assertEquals("Reserva", TraceText.link(decode(both), null))
    }

    @Test
    fun roomIdWithoutRoomNumberShowsNoLinkAndNoId() {
        val label = TraceText.link(decode(roomIdOnly), null)
        assertEquals("Sem vínculo", label)
        assertFalse(label.contains(ATK_ROOM))
    }

    @Test
    fun absentFieldsShowNoLink() {
        assertEquals("Sem vínculo", TraceText.link(decode(absentAll), null))
        assertEquals("Sem vínculo", TraceText.link(decode(absentRoomFields), null))
    }

    @Test
    fun unknownExtraFieldsDoNotChangeTheLink() {
        assertEquals("UH 101", TraceText.link(decode(extras), null))
    }

    @Test
    fun noLabelLeaksIdsOrTraceText() {
        listOf(both, roomIdOnly, absentAll, extras).forEach { json ->
            listOf(null, "R-77").forEach { code ->
                val label = TraceText.link(decode(json), code)
                assertFalse(label.contains(ATK_RES) || label.contains(ATK_ROOM) || label.contains(ATK_ID))
                assertFalse(label.contains(ATK_TEXT))
            }
        }
    }

    private fun source(vararg traces: TraceResponse) =
        FakeTracesSource().apply {
            departments = listOf(ATK_DEPT)
            traces.forEach { seed(it) }
        }

    @Test
    fun tableKeepsReservationLabelWhenCodeLookupFailsAndStillShowsRoomRows() =
        runTest {
            // Sem código registrado no falso, a leitura falha com UNKNOWN_RESERVATION.
            val reservation = decode(""""reservationId": "$ATK_RES", "roomId": null, "roomNumber": null,""")
            val room = decode(extras, "50000000-0000-0000-0000-00000000000a")
            val src = source(reservation, room)
            val state = TracesListState(src, backgroundScope, src.permissions, null)
            state.start()
            runCurrent()
            val rows = state.table.rows
            assertEquals(2, rows.size)
            assertEquals(setOf("Reserva", "UH 101"), rows.map { it.link }.toSet())
            assertFalse(rows.any { it.link.contains(ATK_RES) || it.link.contains(ATK_TEXT) })
        }

    @Test
    fun editFormWithFailedCodeLookupShowsReservationAndStaysUsable() =
        runTest {
            val reservation = decode(""""reservationId": "$ATK_RES", "roomId": null, "roomNumber": null,""")
            val src = source(reservation)
            val form = edit(src, reservation)
            assertEquals("Reserva", form.linkText)
            assertEquals(null, form.error)
        }

    @Test
    fun editFormForRoomIdWithoutNumberShowsNoLink() =
        runTest {
            val trace = decode(roomIdOnly)
            assertEquals("Sem vínculo", edit(source(trace), trace).linkText)
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
