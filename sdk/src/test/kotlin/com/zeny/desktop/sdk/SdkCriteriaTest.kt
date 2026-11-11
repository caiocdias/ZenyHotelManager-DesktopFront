package com.zeny.desktop.sdk

import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.NightlyRateRequest
import com.zeny.desktop.sdk.model.RegisterPreAuthorizationRequest
import com.zeny.desktop.sdk.model.RoomMoveRequest
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Evidência permanente dos critérios do design §9 contra o `sdk` gerado (ADR 0007).
 * Tudo roda com um engine HTTP falso, sem rede.
 */
class SdkCriteriaTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<HttpRequestData>()

    private val idempotencyKey = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val someId = Uuid.parse(idempotencyKey)
    private val approvalToken = "approval-token-de-teste"

    private fun client(
        body: String,
        vararg headers: Pair<String, String>,
    ): HttpClientOgen {
        val engine =
            MockEngine { request ->
                requests += request
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        *headers.map { it.first to listOf(it.second) }.toTypedArray(),
                    ),
                )
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return HttpClientOgen.Base(http, "http://servidor.teste", {}, json)
    }

    private val hotelJson =
        """{"configured":true,"id":"$idempotencyKey","timezone":"America/Sao_Paulo","updatedAt":"2026-11-10T13:00:00Z"}"""

    private fun bookingJson(extra: String = "") =
        """{"channel":"TELEPATHY","code":"B-1","createdAt":"2026-11-10T13:00:00Z",""" +
            """"holderGuestId":"$idempotencyKey",""" +
            """"id":"$idempotencyKey","origin":"PIGEON","reservations":[],"restrictionAlerts":[],"version":1$extra}"""

    private val paymentJson =
        """{"amount":"10.50","businessDate":"2026-11-10","cashierShiftId":"$idempotencyKey","deposit":false,""" +
            """"folioId":"$idempotencyKey","id":"$idempotencyKey","method":"CASH",""" +
            """"receivedAt":"2026-11-10T13:00:00Z",""" +
            """"receivedBy":"$idempotencyKey","type":"PAYMENT"}"""

    private fun sentBody(request: HttpRequestData) = String((request.body as OutgoingContent.ByteArrayContent).bytes())

    @Test
    fun readsEtagFromResponse() =
        runBlocking {
            val response = HotelApi(client(hotelJson, "ETag" to "\"7\"")).getHotel()

            assertEquals("\"7\"", response.raw.headers[HttpHeaders.ETag])
        }

    // moveReservationRoom declara Idempotency-Key e If-Match; nenhuma operação do contrato declara os três.
    // O X-Approval-Token entra pelo block, que é como o core o injeta onde a operação não o declara.
    @Test
    fun sendsDeclaredIdempotencyKeyIfMatchAndApprovalToken() =
        runBlocking {
            FrontdeskApi(client("{}")).moveReservationRoom(
                id = someId,
                ifMatch = "\"7\"",
                idempotencyKey = idempotencyKey,
                roomMoveRequest = RoomMoveRequest(reason = null, roomId = someId),
            ) {
                headers["X-Approval-Token"] = approvalToken
            }

            val headers = requests.single().headers
            assertEquals(listOf(idempotencyKey), headers.getAll("Idempotency-Key"))
            assertEquals(listOf("\"7\""), headers.getAll("If-Match"))
            assertEquals(listOf(approvalToken), headers.getAll("X-Approval-Token"))
        }

    // getHotel não declara nenhum dos dois headers.
    @Test
    fun injectsHeadersIntoOperationThatDoesNotDeclareThem() =
        runBlocking {
            HotelApi(client(hotelJson)).getHotel {
                headers["X-Approval-Token"] = approvalToken
                headers["Idempotency-Key"] = idempotencyKey
            }

            val headers = requests.single().headers
            assertEquals(listOf(idempotencyKey), headers.getAll("Idempotency-Key"))
            assertEquals(listOf(approvalToken), headers.getAll("X-Approval-Token"))
        }

    @Test
    fun deserializesUnknownExtensibleEnumValue() =
        runBlocking {
            val booking = ReservationsApi(client(bookingJson())).getBooking(someId).body()

            val channel: String = booking.channel // não compila se o tipo gerado deixar de ser String

            assertEquals("TELEPATHY", channel)
        }

    @Test
    fun ignoresUnknownJsonProperty() =
        runBlocking {
            val booking = ReservationsApi(client(bookingJson(""","campoNovoDoServidor":1"""))).getBooking(someId).body()

            assertEquals("TELEPATHY", booking.channel)
        }

    // No sdk, dinheiro, data e instante são String (ADR 0007); o core converte.
    @Test
    fun keepsMoneyDateAndInstantExact() =
        runBlocking {
            val payment = BillingApi(client(paymentJson)).getPayment(someId, someId).body()

            val amount: String = payment.amount // não compila se o dinheiro virar Double ou Float

            assertEquals("10.50", amount)
            assertEquals("2026-11-10", payment.businessDate)
            assertEquals("2026-11-10T13:00:00Z", payment.receivedAt)
        }

    @Test
    fun writesMoneyAsDecimalString() =
        runBlocking {
            BillingApi(client("{}")).registerCardPreAuthorization(
                someId,
                idempotencyKey,
                RegisterPreAuthorizationRequest(amount = "10.50", expiresAt = "2026-11-10T13:00:00Z"),
            )
            val preAuthorization = sentBody(requests.single())
            val nightly =
                json.encodeToString(
                    NightlyRateRequest.serializer(),
                    NightlyRateRequest(date = "2026-11-10", roomAmount = "10.50"),
                )

            assertEquals("""{"amount":"10.50","expiresAt":"2026-11-10T13:00:00Z"}""", preAuthorization)
            assertEquals("""{"date":"2026-11-10","roomAmount":"10.50"}""", nightly)
        }
}
