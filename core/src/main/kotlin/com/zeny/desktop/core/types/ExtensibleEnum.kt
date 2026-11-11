@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.desktop.core.types

/** Rótulos de um enum que o servidor pode estender; valor desconhecido vira "Outro (VALOR)". */
class ExtensibleEnumLabels(
    private val labels: Map<String, String>,
) {
    fun label(value: String): String = labels[value] ?: "Outro ($value)"
}
