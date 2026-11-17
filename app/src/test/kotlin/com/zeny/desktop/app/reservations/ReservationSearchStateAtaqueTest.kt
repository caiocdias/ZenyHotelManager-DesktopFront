package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CPF = "529.982.247-25"
private const val DIGITS = "52998224725"
private const val DASH_TEXT = "—"

private typealias Op = FakeReservationSearchSource.Op

private fun gid(n: Int) = "40000000-0000-0000-0000-" + n.toString().padStart(12, '0')

private fun problemOf(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, null, emptyList())

private fun FakeReservationSearchSource.fill(
    count: Int,
    name: (Int) -> String = { "Hóspede $it" },
) = apply {
    (1..count).forEach { n ->
        reservations += searchItemOf(code = "R-$n", holderGuestId = gid(n))
        holders[gid(n)] = HolderSummary(name(n), null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions")
class ReservationSearchStateAtaqueTest {
    private val today = BusinessDate.parse("2026-11-13")
    private val invalidDate = (DateInput.parse("31/02/2026") as InputResult.Invalid).message

    private fun source() = FakeReservationSearchSource().apply { businessDay = BusinessDay(today, null) }

    private fun TestScope.state(
        source: FakeReservationSearchSource,
        pageSize: Int = 50,
    ) = ReservationSearchState(source, backgroundScope, pageSize)

    private fun TestScope.started(
        source: FakeReservationSearchSource,
        pageSize: Int = 50,
    ) = state(source, pageSize).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.loadMore(state: ReservationSearchState) {
        backgroundScope.launch { state.table.loadMore() }
        runCurrent()
    }

    private fun ReservationSearchState.codes() = table.rows.map { it.code }

    private fun ReservationSearchState.documentSearch(
        number: String = CPF,
        type: String = "CPF",
        country: String = "",
    ) {
        documentType = type
        documentNumber = number
        issuingCountry = country
        searchByDocument()
    }

    private fun ReservationSearchState.currentQuery(): Any? =
        ReservationSearchState::class.java
            .getDeclaredField("current")
            .apply { isAccessible = true }
            .get(this)

    /** Se o grafo (raso) de [root] alcança uma [DocumentQuery], isto é, se o número ainda está na memória do estado. */
    private fun holdsDocumentQuery(
        root: Any?,
        depth: Int = 3,
    ): Boolean =
        root is DocumentQuery ||
            (
                root != null && depth > 0 && !root.javaClass.name.startsWith("java.") &&
                    !root.javaClass.name.startsWith("kotlin.") &&
                    root.javaClass.declaredFields.any { field ->
                        field.isAccessible = true
                        holdsDocumentQuery(field.get(root), depth - 1)
                    }
            )

    // ---- corridas ----

    @Test
    fun twoSearchesInARowOnlyTheLastRowsSurvive() =
        runTest {
            val source = source().fill(3) { if (it == 3) "Bruna" else "Ana $it" }
            val gate = source.hold(Op.SEARCH)
            val state = state(source)
            state.start()
            runCurrent()
            assertEquals(1, source.searchCalls.size)

            state.name = "Bruna"
            state.search()
            state.name = "Ana"
            state.search()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-1", "R-2"), state.codes())
            assertEquals(
                "Ana",
                source.searchCalls
                    .last()
                    .filter.name,
            )
            assertNull(state.table.error)
        }

    @Test
    fun documentSearchWinsOverEarlierHeldSearch() =
        runTest {
            val source = source().fill(3).apply { documentHolders["CPF" to CPF] = setOf(gid(1), gid(2), gid(3)) }
            val gate = source.hold(Op.SEARCH)
            val state = state(source, pageSize = 2)
            state.start()
            runCurrent()

            state.documentSearch()
            runCurrent()
            assertEquals(listOf("R-1", "R-2"), state.codes())

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-1", "R-2"), state.codes())
            assertEquals("CPF ***4725", state.searchedDocument)
            loadMore(state)
            assertEquals(2, source.documentCalls.size)
            assertEquals(1, source.searchCalls.size)
            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
        }

    @Test
    fun filterSearchWinsOverEarlierHeldDocumentSearch() =
        runTest {
            val source = source().fill(3).apply { documentHolders["CPF" to CPF] = setOf(gid(1)) }
            val state = started(source)
            val gate = source.hold(Op.SEARCH_BY_DOCUMENT)

            state.documentSearch()
            runCurrent()
            assertEquals(1, source.documentCalls.size)
            state.search()
            runCurrent()
            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
            assertNull(state.searchedDocument)

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
            assertNull(state.searchedDocument)
            assertNull(state.table.error)
        }

    @Test
    fun loadMoreInFlightDuringNewSearchIsDiscarded() =
        runTest {
            val source = source().fill(5)
            val state = started(source, pageSize = 2)
            val stale = source.hold(Op.SEARCH)
            loadMore(state)
            assertEquals("2", source.searchCalls.last().cursor)

            source.hold(Op.SEARCH).complete(Unit)
            state.name = "Hóspede 5"
            state.search()
            runCurrent()
            assertEquals(listOf("R-5"), state.codes())

            stale.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-5"), state.codes())
            assertEquals(3, source.searchCalls.size)
            assertEquals(
                "Hóspede 5",
                source.searchCalls
                    .last()
                    .filter.name,
            )
            assertNull(source.searchCalls.last().cursor)
            assertFalse(state.table.hasMore)
        }

    @Test
    fun closeWhileBusinessDayHeldAssignsNothingLater() =
        runTest {
            val source = source().fill(2)
            val gate = source.hold(Op.BUSINESS_DAY)
            val state = state(source)
            state.start()
            runCurrent()

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(state.day)
            assertEquals("", state.fromText)
            assertEquals("", state.toText)
            assertNull(state.error)
            assertTrue(source.searchCalls.isEmpty())
            assertTrue(state.table.rows.isEmpty())
        }

    @Test
    fun closeWhileBusinessDayHeldThenFailingDoesNotShowError() =
        runTest {
            val source = source()
            val gate = source.hold(Op.BUSINESS_DAY)
            val state = state(source)
            state.start()
            runCurrent()
            source.failures[Op.BUSINESS_DAY] = problemOf(500, "X")

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(state.error)
        }

    @Test
    fun closeWithFirstPageInFlightDiscardsRowsAndError() =
        runTest {
            val source = source().fill(2)
            val gate = source.hold(Op.SEARCH)
            val state = state(source)
            state.start()
            runCurrent()
            source.failures[Op.SEARCH] = problemOf(422, "GUEST_NAME_TOO_BROAD")

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertTrue(state.table.rows.isEmpty())
            assertNull(state.table.error)
            assertNull(state.currentQuery())
        }

    @Test
    fun closeReleasesFetchWaitingForTheFirstQuery() =
        runTest {
            val source = source().fill(1)
            val state = state(source)
            val job = backgroundScope.launch { state.table.loadMore() }
            runCurrent()
            assertFalse(job.isCompleted)

            state.close()
            runCurrent()

            assertTrue(job.isCompleted)
            assertTrue(source.searchCalls.isEmpty())
            assertTrue(source.documentCalls.isEmpty())
        }

    @Test
    fun closeBeforeStartKeepsStartInert() =
        runTest {
            val source = source().fill(1)
            val state = state(source)
            state.close()
            state.close()
            state.start()
            runCurrent()
            loadMore(state)

            assertEquals(0, source.businessDayCalls)
            assertTrue(source.searchCalls.isEmpty())
            assertTrue(state.table.rows.isEmpty())
        }

    @Test
    fun startTwiceDoesNotDuplicateRows() =
        runTest {
            val source = source().fill(3)
            val state = state(source)
            state.start()
            state.start()
            runCurrent()

            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
            assertEquals(
                3,
                state.table.rows
                    .map { it.id }
                    .distinct()
                    .size,
            )
            val period = ReservationFilter(from = today, to = BusinessDate.parse("2026-12-13"))
            assertEquals(period, source.searchCalls.last().filter)
            assertNull(state.table.error)
        }

    @Test
    fun startTwiceWithDayHeldReleasesIntoOneConsistentList() =
        runTest {
            val source = source().fill(2)
            val gate = source.hold(Op.BUSINESS_DAY)
            val state = state(source)
            state.start()
            state.start()
            runCurrent()
            assertTrue(source.searchCalls.isEmpty())

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-1", "R-2"), state.codes())
            assertTrue(source.searchCalls.size <= 2, "buscas: ${source.searchCalls.size}")
        }

    @Test
    fun severalLoadMoresBeforeStartYieldOneFirstPage() =
        runTest {
            val source = source().fill(2)
            val gate = source.hold(Op.BUSINESS_DAY)
            val state = state(source)
            state.start()
            repeat(3) { loadMore(state) }
            assertTrue(source.searchCalls.isEmpty())

            gate.complete(Unit)
            runCurrent()

            assertEquals(1, source.searchCalls.size)
            assertEquals(listOf("R-1", "R-2"), state.codes())
        }

    @Test
    fun loadMoreAfterBusinessDayFailureNeverGoesToTheNetwork() =
        runTest {
            val source = source().fill(2).apply { failures[Op.BUSINESS_DAY] = problemOf(500, "X") }
            val state = started(source)
            assertNotNull(state.error)

            loadMore(state)
            loadMore(state)

            assertTrue(source.searchCalls.isEmpty())
            assertTrue(source.documentCalls.isEmpty())

            source.failures.remove(Op.BUSINESS_DAY)
            state.start()
            runCurrent()

            assertNull(state.error)
            assertEquals(1, source.searchCalls.size)
            assertEquals(listOf("R-1", "R-2"), state.codes())
        }

    // ---- o número do documento ----

    @Test
    fun documentQueryIsGoneAfterNewSearchAndAfterClose() =
        runTest {
            val source = source().fill(2).apply { documentHolders["CPF" to CPF] = setOf(gid(1)) }
            val state = started(source)

            state.documentSearch()
            runCurrent()
            assertEquals("", state.documentNumber)
            assertTrue(holdsDocumentQuery(state.currentQuery()), "a consulta vigente deve guardar o número")

            state.search()
            runCurrent()
            assertFalse(holdsDocumentQuery(state.currentQuery()))
            assertNull(state.searchedDocument)

            state.documentSearch()
            runCurrent()
            assertTrue(holdsDocumentQuery(state.currentQuery()))
            state.close()
            assertNull(state.currentQuery())
            assertEquals("", state.documentNumber)
            assertNull(state.searchedDocument)
        }

    @Test
    fun documentNumberIsResentOnEveryPage() =
        runTest {
            val source = source().fill(4).apply { documentHolders["CPF" to CPF] = (1..4).map(::gid).toSet() }
            val state = started(source, pageSize = 1)
            state.documentSearch()
            runCurrent()
            repeat(3) { loadMore(state) }

            assertEquals(4, source.documentCalls.size)
            assertTrue(source.documentCalls.all { it.query == DocumentQuery("CPF", CPF, null) })
            assertEquals(listOf(null, "1", "2", "3"), source.documentCalls.map { it.cursor })
            assertEquals(listOf("R-1", "R-2", "R-3", "R-4"), state.codes())
            assertEquals("", state.documentNumber)
        }

    @Test
    fun secondDocumentSearchReplacesTheFirstNumber() =
        runTest {
            val other = "98765432100"
            val source =
                source().fill(4).apply {
                    documentHolders["CPF" to CPF] = setOf(gid(1))
                    documentHolders["CPF" to other] = setOf(gid(2), gid(3), gid(4))
                }
            val state = started(source, pageSize = 2)
            state.documentSearch()
            runCurrent()
            state.documentSearch(other)
            runCurrent()
            loadMore(state)

            assertEquals(listOf(CPF, other, other), source.documentCalls.map { it.query.number })
            assertEquals(listOf("R-2", "R-3", "R-4"), state.codes())
            assertEquals("CPF ***2100", state.searchedDocument)
        }

    @Test
    fun invalidSearchKeepsTheCurrentDocumentQuery() =
        runTest {
            val source = source().fill(3).apply { documentHolders["CPF" to CPF] = setOf(gid(1), gid(2), gid(3)) }
            val state = started(source, pageSize = 2)
            state.documentSearch()
            runCurrent()

            state.documentSearch(number = "")
            runCurrent()
            assertEquals(ReservationSearchText.NEED_NUMBER, state.fieldError)
            assertEquals("CPF ***4725", state.searchedDocument)

            state.name = "a"
            state.search()
            runCurrent()
            assertEquals(ReservationSearchText.SHORT_NAME, state.fieldError)
            assertEquals("CPF ***4725", state.searchedDocument)

            loadMore(state)
            assertEquals(2, source.documentCalls.size)
            assertEquals(DocumentQuery("CPF", CPF, null), source.documentCalls.last().query)
            assertEquals(1, source.searchCalls.size)
            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
        }

    @Test
    fun shortDocumentIsMaskedToStarsOnly() =
        runTest {
            val source = source().fill(1)
            val state = started(source)
            state.documentSearch(number = " 12 ")
            runCurrent()

            assertEquals("CPF ***", state.searchedDocument)
            assertEquals(DocumentQuery("CPF", "12", null), source.documentCalls.single().query)
        }

    @Test
    fun documentWithPunctuationIsMaskedByAlphanumericTail() =
        runTest {
            val source = source().fill(1)
            val state = started(source)
            state.documentSearch(number = "ab-12.34/x", type = "PASSPORT", country = "br")
            runCurrent()

            assertEquals("Passaporte ***234x", state.searchedDocument)
            assertEquals(DocumentQuery("PASSPORT", "ab-12.34/x", "BR"), source.documentCalls.single().query)
        }

    @Test
    fun errorsAndFieldErrorNeverCarryTheNumber() =
        runTest {
            val source = source().fill(1)
            val state = started(source)
            source.failures[Op.SEARCH_BY_DOCUMENT] = problemOf(400, "DOCUMENT_NUMBER_INVALID")

            // Recusado no cliente: a mensagem não traz o número.
            state.documentSearch(number = DIGITS + "9".repeat(30))
            runCurrent()
            assertEquals(ReservationSearchText.DOCUMENT_TOO_LONG, state.fieldError)
            assertFalse(DIGITS in state.fieldError.orEmpty())

            // Recusado pelo servidor: nem o erro da tabela, nem o `toString` trazem o número.
            state.documentSearch(number = DIGITS)
            runCurrent()
            assertNotNull(state.table.error)
            val everything =
                listOf(
                    state.toString(),
                    state.table.toString(),
                    state.fieldError.orEmpty(),
                    state.error?.message.orEmpty(),
                    state.table.error
                        ?.message
                        .orEmpty(),
                    state.table.error
                        ?.toString()
                        .orEmpty(),
                    state.searchedDocument.orEmpty(),
                )
            everything.forEach { assertFalse(DIGITS in it, it) }
        }

    // ---- bordas de validação ----

    @Test
    fun nameBoundaries() =
        runTest {
            val source = source().fill(1)
            val state = started(source)
            val base = source.searchCalls.size

            fun attempt(name: String): String? {
                state.name = name
                state.search()
                runCurrent()
                return state.fieldError
            }

            assertEquals(ReservationSearchText.SHORT_NAME, attempt("a"))
            assertEquals(base, source.searchCalls.size)
            assertNull(attempt("ab"))
            assertEquals(base + 1, source.searchCalls.size)
            assertNull(attempt("  " + "x".repeat(200) + "  "))
            assertEquals(
                "x".repeat(200),
                source.searchCalls
                    .last()
                    .filter.name,
            )
            assertEquals(ReservationSearchText.NAME_TOO_LONG, attempt("x".repeat(201)))
            assertEquals(base + 2, source.searchCalls.size)
            assertNull(attempt("   "))
            assertNull(
                source.searchCalls
                    .last()
                    .filter.name,
            )
        }

    @Test
    fun numberLengthBoundaries() =
        runTest {
            val source = source().fill(1)
            val state = started(source)
            val base = source.searchCalls.size

            fun attempt(number: String): String? {
                state.number = number
                state.search()
                runCurrent()
                return state.fieldError
            }

            assertNull(attempt("R-" + "1".repeat(28)))
            assertEquals(
                30,
                source.searchCalls
                    .last()
                    .filter.code
                    ?.length,
            )
            assertEquals(ReservationSearchText.NUMBER_TOO_LONG, attempt("R-" + "1".repeat(29)))
            assertNull(attempt("B-" + "1".repeat(28)))
            assertEquals(ReservationSearchText.NUMBER_TOO_LONG, attempt("b-" + "1".repeat(29)))
            assertNull(attempt("  R-" + "1".repeat(28) + "  "))
            assertNull(attempt("O".repeat(60)))
            assertEquals(
                60,
                source.searchCalls
                    .last()
                    .filter.externalId
                    ?.length,
            )
            assertEquals(ReservationSearchText.NUMBER_TOO_LONG, attempt("O".repeat(61)))
            assertNull(attempt("R-"))
            assertEquals(
                "R-",
                source.searchCalls
                    .last()
                    .filter.code,
            )
            assertEquals(base + 5, source.searchCalls.size)
        }

    @Test
    fun documentLengthBoundaries() =
        runTest {
            val source = source().fill(1)
            val state = started(source)

            state.documentSearch(number = "9".repeat(41))
            runCurrent()
            assertEquals(ReservationSearchText.DOCUMENT_TOO_LONG, state.fieldError)
            assertTrue(source.documentCalls.isEmpty())
            assertEquals("9".repeat(41), state.documentNumber)

            state.documentSearch(number = "9".repeat(40))
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(1, source.documentCalls.size)

            state.documentSearch(number = "  " + "8".repeat(40) + " ")
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(
                "8".repeat(40),
                source.documentCalls
                    .last()
                    .query.number,
            )
        }

    @Test
    fun lowercaseCountryIsUppercasedAndTwoLettersRequired() =
        runTest {
            val source = source().fill(1)
            val state = started(source)

            state.documentSearch("AB1234", "PASSPORT", "ar")
            runCurrent()
            assertEquals(
                "AR",
                source.documentCalls
                    .single()
                    .query.issuingCountry,
            )

            state.documentSearch("AB1234", "PASSPORT", "a")
            runCurrent()
            assertEquals(ReservationSearchText.BAD_COUNTRY, state.fieldError)
            state.documentSearch("AB1234", "PASSPORT", "arg")
            runCurrent()
            assertEquals(ReservationSearchText.BAD_COUNTRY, state.fieldError)
            assertEquals(1, source.documentCalls.size)

            // Informado num documento que não o exige, vale e vai maiúsculo.
            state.documentSearch("123", "RG", "br")
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(
                "BR",
                source.documentCalls
                    .last()
                    .query.issuingCountry,
            )
        }

    @Test
    fun leapDayIsAcceptedOnlyInLeapYears() =
        runTest {
            val source = source().fill(1)
            val state = started(source)

            state.fromText = "29/02/2028"
            state.toText = "29/02/2028"
            state.search()
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(
                BusinessDate.parse("2028-02-29"),
                source.searchCalls
                    .last()
                    .filter.from,
            )
            assertEquals(
                BusinessDate.parse("2028-02-29"),
                source.searchCalls
                    .last()
                    .filter.to,
            )

            val calls = source.searchCalls.size
            state.fromText = "29/02/2027"
            state.toText = "01/03/2027"
            state.search()
            runCurrent()
            assertEquals(invalidDate, state.fieldError)
            assertEquals(calls, source.searchCalls.size)
        }

    @Test
    fun periodAllowsEqualDatesAndOpenEnds() =
        runTest {
            val source = source().fill(1)
            val state = started(source)

            state.fromText = "10/11/2026"
            state.toText = ""
            state.search()
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(
                BusinessDate.parse("2026-11-10"),
                source.searchCalls
                    .last()
                    .filter.from,
            )
            assertNull(
                source.searchCalls
                    .last()
                    .filter.to,
            )

            state.fromText = ""
            state.toText = "10/11/2026"
            state.search()
            runCurrent()
            assertNull(state.fieldError)
            assertNull(
                source.searchCalls
                    .last()
                    .filter.from,
            )
            assertEquals(
                BusinessDate.parse("2026-11-10"),
                source.searchCalls
                    .last()
                    .filter.to,
            )
        }

    @Test
    fun invalidFirstThenValidClearsTheFieldError() =
        runTest {
            val source = source().fill(1)
            val state = started(source)
            state.name = "a"
            state.search()
            assertEquals(ReservationSearchText.SHORT_NAME, state.fieldError)

            state.name = "ab"
            state.search()
            runCurrent()
            assertNull(state.fieldError)

            state.documentSearch(number = "")
            assertEquals(ReservationSearchText.NEED_NUMBER, state.fieldError)
            state.documentSearch()
            runCurrent()
            assertNull(state.fieldError)
        }

    @Test
    fun unknownStatusesNeverReachTheSource() =
        runTest {
            val source = source().fill(1).apply { documentHolders["CPF" to CPF] = setOf(gid(1)) }
            val state = started(source)
            state.statuses = setOf("FOO", "CONFIRMED")
            state.search()
            runCurrent()
            state.documentSearch()
            runCurrent()

            assertEquals(
                setOf("CONFIRMED"),
                source.searchCalls
                    .last()
                    .filter.statuses,
            )
            assertEquals(setOf("CONFIRMED"), source.documentCalls.single().statuses)
        }

    // ---- leituras de titular e de tipos de UH ----

    @Test
    fun holdersAreReadOncePerGuestAcrossPagesAndSearches() =
        runTest {
            val source = source().fill(4)
            val state = started(source, pageSize = 2)
            loadMore(state)
            state.name = "Hóspede"
            state.search()
            runCurrent()
            loadMore(state)

            assertEquals(listOf("R-1", "R-2", "R-3", "R-4"), state.codes())
            assertEquals(4, source.holderCalls.size)
            assertEquals(4, source.holderCalls.distinct().size)
        }

    @Test
    fun roomTypesAreReadOncePerStateAcrossPagesAndSearches() =
        runTest {
            val source = source().fill(4)
            val state = started(source, pageSize = 2)
            loadMore(state)
            state.search()
            runCurrent()
            state.documentSearch()
            runCurrent()

            assertEquals(1, source.roomTypesCalls)
        }

    @Test
    fun roomTypesProblemShowsDashAndIsNotRetried() =
        runTest {
            val source = source().fill(3).apply { failures[Op.ROOM_TYPES] = problemOf(403, "PERMISSION_DENIED") }
            val state = started(source, pageSize = 2)
            loadMore(state)

            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
            assertTrue(state.table.rows.all { it.roomType == DASH_TEXT })
            assertNull(state.table.error)
            assertEquals(1, source.roomTypesCalls)
        }

    @Test
    fun roomTypesNetworkFailureShowsInTableAndRetryWorks() =
        runTest {
            val source = source().fill(2).apply { failures[Op.ROOM_TYPES] = ApiError.Network(RuntimeException("boom")) }
            val state = started(source)

            assertNotNull(state.table.error)
            assertTrue(state.table.rows.isEmpty())

            source.failures.remove(Op.ROOM_TYPES)
            loadMore(state)

            assertNull(state.table.error)
            assertEquals(listOf("R-1", "R-2"), state.codes())
        }

    @Test
    fun holderServerErrorShowsInTableNotAsDash() =
        runTest {
            val source = source().fill(2).apply { holderFailures[gid(2)] = problemOf(500, "INTERNAL") }
            val state = started(source)

            assertNotNull(state.table.error)
            assertTrue(state.table.rows.none { it.holder == DASH_TEXT })
        }

    @Test
    fun holderReadsOfParallelPagesAreSerialized() =
        runTest {
            val source = source().fill(6)
            val gate = source.hold(Op.HOLDER)
            val state = state(source, pageSize = 3)
            state.start()
            runCurrent()
            val firstReads = source.holderCalls.size
            assertTrue(firstReads in 1..3, "leituras: $firstReads")

            // Busca nova enquanto a primeira página lê titulares: a nova espera a trava e não relê o que já veio.
            state.name = "Hóspede"
            state.search()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
            // Cada titular é lido uma vez por leitura bem-sucedida; a busca cancelada não deixa nome pela metade.
            assertEquals(listOf("Hóspede 1", "Hóspede 2", "Hóspede 3"), state.table.rows.map { it.holder })
            assertNull(state.table.error)
        }

    @Test
    fun holderForbiddenOnLaterPageStillShowsDash() =
        runTest {
            val source = source().fill(4).apply { holderFailures[gid(4)] = problemOf(403, "PERMISSION_DENIED") }
            val state = started(source, pageSize = 2)
            loadMore(state)

            assertEquals(listOf("Hóspede 1", "Hóspede 2", "Hóspede 3", DASH_TEXT), state.table.rows.map { it.holder })
            assertNull(state.table.error)
        }
}
