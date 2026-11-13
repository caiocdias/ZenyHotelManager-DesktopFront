package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ReconnectPolicyAtaqueTest {
    private fun problem(status: Int) = ApiError.Problem(status, null, null, emptyList())

    // ---- tabela de status ----

    @Test
    fun everyStatusFrom500To599Retries() {
        for (status in 500..599) {
            assertEquals(StreamOutcome.Retry, StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun commonClientErrorsOtherThan401408429Fail() {
        for (status in listOf(400, 402, 403, 404, 405, 406, 409, 410, 412, 413, 415, 418, 422, 423, 428, 431, 451)) {
            assertIs<StreamOutcome.Fail>(StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun allOther4xxFail() {
        for (status in 400..499) {
            if (status == 401 || status == 408 || status == 429) continue
            assertIs<StreamOutcome.Fail>(StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun redirectsFail() {
        for (status in 300..308) {
            assertIs<StreamOutcome.Fail>(StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun unexpectedInformationalAndSuccessStatusesFail() {
        for (status in listOf(100, 101, 102, 200, 201, 204, 206)) {
            assertIs<StreamOutcome.Fail>(StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun outOfRangeStatusesBelow500DoNotRetryOrStop() {
        for (status in listOf(0, 99, -1, -500, -401, Int.MIN_VALUE)) {
            assertIs<StreamOutcome.Fail>(StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun statusAbove599IsTreatedAsServerFailureAndRetries() {
        // Caracterização: o corte é "status >= 500", sem teto.
        for (status in listOf(600, 999, Int.MAX_VALUE)) {
            assertEquals(StreamOutcome.Retry, StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun synthesizedFailCarriesTheStatusAndNoCode() {
        val fail = assertIs<StreamOutcome.Fail>(StreamFailures.classify(404, null))
        val error = assertIs<ApiError.Problem>(fail.error)
        assertEquals(404, error.status)
        assertEquals(null, error.code)
    }

    @Test
    fun failKeepsTheGivenErrorInstanceWhatEverItsType() {
        val errors =
            listOf<ApiError>(
                problem(409),
                ApiError.PreconditionFailed("x"),
                ApiError.PasswordChangeRequired,
                ApiError.Unexpected(418),
            )
        for (error in errors) {
            val outcome = assertIs<StreamOutcome.Fail>(StreamFailures.classify(400, error))
            assertSame(error, outcome.error)
        }
    }

    // ---- precedência entre status e error ----

    @Test
    fun sessionEndedWinsOverRetryableStatus() {
        for (status in listOf(500, 503, 408, 429, 600)) {
            val outcome = StreamFailures.classify(status, ApiError.SessionEnded("X"))
            assertEquals(StreamOutcome.Stop, outcome, "status $status")
        }
    }

    @Test
    fun sessionEndedWinsOverFailStatuses() {
        for (status in listOf(0, 200, 301, 400, 404, -1)) {
            val outcome = StreamFailures.classify(status, ApiError.SessionEnded(null))
            assertEquals(StreamOutcome.Stop, outcome, "status $status")
        }
    }

    @Test
    fun status401StopsEvenWithAnotherErrorAttached() {
        assertEquals(StreamOutcome.Stop, StreamFailures.classify(401, problem(401)))
        assertEquals(StreamOutcome.Stop, StreamFailures.classify(401, ApiError.Network(IOException("x"))))
    }

    @Test
    fun retryableStatusIgnoresAttachedProblem() {
        assertEquals(StreamOutcome.Retry, StreamFailures.classify(503, problem(503)))
        assertEquals(StreamOutcome.Retry, StreamFailures.classify(429, problem(429)))
    }

    // ---- Throwables ----

    @Test
    fun ioExceptionSubclassesRetry() {
        val causes =
            listOf(
                EOFException("fim do corpo"),
                SocketTimeoutException("watchdog"),
                ConnectException("recusada"),
                UnknownHostException("dns"),
                IOException("x"),
            )
        for (cause in causes) {
            assertEquals(StreamOutcome.Retry, StreamFailures.classify(cause), cause::class.simpleName)
        }
    }

    @Test
    fun timeoutCancellationExceptionPropagatesInsteadOfBeingClassified() {
        val timeout =
            runBlocking {
                assertFailsWith<TimeoutCancellationException> { withTimeout(1) { awaitCancellation() } }
            }
        val thrown = assertFailsWith<TimeoutCancellationException> { StreamFailures.classify(timeout) }
        assertSame(timeout, thrown)
    }

    @Test
    fun cancellationSubclassIsRethrownSameInstance() {
        val cancel = object : CancellationException("sub") {}
        val thrown = assertFailsWith<CancellationException> { StreamFailures.classify(cancel) }
        assertSame(cancel, thrown)
    }

    @Test
    fun apiErrorsOtherThanSessionEndedFailWithTheSameInstance() {
        val errors =
            listOf<ApiError>(
                problem(500),
                ApiError.PreconditionFailed(null),
                ApiError.PasswordChangeRequired,
                ApiError.IncompatibleResponse(IllegalStateException("x")),
                ApiError.Unexpected(502),
                ApiError.ApprovalRequired("a", "p", "r", null, null),
            )
        for (error in errors) {
            assertSame(error, assertIs<StreamOutcome.Fail>(StreamFailures.classify(error)).error)
        }
    }

    @Test
    fun runtimeExceptionFailsWrappedAsNetworkKeepingTheCause() {
        val boom = IllegalStateException("boom")
        val error = assertIs<StreamOutcome.Fail>(StreamFailures.classify(boom)).error
        assertIs<ApiError.Network>(error)
        assertSame(boom, error.cause)
    }

    @Test
    fun wrappedFailsAreDistinctPerCall() {
        val boom = RuntimeException("x")
        val a = assertIs<StreamOutcome.Fail>(StreamFailures.classify(boom)).error
        val b = assertIs<StreamOutcome.Fail>(StreamFailures.classify(boom)).error
        assertNotSame(a, b)
    }

    /**
     * O ApiExecutor converte IOException em ApiError.Network. Se o stream usar o executor,
     * a queda de rede chega aqui como ApiError.Network(IOException) e a política desiste
     * em vez de reconectar. O contrato diz que IOException/queda de rede => Retry.
     */
    @Test
    fun networkErrorWrappingIoExceptionRetries() {
        assertEquals(StreamOutcome.Retry, StreamFailures.classify(ApiError.Network(ConnectException("recusada"))))
    }

    // ---- sequência de espera ----

    @Test
    fun delayStaysAtCapForManyIterationsWithoutOverflow() {
        val policy = ReconnectPolicy()
        repeat(10) { policy.nextDelayMillis() }
        repeat(100_000) { i -> assertEquals(30_000L, policy.nextDelayMillis(), "iteração $i") }
    }

    @Test
    fun delaysAreMonotonicAndPositive() {
        val policy = ReconnectPolicy()
        var previous = 0L
        repeat(1_000) {
            val d = policy.nextDelayMillis()
            assertEquals(true, d >= previous && d in 1..30_000, "d=$d previous=$previous")
            previous = d
        }
    }

    @Test
    fun resetRightAfterCreationAndRepeatedResetsAreHarmless() {
        val policy = ReconnectPolicy()
        policy.reset()
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMillis())
    }

    @Test
    fun resetAtCapRestartsFromOneSecond() {
        val policy = ReconnectPolicy()
        repeat(50) { policy.nextDelayMillis() }
        policy.reset()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(6) { policy.nextDelayMillis() })
    }

    @Test
    fun resetInTheMiddleOfEachStepRestarts() {
        for (steps in 1..8) {
            val policy = ReconnectPolicy()
            repeat(steps) { policy.nextDelayMillis() }
            policy.reset()
            assertEquals(1_000L, policy.nextDelayMillis(), "após $steps passos")
        }
    }

    @Test
    fun instancesAreIndependent() {
        val a = ReconnectPolicy()
        val b = ReconnectPolicy()
        repeat(4) { a.nextDelayMillis() }
        assertEquals(1_000L, b.nextDelayMillis())
        assertEquals(16_000L, a.nextDelayMillis())
        a.reset()
        assertEquals(2_000L, b.nextDelayMillis())
        assertEquals(1_000L, a.nextDelayMillis())
    }
}
