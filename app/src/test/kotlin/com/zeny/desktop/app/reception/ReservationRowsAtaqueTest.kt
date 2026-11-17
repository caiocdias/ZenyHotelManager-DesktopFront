package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.ListedAssignmentResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private fun entry(
    status: String = "CONFIRMED",
    roomNumber: Option<String>? = Option.Undefined,
    dayUse: Boolean = false,
    arrivalOverdue: Boolean = false,
    departureOverdue: Boolean = false,
) = ReservationListItemResponse(
    adults = 2,
    arrival = "2026-11-13",
    arrivalOverdue = arrivalOverdue,
    bookingCode = "B-1",
    bookingId = Uuid.random(),
    channel = "DIRECT",
    children = 0,
    code = "R-9",
    dayUse = dayUse,
    departure = "2026-11-15",
    departureOverdue = departureOverdue,
    folioId = Uuid.random(),
    holderGuestId = Uuid.random(),
    id = Uuid.random(),
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = Uuid.random(),
    roomAssignment =
        if (roomNumber != null) {
            Option.Some(ListedAssignmentResponse(false, Uuid.random(), false, Uuid.random(), roomNumber))
        } else {
            Option.Undefined
        },
    roomTypeId = Uuid.parse(typeId(1)),
    segment = "LEISURE",
    status = status,
    total = "500.00",
    version = 1,
)

private fun problemOf(status: Int) = ApiError.Problem(status, "X", null, emptyList())

class ReservationRowsAtaqueTest {
    private val types = mapOf(typeId(1) to "Standard")

    @Test
    fun alertsComeInOrderDayUseArrivalDeparture() {
        val row = entry(dayUse = true, arrivalOverdue = true, departureOverdue = true).toReservationRow("Ana", types)

        assertEquals(
            "${ReceptionText.status("CONFIRMED").label} · Day use · Chegada pendente · Saída pendente",
            row.status,
        )
    }

    @Test
    fun departureAlertAloneHasNoStraySeparators() {
        val row = entry(departureOverdue = true).toReservationRow("Ana", types)

        assertEquals("${ReceptionText.status("CONFIRMED").label} · Saída pendente", row.status)
    }

    @Test
    fun noAlertsLeavesOnlyTheStatusLabel() {
        assertEquals(ReceptionText.status("CONFIRMED").label, entry().toReservationRow("Ana", types).status)
    }

    @Test
    fun blankRoomNumberBecomesDash() {
        assertEquals("—", entry(roomNumber = Option.Some("   ")).toReservationRow("Ana", types).room)
        assertEquals("—", entry(roomNumber = Option.Some("")).toReservationRow("Ana", types).room)
    }

    @Test
    fun assignmentWithoutNumberOrWithoutAssignmentBecomesDash() {
        assertEquals("—", entry(roomNumber = Option.Undefined).toReservationRow("Ana", types).room)
        assertEquals("—", entry(roomNumber = null).toReservationRow("Ana", types).room)
    }

    @Test
    fun roomNumberIsNotAltered() {
        assertEquals("101A", entry(roomNumber = Option.Some("101A")).toReservationRow("Ana", types).room)
    }

    @Test
    fun unknownRoomTypeBecomesDash() {
        val row = entry().let { it.copy(roomTypeId = Uuid.parse(typeId(2))) }.toReservationRow("Ana", types)

        assertEquals("—", row.roomType)
    }

    @Test
    fun unknownStatusKeepsAlerts() {
        val row = entry(status = "ZZZ", dayUse = true).toReservationRow("Ana", types)

        assertEquals("${ReceptionText.status("ZZZ").label} · Day use", row.status)
    }

    @Test
    fun rowFieldsMatchTheTextHelpers() {
        val item = entry()
        val row = item.toReservationRow("Ana", types)

        assertEquals(item.id.toString(), row.id)
        assertEquals(ReceptionText.date("2026-11-13"), row.arrival)
        assertEquals(ReceptionText.date("2026-11-15"), row.departure)
        assertEquals(ReceptionText.guests(2, 0), row.guests)
    }

