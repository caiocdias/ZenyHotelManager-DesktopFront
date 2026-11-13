package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.cli.Secret
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val USER_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ROLE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val LOGIN =
    """{"accessToken":"A1","accessTokenExpiresAt":"2036-11-11T13:15:00Z","passwordChangeRequired":false,
       |"refreshToken":"R1","refreshTokenExpiresAt":"2036-12-11T13:00:00Z",
       |"sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
       |"userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}"""
private const val USER =
    """{"active":true,"administrator":false,"createdAt":"2026-01-01T00:00:00Z","hasPin":false,"id":"$USER_ID",
       |"login":"a","name":"Nome","passwordChangeRequired":false,"roles":[],"updatedAt":"2026-01-01T00:00:00Z"}"""

class SdkUserGatewayBlankEtagTest {
    @Test
    fun setRolesWithBlankEtagFailsBeforeWriting() =
        runTest {
            val methods = mutableListOf<HttpMethod>()
            val engine =
                MockEngine { request ->
                    val json = Headers.build { append(HttpHeaders.ContentType, "application/json") }
                    if (request.url.encodedPath.endsWith("/login")) {
                        respond(LOGIN.trimMargin(), headers = json)
                    } else {
                        methods += request.method
                        val blank = request.method == HttpMethod.Get
                        respond(
                            USER.trimMargin(),
                            headers =
                                Headers.build {
                                    append(HttpHeaders.ContentType, "application/json")
                                    if (blank) append(HttpHeaders.ETag, " ")
                                },
                        )
                    }
                }
            val session = SdkSessionFactory(checkNotNull(ServerUrl.parse("http://servidor.teste"))) { engine }.open()
            session.signIn("admin", Secret("Admin-Senha-000"))
            val gateway = SdkUserGateway(session.seed)
            val failure = assertFailsWith<ApiError.Problem> { gateway.setRoles(USER_ID, listOf(ROLE)) }
            assertEquals(428, failure.status)
            assertTrue(HttpMethod.Put !in methods, "PUT enviado com ETag em branco")
        }
}
