package com.zeny.desktop.core.error

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ataques à regra única: as bordas de status (499/500/599), o `code` só casa exato (o servidor manda o enum), e as
 * variantes que poderiam ser confundidas entre si (`Unexpected` nunca é guardado; `SessionEnded` com qualquer `code`).
 */
class WriteFailureAtaqueTest {
    private fun problem(
        status: Int,
        code: String?,
    ) = ApiError.Problem(status, code, null, emptyList())

    @Test
    fun theStoredBoundaryIsExactlyFiveHundredForAProblem() {
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(problem(499, null)))
        assertEquals(WriteFailure.UNKNOWN_STORED, classifyWriteFailure(problem(500, null)))
        assertEquals(WriteFailure.UNKNOWN_STORED, classifyWriteFailure(problem(599, "SOMETHING")))
    }

    @Test
    fun theUnknownBoundaryIsExactlyFiveHundredForAnUnexpectedAndItIsNeverStored() {
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(ApiError.Unexpected(499)))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.Unexpected(500)))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.Unexpected(599)))
    }

    @Test
    fun keyInUseWinsOverTheStoredFiveHundredsAtTheBorders() {
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(problem(500, "IDEMPOTENCY_KEY_IN_USE")))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(problem(599, "IDEMPOTENCY_KEY_IN_USE")))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(problem(499, "IDEMPOTENCY_KEY_IN_USE")))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(problem(400, "IDEMPOTENCY_KEY_IN_USE")))
    }

    // O `code` é o enum do contrato, copiado sem normalizar pelo `ProblemMapper`: só o valor exato é "chave em uso".
    // Uma variante de caixa ou com espaços é outro `code`, e segue a regra do status.
    @Test
    fun aKeyInUseCodeWithOtherCaseOrSpacesIsNotKeyInUseAndFollowsTheStatus() {
        for (code in listOf("idempotency_key_in_use", " IDEMPOTENCY_KEY_IN_USE", "IDEMPOTENCY_KEY_IN_USE ", "")) {
            assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(problem(409, code)), "409 [$code]")
            assertEquals(WriteFailure.UNKNOWN_STORED, classifyWriteFailure(problem(500, code)), "500 [$code]")
        }
    }

    @Test
    fun aKeyReusedCodeIsNotKeyInUse() {
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(problem(422, "IDEMPOTENCY_KEY_REUSED")))
        assertEquals(WriteFailure.UNKNOWN_STORED, classifyWriteFailure(problem(500, "IDEMPOTENCY_KEY_REUSED")))
    }

    @Test
    fun sessionEndedIsUnknownWhateverItsCode() {
        for (code in listOf(null, "SESSION_REVOKED", "IDEMPOTENCY_KEY_IN_USE")) {
            assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.SessionEnded(code)), "code $code")
        }
    }

    @Test
    fun networkAndIncompatibleResponseAreUnknownWhateverTheCause() {
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.Network(IllegalStateException("x"))))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.IncompatibleResponse(IOException("x"))))
        assertEquals(
            WriteFailure.UNKNOWN,
            classifyWriteFailure(ApiError.IncompatibleResponse(NoSuchElementException("sem reserva"))),
        )
    }

    @Test
    fun anApprovalRequiredWithALimitIsStillDefinitive() {
        val failure = ApiError.ApprovalRequired("DISCOUNT", "p", "r", ApprovalLimit("k", "10.00"), "detalhe")
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(failure))
    }

    @Test
    fun aPreconditionFailedWithDetailIsDefinitive() {
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(ApiError.PreconditionFailed("etag velho")))
    }

    // `when` sem `else`: um subtipo novo de `ApiError` não compila aqui até alguém decidir a classe dele de propósito.
    private fun expectedFor(error: ApiError): WriteFailure =
        when (error) {
            is ApiError.Problem -> WriteFailure.DEFINITIVE
            is ApiError.ApprovalRequired -> WriteFailure.DEFINITIVE
            is ApiError.PreconditionFailed -> WriteFailure.DEFINITIVE
            ApiError.PasswordChangeRequired -> WriteFailure.DEFINITIVE
            is ApiError.SessionEnded -> WriteFailure.UNKNOWN
            is ApiError.Network -> WriteFailure.UNKNOWN
            is ApiError.IncompatibleResponse -> WriteFailure.UNKNOWN
            is ApiError.Unexpected -> WriteFailure.DEFINITIVE
        }

    @Test
    fun everySubtypeOfApiErrorBelowFiveHundredHasTheExpectedClassification() {
        val samples =
            listOf(
                problem(400, null),
                ApiError.ApprovalRequired("a", "p", "r", null, null),
                ApiError.PreconditionFailed(null),
                ApiError.PasswordChangeRequired,
                ApiError.SessionEnded(null),
                ApiError.Network(IOException("x")),
                ApiError.IncompatibleResponse(IOException("x")),
                ApiError.Unexpected(400),
            )
        for (error in samples) {
            assertEquals(expectedFor(error), classifyWriteFailure(error), error::class.simpleName)
        }
    }
}
