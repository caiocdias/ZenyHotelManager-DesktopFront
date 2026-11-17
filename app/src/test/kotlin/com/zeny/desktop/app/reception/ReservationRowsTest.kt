package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.ListedAssignmentResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

private fun item(
    status: String = "CONFIRMED",
    room: String? = null,
    dayUse: Boolean = false,
    arrivalOverdue: Boolean = false,
) = ReservationListItemResponse(
    adults = 2,
    arrival = "2026-11-13",
    arrivalOverdue = arrivalOverdue,
    bookingCode = "B-1",
    bookingId = Uuid.random(),
    channel = "DIRECT",
    children = 1,
    code = "R-1",
    dayUse = dayUse,
    departure = "2026-11-15",
    departureOverdue = false,
    folioId = Uuid.random(),
    holderGuestId = Uuid.random(),
    id = Uuid.random(),
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = Uuid.random(),
    roomAssignment =
        room?.let {
            Option.Some(ListedAssignmentResponse(false, Uuid.random(), false, Uuid.random(), Option.Some(it)))
        } ?: Option.Undefined,
    roomTypeId = Uuid.parse(typeId(1)),
    segment = "LEISURE",
    status = status,
    total = "500.00",
    version = 1,
)

private fun problem(status: Int) = ApiError.Problem(status, "X", null, emptyList())

class ReservationRowsTest {
    private val types = mapOf(typeId(1) to "Standard")

    @Test
    fun rowCarriesStatusAlertsAndRoom() {
        val row = item(room = "101", dayUse = true, arrivalOverdue = true).toReservationRow("Ana", types)

        assertEquals("Ana", row.holder)
        assertEquals("101", row.room)
        assertEquals("Standard", row.roomType)
        assertEquals("R-1", row.code)
        assertEquals("${ReceptionText.status("CONFIRMED").label} · Day use · Chegada pendente", row.status)
    }

    @Test
    fun unknownStatusIsTolerated() {
        val row = item(status = "NOVA_SITUACAO").toReservationRow("Ana", emptyMap())

        assertEquals(ReceptionText.status("NOVA_SITUACAO").label, row.status)
        assertEquals("—", row.room)
        assertEquals("—", row.roomType)
    }

    @Test
    fun holderNamesForbiddenAndNotFoundBecomeDash() =
        runTest {
            val names =
                HolderNames { id ->
                    when (id) {
                        "a" -> throw problem(403)
                        "b" -> throw problem(404)
                        else -> HolderSummary("Caio", null)
                    }
                }

            assertEquals(mapOf("a" to "—", "b" to "—", "c" to "Caio"), names.resolve(listOf("a", "b", "c")))
        }

    @Test
    fun holderNamesOtherErrorsPropagate() =
        runTest {
            assertFailsWith<ApiError.Problem> { HolderNames { throw problem(500) }.resolve(listOf("a")) }
            assertFailsWith<ApiError.Network> {
                HolderNames { throw ApiError.Network(IOException("offline")) }.resolve(listOf("a"))
            }
        }

    @Test
    fun holderNamesReadsEachIdOnce() =
        runTest {
            val reads = mutableListOf<String>()
            val names =
                HolderNames { id ->
                    reads += id
                    HolderSummary("N-$id", null)
                }

            names.resolve(listOf("a", "b", "a"))
            val second = names.resolve(listOf("b", "a"))

            assertEquals(listOf("a", "b"), reads.sorted())
            assertEquals(mapOf("b" to "N-b", "a" to "N-a"), second)
        }
}
