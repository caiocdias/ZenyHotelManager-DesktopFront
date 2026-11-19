package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ApprovalLimit
import com.zeny.desktop.core.error.FieldError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

private const val ECHO = "hóspede Maria Aparecida Sigilosa, CPF 123.456.789-09"

class WithoutServerDetailTest {
    @Test
    fun problemKeepsStatusAndCodeButDropsDetailAndFieldErrors() {
        val original = ApiError.Problem(409, "FOLIO_CLOSED", ECHO, listOf(FieldError("f", "c", ECHO)))
        val clean = assertIs<ApiError.Problem>(original.withoutServerDetail())
        assertEquals(409, clean.status)
        assertEquals("FOLIO_CLOSED", clean.code)
        assertNull(clean.detail)
        assertEquals(emptyList(), clean.fieldErrors)
    }

    @Test
    fun approvalRequiredKeepsTheActionButDropsDetail() {
        val limit = ApprovalLimit("k", "10.00")
        val original = ApiError.ApprovalRequired("reservation.cancel", "reservations.cancel", "ref", limit, ECHO)
        val clean = assertIs<ApiError.ApprovalRequired>(original.withoutServerDetail())
        assertEquals("reservation.cancel", clean.action)
        assertEquals("reservations.cancel", clean.permission)
        assertEquals("ref", clean.resourceRef)
        assertEquals(limit, clean.limit)
        assertNull(clean.detail)
    }

    @Test
    fun preconditionFailedDropsDetail() {
        assertNull(
            assertIs<ApiError.PreconditionFailed>(ApiError.PreconditionFailed(ECHO).withoutServerDetail()).detail,
        )
    }

    @Test
    fun errorsWithoutDetailComeBackAsTheyAre() {
        val errors =
            listOf(
                ApiError.Network(IOException("falha")),
                ApiError.Unexpected(502),
                ApiError.IncompatibleResponse(IllegalStateException("formato")),
            )
        errors.forEach { assertSame(it, it.withoutServerDetail()) }
    }
}
