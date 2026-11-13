package com.zeny.desktop.app.rooms

import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomStatusResponse.ServiceFlag
import de.quati.kotlin.util.Option
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val KIND_OOO = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER
private val KIND_OOS = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE

/** As quatro formas do campo `outOfOrder`: ausente, presente e nulo, OOO e OOS. */
private enum class OverlayShape { UNDEFINED, NULL, OOO, OOS }

private fun overlay(
    kind: OutOfOrderOverlayResponse.Kind,
    startsOn: String = "2026-11-10",
    endsOn: String = "2026-11-14",
) = OutOfOrderOverlayResponse(endsOn, Uuid.random(), kind, startsOn)

private fun shape(shape: OverlayShape): Option<OutOfOrderOverlayResponse?> =
    when (shape) {
        OverlayShape.UNDEFINED -> Option.Undefined
        OverlayShape.NULL -> Option.Some(null)
        OverlayShape.OOO -> Option.Some(overlay(KIND_OOO))
        OverlayShape.OOS -> Option.Some(overlay(KIND_OOS))
    }

private fun status(
    occupancy: Occupancy = Occupancy.VACANT,
    housekeeping: HousekeepingStatus = HousekeepingStatus.CLEAN,
    inspectionPending: Boolean = false,
    outOfOrder: Option<OutOfOrderOverlayResponse?> = Option.Undefined,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-13T10:00:00Z",
    housekeepingStatus = housekeeping,
    inspectionPending = inspectionPending,
    number = "204",
    occupancy = occupancy,
    outOfOrder = outOfOrder,
    readyForCheckIn = false,
    roomId = Uuid.random(),
    roomTypeId = Uuid.random(),
    serviceFlag = ServiceFlag.NONE,
    version = 3,
)

private data class Combo(
    val occupancy: Occupancy,
    val housekeeping: HousekeepingStatus,
    val pending: Boolean,
    val overlay: OverlayShape,
) {
    fun build() = status(occupancy, housekeeping, pending, shape(overlay))
}

private val ALL_COMBOS: List<Combo> =
    Occupancy.entries.flatMap { occ ->
        HousekeepingStatus.entries.flatMap { hk ->
            listOf(false, true).flatMap { pending ->
                OverlayShape.entries.map { ov -> Combo(occ, hk, pending, ov) }
            }
        }
    }

/** Oráculo escrito a partir da regra do plano, não do código: OOO > OOS > ocupada > governança. */
private fun expectedHousekeeping(c: Combo): RoomDisplayStatus =
    when (c.housekeeping) {
        HousekeepingStatus.DIRTY -> {
            RoomDisplayStatus.DIRTY
        }

        HousekeepingStatus.CLEAN -> {
            if (c.pending) RoomDisplayStatus.CLEAN_PENDING_INSPECTION else RoomDisplayStatus.CLEAN
        }

        HousekeepingStatus.INSPECTED -> {
            RoomDisplayStatus.INSPECTED
        }
    }

private fun expectedDisplay(c: Combo): RoomDisplayStatus =
    when {
        c.overlay == OverlayShape.OOO -> RoomDisplayStatus.OUT_OF_ORDER
        c.overlay == OverlayShape.OOS -> RoomDisplayStatus.OUT_OF_SERVICE
        c.occupancy == Occupancy.OCCUPIED -> RoomDisplayStatus.OCCUPIED
        else -> expectedHousekeeping(c)
    }

private val EXPECTED_HOUSEKEEPING_LABEL =
    mapOf(
        RoomDisplayStatus.DIRTY to "Suja",
        RoomDisplayStatus.CLEAN_PENDING_INSPECTION to "Limpa, aguardando inspeção",
        RoomDisplayStatus.CLEAN to "Limpa",
        RoomDisplayStatus.INSPECTED to "Inspecionada",
    )

