package com.zeny.desktop.core.error

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Uma linha da regra por caso: definitiva, desconhecida ou desconhecida e guardada sob a chave. */
class WriteFailureTest {
    private fun problem(
        status: Int,
        code: String?,
    ) = ApiError.Problem(status, code, null, emptyList())

    @Test
    fun networkIsUnknown() {
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.Network(IOException("caiu"))))
    }

    @Test
    fun incompatibleResponseIsUnknown() {
        val failure = ApiError.IncompatibleResponse(IllegalStateException("formato"))
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(failure))
    }

    @Test
    fun sessionEndedIsUnknown() {
        assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.SessionEnded(null)))
    }

    @Test
    fun keyInUseIsUnknownWhateverTheStatus() {
        for (status in listOf(409, 422, 500, 503)) {
            val failure = problem(status, "IDEMPOTENCY_KEY_IN_USE")
            assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(failure), "status $status")
        }
    }

    @Test
    fun problem5xxIsStored() {
        for (failure in listOf(problem(500, "INTERNAL_ERROR"), problem(503, null))) {
            assertEquals(WriteFailure.UNKNOWN_STORED, classifyWriteFailure(failure), "status ${failure.status}")
        }
    }

    @Test
    fun unexpected5xxIsUnknownNotStored() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals(WriteFailure.UNKNOWN, classifyWriteFailure(ApiError.Unexpected(status)), "status $status")
        }
    }

    @Test
    fun problem4xxIsDefinitive() {
        val failures =
            listOf(
                problem(409, "ROOM_NOT_READY"),
                problem(422, "IDEMPOTENCY_KEY_REUSED"),
                problem(422, null),
                problem(499, "SOMETHING_NEW"),
            )
        for (failure in failures) {
            assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(failure), "${failure.status} ${failure.code}")
        }
    }

    @Test
    fun approvalRequiredIsDefinitive() {
        val failure = ApiError.ApprovalRequired("DISCOUNT", "folio.discount", "folio/1", null, null)
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(failure))
    }

    @Test
    fun unexpected4xxIsDefinitive() {
        for (status in listOf(400, 418, 499)) {
            assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(ApiError.Unexpected(status)), "status $status")
        }
    }

    @Test
    fun preconditionFailedIsDefinitive() {
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(ApiError.PreconditionFailed(null)))
    }

    @Test
    fun passwordChangeRequiredIsDefinitive() {
        assertEquals(WriteFailure.DEFINITIVE, classifyWriteFailure(ApiError.PasswordChangeRequired))
    }
}
