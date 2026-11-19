package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val UNKNOWN_TEXT = "Não foi possível confirmar. Repita sem mudar nada."
private const val STORED_TEXT = "O servidor não confirmou. Feche e confira."

/**
 * Ataque ao ajudante comum de escrita ([FolioWrite]): o `ensureActive` depois da escrita (a resposta que chega quando o
 * diálogo já fechou, com a escrita fora do alcance do cancelamento) e a intenção nas sequências que a revisão apontou.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioWriteAtaqueTest {
    private val done = mutableListOf<String>()
    private val refused = mutableListOf<String>()
    private val intents = mutableListOf<Intent>()

    private fun TestScope.write() = FolioWrite(backgroundScope, UNKNOWN_TEXT, STORED_TEXT, null) { refused += it }

    @Test
    fun successThatEndsAfterCloseIsDropped() =
        runTest {
            val write = write()

            // A escrita termina sem passar por um ponto de suspensão depois do close: só o ensureActive a barra.
            write.send({
                write.close()
                WriteOutcome.Done("ok")
            }) { done += it }
            runCurrent()

            assertTrue(done.isEmpty(), "a resposta depois do close não chega a quem enviou")
            assertFalse(write.unconfirmed)
            assertNull(write.message)
        }

    @Test
    fun failureThatEndsAfterCloseIsDropped() =
        runTest {
            val write = write()

            write.send<String>({
                write.close()
                throw ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
            }) { done += it }
            runCurrent()

            assertTrue(refused.isEmpty(), "a recusa depois do close não chama o gancho")
            assertNull(write.message, "nem escreve o texto no diálogo fechado")
            assertFalse(write.unconfirmed)
        }

    @Test
    fun networkFailureThatEndsAfterCloseIsDropped() =
        runTest {
            val write = write()

            write.send<String>({
                write.close()
                throw ApiError.Network(IOException("caiu"))
            }) { done += it }
            runCurrent()

            assertFalse(write.unconfirmed, "o resultado desconhecido depois do close não muda o estado")
            assertNull(write.message)
        }

    @Test
    fun nonCancellableWriteAnsweredAfterCloseIsDropped() =
        runTest {
            val gate = CompletableDeferred<String>()
            val write = write()
            write.send({
                WriteOutcome.Done(withContext(NonCancellable) { gate.await() })
            }) { done += it }
            runCurrent()
            assertTrue(write.busy)

            write.close()
            gate.complete("ok")
            runCurrent()

            assertTrue(done.isEmpty(), "a escrita não cancelável terminou, mas o diálogo fechou antes")
        }

    @Test
    fun unknownThenDefinitiveThenRetryIsANewIntent() =
        runTest {
            val write = write()
            val answers =
                ArrayDeque<() -> WriteOutcome<String>>().apply {
                    add { throw ApiError.Network(IOException("caiu")) }
                    add { throw ApiError.Problem(422, "PRE_AUTHORIZATION_SETTLED", null, emptyList()) }
                    add { WriteOutcome.Done("ok") }
                }
            repeat(3) { _ ->
                write.send({ intent ->
                    intents += intent
                    answers.removeFirst().invoke()
                }) { done += it }
                runCurrent()
            }

            val (first, retry, after) = intents
            assertSame(first, retry, "o desconhecido repete a mesma intenção")
            assertFalse(after === first, "depois da recusa definitiva, outra intenção")
            assertEquals(listOf("PRE_AUTHORIZATION_SETTLED"), refused)
            assertEquals(listOf("ok"), done)
        }

    @Test
    fun editWhileUnknownKeepsTheIntent() =
        runTest {
            val write = write()
            val answers =
                ArrayDeque<() -> WriteOutcome<String>>().apply {
                    add { throw ApiError.Network(IOException("caiu")) }
                    add { WriteOutcome.Done("ok") }
                }
            write.send({ intent ->
                intents += intent
                answers.removeFirst().invoke()
            }) { done += it }
            runCurrent()

            write.edited()
            write.send({ intent ->
                intents += intent
                answers.removeFirst().invoke()
            }) { done += it }
            runCurrent()

            assertSame(intents[0], intents[1], "com o resultado desconhecido, editar não troca a chave")
        }
}
