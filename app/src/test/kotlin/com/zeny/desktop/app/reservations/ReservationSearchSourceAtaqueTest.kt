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
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.uuid.Uuid

private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a03"
private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val TYPE_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val NUMBER = "52998224725"
private const val TERM = "Zuleica Termosecreto"

private class Reply(
    val status: Int,
    val body: String,
)

private class Request(
    val method: String,
    val path: String,
    val query: String?,
    val body: String,
    val headers: String,
)

/**
 * Ataques à porta da busca de reservas: o adaptador real do `CoreConnection` (servidor HTTP local do JDK, pelo
 * `ZenyCore`) e o falso `FakeReservationSearchSource`. Só dados fictícios; nenhum teste dorme.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationSearchSourceAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val requests = CopyOnWriteArrayList<Request>()

    private fun problem(
        status: Int,
        code: String,
    ) = Reply(status, """{"code":"$code","detail":"Detalhe do servidor."}""")

    private fun withSource(
        stopServerFirst: Boolean = false,
        routes: (String) -> Reply,
        block: suspend (ReservationSearchSource) -> Unit,
    ) = runTest {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val headers = exchange.requestHeaders.entries.joinToString("\n") { "${it.key}=${it.value}" }
            val uri = exchange.requestURI
            requests += Request(exchange.requestMethod, uri.path, uri.rawQuery, body, headers)
            exchange.send(routes(uri.path))
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        if (stopServerFirst) server.stop(0)
        try {
            CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                block(assertNotNull(connection.reservationSearchSource()))
            }
        } finally {
            if (!stopServerFirst) server.stop(0)
        }
    }

    private fun HttpExchange.send(reply: Reply) {
        val bytes = reply.body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(reply.status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun item(status: String = "CONFIRMED") =
        """{"adults":2,"arrival":"2026-11-13","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$ANY",
        "channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15","departureOverdue":false,
        "folioId":"$ANY","holderGuestId":"$GUEST","id":"$RES","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$ANY","roomTypeId":"$TYPE_A","segment":"LEISURE",
        "status":"$status","total":"300.00","version":1}"""

    private fun guest(
        social: String? = null,
        documents: String = "[]",
    ) = """{"createdAt":"2026-11-12T10:00:00Z","documents":$documents,"fullName":"Fulana Fictícia de Teste",
        "hasSensitiveData":false,"id":"$GUEST","preferences":{},"status":"ACTIVE",
        ${social?.let { """"socialName":"$it",""" }.orEmpty()}"updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    private fun doc(
        type: String,
        masked: String,
    ) =
        """{"createdAt":"2026-11-12T10:00:00Z","id":"$ANY","issuingCountry":"BR","maskedNumber":"$masked","type":"$type"}"""

    private fun two() = "[${doc("CPF", "***.111.111-**")},${doc("PASSPORT", "***9999")}]"

    private fun roomType(
        id: String,
        code: String,
        active: Boolean,
    ) = """{"active":$active,"code":"$code","createdAt":"2026-11-12T10:00:00Z","displayOrder":1,"featureIds":[],
        "id":"$id","maxAdults":2,"maxChildren":1,"name":"Tipo $code","standardAdults":2,"standardChildren":0,
        "updatedAt":"2026-11-12T10:00:00Z"}"""

    private fun everything(t: Throwable): String {
        val all = generateSequence(t) { it.cause }.take(10).toList()
        return all.joinToString("\n") { "${it::class.qualifiedName}|${it.message}|$it" }
    }

    private fun leaks(t: Throwable) = NUMBER in everything(t) || "Termosecreto" in everything(t)

    // ---- adaptador: o número do documento fora da URL

    @Test
    fun documentNumberNeverAppearsInUrlOrHeadersNotEvenUnformatted() =
        withSource(routes = { Reply(200, """{"items":[${item()}]}""") }) { source ->
            source.searchByDocument(DocumentQuery("CPF", NUMBER, "br"), setOf("IN_HOUSE"), "c1", 20)

            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/reservations/search-by-document", request.path)
            assertFalse("5299" in request.path + request.query.orEmpty() + request.headers, request.query)
            assertTrue(NUMBER in request.body)
            assertTrue("cursor=c1" in request.query.orEmpty() && "limit=20" in request.query.orEmpty())
        }

    @Test
    fun documentSearchSendsStatusesInContractOrderAndUppercaseCountry() =
        withSource(routes = { Reply(200, """{"items":[]}""") }) { source ->
            source.searchByDocument(DocumentQuery("CPF", NUMBER, "br"), setOf("IN_HOUSE", "CONFIRMED"), null, 10)

            val body = requests.single().body
            val statuses = Regex(""""statuses"\s*:\s*\[\s*"CONFIRMED"\s*,\s*"IN_HOUSE"\s*]""")
            assertTrue(statuses.containsMatchIn(body), body)
            assertTrue("\"BR\"" in body, body)
        }

    @Test
    fun filterSearchSendsStatusesCommaSeparatedInContractOrder() =
        withSource(routes = { Reply(200, """{"items":[]}""") }) { source ->
            source.search(ReservationFilter(statuses = setOf("NO_SHOW", "PROVISIONAL")), null, 10)

            assertTrue("PROVISIONAL" in requests.single().query.orEmpty(), requests.single().query)
            val query = requests.single().query.orEmpty()
            assertTrue(query.indexOf("PROVISIONAL") < query.indexOf("NO_SHOW"), query)
        }

    @Test
    fun invalidInputsNeverReachTheNetworkNorLeakTheValue() =
        withSource(routes = { Reply(200, """{"items":[]}""") }) { source ->
            val inverted =
                ReservationFilter(from = BusinessDate.parse("2026-11-20"), to = BusinessDate.parse("2026-11-10"))
            val errors =
                listOf(
                    assertFailsWith<IllegalArgumentException> {
                        source.searchByDocument(DocumentQuery("CPF", "   ", null), emptySet(), null, 10)
                    },
                    assertFailsWith<IllegalArgumentException> {
                        source.searchByDocument(DocumentQuery("CPF", NUMBER, null), setOf("BOGUS"), null, 10)
                    },
                    assertFailsWith<IllegalArgumentException> {
                        source.search(ReservationFilter(name = " $TERM ", statuses = setOf("BOGUS")), null, 10)
                    },
                    assertFailsWith<IllegalArgumentException> { source.search(inverted, null, 10) },
                )

            assertEquals(emptyList(), requests.toList())
            for (error in errors) assertFalse(leaks(error), everything(error))
        }

    // ---- adaptador: erros do servidor chegam como ApiError sem o termo

    @Test
    fun filterSearch403And404And422ArriveAsProblemWithoutTheTerm() {
        for ((status, code) in listOf(403 to "FORBIDDEN", 404 to "NOT_FOUND", 422 to "GUEST_NAME_TOO_BROAD")) {
            withSource(routes = { problem(status, code) }) { source ->
                val filter = ReservationFilter(name = TERM)
                val error = assertFailsWith<ApiError.Problem> { source.search(filter, null, 10) }

                assertEquals(status, error.status)
                assertEquals(code, error.code)
                assertTrue(error.userMessage.isNotBlank())
                assertFalse(code in error.userMessage, error.userMessage)
                assertFalse(leaks(error), everything(error))
            }
        }
    }

    @Test
    fun documentSearch400And403ArriveAsProblemWithoutTheNumber() {
        for ((status, code) in listOf(400 to "DOCUMENT_NUMBER_INVALID", 403 to "FORBIDDEN")) {
            withSource(routes = { problem(status, code) }) { source ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        source.searchByDocument(DocumentQuery("CPF", NUMBER, null), emptySet(), null, 10)
                    }

                assertEquals(status, error.status)
                assertEquals(code, error.code)
                assertFalse(code in error.userMessage, error.userMessage)
                assertFalse(leaks(error), everything(error))
            }
        }
    }

    @Test
    fun networkFailureOnFilterSearchCarriesNeitherTheNameNorTheUrl() =
        withSource(stopServerFirst = true, routes = { Reply(200, "{}") }) { source ->
            val error = assertFailsWith<ApiError.Network> { source.search(ReservationFilter(name = TERM), null, 10) }

            assertFalse(leaks(error), everything(error))
            assertFalse("127.0.0.1" in everything(error).substringAfter("|"), everything(error))
        }

    @Test
    fun networkFailureOnDocumentSearchCarriesNoNumber() =
        withSource(stopServerFirst = true, routes = { Reply(200, "{}") }) { source ->
            val error =
                assertFailsWith<ApiError.Network> {
                    source.searchByDocument(DocumentQuery("CPF", NUMBER, null), emptySet(), null, 10)
                }

            assertFalse(leaks(error), everything(error))
        }

    @Test
    fun brokenResponseThatEchoesTheTermCarriesNeitherNumberNorName() =
        withSource(routes = { Reply(200, """{"items":"$NUMBER $TERM"}""") }) { source ->
            val byDocument =
                assertFailsWith<ApiError.IncompatibleResponse> {
                    source.searchByDocument(DocumentQuery("CPF", NUMBER, null), emptySet(), null, 10)
                }
            val byName =
                assertFailsWith<ApiError.IncompatibleResponse> {
                    source.search(ReservationFilter(name = TERM), null, 10)
                }

            assertFalse(leaks(byDocument), everything(byDocument))
            assertFalse(leaks(byName), everything(byName))
        }

    @Test
    fun unknownStatusInAnItemIsToleratedNotAFailure() =
        withSource(routes = { Reply(200, """{"items":[${item("SOMETHING_NEW")}]}""") }) { source ->
            val page = source.search(ReservationFilter(), null, 10)

            assertEquals("SOMETHING_NEW", page.items.single().status)
            assertNull(page.nextCursor)
        }

    @Test
    fun businessDayAndRoomTypesAndHolderErrorsAreProblems() {
        for (status in listOf(403, 404)) {
            withSource(routes = { problem(status, "X_ERROR") }) { source ->
                assertEquals(status, assertFailsWith<ApiError.Problem> { source.businessDay() }.status)
                assertEquals(status, assertFailsWith<ApiError.Problem> { source.roomTypes() }.status)
                assertEquals(status, assertFailsWith<ApiError.Problem> { source.holder(GUEST) }.status)
            }
        }
    }

    // ---- adaptador: titular e tipos de UH

    @Test
    fun holderFallsBackToFullNameWhenSocialNameIsMissingOrBlank() {
        for (social in listOf(null, "", "   ")) {
            withSource(routes = { Reply(200, guest(social, "[${doc("CPF", "***.456.789-**")}]")) }) { source ->
                assertEquals(HolderSummary("Fulana Fictícia de Teste", "CPF ***.456.789-**"), source.holder(GUEST))
            }
        }
    }

    @Test
    fun holderWithoutDocumentHasNullDocument() =
        withSource(routes = { Reply(200, guest("Social")) }) { source ->
            assertNull(source.holder(GUEST).document)
        }

    @Test
    fun holderWithManyDocumentsTakesTheFirstOne() =
        withSource(routes = { Reply(200, guest("Social", two())) }) { source ->
            assertEquals("CPF ***.111.111-**", source.holder(GUEST).document)
        }

    @Test
    fun roomTypesKeepServerOrderAndIncludeInactiveOnes() =
        withSource(
            routes = {
                val items = listOf(roomType(TYPE_B, "LUX", false), roomType(TYPE_A, "STD", true))
                Reply(200, """{"items":[${items.joinToString(",")}]}""")
            },
        ) { source ->
            val types = source.roomTypes()

            assertEquals(listOf("LUX", "STD"), types.map { it.code })
            assertEquals(listOf(TYPE_B, TYPE_A), types.map { it.id.toString() })
        }

    @Test
    fun roomTypesEmptyCatalogIsAnEmptyList() =
        withSource(routes = { Reply(200, """{"items":[]}""") }) { source ->
            assertEquals(emptyList(), source.roomTypes())
        }

    // ---- fake: paginação por índice

    private fun fake(
        count: Int,
        status: (Int) -> String = { "CONFIRMED" },
    ) = FakeReservationSearchSource().apply {
        for (i in 1..count) reservations += searchItemOf(code = "R-$i", status = status(i), holderGuestId = GUEST)
    }

    private suspend fun collect(
        limits: List<Int>,
        fetch: suspend (String?, Int) -> Page<ReservationListItemResponse>,
    ): List<String> {
        val codes = mutableListOf<String>()
        var cursor: String? = null
        var turn = 0
        do {
            val page = fetch(cursor, limits[turn % limits.size])
            codes += page.items.map { it.code }
            cursor = page.nextCursor
            turn++
            check(turn < 100) { "paginação não termina" }
        } while (cursor != null)
        return codes
    }

    @Test
    fun fakePaginationNeverRepeatsNorSkipsWithAnyLimitAndSizeEvenWhenLimitChangesPerPage() =
        runTest {
            val everyLimits = listOf(1, 2, 3, 4, 9, 10).map { listOf(it) } + listOf(listOf(1, 3, 2, 5))
            for (size in 0..9) {
                for (limits in everyLimits) {
                    val fake = fake(size)
                    fake.documentHolders["CPF" to NUMBER] = setOf(GUEST)
                    val query = DocumentQuery("CPF", NUMBER, null)

                    val codes = collect(limits) { cursor, limit -> fake.search(ReservationFilter(), cursor, limit) }
                    val byDocument =
                        collect(limits) { c, l -> fake.searchByDocument(query, emptySet(), c, l) }

                    val expected = (1..size).map { "R-$it" }
                    assertEquals(expected, codes, "size=$size limits=$limits")
                    assertEquals(expected, byDocument, "documento size=$size limits=$limits")
                }
            }
        }

    @Test
    fun fakeLastPageOfAnExactMultipleHasNoCursorAndNoExtraEmptyPage() =
        runTest {
            val fake = fake(4)

            val first = fake.search(ReservationFilter(), null, 2)
            val second = fake.search(ReservationFilter(), first.nextCursor, 2)

            assertNotNull(first.nextCursor)
            assertEquals(listOf("R-3", "R-4"), second.items.map { it.code })
            assertNull(second.nextCursor)
        }

    @Test
    fun fakeCursorAtOrBeyondTheEndGivesAnEmptyPageWithoutCursor() =
        runTest {
            val fake = fake(3)

            for (cursor in listOf("3", "4", "99")) {
                val page = fake.search(ReservationFilter(), cursor, 2)
                assertEquals(emptyList(), page.items, cursor)
                assertNull(page.nextCursor, cursor)
            }
        }

    @Test
    fun fakeLimitLargerThanTheBaseReturnsEverythingOnePage() =
        runTest {
            val fake = fake(3)

            val page = fake.search(ReservationFilter(), null, 100)

            assertEquals(3, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun fakePaginatesTheFilteredListNotTheWholeBase() =
        runTest {
            val fake = fake(6) { if (it % 2 == 0) "CANCELLED" else "CONFIRMED" }

            val codes =
                collect(listOf(2)) { cursor, limit ->
                    fake.search(ReservationFilter(statuses = setOf("CANCELLED")), cursor, limit)
                }

            assertEquals(listOf("R-2", "R-4", "R-6"), codes)
        }

    // ---- fake: filtros combinados com "e"

    private fun filterFake() =
        FakeReservationSearchSource().apply {
            reservations += searchItemOf("R-1", "CONFIRMED", "2026-11-10", "2026-11-12", GUEST)
            reservations += searchItemOf("R-2", "CANCELLED", "2026-11-10", "2026-11-12", GUEST)
            reservations += searchItemOf("R-3", "CONFIRMED", "2026-11-20", "2026-11-22", GUEST)
            reservations += searchItemOf("R-4", "CONFIRMED", "2026-11-10", "2026-11-12", GUEST_B)
            holders[GUEST] = HolderSummary("Ana Souza", null)
            holders[GUEST_B] = HolderSummary("Bruno Souza", null)
        }

    private suspend fun FakeReservationSearchSource.codes(filter: ReservationFilter) =
        search(filter, null, 50).items.map { it.code }

    @Test
    fun fakeCombinesFiltersWithAnd() =
        runTest {
            val fake = filterFake()
            val confirmed = setOf("CONFIRMED")

            assertEquals(listOf("R-1", "R-2", "R-3", "R-4"), fake.codes(ReservationFilter(name = "souza")))
            val souzaConfirmed = ReservationFilter(name = "souza", statuses = confirmed)
            assertEquals(listOf("R-1", "R-3", "R-4"), fake.codes(souzaConfirmed))
            assertEquals(listOf("R-1", "R-3"), fake.codes(ReservationFilter(name = "ana", statuses = confirmed)))
            val window =
                ReservationFilter(
                    name = "ana",
                    statuses = confirmed,
                    from = BusinessDate.parse("2026-11-09"),
                    to = BusinessDate.parse("2026-11-13"),
                )
            assertEquals(listOf("R-1"), fake.codes(window))
            val late = ReservationFilter(from = BusinessDate.parse("2026-11-15"), to = BusinessDate.parse("2026-11-25"))
            assertEquals(listOf("R-3"), fake.codes(late))
            assertEquals(emptyList(), fake.codes(ReservationFilter(code = "R-1", statuses = setOf("CANCELLED"))))
            assertEquals(emptyList(), fake.codes(ReservationFilter(code = "R-1", name = "bruno")))
            val bruno = ReservationFilter(code = "R-4", name = "bruno", statuses = confirmed)
            assertEquals(listOf("R-4"), fake.codes(bruno))
        }

    @Test
    fun fakeStatusSetIsAnyOfAndBlankTextsAreIgnored() =
        runTest {
            val fake = filterFake()

            assertEquals(
                listOf("R-1", "R-2", "R-3", "R-4"),
                fake.codes(ReservationFilter(statuses = setOf("CONFIRMED", "CANCELLED"), name = "   ", code = " ")),
            )
            assertEquals(
                listOf("R-1", "R-3"),
                fake.codes(ReservationFilter(name = "  ana  ", statuses = setOf("CONFIRMED"))),
            )
        }

    @Test
    fun fakeNameFilterMatchesTheHolderNameNotACodeAndMissesHoldersWithoutSummary() =
        runTest {
            val fake = filterFake()
            fake.reservations += searchItemOf("R-5", "CONFIRMED", "2026-11-10", "2026-11-12", Uuid.random().toString())

            assertEquals(emptyList(), fake.codes(ReservationFilter(name = "R-1")))
            assertFalse("R-5" in fake.codes(ReservationFilter(name = "souza")))
        }

    @Test
    fun fakeDocumentSearchCombinesStatusesAndKeysOnTypeAndNumber() =
        runTest {
            val fake = filterFake()
            fake.documentHolders["CPF" to NUMBER] = setOf(GUEST, GUEST_B)
            fake.documentHolders["PASSPORT" to NUMBER] = setOf(GUEST_B)

            val all = fake.searchByDocument(DocumentQuery("CPF", NUMBER, null), emptySet(), null, 50)
            val confirmed = fake.searchByDocument(DocumentQuery("CPF", NUMBER, null), setOf("CONFIRMED"), null, 50)
            val passport = fake.searchByDocument(DocumentQuery("PASSPORT", NUMBER, null), emptySet(), null, 50)
            val other = fake.searchByDocument(DocumentQuery("RG", NUMBER, null), emptySet(), null, 50)

            assertEquals(listOf("R-1", "R-2", "R-3", "R-4"), all.items.map { it.code })
            assertEquals(listOf("R-1", "R-3", "R-4"), confirmed.items.map { it.code })
            assertEquals(listOf("R-4"), passport.items.map { it.code })
            assertEquals(emptyList(), other.items)
            assertEquals(4, fake.documentCalls.size)
            assertEquals(setOf("CONFIRMED"), fake.documentCalls[1].statuses)
        }

    // ---- fake: gate e falhas

    private fun TestScope.searching(
        fake: FakeReservationSearchSource,
        out: MutableList<Result<Page<ReservationListItemResponse>>>,
    ) = launch(start = CoroutineStart.UNDISPATCHED) {
        out += runCatching { fake.search(ReservationFilter(), null, 10) }
    }

    @Test
    fun fakeGateReleasedAfterTheCallerWasCancelledDoesNothingAndDoesNotPoisonTheFake() =
        runTest {
            val fake = fake(2)
            val gate = fake.hold(FakeReservationSearchSource.Op.SEARCH)
            val out = mutableListOf<Result<Page<ReservationListItemResponse>>>()
            val job = searching(fake, out)
            runCurrent()

            job.cancel()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertTrue(job.isCancelled)
            assertEquals(emptyList(), out.filter { it.isSuccess })
            assertEquals(1, fake.searchCalls.size)
            assertEquals(2, fake.search(ReservationFilter(), null, 10).items.size)
        }

    @Test
    fun fakeGateReleasesEveryWaitingCallAndALaterHoldBlocksAgain() =
        runTest {
            val fake = fake(2)
            val gate = fake.hold(FakeReservationSearchSource.Op.SEARCH)
            val out = mutableListOf<Result<Page<ReservationListItemResponse>>>()
            searching(fake, out)
            searching(fake, out)
            runCurrent()
            assertEquals(0, out.size)

            gate.complete(Unit)
            runCurrent()
            assertEquals(2, out.size)

            val second = fake.hold(FakeReservationSearchSource.Op.SEARCH)
            searching(fake, out)
            runCurrent()
            assertEquals(2, out.size)
            second.complete(Unit)
            runCurrent()
            assertEquals(3, out.size)
        }

    @Test
    fun fakeGateOfOneOperationDoesNotHoldTheOthers() =
        runTest {
            val fake = fake(1)
            fake.hold(FakeReservationSearchSource.Op.SEARCH)
            fake.hold(FakeReservationSearchSource.Op.HOLDER)

            assertEquals(emptyList(), fake.roomTypes())
            assertEquals(fake.businessDay, fake.businessDay())
            val byDocument = fake.searchByDocument(DocumentQuery("CPF", NUMBER, null), emptySet(), null, 10)
            assertEquals(emptyList(), byDocument.items)
            assertEquals(1, fake.roomTypesCalls)
            assertEquals(1, fake.businessDayCalls)
        }

    @Test
    fun fakeScheduledFailureIsThrownAfterTheGateAndStopsWhenRemoved() =
        runTest {
            val fake = fake(2)
            val boom = ApiError.Unexpected(500)
            fake.failures[FakeReservationSearchSource.Op.SEARCH] = boom
            val gate = fake.hold(FakeReservationSearchSource.Op.SEARCH)
            val out = mutableListOf<Result<Page<ReservationListItemResponse>>>()
            searching(fake, out)
            runCurrent()
            assertEquals(0, out.size)

            gate.complete(Unit)
            runCurrent()

            assertSame(boom, out.single().exceptionOrNull())
            fake.failures.remove(FakeReservationSearchSource.Op.SEARCH)
            assertEquals(2, fake.search(ReservationFilter(), null, 10).items.size)
        }

    @Test
    fun fakeFailureIsPerOperationAndHolderFailureIsPerGuest() =
        runTest {
            val fake = fake(2)
            fake.holders[GUEST] = HolderSummary("Ana", null)
            fake.holders[GUEST_B] = HolderSummary("Bruno", null)
            fake.failures[FakeReservationSearchSource.Op.ROOM_TYPES] = ApiError.Unexpected(500)
            fake.failures[FakeReservationSearchSource.Op.BUSINESS_DAY] = ApiError.Unexpected(503)
            fake.holderFailures[GUEST_B] = ApiError.Problem(404, "NOT_FOUND", null, emptyList())

            assertEquals(500, assertFailsWith<ApiError.Unexpected> { fake.roomTypes() }.status)
            assertEquals(503, assertFailsWith<ApiError.Unexpected> { fake.businessDay() }.status)
            assertEquals(404, assertFailsWith<ApiError.Problem> { fake.holder(GUEST_B) }.status)
            assertEquals("Ana", fake.holder(GUEST).name)
            assertEquals(2, fake.search(ReservationFilter(), null, 10).items.size)
            assertEquals(listOf(GUEST_B, GUEST), fake.holderCalls)
            assertEquals(1, fake.roomTypesCalls)
            assertEquals(1, fake.businessDayCalls)
        }

    @Test
    fun fakeFailingHolderOperationFailsEveryGuest() =
        runTest {
            val fake = fake(1)
            fake.holders[GUEST] = HolderSummary("Ana", null)
            fake.failures[FakeReservationSearchSource.Op.HOLDER] = ApiError.Problem(403, "FORBIDDEN", null, emptyList())

            assertEquals(403, assertFailsWith<ApiError.Problem> { fake.holder(GUEST) }.status)
        }

    @Test
    fun fakeRecordsEveryRoomTypesAndBusinessDayCall() =
        runTest {
            val fake = fake(1)

            fake.roomTypes()
            fake.roomTypes()
            fake.businessDay()

            assertEquals(2, fake.roomTypesCalls)
            assertEquals(1, fake.businessDayCalls)
        }

    // ---- fake: dado pessoal

    @Test
    fun fakeNoCallRecordNorToStringShowsNumberOrName() =
        runTest {
            val fake = filterFake()
            fake.documentHolders["CPF" to NUMBER] = setOf(GUEST)
            fake.searchByDocument(DocumentQuery("CPF", NUMBER, "BR"), setOf("CONFIRMED"), "1", 5)
            fake.search(ReservationFilter(name = TERM, code = "R-1"), "1", 5)

            val texts =
                listOf(
                    fake.toString(),
                    fake.documentCalls.toString(),
                    fake.searchCalls.toString(),
                    fake.documentCalls.single().toString(),
                    fake.documentCalls
                        .single()
                        .query
                        .toString(),
                    fake.searchCalls
                        .single()
                        .filter
                        .toString(),
                    fake.searchCalls
                        .single()
                        .copy(limit = 1)
                        .toString(),
                )
            for (text in texts) {
                assertFalse(NUMBER in text || "Termosecreto" in text || "Ana" in text || "Souza" in text, text)
            }
        }
}