private fun expectedTile(c: Combo): String =
    when (c.overlay) {
        OverlayShape.OOO -> {
            "Fora de ordem"
        }

        OverlayShape.OOS -> {
            "Fora de serviço"
        }

        else -> {
            if (c.occupancy == Occupancy.OCCUPIED) {
                "Ocupada · " + EXPECTED_HOUSEKEEPING_LABEL.getValue(expectedHousekeeping(c))
            } else {
                EXPECTED_HOUSEKEEPING_LABEL.getValue(expectedHousekeeping(c))
            }
        }
    }

/** Todos os valores crus dos enums gerados que a camada recebe. */
private val RAW_ENUM_VALUES: Set<String> =
    (
        Occupancy.entries.map { it.value } +
            HousekeepingStatus.entries.map { it.value } +
            ServiceFlag.entries.map { it.value } +
            OutOfOrderOverlayResponse.Kind.entries.map { it.value } +
            RoomDisplayStatus.entries.map { it.name }
    ).toSet()

private val JSON = Json { ignoreUnknownKeys = true }

private fun statusJson(
    housekeeping: String = "DIRTY",
    occupancy: String = "OCCUPIED",
    outOfOrder: String? = null,
): String {
    val overlayField = if (outOfOrder == null) "" else """"outOfOrder": $outOfOrder,"""
    return """
        {
          "roomId": "0b6f0c5e-6d2a-4a55-9b7e-3f1a4f3c2d10",
          "number": "204",
          "floor": "2",
          "roomTypeId": "6a1d8d1e-1f2b-4c3d-8e9f-0a1b2c3d4e5f",
          "active": true,
          "occupancy": "$occupancy",
          "guestCount": null,
          "housekeepingStatus": "$housekeeping",
          "inspectionPending": false,
          "serviceFlag": "NONE",
          $overlayField
          "readyForCheckIn": false,
          "changedAt": "2026-11-13T10:00:00Z",
          "version": 3
        }
        """.trimIndent()
}

class RoomDisplayAtaqueTest {
    @Test
    fun outOfOrderPresentButNullFallsThroughToOccupancyAndHousekeeping() {
        val occupiedDirty = status(Occupancy.OCCUPIED, HousekeepingStatus.DIRTY, outOfOrder = Option.Some(null))
        assertEquals(RoomDisplayStatus.OCCUPIED, displayStatus(occupiedDirty))
        assertEquals("Ocupada · Suja", tileLabel(occupiedDirty))
        val vacantPending =
            status(housekeeping = HousekeepingStatus.CLEAN, inspectionPending = true, outOfOrder = Option.Some(null))
        assertEquals(RoomDisplayStatus.CLEAN_PENDING_INSPECTION, displayStatus(vacantPending))
        assertEquals("Limpa, aguardando inspeção", tileLabel(vacantPending))
    }

    @Test
    fun outOfOrderUndefinedFallsThroughToOccupancyAndHousekeeping() {
        val occupiedInspected = status(Occupancy.OCCUPIED, HousekeepingStatus.INSPECTED, outOfOrder = Option.Undefined)
        assertEquals(RoomDisplayStatus.OCCUPIED, displayStatus(occupiedInspected))
        assertEquals("Ocupada · Inspecionada", tileLabel(occupiedInspected))
        val vacantDirty = status(housekeeping = HousekeepingStatus.DIRTY, outOfOrder = Option.Undefined)
        assertEquals(RoomDisplayStatus.DIRTY, displayStatus(vacantDirty))
    }

    @Test
    fun nullAndUndefinedOutOfOrderAreEquivalentInEveryCombination() {
        ALL_COMBOS.filter { it.overlay == OverlayShape.UNDEFINED }.forEach { c ->
            val undefined = c.build()
            val nulled = c.copy(overlay = OverlayShape.NULL).build()
            assertEquals(displayStatus(undefined), displayStatus(nulled), "$c")
            assertEquals(housekeepingStatus(undefined), housekeepingStatus(nulled), "$c")
            assertEquals(tileLabel(undefined), tileLabel(nulled), "$c")
        }
    }

