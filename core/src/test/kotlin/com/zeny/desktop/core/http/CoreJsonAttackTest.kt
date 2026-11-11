package com.zeny.desktop.core.http

import com.zeny.desktop.sdk.model.CreateGuestRequest
import com.zeny.desktop.sdk.model.TokenResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreJsonAttackTest {
    private val token =
        """{"accessToken":"a","accessTokenExpiresAt":"2026-11-11T10:00:00Z","passwordChangeRequired":false,
        "refreshToken":"r","refreshTokenExpiresAt":"2026-11-12T10:00:00Z",
        "sessionId":"11111111-1111-1111-1111-111111111111","tokenType":"Bearer",
        "userId":"22222222-2222-2222-2222-222222222222"%s}"""

    @Test
    fun ignoresUnknownKeysWithNestedObjectsAndArrays() {
        val decoded =
            CoreJson.decodeFromString<TokenResponse>(
                token.format(Locale.ROOT, ""","novo":{"a":[1,{"b":null}]},"lista":[[1],[2]]"""),
            )
        assertEquals("a", decoded.accessToken)
        assertEquals("r", decoded.refreshToken)
    }

    @Test
    fun stillFailsWhenRequiredFieldIsMissing() {
        assertFailsWith<Exception> { CoreJson.decodeFromString<TokenResponse>("""{"accessToken":"a"}""") }
    }

    @Test
    fun encodedRequestDoesNotCarryUnsetOptionals() {
        val json = CoreJson.encodeToString(CreateGuestRequest(documents = emptyList(), profile = null))
        val encoded = CoreJson.parseToJsonElement(json).jsonObject
        assertTrue(encoded.keys.none { it == "consents" || it == "sensitiveData" }, encoded.toString())
    }
}
