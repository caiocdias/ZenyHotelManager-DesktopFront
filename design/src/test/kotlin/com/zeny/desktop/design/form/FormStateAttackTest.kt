package com.zeny.desktop.design.form

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataques ao FormState/FormField (Task 4). Casos sem regra no brief seguem o comportamento mais seguro. */
class FormStateAttackTest {
    private val required: (String) -> String? = { if (it.isBlank()) "Obrigatório." else null }

    private fun problem(vararg errors: FieldError) = ApiError.Problem(422, "VALIDATION_FAILED", null, errors.toList())

    @Test
    fun fieldMatchingIsCaseSensitive() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("Price", "X", "maiúscula")))
        assertNull(price.error)
        assertEquals("maiúscula", state.formError)
    }

    @Test
    fun fieldWithJsonPathPrefixOrSlashDoesNotMatch() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(
            problem(FieldError("$.price", "X", "a"), FieldError("/price", "X", "b"), FieldError(" price", "X", "c")),
        )
        assertNull(price.error)
        assertEquals("a\nb\nc", state.formError)
    }

    @Test
    fun blankFieldGoesToFormErrorNotToAField() {
        val name = FormField("name", "Nome")
        val state = FormState(listOf(name))
        state.applyServerErrors(problem(FieldError("", "X", "vazio"), FieldError("   ", "X", "espacos")))
        assertNull(name.error)
        assertEquals("vazio\nespacos", state.formError)
    }

    @Test
    fun formErrorNeverShowsCodeOrTechnicalPath() {
        val state = FormState(listOf(FormField("name", "Nome")))
        state.applyServerErrors(problem(FieldError("guests[0].document", "NOT_VALID_DOC", "documento inválido")))
        val shown = assertNotNull(state.formError)
        assertFalse("NOT_VALID_DOC" in shown)
        assertFalse("guests[0]" in shown)
    }

    @Test
    fun problemWithoutFieldErrorsDoesNotLeakCodeOrDetail() {
        val state = FormState(listOf(FormField("name", "Nome")))
        state.applyServerErrors(ApiError.Problem(500, "SOME_UNKNOWN_CODE", "{\"trace\":\"<html>\"}", emptyList()))
        val shown = assertNotNull(state.formError)
        assertFalse("SOME_UNKNOWN_CODE" in shown)
        assertFalse("{" in shown)
        assertFalse("<html>" in shown)
    }

    @Test
    fun problemWithDetailAndWithoutDetailBothGiveUserMessage() {
        val state = FormState(emptyList())
        listOf(null, "detalhe técnico").forEach { detail ->
            val error = ApiError.Problem(409, "CONFLICT", detail, emptyList())
            state.applyServerErrors(error)
            assertEquals(error.userMessage, state.formError)
        }
    }

    @Test
    fun everyApiErrorTypeGoesToFormErrorWithUserMessageAndLeavesFieldsClean() {
        val errors =
            listOf(
                ApiError.ApprovalRequired("a", "p", "r", null, null),
                ApiError.PreconditionFailed(null),
                ApiError.PasswordChangeRequired,
                ApiError.SessionEnded("X"),
                ApiError.Network(RuntimeException("boom")),
                ApiError.IncompatibleResponse(RuntimeException("boom")),
                ApiError.Unexpected(502),
            )
        for (error in errors) {
            val name = FormField("name", "Nome")
            val state = FormState(listOf(name))
            state.applyServerErrors(error)
            assertEquals(error.userMessage, state.formError, error::class.simpleName)
            assertNull(name.error, error::class.simpleName)
        }
    }

    @Test
    fun formWithoutFieldsSendsEverythingToFormErrorAndValidatesTrue() {
        val state = FormState(emptyList())
        assertTrue(state.validate())
        state.applyServerErrors(problem(FieldError("price", "X", "preço")))
        assertEquals("preço", state.formError)
    }

    @Test
    fun applyingTwiceReplacesFieldErrorAndClearsFormError() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "A", "velho"), FieldError("x", "A", "geral")))
        assertEquals("geral", state.formError)
        state.applyServerErrors(problem(FieldError("price", "B", "novo")))
        assertEquals("novo", price.error)
        assertNull(state.formError)
    }

    @Test
    fun applyingNonProblemAfterFieldErrorsClearsFieldErrors() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "A", "velho")))
        state.applyServerErrors(ApiError.Network(RuntimeException("x")))
        assertNull(price.error)
    }

    @Test
    fun serverErrorTakesPriorityOverLocalError() {
        val price = FormField("price", "Preço", validate = required)
        val state = FormState(listOf(price))
        price.onBlur()
        state.applyServerErrors(problem(FieldError("price", "A", "do servidor")))
        assertEquals("do servidor", price.error)
        state.clearServerErrors()
        assertEquals("Obrigatório.", price.error)
    }

    @Test
    fun clearServerErrorsDoesNotTouchLocalStateOrText() {
        val price = FormField("price", "Preço", initial = "7", validate = required)
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "A", "x")))
        state.clearServerErrors()
        assertEquals("7", price.text)
        assertFalse(price.touched)
        assertNull(price.error)
    }

    @Test
    fun validateAfterServerErrorKeepsServerErrorVisible() {
        val price = FormField("price", "Preço", initial = "10", validate = required)
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "A", "do servidor")))
        assertTrue(state.validate())
        assertEquals("do servidor", price.error)
    }

    @Test
    fun validateReportsFalseAndTouchesAllFields() {
        val a = FormField("a", "A", initial = "x", validate = required)
        val b = FormField("b", "B", validate = required)
        val state = FormState(listOf(a, b))
        assertFalse(state.validate())
        assertTrue(a.touched && b.touched)
        assertNull(a.error)
        assertEquals("Obrigatório.", b.error)
    }

    @Test
    fun onChangeClearsOnlyOwnServerErrorAndKeepsFormError() {
        val a = FormField("a", "A")
        val b = FormField("b", "B")
        val state = FormState(listOf(a, b))
        state.applyServerErrors(
            problem(FieldError("a", "X", "ea"), FieldError("b", "X", "eb"), FieldError("z", "X", "geral")),
        )
        a.onChange("novo")
        assertNull(a.error)
        assertEquals("eb", b.error)
        assertEquals("geral", state.formError)
    }

    @Test
    fun onChangeDoesNotResetTouchedAndLocalErrorFollowsText() {
        val a = FormField("a", "A", validate = required)
        a.onBlur()
        a.onChange("ok")
        assertTrue(a.touched)
        assertNull(a.error)
        a.onChange("")
        assertEquals("Obrigatório.", a.error)
    }

    @Test
    fun editingAfterServerErrorShowsLocalErrorWhenInvalidAndTouched() {
        val a = FormField("a", "A", initial = "x", validate = required)
        val state = FormState(listOf(a))
        a.onBlur()
        state.applyServerErrors(problem(FieldError("a", "X", "servidor")))
        a.onChange("")
        assertEquals("Obrigatório.", a.error)
    }

    // Lacunas do brief: comportamento mais seguro para o usuário.

    @Test
    fun twoServerErrorsForSameFieldShowBothMessages() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(
            problem(FieldError("price", "A", "primeira"), FieldError("price", "B", "segunda")),
        )
        val shown = assertNotNull(price.error)
        assertTrue("primeira" in shown, shown)
        assertTrue("segunda" in shown, shown)
        assertNull(state.formError)
    }

    @Test
    fun exactNameWinsOverAliasOfEarlierField() {
        val a = FormField("a", "A", serverPaths = setOf("b"))
        val b = FormField("b", "B")
        val state = FormState(listOf(a, b))
        state.applyServerErrors(problem(FieldError("b", "X", "para b")))
        assertNull(a.error)
        assertEquals("para b", b.error)
    }

    @Test
    fun blankMessageDoesNotProduceEmptyBanner() {
        val state = FormState(listOf(FormField("name", "Nome")))
        state.applyServerErrors(problem(FieldError("x", "A", "")))
        val shown = state.formError
        assertTrue(shown == null || shown.isNotBlank(), "formError vazio: '$shown'")
    }

    @Test
    fun blankMessageForMatchedFieldDoesNotLeaveSilentErrorState() {
        val price = FormField("price", "Preço")
        val state = FormState(listOf(price))
        state.applyServerErrors(problem(FieldError("price", "A", "")))
        val shown = price.error
        assertTrue(shown == null || shown.isNotBlank(), "error vazio: '$shown'")
    }
}
