package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ReconnectPolicyTest {
    @Test
    fun delaysGrowAndCap() {
        val policy = ReconnectPolicy()
        val delays = List(8) { policy.nextDelayMillis() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun resetStartsOver() {
        val policy = ReconnectPolicy()
        repeat(4) { policy.nextDelayMillis() }
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMillis())
        assertEquals(2_000L, policy.nextDelayMillis())
    }

    @Test
    fun serverErrorsRetry() {
        for (status in listOf(500, 502, 503, 504, 408, 429)) {
            assertEquals(StreamOutcome.Retry, StreamFailures.classify(status, null), "status $status")
        }
    }

    @Test
    fun unauthorizedStops() {
        assertEquals(StreamOutcome.Stop, StreamFailures.classify(401, null))
    }

    @Test
    fun sessionEndedStops() {
        assertEquals(StreamOutcome.Stop, StreamFailures.classify(403, ApiError.SessionEnded("X")))
        assertEquals(StreamOutcome.Stop, StreamFailures.classify(ApiError.SessionEnded(null)))
    }

    @Test
    fun clientErrorsFail() {
        for (status in listOf(301, 400, 403, 404, 409)) {
            val outcome = StreamFailures.classify(status, null)
            assertIs<StreamOutcome.Fail>(outcome, "status $status")
        }
        val error = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
        val outcome = StreamFailures.classify(403, error)
        assertSame(error, assertIs<StreamOutcome.Fail>(outcome).error)
    }

    @Test
    fun ioExceptionRetries() {
        assertEquals(StreamOutcome.Retry, StreamFailures.classify(IOException("caiu")))
    }

    @Test
    fun cancellationIsNotClassified() {
        assertFailsWith<CancellationException> { StreamFailures.classify(CancellationException("fim")) }
    }
}
