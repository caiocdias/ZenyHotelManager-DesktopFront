package com.zeny.desktop.app.rooms

import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomStatusResponse.ServiceFlag
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val OOO = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER
private val OOS = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE

private fun room(
    occupancy: Occupancy = Occupancy.VACANT,
    housekeeping: HousekeepingStatus = HousekeepingStatus.CLEAN,
    inspectionPending: Boolean = false,
    outOfOrder: OutOfOrderOverlayResponse.Kind? = null,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = housekeeping,
    inspectionPending = inspectionPending,
    number = "101",
    occupancy = occupancy,
    outOfOrder =
        outOfOrder?.let {
            Option.Some(OutOfOrderOverlayResponse("2026-11-12", Uuid.random(), it, "2026-11-10"))
        } ?: Option.Undefined,
    readyForCheckIn = false,
    roomId = Uuid.random(),
    roomTypeId = Uuid.random(),
    serviceFlag = ServiceFlag.NONE,
    version = 1,
)

class RoomDisplayTest {
    @Test
    fun precedence() {
        val ooo = room(Occupancy.OCCUPIED, outOfOrder = OOO)
        assertEquals(RoomDisplayStatus.OUT_OF_ORDER, displayStatus(ooo))
        val oos = room(housekeeping = HousekeepingStatus.DIRTY, outOfOrder = OOS)
        assertEquals(RoomDisplayStatus.OUT_OF_SERVICE, displayStatus(oos))
        val occupied = room(Occupancy.OCCUPIED, HousekeepingStatus.DIRTY)
        assertEquals(RoomDisplayStatus.OCCUPIED, displayStatus(occupied))
        val pending = room(housekeeping = HousekeepingStatus.CLEAN, inspectionPending = true)
        assertEquals(RoomDisplayStatus.CLEAN_PENDING_INSPECTION, displayStatus(pending))
        assertEquals(RoomDisplayStatus.DIRTY, displayStatus(room(housekeeping = HousekeepingStatus.DIRTY)))
        assertEquals(RoomDisplayStatus.CLEAN, displayStatus(room()))
        assertEquals(RoomDisplayStatus.INSPECTED, displayStatus(room(housekeeping = HousekeepingStatus.INSPECTED)))
    }

    @Test
    fun housekeepingIgnoresOccupancy() {
        val occupiedDirty = room(Occupancy.OCCUPIED, HousekeepingStatus.DIRTY, outOfOrder = OOO)
        assertEquals(RoomDisplayStatus.DIRTY, housekeepingStatus(occupiedDirty))
        assertEquals(RoomDisplayStatus.CLEAN_PENDING_INSPECTION, housekeepingStatus(room(inspectionPending = true)))
        assertEquals(RoomDisplayStatus.INSPECTED, housekeepingStatus(room(housekeeping = HousekeepingStatus.INSPECTED)))
    }

    @Test
    fun occupiedTileShowsHousekeeping() {
        assertEquals("Ocupada · Suja", tileLabel(room(Occupancy.OCCUPIED, HousekeepingStatus.DIRTY)))
        assertEquals("Suja", tileLabel(room(housekeeping = HousekeepingStatus.DIRTY)))
        assertEquals("Fora de ordem", tileLabel(room(Occupancy.OCCUPIED, outOfOrder = OOO)))
    }

    @Test
    fun labelsInPortuguese() {
        assertEquals("Vaga", occupancyLabel(Occupancy.VACANT))
        assertEquals("Ocupada", occupancyLabel(Occupancy.OCCUPIED))
        assertEquals("Nenhuma", serviceFlagLabel(ServiceFlag.NONE))
        assertEquals("Não perturbe", serviceFlagLabel(ServiceFlag.DO_NOT_DISTURB))
        assertEquals("Arrumar o quarto", serviceFlagLabel(ServiceFlag.MAKE_UP_ROOM))
        assertEquals("Fora de ordem", outOfOrderKindLabel(OOO))
        assertEquals("Fora de serviço", outOfOrderKindLabel(OOS))
        assertEquals(StatusTone.DANGER, RoomDisplayStatus.OUT_OF_ORDER.tone)
        assertEquals(StatusTone.INFO, RoomDisplayStatus.OCCUPIED.tone)
        assertEquals(StatusTone.SUCCESS, RoomDisplayStatus.INSPECTED.tone)
    }

    @Test
    fun legendOrderIsEnumOrder() {
        assertEquals(
            listOf(
                "Fora de ordem",
                "Fora de serviço",
                "Ocupada",
                "Suja",
                "Limpa, aguardando inspeção",
                "Limpa",
                "Inspecionada",
            ),
            RoomDisplayStatus.entries.map { it.label },
        )
    }
}
