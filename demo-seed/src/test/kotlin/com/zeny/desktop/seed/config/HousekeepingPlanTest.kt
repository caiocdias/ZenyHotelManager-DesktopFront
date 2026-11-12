package com.zeny.desktop.seed.config

import com.zeny.desktop.seed.data.HousekeepingTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import com.zeny.desktop.seed.config.HousekeepingTransition.MARK_CLEAN as CLEAN
import com.zeny.desktop.seed.config.HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION as PENDING
import com.zeny.desktop.seed.config.HousekeepingTransition.MARK_DIRTY as DIRTY
import com.zeny.desktop.seed.config.HousekeepingTransition.MARK_INSPECTED as INSPECTED
import com.zeny.desktop.seed.config.HousekeepingTransition.REJECT_INSPECTION as REJECT

class HousekeepingPlanTest {
    private fun room(
        housekeeping: String,
        pending: Boolean = false,
        occupied: Boolean = false,
    ) = RoomState("id", "101", "t", occupied, housekeeping, pending, false)

    private val d = room("DIRTY")
    private val cp = room("CLEAN", pending = true)
    private val c = room("CLEAN")
    private val i = room("INSPECTED")

    private fun plan(
        from: RoomState,
        to: HousekeepingTarget,
    ) = HousekeepingPlan.transitions(from, to)

    @Test
    fun fromDirty() {
        assertEquals(emptyList(), plan(d, HousekeepingTarget.DIRTY))
        assertEquals(listOf(PENDING), plan(d, HousekeepingTarget.CLEAN_PENDING_INSPECTION))
        assertEquals(listOf(CLEAN), plan(d, HousekeepingTarget.CLEAN))
        assertEquals(listOf(CLEAN, INSPECTED), plan(d, HousekeepingTarget.INSPECTED))
    }

    @Test
    fun fromCleanPending() {
        assertEquals(listOf(REJECT), plan(cp, HousekeepingTarget.DIRTY))
        assertEquals(emptyList(), plan(cp, HousekeepingTarget.CLEAN_PENDING_INSPECTION))
        assertEquals(listOf(REJECT, CLEAN), plan(cp, HousekeepingTarget.CLEAN))
        assertEquals(listOf(INSPECTED), plan(cp, HousekeepingTarget.INSPECTED))
    }

    @Test
    fun fromClean() {
        assertEquals(listOf(DIRTY), plan(c, HousekeepingTarget.DIRTY))
        assertEquals(listOf(DIRTY, PENDING), plan(c, HousekeepingTarget.CLEAN_PENDING_INSPECTION))
        assertEquals(emptyList(), plan(c, HousekeepingTarget.CLEAN))
        assertEquals(listOf(INSPECTED), plan(c, HousekeepingTarget.INSPECTED))
    }

    @Test
    fun fromInspected() {
        assertEquals(listOf(DIRTY), plan(i, HousekeepingTarget.DIRTY))
        assertEquals(listOf(DIRTY, PENDING), plan(i, HousekeepingTarget.CLEAN_PENDING_INSPECTION))
        assertEquals(listOf(DIRTY, CLEAN), plan(i, HousekeepingTarget.CLEAN))
        assertEquals(emptyList(), plan(i, HousekeepingTarget.INSPECTED))
    }

    @Test
    fun occupiedRoomIsLeftAlone() {
        HousekeepingTarget.entries.forEach {
            assertEquals(emptyList(), plan(room("DIRTY", occupied = true), it))
        }
    }

    @Test
    fun unknownServerValueIsLeftAlone() {
        HousekeepingTarget.entries.forEach {
            assertEquals(emptyList(), plan(room("SOMETHING_NEW"), it))
        }
    }
}
