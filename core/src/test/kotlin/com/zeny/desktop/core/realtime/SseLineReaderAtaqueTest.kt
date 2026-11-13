package com.zeny.desktop.core.realtime

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataque ao corte de linhas do stream: terminadores, BOM, UTF-8 partido, limites e *watchdog*. */
@OptIn(ExperimentalCoroutinesApi::class)
class SseLineReaderAtaqueTest {
    private val mib = 1024 * 1024

    /** Todas as linhas até o fim do corpo. */
    private suspend fun SseLineReader.drain(): List<String> {
        val lines = mutableListOf<String>()
        while (true) lines += readLine() ?: return lines
    }

    private fun linesOf(
        text: String,
        maxLineBytes: Int = mib,
    ): List<String> = linesOfBytes(text.toByteArray(Charsets.UTF_8), maxLineBytes)

    private fun linesOfBytes(
        bytes: ByteArray,
        maxLineBytes: Int = mib,
    ): List<String> {
        var result: List<String>? = null
        runTest { result = SseLineReader(ByteReadChannel(bytes), maxLineBytes).drain() }
        return assertNotNull(result)
    }

    /**
     * Entrega [parts] em blocos separados: cada parte só é escrita depois que o leitor consumiu a anterior.
     * Devolve as linhas lidas até o fim do corpo.
     */
    private fun TestScope.readInChunks(parts: List<ByteArray>): List<String> {
        val channel = ByteChannel(autoFlush = true)
        val reader = SseLineReader(channel)
        val lines = async { reader.drain() }
        runCurrent()
        for (part in parts) {
            launch { channel.writeFully(part) }
            runCurrent()
        }
        channel.close()
        runCurrent()
        assertTrue(lines.isCompleted, "o leitor terminou no fim do corpo")
        return lines.getCompleted()
    }

    private val mixedEndings = "a\r\nb\rc\n\r\nd\r\r\ne"
    private val mixedEndingsLines = listOf("a", "b", "c", "", "d", "")

    // ---- terminadores ----

    @Test
    fun mixedLineEndingsAreSplitPerSpecification() {
        // "e" sem terminador no fim do corpo é descartado.
        assertEquals(mixedEndingsLines, linesOf(mixedEndings))
    }

