package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val GUEST_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val OTHER_GUEST_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a03"
private const val DOC_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val RES_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val TYPE_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val CPF = "529.982.247-25"
private const val DATE_BODY = """{"businessDate":"2026-11-11","timezone":"America/Sao_Paulo"}"""

/**
 * O adaptador `CoreConnection.reservationSearchSource()` contra um servidor HTTP local de verdade (JDK), pelo
 * `ZenyCore` real, e o falso da porta. Só dados fictícios; nenhum teste dorme.
 */
class ReservationSearchSourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val path: String,
        val query: String?,
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun withSource(block: suspend (ReservationSearchSource, CoreConnection) -> Unit) =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                seen += Seen(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery, body)
                exchange.reply(
                    when (exchange.requestURI.path) {
                        "/api/v1/hotel/business-date" -> DATE_BODY
                        "/api/v1/reservations", "/api/v1/reservations/search-by-document" -> page()
                        "/api/v1/guests/$GUEST_ID" -> guest()
                        "/api/v1/room-types" -> """{"items":[${roomType()}]}"""
                        else -> null
                    },
                )
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    block(assertNotNull(connection.reservationSearchSource()), connection)
                }
            } finally {
                server.stop(0)
            }
        }

    private fun HttpExchange.reply(body: String?) {
        val bytes = (body ?: """{"code":"NOT_FOUND","detail":"x"}""").toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun page() =
        """{"items":[{"adults":2,"arrival":"2026-11-13","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$ANY_ID",
        "channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15","departureOverdue":false,
        "folioId":"$ANY_ID","holderGuestId":"$GUEST_ID","id":"$RES_1","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$ANY_ID","roomTypeId":"$TYPE_1","segment":"LEISURE",
        "status":"CONFIRMED","total":"300.00","version":1}],"nextCursor":"c2"}"""

    private fun guest() =
        """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC_ID",
        "issuingCountry":"BR","maskedNumber":"***.456.789-**","type":"CPF"}],"fullName":"Fulana Fictícia de Teste",
        "hasSensitiveData":false,"id":"$GUEST_ID","preferences":{},"socialName":"Fulana Social","status":"ACTIVE",
        "updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    private fun roomType() =
        """{"active":true,"code":"STD","createdAt":"2026-11-12T10:00:00Z","displayOrder":1,"featureIds":[],
        "id":"$TYPE_1","maxAdults":2,"maxChildren":1,"name":"Tipo STD","standardAdults":2,"standardChildren":0,
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    @Test
    fun sourceIsTheSameInstanceOnEveryCall() =
        withSource { source, connection -> assertSame(source, connection.reservationSearchSource()) }

    @Test
    fun businessDayReadsTheHotelBusinessDate() =
        withSource { source, _ ->
            assertEquals(BusinessDate.parse("2026-11-11"), source.businessDay().date)
            assertEquals("/api/v1/hotel/business-date", seen.single().path)
        }

    @Test
    fun searchCallsTheFilterSearchWithCursorAndLimit() =
        withSource { source, _ ->
            val result = source.search(ReservationFilter(code = "R-1", statuses = setOf("CONFIRMED")), "c1", 25)

            assertEquals(
                RES_1,
                result.items
                    .single()
                    .id
                    .toString(),
            )
            assertEquals("c2", result.nextCursor)
            val request = seen.single()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/reservations", request.path)
            val query = checkNotNull(request.query)
            assertTrue("code=R-1" in query && "status=CONFIRMED" in query, query)
            assertTrue("cursor=c1" in query && "limit=25" in query, query)
        }

    @Test
    fun searchByDocumentSendsTheNumberOnlyInTheBody() =
        withSource { source, _ ->
            val result = source.searchByDocument(DocumentQuery("CPF", CPF, null), setOf("IN_HOUSE"), null, 20)

            assertEquals(
                RES_1,
                result.items
                    .single()
                    .id
                    .toString(),
            )
            val request = seen.single()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/reservations/search-by-document", request.path)
            assertFalse("529" in request.path + request.query.orEmpty())
            assertTrue(CPF in request.body, request.body)
        }

    @Test
    fun holderIsTheSocialNameAndMaskedDocument() =
        withSource { source, _ ->
            assertEquals(HolderSummary("Fulana Social", "CPF ***.456.789-**"), source.holder(GUEST_ID))
            assertEquals("/api/v1/guests/$GUEST_ID", seen.single().path)
        }

    @Test
    fun roomTypesReadTheCatalog() =
        withSource { source, _ ->
            assertEquals(listOf("STD"), source.roomTypes().map { it.code })
            assertEquals("/api/v1/room-types", seen.single().path)
        }

    @Test
    fun invalidInputIsRejectedByTheFacadeBeforeAnyRequest() =
        withSource { source, _ ->
            assertFailsWith<IllegalArgumentException> { source.holder("não-é-uuid") }
            assertFailsWith<IllegalArgumentException> { source.search(ReservationFilter(name = "a"), null, 10) }
            assertFailsWith<IllegalArgumentException> {
                source.searchByDocument(DocumentQuery("XYZ", CPF, null), emptySet(), null, 10)
            }
            assertEquals(emptyList(), seen.map { it.path })
        }

    // O falso da porta: o que as Tasks 4 a 6 usam.

    private fun fake(count: Int = 5) =
        FakeReservationSearchSource().apply {
            for (i in 1..count) {
                val holder = if (i % 2 == 0) GUEST_ID else OTHER_GUEST_ID
                reservations += searchItemOf(code = "R-$i", holderGuestId = holder)
            }
            holders[GUEST_ID] = HolderSummary("Fulana Social", "CPF ***.456.789-**")
        }

    @Test
    fun fakePagesByIndexWithTheGivenLimit() =
        runTest {
            val fake = fake()

            val first = fake.search(ReservationFilter(), null, 2)
            val second = fake.search(ReservationFilter(), first.nextCursor, 2)
            val last = fake.search(ReservationFilter(), second.nextCursor, 2)

            assertEquals(listOf("R-1", "R-2"), first.items.map { it.code })
            assertEquals(listOf("R-3", "R-4"), second.items.map { it.code })
            assertEquals(listOf("R-5"), last.items.map { it.code })
            assertNull(last.nextCursor)
            assertEquals(listOf(null, first.nextCursor, second.nextCursor), fake.searchCalls.map { it.cursor })
            assertEquals(listOf(2, 2, 2), fake.searchCalls.map { it.limit })
        }

    @Test
    fun fakeFiltersByCodeAndStatus() =
        runTest {
            val fake = fake()
            fake.reservations += searchItemOf(code = "R-9", status = "CANCELLED")

            assertEquals(listOf("R-9"), fake.search(ReservationFilter(code = "r-9"), null, 10).items.map { it.code })
            assertEquals(
                listOf("R-9"),
                fake.search(ReservationFilter(statuses = setOf("CANCELLED")), null, 10).items.map { it.code },
            )
        }

    @Test
    fun fakeSearchesByDocumentThroughTheHolderGuest() =
        runTest {
            val fake = fake()
            fake.documentHolders["CPF" to CPF] = setOf(GUEST_ID)

            val found = fake.searchByDocument(DocumentQuery("CPF", CPF, null), emptySet(), null, 10)
            val none = fake.searchByDocument(DocumentQuery("CPF", "outro", null), emptySet(), null, 10)

            assertEquals(listOf("R-2", "R-4"), found.items.map { it.code })
            assertEquals(emptyList(), none.items)
            assertEquals(listOf(emptySet(), emptySet()), fake.documentCalls.map { it.statuses })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fakeHoldsAnOperationUntilItsGateIsReleased() =
        runTest {
            val fake = fake()
            val gate = fake.hold(FakeReservationSearchSource.Op.SEARCH)
            var done = false
            val job =
                launch {
                    fake.search(ReservationFilter(), null, 10)
                    done = true
                }
            runCurrent()

            assertEquals(1, fake.searchCalls.size)
            assertFalse(done)

            gate.complete(Unit)
            job.join()

            assertTrue(done)
        }

    @Test
    fun fakeFailsPerOperationWithTheScheduledError() =
        runTest {
            val fake = fake()
            val error = ApiError.Unexpected(500)
            fake.failures[FakeReservationSearchSource.Op.SEARCH] = error
            fake.holderFailures[GUEST_ID] = ApiError.Problem(403, "FORBIDDEN", null, emptyList())

            assertSame(error, assertFailsWith<ApiError.Unexpected> { fake.search(ReservationFilter(), null, 10) })
            assertEquals(1, fake.searchCalls.size)
            assertFailsWith<ApiError.Problem> { fake.holder(GUEST_ID) }
            // As outras operações seguem de pé.
            val found = fake.searchByDocument(DocumentQuery("CPF", CPF, null), emptySet(), null, 10)
            assertEquals(emptyList(), found.items)
        }

    @Test
    fun fakeAnswersTheHolderById() =
        runTest {
            val fake = fake()

            assertEquals("Fulana Social", fake.holder(GUEST_ID).name)
            assertEquals(listOf(GUEST_ID), fake.holderCalls)
        }

    @Test
    fun fakeNeverShowsTheDocumentNumberOrNameInToString() =
        runTest {
            val fake = fake()
            fake.searchByDocument(DocumentQuery("CPF", CPF, null), setOf("IN_HOUSE"), null, 10)
            fake.search(ReservationFilter(name = "Fulana"), null, 10)

            val texts =
                listOf(fake.toString()) + fake.documentCalls.map { "$it" } + fake.searchCalls.map { "$it" }
            for (text in texts) {
                assertFalse("529" in text || "Fulana" in text, text)
            }
        }
}
