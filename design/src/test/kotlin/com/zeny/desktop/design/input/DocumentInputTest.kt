package com.zeny.desktop.design.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DocumentInputTest {
    private fun checkDigit(
        base: String,
        weights: List<Int>,
    ): Char {
        val remainder = base.toList().zip(weights).sumOf { (c, w) -> (c.code - '0'.code) * w } % 11
        return if (remainder < 2) '0' else ('0' + (11 - remainder))
    }

    private fun cpf(base: String): String {
        val first = checkDigit(base, (10 downTo 2).toList())
        val second = checkDigit(base + first, (11 downTo 2).toList())
        return base + first + second
    }

    private fun cnpj(base: String): String {
        val weights = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val first = checkDigit(base, weights)
        val second = checkDigit(base + first, listOf(6) + weights)
        return base + first + second
    }

    private fun maskedCpf(cpf: String) =
        "${cpf.substring(0, 3)}.${cpf.substring(3, 6)}.${cpf.substring(6, 9)}-${cpf.substring(9)}"

    private fun maskedCnpj(cnpj: String) =
        "${cnpj.substring(0, 2)}.${cnpj.substring(2, 5)}.${cnpj.substring(5, 8)}/" +
            "${cnpj.substring(8, 12)}-${cnpj.substring(12)}"

    private fun valid(text: String): Document {
        val result = DocumentInput.parse(text)
        assertIs<InputResult.Valid<Document>>(result, "esperava Valid para '$text'")
        return result.value
    }

    private fun flip(text: String) = text.dropLast(1) + (if (text.last() == '0') '1' else '0')

    @Test
    fun validCpfAndCnpj() {
        val cpf = cpf("123456789")
        listOf(cpf, maskedCpf(cpf), " ${maskedCpf(cpf)} ").forEach {
            assertEquals(Document(DocumentKind.CPF, cpf), valid(it))
        }
        val cnpj = cnpj("112223330001")
        listOf(cnpj, maskedCnpj(cnpj)).forEach {
            assertEquals(Document(DocumentKind.CNPJ, cnpj), valid(it))
        }
    }

    @Test
    fun alphanumericCnpj() {
        val cnpj = cnpj("A1B2C3D4E5F6")
        assertEquals(Document(DocumentKind.CNPJ, cnpj), valid(maskedCnpj(cnpj).lowercase()))
        // Exemplo do contrato (api/openapi.yaml): pelos pesos do módulo 11 os DVs de 12ABC34501DE são 3 e 5.
        assertEquals("12ABC34501DE35", cnpj("12ABC34501DE"))
        assertEquals(Document(DocumentKind.CNPJ, "12ABC34501DE35"), valid("12ABC34501DE35"))
        assertEquals(Document(DocumentKind.CNPJ, "12ABC34501DE35"), valid("12.abc.345/01de-35"))
    }

    @Test
    fun rejectsWrongCheckDigit() {
        assertEquals(InputResult.Invalid("CPF inválido."), DocumentInput.parse(flip(cpf("123456789"))))
        listOf(cnpj("112223330001"), cnpj("A1B2C3D4E5F6")).forEach {
            assertEquals(InputResult.Invalid("CNPJ inválido."), DocumentInput.parse(flip(it)))
        }
        assertEquals(InputResult.Invalid("CNPJ inválido."), DocumentInput.parse("12ABC34501DE36"))
    }

    @Test
    fun rejectsRepeatedDigits() {
        assertEquals(InputResult.Invalid("CPF inválido."), DocumentInput.parse("111.111.111-11"))
        assertEquals(InputResult.Invalid("CNPJ inválido."), DocumentInput.parse("00000000000000"))
    }

    @Test
    fun rejectsWrongLength() {
        val message = "Documento deve ter 11 (CPF) ou 14 (CNPJ) caracteres."
        listOf("123", "123456789012", "123456789012345", "ABC").forEach {
            assertEquals(InputResult.Invalid(message), DocumentInput.parse(it), "para '$it'")
        }
        assertEquals(InputResult.Invalid("CPF inválido."), DocumentInput.parse("12345678!01"))
        assertEquals(InputResult.Empty, DocumentInput.parse("  "))
    }

    @Test
    fun masksWhileTyping() {
        assertEquals("", DocumentInput.mask(""))
        assertEquals("123", DocumentInput.mask("123"))
        assertEquals("123.4", DocumentInput.mask("1234"))
        assertEquals("123.456.789", DocumentInput.mask("123456789"))
        assertEquals("123.456.789-0", DocumentInput.mask("1234567890"))
        assertEquals("123.456.789-01", DocumentInput.mask("12345678901"))
        assertEquals("123.456.789-01", DocumentInput.mask("123.456.789-01"))
        assertEquals("12.345.678/9012", DocumentInput.mask("123456789012"))
        assertEquals("12.345.678/9012-3", DocumentInput.mask("1234567890123"))
        assertEquals("12.ABC.345/01DE-35", DocumentInput.mask("12abc34501de35"))
        assertEquals("12.ABC.345/01DE-35", DocumentInput.mask("12abc34501de35999"))
    }

    @Test
    fun documentToStringHidesNumber() {
        val cpf = cpf("123456789")
        assertEquals("Document(CPF, ***)", Document(DocumentKind.CPF, cpf).toString())
        assertFalse(Document(DocumentKind.CNPJ, "12ABC34501DE35").toString().contains("12ABC"))
        listOf(flip(cpf), cpf + "1", "12ABC34501DE36", "111.111.111-11").forEach {
            val result = DocumentInput.parse(it)
            assertIs<InputResult.Invalid>(result)
            assertFalse(result.message.contains(it.filter(Char::isLetterOrDigit)), "mensagem vaza '$it'")
        }
        assertEquals(Document(DocumentKind.CPF, cpf), Document(DocumentKind.CPF, cpf))
        assertEquals(Document(DocumentKind.CPF, cpf).hashCode(), Document(DocumentKind.CPF, cpf).hashCode())
    }
}
