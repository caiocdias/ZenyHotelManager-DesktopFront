@file:Suppress("MatchingDeclarationName")

package com.zeny.desktop.app.rooms

import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomStatusResponse.ServiceFlag
import de.quati.kotlin.util.Option

/** Situação de uma UH como o mapa a mostra; a ordem dos valores é a ordem da legenda. */
enum class RoomDisplayStatus(
    val label: String,
    val tone: StatusTone,
) {
    OUT_OF_ORDER("Fora de ordem", StatusTone.DANGER),
    OUT_OF_SERVICE("Fora de serviço", StatusTone.NEUTRAL),
    OCCUPIED("Ocupada", StatusTone.INFO),
    DIRTY("Suja", StatusTone.WARNING),
    CLEAN_PENDING_INSPECTION("Limpa, aguardando inspeção", StatusTone.NEUTRAL),
    CLEAN("Limpa", StatusTone.SUCCESS),
    INSPECTED("Inspecionada", StatusTone.SUCCESS),
}

private fun overlayKind(status: RoomStatusResponse): OutOfOrderOverlayResponse.Kind? =
    (status.outOfOrder as? Option.Some)?.value?.kind

/** Governança da UH, ignorando ocupação e bloqueio. */
fun housekeepingStatus(status: RoomStatusResponse): RoomDisplayStatus =
    when (status.housekeepingStatus) {
        HousekeepingStatus.DIRTY -> {
            RoomDisplayStatus.DIRTY
        }

        HousekeepingStatus.CLEAN -> {
            if (status.inspectionPending) RoomDisplayStatus.CLEAN_PENDING_INSPECTION else RoomDisplayStatus.CLEAN
        }

        HousekeepingStatus.INSPECTED -> {
            RoomDisplayStatus.INSPECTED
        }
    }

/** Precedência: bloqueio em vigor, depois ocupada, depois governança. */
fun displayStatus(status: RoomStatusResponse): RoomDisplayStatus =
    when (overlayKind(status)) {
        OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER -> {
            RoomDisplayStatus.OUT_OF_ORDER
        }

        OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE -> {
            RoomDisplayStatus.OUT_OF_SERVICE
        }

        null -> {
            when (status.occupancy) {
                Occupancy.OCCUPIED -> RoomDisplayStatus.OCCUPIED
                Occupancy.VACANT -> housekeepingStatus(status)
            }
        }
    }

/** Rótulo principal do ladrilho; a UH ocupada leva a governança junto. */
fun tileLabel(status: RoomStatusResponse): String {
    val main = displayStatus(status)
    return if (main == RoomDisplayStatus.OCCUPIED) "${main.label} · ${housekeepingStatus(status).label}" else main.label
}

fun occupancyLabel(occupancy: Occupancy): String =
    when (occupancy) {
        Occupancy.VACANT -> "Vaga"
        Occupancy.OCCUPIED -> "Ocupada"
    }

fun serviceFlagLabel(flag: ServiceFlag): String =
    when (flag) {
        ServiceFlag.NONE -> "Nenhuma"
        ServiceFlag.DO_NOT_DISTURB -> "Não perturbe"
        ServiceFlag.MAKE_UP_ROOM -> "Arrumar o quarto"
    }

fun outOfOrderKindLabel(kind: OutOfOrderOverlayResponse.Kind): String =
    when (kind) {
        OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER -> "Fora de ordem"
        OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE -> "Fora de serviço"
    }
