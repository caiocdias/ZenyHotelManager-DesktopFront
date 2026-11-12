package com.zeny.desktop.app.catalog

import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.design.table.Column as TableColumn

// Dados fictícios do catálogo, em memória.
private const val TABLE_PAGE_SIZE = 5
private const val TABLE_PAGES = 4

internal data class Room(
    val number: Int,
    val kind: String,
    val status: String,
)

internal val roomStatuses = listOf("AVAILABLE", "OCCUPIED", "CLEANING", "MAINTENANCE")
private val roomKinds = listOf("Solteiro", "Casal", "Suíte")

internal val statusLabels =
    ExtensibleEnumLabels(
        mapOf("AVAILABLE" to "Disponível", "OCCUPIED" to "Ocupado", "CLEANING" to "Em limpeza"),
    )
internal val statusTones =
    mapOf(
        "AVAILABLE" to StatusTone.SUCCESS,
        "OCCUPIED" to StatusTone.INFO,
        "CLEANING" to StatusTone.WARNING,
    )

/** Páginas falsas em memória: 4 páginas de 5 quartos, cursor é o índice da primeira linha. */
internal fun fakeRoomPager() =
    CursorPager<Room>(TABLE_PAGE_SIZE) { cursor, limit ->
        val from = cursor?.toInt() ?: 0
        val items =
            (from until from + limit).map {
                Room(101 + it, roomKinds[it % roomKinds.size], roomStatuses[it % roomStatuses.size])
            }
        val next = from + limit
        Page(items, if (next < TABLE_PAGE_SIZE * TABLE_PAGES) next.toString() else null)
    }

internal val roomColumns =
    listOf(
        TableColumn<Room>("number", "Quarto", comparator = compareBy { it.number }) { it.number.toString() },
        TableColumn<Room>("kind", "Tipo", weight = 2f, comparator = compareBy { it.kind }) { it.kind },
        TableColumn<Room>("status", "Situação", weight = 2f) { statusLabels.label(it.status) },
    )