    @Test
    fun outOfServiceWinsOverOccupiedWithoutCompositeLabel() {
        HousekeepingStatus.entries.forEach { hk ->
            listOf(false, true).forEach { pending ->
                val room = status(Occupancy.OCCUPIED, hk, pending, Option.Some(overlay(KIND_OOS)))
                assertEquals(RoomDisplayStatus.OUT_OF_SERVICE, displayStatus(room), "$hk/$pending")
                assertEquals("Fora de serviço", tileLabel(room), "$hk/$pending")
            }
        }
    }

    @Test
    fun outOfOrderWinsOverOccupiedAndEveryHousekeeping() {
        HousekeepingStatus.entries.forEach { hk ->
            listOf(false, true).forEach { pending ->
                val room = status(Occupancy.OCCUPIED, hk, pending, Option.Some(overlay(KIND_OOO)))
                assertEquals(RoomDisplayStatus.OUT_OF_ORDER, displayStatus(room), "$hk/$pending")
                assertEquals("Fora de ordem", tileLabel(room), "$hk/$pending")
            }
        }
    }

    @Test
    fun housekeepingStatusUnderOverlayStillReportsHousekeeping() {
        val oosOccupiedPending =
            status(Occupancy.OCCUPIED, HousekeepingStatus.CLEAN, true, Option.Some(overlay(KIND_OOS)))
        assertEquals(RoomDisplayStatus.CLEAN_PENDING_INSPECTION, housekeepingStatus(oosOccupiedPending))
        val oooInspected =
            status(housekeeping = HousekeepingStatus.INSPECTED, outOfOrder = Option.Some(overlay(KIND_OOO)))
        assertEquals(RoomDisplayStatus.INSPECTED, housekeepingStatus(oooInspected))
    }

    @Test
    fun eachDisplayStatusHasItsTone() {
        assertEquals(StatusTone.DANGER, RoomDisplayStatus.OUT_OF_ORDER.tone)
        assertEquals(StatusTone.NEUTRAL, RoomDisplayStatus.OUT_OF_SERVICE.tone)
        assertEquals(StatusTone.INFO, RoomDisplayStatus.OCCUPIED.tone)
        assertEquals(StatusTone.WARNING, RoomDisplayStatus.DIRTY.tone)
        assertEquals(StatusTone.NEUTRAL, RoomDisplayStatus.CLEAN_PENDING_INSPECTION.tone)
        assertEquals(StatusTone.SUCCESS, RoomDisplayStatus.CLEAN.tone)
        assertEquals(StatusTone.SUCCESS, RoomDisplayStatus.INSPECTED.tone)
        assertEquals(7, RoomDisplayStatus.entries.size, "um valor novo precisa de tom afirmado aqui")
    }

    @Test
    fun displayStatusFollowsFullPrecedenceInEveryCombination() {
        assertEquals(2 * 3 * 2 * 4, ALL_COMBOS.size)
        ALL_COMBOS.forEach { c ->
            assertEquals(expectedDisplay(c), displayStatus(c.build()), "$c")
        }
    }

    @Test
    fun housekeepingStatusIgnoresOccupancyAndOverlayInEveryCombination() {
        ALL_COMBOS.forEach { c ->
            assertEquals(expectedHousekeeping(c), housekeepingStatus(c.build()), "$c")
        }
    }

    @Test
    fun tileLabelInEveryCombination() {
        ALL_COMBOS.forEach { c ->
            assertEquals(expectedTile(c), tileLabel(c.build()), "$c")
        }
    }

