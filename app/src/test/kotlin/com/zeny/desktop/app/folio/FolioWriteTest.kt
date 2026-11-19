package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val UNKNOWN_TEXT = "Não foi possível confirmar. Repita sem mudar nada."
private const val STORED_TEXT = "O servidor não confirmou. Feche e confira."
private const val CANCELLED_TEXT = "Aprovação cancelada."

/** O ajudante comum de escrita da conta ([FolioWrite]), sem formulário em volta. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do contrato do ajudante
class FolioWriteTest {
    private val intents = mutableListOf<Intent>()
    private val done = mutableListOf<String>()
    private val refused = mutableListOf<Pair<String, String?>>()
    private val outcomes = ArrayDeque<() -> WriteOutcome<String>>()

    private fun TestScope.write(openShift: (() -> Unit)? = null): FolioWrite {
        lateinit var write: FolioWrite
        write =
            FolioWrite(backgroundScope, UNKNOWN_TEXT, STORED_TEXT, openShift) { code ->
                refused += code to write.message
            }
        return write
    }

    private fun FolioWrite.sendNext() =
        send({ intent ->
            intents += intent
            outcomes.removeFirst().invoke()
        }) { done += it }

    private fun TestScope.sendThrowing(
        write: FolioWrite,
        failure: Exception,
    ) {
        outcomes += { throw failure }
        write.sendNext()
        runCurrent()
    }

    private fun TestScope.sendDone(write: FolioWrite) {
        outcomes += { WriteOutcome.Done("ok") }
        write.sendNext()
        runCurrent()
    }

    private fun problem(
        status: Int,
        code: String,
    ) = ApiError.Problem(status, code, null, emptyList())

    @Test
    fun unknownKeepsTheIntent() =
        runTest {
            val write = write()

            sendThrowing(write, ApiError.Network(IOException("caiu")))
            assertTrue(write.unconfirmed)
            assertFalse(write.closeOnly)
            assertFalse(write.busy)
            assertTrue(write.locked)
            assertTrue(write.canSend, "repetir é a mesma intenção")
            assertFalse(write.closesOnEscape)
            assertEquals(UNKNOWN_TEXT, write.message)

            sendThrowing(write, IOException("fora do ApiError conta como rede"))
            assertTrue(write.unconfirmed)
            assertEquals(UNKNOWN_TEXT, write.message)

            sendDone(write)
            assertEquals(3, intents.size)
            assertSame(intents[0], intents[1])
            assertSame(intents[0], intents[2])
            assertFalse(write.unconfirmed)
            assertFalse(write.locked)
            assertEquals(listOf("ok"), done)

            sendDone(write)
            assertNotSame(intents[2], intents[3], "o sucesso encerra a intenção")
            assertTrue(refused.isEmpty())
        }

    @Test
    fun storedIsCloseOnly() =
        runTest {
            val write = write()

            sendThrowing(write, problem(500, "INTERNAL_ERROR"))

            assertTrue(write.unconfirmed)
            assertTrue(write.closeOnly)
            assertTrue(write.locked)
            assertFalse(write.canSend)
            assertTrue(write.closesOnEscape)
            assertEquals(STORED_TEXT, write.message)

            write.sendNext()
            runCurrent()
            assertEquals(1, intents.size, "com a resposta guardada, nada mais vai à rede")
            assertTrue(refused.isEmpty())
        }

    @Test
    fun definitiveDropsTheIntent() =
        runTest {
            val write = write()
            val failure = problem(422, "ALGUMA_REGRA")

            sendThrowing(write, failure)

            assertFalse(write.unconfirmed)
            assertFalse(write.closeOnly)
            assertFalse(write.locked)
            assertTrue(write.closesOnEscape)
            assertEquals(failure.userMessage, write.message)

            sendDone(write)
            assertNotSame(intents[0], intents[1])
            assertEquals(listOf("ok"), done)
        }

    @Test
    fun refusedCodeCallsTheHookOnlyWhenDefinitive() =
        runTest {
            val stored = write()
            sendThrowing(stored, problem(500, "FOLIO_CLOSED"))
            val inUse = write()
            sendThrowing(inUse, problem(409, "IDEMPOTENCY_KEY_IN_USE"))
            assertTrue(refused.isEmpty(), "resultado desconhecido não é recusa")
            assertTrue(inUse.unconfirmed)

            val definitive = write()
            val failure = problem(409, "FOLIO_CLOSED")
            sendThrowing(definitive, failure)

            assertEquals(
                listOf<Pair<String, String?>>("FOLIO_CLOSED" to failure.userMessage),
                refused,
                "o gancho vem depois do texto",
            )
        }

    @Test
    fun shiftRefusalOffersTheShortcutOnlyWithIt() =
        runTest {
            var opened = 0
            val shortcut = write { opened++ }
            val failure = problem(422, "CASHIER_SHIFT_REQUIRED")

            sendThrowing(shortcut, failure)
            assertTrue(shortcut.offersOpenShift)
            assertEquals(failure.userMessage, shortcut.message)

            sendDone(shortcut)
            assertFalse(shortcut.offersOpenShift, "um envio novo tira o atalho")
            assertEquals(0, opened, "o ajudante não abre o turno sozinho")

            val textOnly = write()
            sendThrowing(textOnly, failure)
            assertFalse(textOnly.offersOpenShift)
            assertEquals(failure.userMessage, textOnly.message)
        }

    @Test
    fun cancelledKeepsTheKeyAndUnlocks() =
        runTest {
            val write = write()
            sendThrowing(write, ApiError.Network(IOException("caiu")))
            assertTrue(write.unconfirmed)

            outcomes += { WriteOutcome.Cancelled(CANCELLED_TEXT) }
            write.sendNext()
            runCurrent()

            assertFalse(write.unconfirmed)
            assertFalse(write.busy)
            assertFalse(write.locked)
            assertEquals(CANCELLED_TEXT, write.message)
            assertTrue(done.isEmpty())

            sendDone(write)
            assertSame(intents[0], intents[1])
            assertSame(intents[0], intents[2], "a aprovação cancelada mantém a chave")
        }

    @Test
    fun editAfterUnlockRotatesTheKey() =
        runTest {
            val write = write()
            sendThrowing(write, ApiError.Network(IOException("caiu")))
            write.edited()
            outcomes += { WriteOutcome.Cancelled(CANCELLED_TEXT) }
            write.sendNext()
            runCurrent()
            assertSame(intents[0], intents[1], "editar travado não muda a intenção")

            write.edited()
            sendDone(write)

            assertNotSame(intents[1], intents[2], "a edição destravada é outra intenção")
        }

    @Test
    fun sendWhileBusyIsIgnored() =
        runTest {
            val write = write()
            val answer = CompletableDeferred<String>()
            write.send({ intent ->
                intents += intent
                WriteOutcome.Done(answer.await())
            }) { done += it }
            runCurrent()
            assertTrue(write.busy)

            write.sendNext()
            runCurrent()
            answer.complete("ok")
            runCurrent()

            assertEquals(1, intents.size)
            assertEquals(listOf("ok"), done)
            assertFalse(write.busy)
        }

    @Test
    fun lateAnswerAfterCloseIsDropped() =
        runTest {
            val write = write()
            val answer = CompletableDeferred<String>()
            write.send({ intent ->
                intents += intent
                WriteOutcome.Done(answer.await())
            }) { done += it }
            runCurrent()

            write.close()
            answer.complete("tarde")
            runCurrent()

            assertTrue(write.closed)
            assertTrue(write.locked)
            assertFalse(write.canSend)
            assertTrue(done.isEmpty())
            assertFalse(write.unconfirmed)
            assertNull(write.message)

            write.sendNext()
            runCurrent()
            assertEquals(1, intents.size, "depois de fechado, nenhum envio roda")
        }
}
