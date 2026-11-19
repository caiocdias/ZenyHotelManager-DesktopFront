package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

private fun listItem(holder: Uuid = Uuid.random()) =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-1",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 1,
        code = "R-1",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = holder,
        id = Uuid.random(),
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = Uuid.random(),
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

class ReservationRowResolverTest {
    private val holderReads = mutableListOf<String>()
    private var typeReads = 0

    private val standard = listOf(roomType(typeId(1), "Standard"))

    private fun resolver(readTypes: suspend () -> List<RoomTypeResponse> = { standard }) =
        ReservationRowResolver(
            readHolder = { id ->
                holderReads += id
                HolderSummary("Titular $id", null)
            },
            readRoomTypes = {
                typeReads++
                readTypes()
            },
        )

    @Test
    fun parallelPagesReadEachHolderAndTheTypesOnce() =
        runTest {
            val shared = Uuid.random()
            val resolver = resolver()
            val pages =
                List(3) { listOf(listItem(shared), listItem(Uuid.random())) }
                    .map { page -> async { resolver.rows(page) } }
                    .awaitAll()

            assertEquals(1, typeReads)
            assertEquals(holderReads.size, holderReads.toSet().size)
            assertEquals(1 + 3, holderReads.size)
            assertEquals(List(3) { "Standard" }, pages.map { it.first().roomType })
        }

    @Test
    fun forgetRoomTypesRereadsOnTheNextPage() =
        runTest {
            val resolver = resolver()
            val page = listOf(listItem())

            resolver.rows(page)
            resolver.rows(page)
            assertEquals(1, typeReads)

            resolver.forgetRoomTypes()
            resolver.rows(page)
            assertEquals(2, typeReads)
        }

    @Test
    fun roomTypesProblemGivesDashesAndNetworkFailureRises() =
        runTest {
            val refused = resolver { throw ApiError.Problem(403, "X", null, emptyList()) }
            assertEquals(listOf("—"), refused.rows(listOf(listItem())).map { it.roomType })

            val offline = resolver { throw ApiError.Network(IOException("sem rede")) }
            assertFailsWith<ApiError.Network> { offline.rows(listOf(listItem())) }
        }

    @Test
    fun rowsKeepTheServerOrder() =
        runTest {
            val items = List(6) { listItem() }

            assertEquals(items.map { it.id.toString() }, resolver().rows(items).map { it.id })
        }

    @Test
    fun columnsAreTheEightOfToday() {
        assertEquals(
            listOf(
                "code" to "Reserva",
                "holder" to "Titular",
                "room" to "UH",
                "roomType" to "Tipo",
                "arrival" to "Chegada",
                "departure" to "Saída",
                "guests" to "Hóspedes",
                "status" to "Situação",
            ),
            reservationColumns.map { it.id to it.header },
        )
    }
}
