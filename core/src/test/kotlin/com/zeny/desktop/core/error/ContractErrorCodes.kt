package com.zeny.desktop.core.error

import java.io.File

/** Extrai do `api/openapi.yaml` os códigos de erro estáveis citados no contrato (utilitário de teste). */
object ContractErrorCodes {
    private val codePattern = Regex("""\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b""")
    private val wordPattern = Regex("""[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*""")
    private val foldPattern = Regex("""\\\s*\n\s*\\""")
    private val methods = setOf("get", "post", "put", "delete", "patch")

    fun all(): Set<String> {
        val lines = contractFile().readLines()
        return commonCodes(lines) + operationCodes(lines)
    }

    private fun contractFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "api/openapi.yaml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("api/openapi.yaml não encontrado acima de ${System.getProperty("user.dir")}")
    }

    private fun indent(line: String) = line.length - line.trimStart().length

    private fun unfold(text: String) = text.replace(foldPattern, "")

    /** Os códigos listados depois de "Comuns:" na `description` de `Problem.code`. */
    private fun commonCodes(lines: List<String>): Set<String> {
        val start = lines.indexOf("    Problem:")
        require(start >= 0) { "schema Problem ausente" }
        val codeAt = (start until lines.size).first { lines[it] == "        code:" }
        val block = lines.drop(codeAt + 1).takeWhile { indent(it) > 8 }.joinToString("\n")
        val list = unfold(block).substringAfter("Comuns:").substringBefore("Cada módulo")
        return wordPattern.findAll(list).map { it.value }.toSet()
    }

    private fun startsDescription(
        line: String,
        inOperation: Boolean,
        inResponses: Boolean,
    ): Boolean {
        val level = indent(line)
        val atDescription = inOperation && line.trim().startsWith("description:")
        return atDescription && (level == 6 || (inResponses && level == 10))
    }

    /** `description` da operação (indent 6) e de cada resposta (indent 10, dentro de `responses:`). */
    private fun operationCodes(lines: List<String>): Set<String> {
        val descriptions = mutableListOf<StringBuilder>()
        var inOperation = false
        var inResponses = false
        var descriptionLevel = -1
        for (line in lines.subList(lines.indexOf("paths:") + 1, lines.indexOf("components:"))) {
            val level = indent(line)
            if (line.isNotBlank() && level <= descriptionLevel) descriptionLevel = -1
            if (line.isNotBlank() && level <= 6) {
                if (level <= 4) inOperation = level == 4 && line.trim().removeSuffix(":") in methods
                inResponses = inOperation && level == 6 && line.trim() == "responses:"
            }
            val starts = startsDescription(line, inOperation, inResponses)
            if (starts) {
                descriptionLevel = level
                descriptions += StringBuilder()
            }
            if (descriptionLevel >= 0) descriptions.last().append(line).append('\n')
        }
        val found = descriptions.flatMap { d -> codePattern.findAll(unfold(d.toString())).map { it.value } }
        return found.toSet() - enumValues(lines)
    }

    /** Valores de qualquer `enum` ou `x-extensible-enum` do contrato. */
    private fun enumValues(lines: List<String>): Set<String> {
        val values = mutableSetOf<String>()
        for ((n, line) in lines.withIndex()) {
            val t = line.trim()
            if (t.startsWith("enum: [") || t.startsWith("x-extensible-enum: [")) {
                t
                    .substringAfter("[")
                    .substringBefore("]")
                    .split(",")
                    .forEach { values += it.trim().trim('"', '\'') }
            } else if (t == "enum:" || t == "x-extensible-enum:") {
                var k = n + 1
                while (k < lines.size && lines[k].trim().startsWith("- ") && indent(lines[k]) >= indent(line)) {
                    values +=
                        lines[k]
                            .trim()
                            .removePrefix("- ")
                            .trim()
                            .trim('"', '\'')
                    k++
                }
            }
        }
        return values
    }
}
