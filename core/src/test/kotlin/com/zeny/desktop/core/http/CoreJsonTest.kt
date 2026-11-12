package com.zeny.desktop.core.http

import com.zeny.desktop.sdk.model.CreateGuestRequest
import com.zeny.desktop.sdk.model.TokenResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CoreJsonTest {
    @Test
    fun ignoresUnknownKeysAndOmitsDefaults() {
        val token =
            CoreJson.decodeFromString<TokenResponse>(
                """
                {"accessToken":"a","accessTokenExpiresAt":"2026-11-11T10:00:00Z","passwordChangeRequired":false,
                 "refreshToken":"r","refreshTokenExpiresAt":"2026-11-12T10:00:00Z",
                 "sessionId":"11111111-1111-1111-1111-111111111111","tokenType":"Bearer",
                 "userId":"22222222-2222-2222-2222-222222222222","novaPropriedade":42}
                """.trimIndent(),
            )
        assertEquals("a", token.accessToken)

        val encoded = CoreJson.encodeToString(CreateGuestRequest(documents = emptyList(), profile = null))
        val obj: JsonObject = CoreJson.parseToJsonElement(encoded).jsonObject
        assertFalse(obj.containsKey("consents"), encoded)
        assertFalse(obj.containsKey("sensitiveData"), encoded)
    }
}
