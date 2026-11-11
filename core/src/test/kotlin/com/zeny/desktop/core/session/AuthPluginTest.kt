package com.zeny.desktop.core.session

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ProblemMapper
import com.zeny.desktop.core.http.CredentialCheck
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.client.MetaApi
import com.zeny.desktop.sdk.model.ChangePasswordRequest
import com.zeny.desktop.sdk.model.DeviceRequest
import com.zeny.desktop.sdk.model.LoginRequest
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// O refresh roda em threads reais (Dispatchers.Default), como no escopo do core.
@Suppress("InjectDispatcher")
class AuthPluginTest {
    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val hotelJson =
        """{"configured":true,"id":"$id","timezone":"America/Sao_Paulo","updatedAt":"2026-11-10T13:00:00Z"}"""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val refresher = RotatingRefresher()
    private val ended = CopyOnWriteArrayList<String?>()
    private val auth = Authenticator(store, refresher, scope) { ended += it }
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClientOgen =
        ogenBase(
            createCoreHttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ) { install(AuthPlugin) { authenticator = { auth } } },
            "http://servidor.teste",
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(code: String) =
        json("""{"code":"$code","detail":"detalhe"}""", HttpStatusCode.Unauthorized)

    private val HttpRequestData.authorization: List<String>? get() = headers.getAll(HttpHeaders.Authorization)

    @Test
    fun bearerOnlyOnSecuredOperations() =
        runTest(timeout = 5.seconds) {
            val base = api { json("{}") }

            HotelApi(base).getHotel()
            IamApi(base).login(LoginRequest(DeviceRequest("dev-1", "Recepção"), "ana", "senha-longa-1"))
            MetaApi(base).getServerVersion()

            assertEquals(listOf("Bearer A1"), requests[0].authorization)
            assertNull(requests[1].authorization)
            assertNull(requests[2].authorization)
        }

    @Test
    fun noSessionSendsNoAuthorization() =
        runTest(timeout = 5.seconds) {
            store.clear()
            val hotel = HotelApi(api { problem("UNAUTHENTICATED") })

            val response = hotel.getHotel()

            assertEquals(401, response.raw.status.value)
            assertNull(requests.single().authorization)
            assertEquals(0, refresher.calls)
        }

    @Test
    fun unauthenticatedRefreshesAndRetries() =
        runTest(timeout = 5.seconds) {
            val hotel =
                HotelApi(
                    api { request ->
                        val refreshed = request.authorization == listOf("Bearer A2")
                        if (refreshed) json(hotelJson) else problem("UNAUTHENTICATED")
                    },
                )

            val response = hotel.getHotel()

            assertEquals(200, response.raw.status.value)
            assertEquals(1, refresher.calls)
            assertEquals(2, requests.size)
            assertEquals(listOf("Bearer A1"), requests[0].authorization)
            assertEquals(listOf("Bearer A2"), requests[1].authorization)
            assertEquals(id, response.body().id.toString())
            assertTrue(ended.isEmpty())
        }

    @Test
    fun otherUnauthorizedEndsSessionWithoutRetry() =
        runTest(timeout = 5.seconds) {
            for (code in listOf("ACCOUNT_DISABLED", "SOMETHING_NEW")) {
                store.set(Tokens("A1", "R1", "s1", "u1", false))
                ended.clear()
                requests.clear()
                val hotel = HotelApi(api { problem(code) })

                val response = hotel.getHotel()

                assertEquals(listOf<String?>(code), ended.toList())
                assertEquals(1, requests.size)
                assertEquals(0, refresher.calls)
                assertNull(store.current())
                val error = assertIs<ApiError.Problem>(ProblemMapper.fromResponse(response.raw))
                assertEquals(code, error.code)
                assertEquals("detalhe", error.detail)
            }
        }

    @Test
    fun credentialCheckUnauthorizedKeepsSession() =
        runTest(timeout = 5.seconds) {
            for (code in listOf("INVALID_CREDENTIALS", "ACCOUNT_LOCKED")) {
                requests.clear()
                val iam = IamApi(api { problem(code) })

                val response =
                    iam.changePassword(ChangePasswordRequest("antiga-senha-1", "nova-senha-longa-1")) {
                        attributes.put(CredentialCheck, true)
                    }

                assertEquals(401, response.raw.status.value)
                assertEquals(1, requests.size)
                assertTrue(ended.isEmpty())
                assertEquals("A1", store.current()?.accessToken)
                assertEquals(0, refresher.calls)
                val error = assertIs<ApiError.Problem>(ProblemMapper.fromResponse(response.raw))
                assertEquals(code, error.code)
            }
        }

    @Test
    fun retryStillUnauthorizedEndsSession() =
        runTest(timeout = 5.seconds) {
            val hotel = HotelApi(api { problem("UNAUTHENTICATED") })

            val response = hotel.getHotel()

            assertEquals(401, response.raw.status.value)
            assertEquals(2, requests.size)
            assertEquals(listOf("Bearer A2"), requests[1].authorization)
            assertEquals(1, refresher.calls)
            assertEquals(listOf<String?>("UNAUTHENTICATED"), ended.toList())
            assertNull(store.current())
        }
}
