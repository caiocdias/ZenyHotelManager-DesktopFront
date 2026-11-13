package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.seed.data.GuestSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val GENDER_GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GENDER_STAMP = "2026-11-12T10:00:00Z"

/** O seed lê `GuestResponse` e monta `GuestProfileRequest`: o campo novo `genderDescription` não pode vazar. */
class SdkBookingGatewayGenderAtaqueTest {
    private val bodies = CopyOnWriteArrayList<String>()

    private fun guestWith(gender: String) =
        """{"createdAt":"$GENDER_STAMP","documents":[],"fullName":"Fulano","hasSensitiveData":false,"id":"$GENDER_GUEST",
        |"preferences":{},"status":"ACTIVE","updatedAt":"$GENDER_STAMP","vip":false$gender}
        """.trimMargin()

    private fun gatewayAnswering(responseBody: String) =
        SdkBookingGateway(
            SeedCore(
                checkNotNull(ServerUrl.parse("http://servidor.teste")),
                MockEngine { request ->
                    bodies += request.body.toByteArray().decodeToString()
                    respond(
                        responseBody,
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

    private val spec = GuestSpec("G1", "Fulano de Tal", LocalDate.of(1985, 3, 9), "52998224725", "11222333000181")

    @Test
    fun createGuestDoesNotSendGenderDescriptionNorGender() =
        runTest {
            gatewayAnswering(guestWith("")).createGuest(spec, null)

            val profile =
                Json
                    .parseToJsonElement(bodies.single())
                    .jsonObject
                    .getValue("profile")
                    .jsonObject
            assertFalse("genderDescription" in profile, "campo novo vazou no pedido: $profile")
            assertFalse("gender" in profile)
        }

    @Test
    fun createGuestAcceptsResponsesWithGenderDescriptionPresentNullOrAbsent() =
        runTest {
            for (gender in listOf(""","genderDescription":"Não binário"""", ""","genderDescription":null""", "")) {
                bodies.clear()

                assertEquals(GENDER_GUEST, gatewayAnswering(guestWith(gender)).createGuest(spec, null), gender)
            }
        }
}