    @Test
    fun toStringNeverShowsTheHolder() {
        val row = entry().toReservationRow("Fulano Secreto da Silva", types)

        assertFalse("Fulano" in row.toString())
        assertTrue("R-9" in row.toString())
    }

    @Test
    fun holderNamesNeverRunMoreThanFourReadsAtOnce() =
        runTest {
            var running = 0
            var peak = 0
            val names =
                HolderNames { id ->
                    running++
                    peak = maxOf(peak, running)
                    delay(100)
                    running--
                    HolderSummary("N-$id", null)
                }

            val result = names.resolve((1..12).map { "h$it" })

            assertEquals(4, peak)
            assertEquals(12, result.size)
        }

    @Test
    fun holderNamesEmptyInputReadsNothing() =
        runTest {
            var reads = 0
            val names =
                HolderNames {
                    reads++
                    HolderSummary("x", null)
                }

            assertEquals(emptyMap(), names.resolve(emptyList()))
            assertEquals(0, reads)
        }

    @Test
    fun holderNamesDuplicatedIdsInOneCallReadOnceAndAnswerOnce() =
        runTest {
            val reads = mutableListOf<String>()
            val names =
                HolderNames { id ->
                    reads += id
                    delay(10)
                    HolderSummary("N-$id", null)
                }

            val result = names.resolve(listOf("a", "a", "a"))

            assertEquals(listOf("a"), reads)
            assertEquals(mapOf("a" to "N-a"), result)
        }

    @Test
    fun holderNamesForbiddenIsCachedAsDashAndNotReadAgain() =
        runTest {
            var reads = 0
            val names =
                HolderNames {
                    reads++
                    throw problemOf(403)
                }

            names.resolve(listOf("a"))
            assertEquals(mapOf("a" to "—"), names.resolve(listOf("a")))
            assertEquals(1, reads)
        }

    @Test
    fun holderNamesFailureIsNotCachedAndNextCallReadsAgain() =
        runTest {
            var reads = 0
            var broken = true
            val names =
                HolderNames {
                    reads++
                    if (broken) throw problemOf(500)
                    HolderSummary("Ok", null)
                }

            assertFailsWith<ApiError.Problem> { names.resolve(listOf("a")) }
            broken = false

            assertEquals(mapOf("a" to "Ok"), names.resolve(listOf("a")))
            assertEquals(2, reads)
        }

    @Test
    fun holderNamesOtherStatusCodesAreNotSwallowed() =
        runTest {
            for (status in listOf(400, 401, 409, 422, 502)) {
                val e =
                    assertFailsWith<ApiError.Problem> {
                        HolderNames { throw problemOf(status) }.resolve(listOf("a"))
                    }
                assertEquals(status, e.status)
            }
        }

    @Test
    fun holderNamesFailureOfOneIdStillLetsTheNextCallComplete() =
        runTest {
            var broken = true
            val names =
                HolderNames { id ->
                    if (id == "bad" && broken) throw problemOf(500)
                    HolderSummary("N-$id", null)
                }

            assertFailsWith<ApiError.Problem> { names.resolve(listOf("good", "bad")) }
            broken = false

            assertEquals(mapOf("good" to "N-good", "bad" to "N-bad"), names.resolve(listOf("good", "bad")))
        }

    @Test
    fun holderNamesCancelledResolveCachesNothingForTheUnfinishedId() =
        runTest {
            val never = CompletableDeferred<HolderSummary>()
            var hang = true
            val names = HolderNames { if (hang) never.await() else HolderSummary("Ana", null) }

            val job = launch(start = CoroutineStart.UNDISPATCHED) { names.resolve(listOf("a")) }
            job.cancel()
            job.join()
            hang = false

            assertEquals(mapOf("a" to "Ana"), names.resolve(listOf("a")))
        }

    @Test
    fun holderNamesLaterCallsServeFromCacheInAnyOrder() =
        runTest {
            var reads = 0
            val names =
                HolderNames {
                    reads++
                    HolderSummary("Ana", null)
                }

            names.resolve(listOf("a", "b"))
            val again = names.resolve(listOf("b", "a", "a"))

            assertEquals(mapOf("b" to "Ana", "a" to "Ana"), again)
            assertEquals(2, reads)
        }
}
