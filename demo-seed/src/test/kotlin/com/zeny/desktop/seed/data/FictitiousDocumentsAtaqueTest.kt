package com.zeny.desktop.seed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FictitiousDocumentsAtaqueTest {
    // Referência independente (outra formulação do módulo 11), para comparar com o código de produção.
    private fun refCpf(base: String): String {
        var s = base
        repeat(2) {
            val w = s.length + 1
            val sum = s.mapIndexed { i, c -> (c - '0') * (w - i) }.sum()
            s += ((sum * 10) % 11 % 10).toString()
        }
        return s
    }

    private fun refCnpj(base: String): String {
        var s = base
        repeat(2) {
            val weights =
                if (s.length ==
                    12
                ) {
                    listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
                } else {
                    listOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
                }
            val r = s.mapIndexed { i, c -> (c - '0') * weights[i] }.sum() % 11
            s += (if (r < 2) 0 else 11 - r).toString()
        }
        return s
    }

    @Test
    fun cpfMatchesIndependentReferenceOnManyBases() {
        for (n in 0 until 20000) {
            val base = (n * 49999L % 1_000_000_000L).toString().padStart(9, '0')
            assertEquals(refCpf(base), FictitiousDocuments.cpf(base), "base $base")
        }
    }

    @Test
    fun cnpjMatchesIndependentReferenceOnManyBases() {
        for (n in 0 until 20000) {
            val base = (n * 49999999L % 1_000_000_000_000L).toString().padStart(12, '0')
            assertEquals(refCnpj(base), FictitiousDocuments.cnpj(base), "base $base")
        }
    }

    @Test
    fun cpfRemainderTenAndZeroBordersGiveCheckDigitZero() {
        // 000000001: soma ponderada 2 -> resto < 2 no primeiro dígito
        assertEquals("00000000191", FictitiousDocuments.cpf("000000001"))
        assertEquals("00000000000", FictitiousDocuments.cpf("000000000"))
        // 111444777 tem DV 3 e 5; base 100000000 exercita resto 10 -> dígito 0
        assertEquals(refCpf("100000000"), FictitiousDocuments.cpf("100000000"))
    }

    @Test
    fun cnpjKnownRealisticVectors() {
        assertEquals("11444777000161", FictitiousDocuments.cnpj("114447770001"))
        assertEquals("00000000000191", FictitiousDocuments.cnpj("000000000001"))
    }

    @Test
    fun generatedValuesHaveOnlyDigitsAndRightLength() {
        assertEquals(11, FictitiousDocuments.cpf("987654321").length)
        assertEquals(14, FictitiousDocuments.cnpj("987654321000").length)
        assertTrue(FictitiousDocuments.cpf("987654321").all { it in '0'..'9' })
    }

    @Test
    fun validatorsRejectPunctuationSpacesAndWrongLength() {
        assertFalse(FictitiousDocuments.isValidCpf("111.444.777-35"))
        assertFalse(FictitiousDocuments.isValidCpf(" 11144477735"))
        assertFalse(FictitiousDocuments.isValidCpf("11144477735 "))
        assertFalse(FictitiousDocuments.isValidCpf("111444777350"))
        assertFalse(FictitiousDocuments.isValidCpf("1114447773"))
        assertFalse(FictitiousDocuments.isValidCpf(""))
        assertFalse(FictitiousDocuments.isValidCnpj("11.222.333/0001-81"))
        assertFalse(FictitiousDocuments.isValidCnpj(" 11222333000181"))
        assertFalse(FictitiousDocuments.isValidCnpj("112223330001810"))
        assertFalse(FictitiousDocuments.isValidCnpj("1122233300018"))
        assertFalse(FictitiousDocuments.isValidCnpj(""))
    }

    @Test
    fun validatorsAcceptKnownValid() {
        assertTrue(FictitiousDocuments.isValidCpf("11144477735"))
        assertTrue(FictitiousDocuments.isValidCnpj("11222333000181"))
    }

    @Test
    fun validatorsRejectNonAsciiDigits() {
        // dígitos árabe-índicos e de largura total são Character.isDigit, mas não são dígitos simples
        assertFalse(FictitiousDocuments.isValidCpf("١١١٤٤٤٧٧٧٣٥"))
        assertFalse(FictitiousDocuments.isValidCpf("１１１４４４７７７３５"))
        assertFalse(FictitiousDocuments.isValidCnpj("１１２２２３３３０００１８１"))
    }

    @Test
    fun everyRepeatedDigitCpfAndCnpjIsInvalid() {
        for (c in '0'..'9') {
            assertFalse(FictitiousDocuments.isValidCpf(c.toString().repeat(11)), "cpf $c")
            assertFalse(FictitiousDocuments.isValidCnpj(c.toString().repeat(14)), "cnpj $c")
        }
    }

    @Test
    fun anyChangedDigitBreaksValidation() {
        val cpf = "11144477735"
        val cnpj = "11222333000181"
        for (i in cpf.indices) {
            for (c in '0'..'9') {
                if (c == cpf[i]) continue
                val mutated = cpf.replaceRange(i, i + 1, c.toString())
                assertFalse(FictitiousDocuments.isValidCpf(mutated), "cpf $mutated")
            }
        }
        for (i in cnpj.indices) {
            for (c in '0'..'9') {
                if (c == cnpj[i]) continue
                val mutated = cnpj.replaceRange(i, i + 1, c.toString())
                assertFalse(FictitiousDocuments.isValidCnpj(mutated), "cnpj $mutated")
            }
        }
    }

    @Test
    fun cpfAndCnpjAreNotInterchangeable() {
        assertFalse(FictitiousDocuments.isValidCnpj("11144477735"))
        assertFalse(FictitiousDocuments.isValidCpf("11222333000181"))
    }

    @Test
    fun badBasesAreRejectedForEverySizeAndCharacterClass() {
        listOf(
            "",
            " ",
            "12345678",
            "1234567890",
            "12345678 ",
            " 23456789",
            "123.456.78",
            "12345678-",
            "-12345678",
            "１２３４５６７８９",
            "١٢٣٤٥٦٧٨٩",
        ).forEach { assertFailsWith<IllegalArgumentException>("cpf '$it'") { FictitiousDocuments.cpf(it) } }
        listOf("", "11222333000", "1122233300011", "11.222.333/0", "11222333000 ", "１１２２２３３３０００１")
            .forEach { assertFailsWith<IllegalArgumentException>("cnpj '$it'") { FictitiousDocuments.cnpj(it) } }
    }

    @Test
    fun cpfBaseWithCnpjLengthAndViceVersaIsRejected() {
        assertFailsWith<IllegalArgumentException> { FictitiousDocuments.cpf("112223330001") }
        assertFailsWith<IllegalArgumentException> { FictitiousDocuments.cnpj("111444777") }
    }

    @Test
    fun repeatedBaseGeneratesDocumentThatValidatorRejects() {
        assertFalse(FictitiousDocuments.isValidCpf(FictitiousDocuments.cpf("111111111")))
        assertFalse(FictitiousDocuments.isValidCnpj(FictitiousDocuments.cnpj("000000000000")))
    }
}
