package com.zeny.desktop.design.form

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormStateTest {
    private val required: (String) -> String? = { if (it.isBlank()) "Obrigatório." else null }

    private fun problem(vararg errors: FieldError) = ApiError.Problem(422, "VALIDATION_FAILED", null, errors.toList())

    @Test
    fun localErrorOnlyAfterTouched() {
        val field = FormField("name", "Nome", validate = required)
        assertNull(field.error)
        field.onBlur()
        assertEquals("Obrigatório.", field.error)
    }

    @Test
    fun localValidationRunsOnValidate() {
        val name = FormField("name", "Nome", validate = required)
        val state = FormState(listOf(name))
        assertFalse(state.validate())
        assertEquals("Obrigatório.", name.error)
        name.onChange("Ana")
        assertTrue(state.validate())
        assertNull(name.error)
    }

    @Test
    fun serverErrorGoesToMatchingField() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "NOT_BLANK", "não deve estar em branco")))
        assertEquals("não deve estar em branco", price.error)
        assertNull(state.formError)
    }

    @Test
    fun serverPathAliasMatches() {
        val email = FormField("email", "E-mail", serverPaths = setOf("guest.email"))
        val state = FormState(listOf(email))
        state.applyServerErrors(problem(FieldError("guest.email", "INVALID", "e-mail inválido")))
        assertEquals("e-mail inválido", email.error)
        assertNull(state.formError)
    }

    @Test
    fun nestedPathDoesNotMatchHomonymField() {
        val document = FormField("document", "Documento")
        val state = FormState(listOf(document))
        state.applyServerErrors(problem(FieldError("guests[0].document", "INVALID", "documento inválido")))
        assertNull(document.error)
        assertEquals("documento inválido", state.formError)
    }

    @Test
    fun unknownFieldErrorGoesToFormError() {
        val state = FormState(listOf(FormField("name", "Nome")))
        state.applyServerErrors(
            problem(FieldError("x.y", "A", "primeira"), FieldError("z", "B", "segunda")),
        )
        assertEquals("primeira\nsegunda", state.formError)
    }

    @Test
    fun problemWithoutFieldErrorsUsesUserMessage() {
        val state = FormState(emptyList())
        val error = ApiError.Problem(409, "CONFLICT", null, emptyList())
        state.applyServerErrors(error)
        assertEquals(error.userMessage, state.formError)
    }

    @Test
    fun nonProblemErrorGoesToFormError() {
        val state = FormState(listOf(FormField("name", "Nome")))
        val error = ApiError.Network(RuntimeException("boom"))
        state.applyServerErrors(error)
        assertEquals(error.userMessage, state.formError)
    }

    @Test
    fun editingClearsServerErrorOfThatField() {
        val price = FormField("price", "Preço")
        val name = FormField("name", "Nome")
        val state = FormState(listOf(name, price))
        state.applyServerErrors(
            problem(FieldError("price", "A", "erro preço"), FieldError("name", "B", "erro nome")),
        )
        price.onChange("10")
        assertNull(price.error)
        assertEquals("erro nome", name.error)
    }

    @Test
    fun newServerErrorsReplaceOldOnesAndClearResets() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "A", "velho")))
        state.applyServerErrors(problem(FieldError("other", "A", "novo")))
        assertNull(price.error)
        assertEquals("novo", state.formError)
        state.clearServerErrors()
        assertNull(state.formError)
    }
}
