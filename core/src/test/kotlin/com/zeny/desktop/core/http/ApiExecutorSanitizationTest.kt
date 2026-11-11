package com.zeny.desktop.core.http

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.client.HotelApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A causa de `IncompatibleResponse` leva só o nome da classe da falha original, nunca o corpo da resposta. */
class ApiExecutorSanitizationTest {
    private val secret = "SEGREDO-do-corpo"
    private val executor = ApiExecutor()

    private fun hotel(
        body: String,
        contentType: String,
    ) = HotelApi(
        ogenBase(
            createCoreHttpClient(
                MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType)) },
            ),
            "http://servidor.teste",
        ),
    )

    private fun assertSanitized(
        error: ApiError.IncompatibleResponse,
        originalClass: String,
    ) {
        val cause = assertIs<ResponseFormatException>(error.cause)
        assertEquals(originalClass, cause.message)
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        for (link in chain) assertTrue(secret !in link.message.orEmpty(), "corpo em ${link::class.qualifiedName}")
        assertTrue(secret !in error.stackTraceToString(), "corpo no stack trace")
    }

    @Test
    fun malformedJsonCauseHasOnlyTheClassName() =
        runTest {
            val api = hotel("""{"configured":true,"id":"$secret""", "application/json")

            val error =
                assertFailsWith<ApiError.IncompatibleResponse> { executor.execute { api.getHotel(block = it.headers) } }

            assertSanitized(error, "io.ktor.serialization.JsonConvertException")
        }

    @Test
    fun bodyWithoutTransformationCauseHasOnlyTheClassName() =
        runTest {
            val api = hotel("<html>$secret</html>", "text/html")

            val error =
                assertFailsWith<ApiError.IncompatibleResponse> { executor.execute { api.getHotel(block = it.headers) } }

            assertSanitized(error, "io.ktor.client.call.NoTransformationFoundException")
        }

    @Test
    fun serializationFailureCauseHasOnlyTheClassName() =
        runTest {
            val error =
                assertFailsWith<ApiError.IncompatibleResponse> {
                    executor.execute<Unit> { throw SerializationException("JSON input: {\"token\":\"$secret\"}") }
                }

            assertSanitized(error, "kotlinx.serialization.SerializationException")
        }
}