    @Test
    fun occupiedTileCarriesEveryHousekeepingLabel() {
        assertEquals("Ocupada · Suja", tileLabel(status(Occupancy.OCCUPIED, HousekeepingStatus.DIRTY)))
        assertEquals("Ocupada · Limpa", tileLabel(status(Occupancy.OCCUPIED, HousekeepingStatus.CLEAN)))
        assertEquals(
            "Ocupada · Limpa, aguardando inspeção",
            tileLabel(status(Occupancy.OCCUPIED, HousekeepingStatus.CLEAN, inspectionPending = true)),
        )
        assertEquals("Ocupada · Inspecionada", tileLabel(status(Occupancy.OCCUPIED, HousekeepingStatus.INSPECTED)))
    }

    @Test
    fun vacantTileIsNotComposite() {
        HousekeepingStatus.entries.forEach { hk ->
            val label = tileLabel(status(Occupancy.VACANT, hk))
            assertFalse(" · " in label, label)
            assertFalse("Ocupada" in label, label)
            assertFalse("Vaga" in label, label)
        }
    }

    @Test
    fun inspectionPendingOnlyQualifiesClean() {
        val dirtyPending = status(housekeeping = HousekeepingStatus.DIRTY, inspectionPending = true)
        assertEquals(RoomDisplayStatus.DIRTY, displayStatus(dirtyPending))
        assertEquals(RoomDisplayStatus.DIRTY, housekeepingStatus(dirtyPending))
        assertEquals("Suja", tileLabel(dirtyPending))
        val inspectedPending = status(housekeeping = HousekeepingStatus.INSPECTED, inspectionPending = true)
        assertEquals(RoomDisplayStatus.INSPECTED, displayStatus(inspectedPending))
        assertEquals(RoomDisplayStatus.INSPECTED, housekeepingStatus(inspectedPending))
        assertEquals("Inspecionada", tileLabel(inspectedPending))
        assertEquals(RoomDisplayStatus.CLEAN, displayStatus(status(housekeeping = HousekeepingStatus.CLEAN)))
    }

    @Test
    fun overlayDatesAreTrustedFromServerNotFromMachineClock() {
        // O servidor só manda o período em vigor na data operacional; o cliente não reavalia as datas.
        val periods =
            listOf(
                "2020-01-01" to "2020-01-02",
                "2099-12-30" to "2099-12-31",
                "2026-11-13" to "2026-11-13",
                "2026-11-14" to "2026-11-20",
                "2026-11-01" to "2026-11-12",
            )
        periods.forEach { (start, end) ->
            val ooo = status(Occupancy.OCCUPIED, outOfOrder = Option.Some(overlay(KIND_OOO, start, end)))
            assertEquals(RoomDisplayStatus.OUT_OF_ORDER, displayStatus(ooo), "$start..$end")
            val oos = status(outOfOrder = Option.Some(overlay(KIND_OOS, start, end)))
            assertEquals(RoomDisplayStatus.OUT_OF_SERVICE, displayStatus(oos), "$start..$end")
        }
    }

    @Test
    fun housekeepingStatusNeverReturnsOverlayOrOccupancy() {
        val housekeepingOnly =
            setOf(
                RoomDisplayStatus.DIRTY,
                RoomDisplayStatus.CLEAN_PENDING_INSPECTION,
                RoomDisplayStatus.CLEAN,
                RoomDisplayStatus.INSPECTED,
            )
        ALL_COMBOS.forEach { c ->
            assertTrue(housekeepingStatus(c.build()) in housekeepingOnly, "$c")
        }
    }

    @Test
    fun everyDisplayStatusIsReachable() {
        val reached = ALL_COMBOS.map { displayStatus(it.build()) }.toSet()
        assertEquals(RoomDisplayStatus.entries.toSet(), reached)
    }