    @Test
    fun crlfAndCrSplitAtEveryPositionGiveSameLines() =
        runTest {
            val bytes = mixedEndings.toByteArray(Charsets.UTF_8)
            for (cut in 0..bytes.size) {
                val parts = listOf(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size))
                assertEquals(mixedEndingsLines, readInChunks(parts), "corte na posição $cut")
            }
        }

    @Test
    fun oneByteAtATimeGivesSameLines() =
        runTest {
            val bytes = mixedEndings.toByteArray(Charsets.UTF_8)
            assertEquals(mixedEndingsLines, readInChunks(bytes.map { byteArrayOf(it) }))
        }

    @Test
    fun crAtEndOfChunkDoesNotSwallowFirstByteOfNextLine() =
        runTest {
            val parts = listOf("a\r", "b\n", "\r", "\r", "\n", "c\n").map { it.toByteArray() }
            assertEquals(listOf("a", "b", "", "", "c"), readInChunks(parts))
        }

    @Test
    fun crAsLastByteOfBodyEndsTheLine() {
        assertEquals(listOf("a"), linesOf("a\r"))
    }

    @Test
    fun crReturnsTheLineWithoutWaitingForNextByte() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel)
            val first = async { reader.readLine() }
            channel.writeFully("ready\r".toByteArray())
            runCurrent()
            assertTrue(first.isCompleted, "um CR sozinho termina a linha sem esperar o próximo bloco")
            assertEquals("ready", first.getCompleted())
            channel.close()
        }

    @Test
    fun incompleteLastLineIsDiscarded() {
        assertEquals(listOf("data: a"), linesOf("data: a\ndata: b"))
    }

    @Test
    fun emptyBodyHasNoLines() {
        assertEquals(emptyList(), linesOf(""))
    }

    // ---- BOM ----

    @Test
    fun byteOrderMarkIsStrippedOnlyFromStartOfBody() {
        assertEquals(listOf("a", "\uFEFFb"), linesOf("\uFEFFa\n\uFEFFb\n"))
    }

    @Test
    fun onlyOneLeadingByteOrderMarkIsStripped() {
        assertEquals(listOf("\uFEFFa"), linesOf("\uFEFF\uFEFFa\n"))
    }

    @Test
    fun byteOrderMarkAloneOnFirstLineLeavesEmptyLine() {
        assertEquals(listOf("", "x"), linesOf("\uFEFF\nx\n"))
    }

    @Test
    fun byteOrderMarkSplitAcrossChunksIsStripped() =
        runTest {
            val bom = "\uFEFF".toByteArray(Charsets.UTF_8)
            val parts = listOf(bom.copyOfRange(0, 1), bom.copyOfRange(1, 3) + "a\n".toByteArray())
            assertEquals(listOf("a"), readInChunks(parts))
        }

    @Test
    fun byteOrderMarkInFirstLineMiddleIsKept() {
        assertEquals(listOf("a\uFEFF"), linesOf("a\uFEFF\n"))
    }

    // ---- UTF-8 ----

    @Test
    fun multibyteCharactersSplitByteByByteAreDecoded() =
        runTest {
            val text = "id: quarto-çã-€-\uD83D\uDE00\n"
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertEquals(listOf("id: quarto-çã-€-\uD83D\uDE00"), readInChunks(bytes.map { byteArrayOf(it) }))
        }

    @Test
    fun multibyteCharacterAcrossInternalBufferBoundaryIsDecoded() {
        // O leitor lê em blocos de 8 KiB: o "€" (3 bytes) cai sobre a fronteira de bloco.
        val text = "x".repeat(8 * 1024 - 1) + "€" + "\n"
        assertEquals(listOf("x".repeat(8 * 1024 - 1) + "€"), linesOf(text))
    }

    // ---- limite de linha ----

    @Test
    fun lineOfExactlyOneMebibyteIsAccepted() {
        val line = "x".repeat(mib)
        assertEquals(listOf(line, "y"), linesOf(line + "\ny\n"))
    }

    @Test
    fun lineOfOneMebibytePlusOneIsRejected() =
        runTest {
            val reader = SseLineReader(ByteReadChannel("x".repeat(mib + 1) + "\n"))
            assertFailsWith<StreamInterruptedException> { reader.readLine() }
        }

    @Test
    fun lineLimitIgnoresTheTerminator() {
        assertEquals(listOf("12345678", "12345678"), linesOf("12345678\r\n12345678\r", maxLineBytes = 8))
    }

    @Test
    fun lineLimitCountsBytesNotCharacters() {
        assertEquals(listOf("éé"), linesOf("éé\n", maxLineBytes = 4))
        runTest {
            val reader = SseLineReader(ByteReadChannel("ééa\n"), maxLineBytes = 4)
            assertFailsWith<StreamInterruptedException> { reader.readLine() }
        }
    }

    @Test
    fun lineLimitIsPerLineAcrossChunks() =
        runTest {
            val parts = listOf("1234", "5678\n1234", "5678\n").map { it.toByteArray() }
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel, maxLineBytes = 8)
            val lines = async { reader.drain() }
            runCurrent()
            for (part in parts) {
                launch { channel.writeFully(part) }
                runCurrent()
            }
            channel.close()
            runCurrent()
            assertEquals(listOf("12345678", "12345678"), lines.getCompleted())
        }

    @Test
    fun oversizedLineErrorDoesNotCarryThePayload() =
        runTest {
            val reader = SseLineReader(ByteReadChannel("data: segredo-do-payload\n"), maxLineBytes = 8)
            val error = assertFailsWith<StreamInterruptedException> { reader.readLine() }
            assertFalse("segredo" in error.stackTraceToString())
        }

    // ---- watchdog ----

    @Test
    fun lineArrivingJustBeforeWatchdogIsRead() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel)
            val line = async { reader.nextLineWithin(45_000) }
            advanceTimeBy(44_999)
            assertFalse(line.isCompleted)
            channel.writeFully(": heartbeat\n".toByteArray())
            runCurrent()
            assertEquals(": heartbeat", line.getCompleted())
            channel.close()
        }

    @Test
    fun silenceBeyondWatchdogBecomesStreamInterruption() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel)
            val line = async { runCatching { reader.nextLineWithin(45_000) } }
            advanceTimeBy(45_001)
            val error = assertNotNull(line.getCompleted().exceptionOrNull())
            assertIs<StreamInterruptedException>(error)
            assertIs<TimeoutCancellationException>(error.cause)
        }

    @Test
    fun partialLineDoesNotRestartTheWatchdog() =
        runTest {
            // O relógio é por linha: bytes soltos sem terminador não contam como linha.
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel)
            val line = async { runCatching { reader.nextLineWithin(45_000) } }
            repeat(4) {
                advanceTimeBy(15_000)
                channel.writeFully("x".toByteArray())
            }
            runCurrent()
            assertIs<StreamInterruptedException>(line.getCompleted().exceptionOrNull())
        }

    @Test
    fun endOfBodyBecomesStreamInterruption() =
        runTest {
            val reader = SseLineReader(ByteReadChannel("a\n"))
            assertEquals("a", reader.nextLineWithin(45_000))
            assertFailsWith<StreamInterruptedException> { reader.nextLineWithin(45_000) }
        }

    @Test
    fun outerCancellationIsNotTurnedIntoStreamInterruption() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel)
            var caught: Throwable? = null
            val job =
                launch {
                    try {
                        reader.nextLineWithin(45_000)
                    } catch (e: Throwable) {
                        caught = e
                        throw e
                    }
                }
            advanceTimeBy(10_000)
            job.cancel()
            runCurrent()
            val error = assertNotNull(caught)
            assertIs<CancellationException>(error)
            assertFalse(error is TimeoutCancellationException, "cancelamento de fora não é o watchdog")
            assertTrue(job.isCancelled)
        }

    @Test
    fun channelClosedWithErrorPropagatesAsIoFailure() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val reader = SseLineReader(channel)
            val line = async { runCatching { reader.readLine() } }
            runCurrent()
            channel.close(IOException("conexão caiu"))
            runCurrent()
            assertIs<IOException>(line.getCompleted().exceptionOrNull())
        }

    @Test
    fun readLineAfterEndKeepsReturningNull() =
        runTest {
            val reader = SseLineReader(ByteReadChannel("a\n"))
            assertEquals("a", reader.readLine())
            assertNull(reader.readLine())
            assertNull(reader.readLine())
        }
}
