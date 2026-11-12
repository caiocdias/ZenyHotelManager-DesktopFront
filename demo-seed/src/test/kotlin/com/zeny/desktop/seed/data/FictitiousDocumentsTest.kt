package com.zeny.desktop.seed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FictitiousDocumentsTest {
    @Test
    fun cpfKnownVector() {
        assertEquals("11144477735", FictitiousDocuments.cpf("111444777"))
    }

    @Test
    fun cnpjKnownVector() {
        assertEquals("11222333000181", FictitiousDocuments.cnpj("112223330001"))
    }

    @Test
    fun generatedDocumentsValidate() {
        assertTrue(FictitiousDocuments.isValidCpf(FictitiousDocuments.cpf("123456789")))
        assertTrue(FictitiousDocuments.isValidCnpj(FictitiousDocuments.cnpj("123456780001")))
        assertFalse(FictitiousDocuments.isValidCpf("11144477736"))
        assertFalse(FictitiousDocuments.isValidCnpj("11222333000182"))
        assertFalse(FictitiousDocuments.isValidCpf("123"))
    }

    @Test
    fun badBaseThrows() {
        assertFailsWith<IllegalArgumentException> { FictitiousDocuments.cpf("12345678") }
        assertFailsWith<IllegalArgumentException> { FictitiousDocuments.cpf("12345678a") }
        assertFailsWith<IllegalArgumentException> { FictitiousDocuments.cnpj("1122233300011") }
        assertFailsWith<IllegalArgumentException> { FictitiousDocuments.cnpj("11222333000x") }
    }

    @Test
    fun repeatedDigitsCpfInvalid() {
        assertFalse(FictitiousDocuments.isValidCpf("11111111111"))
        assertFalse(FictitiousDocuments.isValidCnpj("11111111111111"))
    }
}