    @Test
    fun noLabelExposesRawEnumValue() {
        val labels =
            RoomDisplayStatus.entries.map { it.label } +
                Occupancy.entries.map(::occupancyLabel) +
                ServiceFlag.entries.map(::serviceFlagLabel) +
                OutOfOrderOverlayResponse.Kind.entries.map(::outOfOrderKindLabel) +
                ALL_COMBOS.map { tileLabel(it.build()) }
        labels.forEach { label ->
            assertTrue(label.isNotBlank(), "rótulo vazio")
            assertFalse("_" in label, label)
            RAW_ENUM_VALUES.forEach { raw -> assertFalse(raw in label, "'$label' contém '$raw'") }
            assertFalse("Outro" in label, label)
        }
    }

    @Test
    fun labelsAreDistinctWithinEachEnum() {
        assertEquals(
            RoomDisplayStatus.entries.size,
            RoomDisplayStatus.entries
                .map { it.label }
                .toSet()
                .size,
        )
        assertEquals(
            Occupancy.entries.size,
            Occupancy.entries
                .map(::occupancyLabel)
                .toSet()
                .size,
        )
        assertEquals(
            ServiceFlag.entries.size,
            ServiceFlag.entries
                .map(::serviceFlagLabel)
                .toSet()
                .size,
        )
        assertEquals(
            OutOfOrderOverlayResponse.Kind.entries.size,
            OutOfOrderOverlayResponse.Kind.entries
                .map(::outOfOrderKindLabel)
                .toSet()
                .size,
        )
    }

    @Test
    fun overlayKindLabelMatchesLegendLabel() {
        assertEquals(RoomDisplayStatus.OUT_OF_ORDER.label, outOfOrderKindLabel(KIND_OOO))
        assertEquals(RoomDisplayStatus.OUT_OF_SERVICE.label, outOfOrderKindLabel(KIND_OOS))
        assertEquals(RoomDisplayStatus.OCCUPIED.label, occupancyLabel(Occupancy.OCCUPIED))
    }

    @Test
    fun decodedNullOutOfOrderFallsThroughToOccupancy() {
        val decoded = JSON.decodeFromString(RoomStatusResponse.serializer(), statusJson(outOfOrder = "null"))
        assertEquals(RoomDisplayStatus.OCCUPIED, displayStatus(decoded))
        assertEquals("Ocupada · Suja", tileLabel(decoded))
    }

    @Test
    fun decodedMissingOutOfOrderFallsThroughToHousekeeping() {
        val json = statusJson(housekeeping = "INSPECTED", occupancy = "VACANT")
        val decoded = JSON.decodeFromString(RoomStatusResponse.serializer(), json)
        assertEquals(RoomDisplayStatus.INSPECTED, displayStatus(decoded))
    }

    @Test
    fun decodedOutOfServiceOverlayWinsOverOccupied() {
        val overlayJson =
            """{"id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "kind": "OUT_OF_SERVICE",
               "startsOn": "2026-11-13", "endsOn": "2026-11-13"}"""
        val decoded = JSON.decodeFromString(RoomStatusResponse.serializer(), statusJson(outOfOrder = overlayJson))
        assertEquals(RoomDisplayStatus.OUT_OF_SERVICE, displayStatus(decoded))
        assertEquals("Fora de serviço", tileLabel(decoded))
    }

    @Test
    fun unknownEnumValuesAreRejectedBeforeReachingTheDisplay() {
        // Decisão I7: enum fechado com valor novo é versão incompatível, nunca um rótulo de reserva.
        assertFailsWith<SerializationException> {
            JSON.decodeFromString(RoomStatusResponse.serializer(), statusJson(housekeeping = "BLOCKED"))
        }
        assertFailsWith<SerializationException> {
            JSON.decodeFromString(RoomStatusResponse.serializer(), statusJson(occupancy = "RESERVED"))
        }
        val unknownKind =
            """{"id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "kind": "MAINTENANCE",
               "startsOn": "2026-11-13", "endsOn": "2026-11-13"}"""
        assertFailsWith<SerializationException> {
            JSON.decodeFromString(RoomStatusResponse.serializer(), statusJson(outOfOrder = unknownKind))
        }
    }
}
