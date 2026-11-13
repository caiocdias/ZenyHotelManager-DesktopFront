package com.zeny.desktop.app.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.TimeSource

/** O corpo da resposta (o do login traz o `accessToken`) nunca vai para a mensagem de erro da verificação ao vivo. */
class CurlResponseTest {
    private fun response(body: String) = CurlResponse(200, emptyMap(), body, TimeSource.Monotonic.markNow())

    @Test
    fun objectBodyParses() {
        assertEquals("tok", response("""{"accessToken":"tok"}""").json()["accessToken"].toString().trim('"'))
    }

    @Test
    fun malformedBodyFailsWithoutQuotingIt() {
        for (body in listOf("""{"accessToken":"segredo-123""", "\"segredo-123\"", "segredo-123")) {
            val failure = assertFailsWith<IllegalStateException> { response(body).json() }
            assertEquals("resposta do servidor não é um objeto JSON", failure.message)
            assertNull(failure.cause, "a causa citaria o corpo")
            assertFalse(failure.stackTraceToString().contains("segredo"), "o corpo vazou no erro")
        }
    }
}
