package com.zeny.desktop.seed.data

/** Gera e valida CPF e CNPJ sintéticos (só dígitos), a partir de bases fixas do catálogo. */
object FictitiousDocuments {
    private const val CPF_BASE = 9
    private const val CNPJ_BASE = 12
    private const val MODULUS = 11
    private const val MIN_WEIGHTED_REMAINDER = 2
    private val CNPJ_WEIGHTS = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)

    fun cpf(base9: String): String {
        requireDigits(base9, CPF_BASE)
        val d1 = check(base9, 10)
        val d2 = check(base9 + d1, 11)
        return "$base9$d1$d2"
    }

    fun cnpj(base12: String): String {
        requireDigits(base12, CNPJ_BASE)
        val d1 = check(base12, CNPJ_WEIGHTS, 1)
        val d2 = check(base12 + d1, CNPJ_WEIGHTS, 0)
        return "$base12$d1$d2"
    }

    fun isValidCpf(digits: String): Boolean =
        digits.length == CPF_BASE + 2 && digits.all { it.isPlainDigit() } &&
            digits.toSet().size > 1 && cpf(digits.take(CPF_BASE)) == digits

    fun isValidCnpj(digits: String): Boolean =
        digits.length == CNPJ_BASE + 2 && digits.all { it.isPlainDigit() } &&
            digits.toSet().size > 1 && cnpj(digits.take(CNPJ_BASE)) == digits

    private fun requireDigits(
        base: String,
        size: Int,
    ) = require(base.length == size && base.all { it.isPlainDigit() }) { "A base deve ter $size dígitos." }

    private fun Char.isPlainDigit() = this in '0'..'9'

    private fun check(
        base: String,
        firstWeight: Int,
    ): Int {
        val sum = base.mapIndexed { i, c -> (c - '0') * (firstWeight - i) }.sum()
        return digit(sum)
    }

    private fun check(
        base: String,
        weights: IntArray,
        offset: Int,
    ): Int {
        val sum = base.mapIndexed { i, c -> (c - '0') * weights[i + offset] }.sum()
        return digit(sum)
    }

    private fun digit(sum: Int): Int = (sum % MODULUS).let { if (it < MIN_WEIGHTED_REMAINDER) 0 else MODULUS - it }
}
