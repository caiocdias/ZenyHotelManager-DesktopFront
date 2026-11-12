package com.zeny.desktop.seed.booking

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import java.time.LocalDate
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
internal open class BookingAtaqueBase {
    protected val date = LocalDate.of(2026, 11, 12)
    protected val data = DemoCatalog.build(date)
    protected val config =
        SeededConfig(
            roomTypeIds = mapOf("STD" to "rt-std", "LUX" to "rt-lux", "SUI" to "rt-sui"),
            roomIds = data.rooms.associate { it.number to "room-${it.number}" },
            ratePlanId = "plan-1",
            activeRoomsByType = mapOf("STD" to 12, "LUX" to 6, "SUI" to 1),
            outOfOrderRoomsByType = mapOf("STD" to 0, "LUX" to 0, "SUI" to 1),
        )
    protected val fake = FakeBookingGateway(config, date)
    protected val report = SeedReport()
    protected val unavailable = ApiError.Problem(409, "ROOM_TYPE_UNAVAILABLE", null, emptyList())
    protected val horizon = ApiError.Problem(409, "INVENTORY_DATE_OUT_OF_HORIZON", null, emptyList())

    protected fun TestScope.probe() = Probe(fake) { currentTime }

    protected fun spec(slot: String) = data.reservations.first { it.slot == slot }

    protected fun ref(slot: String) = fake.reservations.getValue(spec(slot).externalId)

    protected fun holderId(slot: String) =
        fake.guests.getValue(data.guests.first { it.key == spec(slot).holderGuestKey }.cpf)

    protected fun assertFullState() {
        assertEquals(2, fake.companies.size)
        assertEquals(12, fake.guests.size)
        assertEquals(11, fake.reservations.size)
        assertEquals(9, fake.assignments)
        assertEquals(7, fake.checkIns.size)
        assertEquals(7, fake.reservations.values.count { it.status == "IN_HOUSE" })
        assertEquals(4, fake.reservations.values.count { it.status == "CONFIRMED" })
    }

    protected val callTotals =
        mapOf("createCompany" to 2, "createGuest" to 12, "createReservation" to 11, "assignRoom" to 9, "checkIn" to 7)
}
