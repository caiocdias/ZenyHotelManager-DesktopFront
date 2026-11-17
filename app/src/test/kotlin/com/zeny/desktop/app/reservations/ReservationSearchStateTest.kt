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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CPF = "529.982.247-25"
private const val CPF_DIGITS = "52998224725"
private const val NAME_TERM = "Demonstração"
private const val TOO_BROAD = "O nome informado é amplo demais. Refine a busca."
private const val DASH = "—"

private fun guestId(n: Int) = "30000000-0000-0000-0000-" + n.toString().padStart(12, '0')

/** [count] reservas, uma por titular, cada titular com um nome fictício no falso. */
private fun FakeReservationSearchSource.withReservations(
    count: Int,
    name: (Int) -> String = { "Hóspede $it" },
) = apply {
    (1..count).forEach { n ->
        val guest = guestId(n)
        reservations += searchItemOf(code = "R-$n", holderGuestId = guest)
        holders[guest] = HolderSummary(name(n), null)
    }
}

private fun problem(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationSearchStateTest {
    private val today = BusinessDate.parse("2026-11-13")
    private val inThirtyDays = BusinessDate.parse("2026-12-13")
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

    @Test
    fun noSearchBeforeStart() =
        runTest {
            val source = source().withReservations(1)
            val gate = source.hold(FakeReservationSearchSource.Op.BUSINESS_DAY)
            val state = state(source)
            state.start()
            runCurrent()

            loadMore(state)
            assertTrue(source.searchCalls.isEmpty())
            assertTrue(source.documentCalls.isEmpty())

            gate.complete(Unit)
            runCurrent()

            val first = source.searchCalls.first().filter
            assertEquals(today, first.from)
            assertEquals(inThirtyDays, first.to)
            assertEquals(1, source.searchCalls.size)
            assertTrue(source.documentCalls.isEmpty())
            assertEquals(listOf("R-1"), state.codes())
        }

    @Test
    fun startLoadsNextThirtyDays() =
        runTest {
            val source = source().withReservations(2)
            val state = started(source)

            assertEquals(1, source.searchCalls.size)
            val call = source.searchCalls.single()
            assertEquals(ReservationFilter(from = today, to = inThirtyDays), call.filter)
            assertTrue(call.filter.statuses.isEmpty())
            assertNull(call.cursor)
            assertEquals(50, call.limit)
            assertEquals("13/11/2026", state.fromText)
            assertEquals("13/12/2026", state.toText)
            assertEquals(BusinessDay(today, null), state.day)
            assertNull(state.error)
            assertEquals(listOf("R-1", "R-2"), state.codes())
            assertEquals(listOf("Hóspede 1", "Hóspede 2"), state.table.rows.map { it.holder })
            assertEquals(8, state.columns.size)
        }

    @Test
    fun businessDayFailureShowsError() =
        runTest {
            val source = source().apply { failures[FakeReservationSearchSource.Op.BUSINESS_DAY] = problem(403, "X") }
            val state = started(source)

            assertNotNull(state.error)
            assertNull(state.day)
            assertTrue(source.searchCalls.isEmpty())
        }

    @Test
    fun filtersBySubmittedValues() =
        runTest {
            val source = source().withReservations(1)
            val state = started(source)

            state.number = " R-2026-000001 "
            state.name = "  Maria Silva "
            state.statuses = setOf("IN_HOUSE", "CONFIRMED")
            state.fromText = "01/11/2026"
            state.toText = "05/11/2026"
            state.search()
            runCurrent()

            assertNull(state.fieldError)
            assertEquals(
                ReservationFilter(
                    code = "R-2026-000001",
                    name = "Maria Silva",
                    from = BusinessDate.parse("2026-11-01"),
                    to = BusinessDate.parse("2026-11-05"),
                    statuses = setOf("IN_HOUSE", "CONFIRMED"),
                ),
                source.searchCalls.last().filter,
            )

            state.number = "ABC-99"
            state.name = ""
            state.statuses = emptySet()
            state.fromText = ""
            state.toText = ""
            state.search()
            runCurrent()

            assertEquals(ReservationFilter(externalId = "ABC-99"), source.searchCalls.last().filter)
            assertEquals(3, source.searchCalls.size)
        }

    @Test
    fun loadMoreKeepsSubmittedFilters() =
        runTest {
            val source = source().withReservations(5)
            val state = started(source, pageSize = 2)
            assertEquals(listOf("R-1", "R-2"), state.codes())

            state.name = "Outro nome"
            state.number = "R-9"
            loadMore(state)
            loadMore(state)

            assertEquals(3, source.searchCalls.size)
            val first = source.searchCalls.first().filter
            assertTrue(source.searchCalls.all { it.filter == first })
            assertEquals(listOf(null, "2", "4"), source.searchCalls.map { it.cursor })
            assertTrue(source.searchCalls.all { it.limit == 2 })
            assertEquals(listOf("R-1", "R-2", "R-3", "R-4", "R-5"), state.codes())
            assertEquals(
                5,
                state.table.rows
                    .map { it.id }
                    .distinct()
                    .size,
            )
            assertFalse(state.table.hasMore)
        }

    @Test
    fun newerSearchWins() =
        runTest {
            val source = source().withReservations(3) { if (it == 3) "Bruna" else "Ana $it" }
            val firstGate = source.hold(FakeReservationSearchSource.Op.SEARCH)
            val state = started(source)
            assertEquals(1, source.searchCalls.size)
            assertTrue(state.table.rows.isEmpty())

            source.hold(FakeReservationSearchSource.Op.SEARCH).complete(Unit)
            state.name = "Bruna"
            state.search()
            runCurrent()
            assertEquals(listOf("R-3"), state.codes())

            firstGate.complete(Unit)
            runCurrent()

            assertEquals(listOf("R-3"), state.codes())
            assertEquals(2, source.searchCalls.size)
        }

    @Test
    fun invalidFiltersDoNotHitServer() =
        runTest {
            val source = source().withReservations(1)
            val state = started(source)
            val calls = source.searchCalls.size

            fun rejects(
                expected: String,
                edit: ReservationSearchState.() -> Unit,
            ) {
                state.number = ""
                state.name = ""
                state.fromText = "13/11/2026"
                state.toText = "13/12/2026"
                state.edit()
                state.search()
                runCurrent()
                assertEquals(expected, state.fieldError)
                assertEquals(calls, source.searchCalls.size)
                assertTrue(source.documentCalls.isEmpty())
            }

            rejects(ReservationSearchText.SHORT_NAME) { name = "a" }
            rejects(ReservationSearchText.SHORT_NAME) { name = "  a  " }
            rejects(ReservationSearchText.NAME_TOO_LONG) { name = "a".repeat(201) }
            rejects(invalidDate) { fromText = "31/02/2026" }
            rejects(invalidDate) { toText = "2026-12-01" }
            rejects(ReservationSearchText.PERIOD_INVERTED) {
                fromText = "10/11/2026"
                toText = "09/11/2026"
            }
            rejects(ReservationSearchText.NUMBER_TOO_LONG) { number = "R-" + "1".repeat(29) }
            rejects(ReservationSearchText.NUMBER_TOO_LONG) { number = "b-" + "1".repeat(29) }
            rejects(ReservationSearchText.NUMBER_TOO_LONG) { number = "X".repeat(61) }

            assertEquals("Digite ao menos 2 letras do nome.", ReservationSearchText.SHORT_NAME)
            assertEquals("O fim do período não pode ser antes do início.", ReservationSearchText.PERIOD_INVERTED)
            assertEquals("O número tem caracteres demais.", ReservationSearchText.NUMBER_TOO_LONG)
            assertEquals("O nome tem caracteres demais (até 200).", ReservationSearchText.NAME_TOO_LONG)

            // Nos limites, a busca sai e o erro de campo some.
            state.number = "R-" + "1".repeat(28)
            state.name = "a".repeat(200)
            state.search()
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(calls + 1, source.searchCalls.size)

            state.number = "X".repeat(60)
            state.name = "ab"
            state.fromText = "13/11/2026"
            state.toText = "13/11/2026"
            state.search()
            runCurrent()
            assertNull(state.fieldError)
            assertEquals(calls + 2, source.searchCalls.size)
        }

    @Test
    fun documentValidation() =
        runTest {
            val source = source()
            val state = started(source)
            val calls = source.searchCalls.size

            fun rejects(
                expected: String,
                type: String,
                number: String,
                country: String = "",
            ) {
                state.documentType = type
                state.documentNumber = number
                state.issuingCountry = country
                state.searchByDocument()
                runCurrent()
                assertEquals(expected, state.fieldError)
                assertTrue(source.documentCalls.isEmpty())
                assertEquals(calls, source.searchCalls.size)
                assertNull(state.searchedDocument)
            }

            rejects(ReservationSearchText.NEED_NUMBER, "CPF", "")
            rejects(ReservationSearchText.NEED_NUMBER, "CPF", "   ")
            rejects(ReservationSearchText.DOCUMENT_TOO_LONG, "RG", "9".repeat(41))
            rejects(ReservationSearchText.NEED_COUNTRY, "PASSPORT", "AB123456")
            rejects(ReservationSearchText.NEED_COUNTRY, "FOREIGN_ID", "AB123456", "  ")
            rejects(ReservationSearchText.BAD_COUNTRY, "PASSPORT", "AB123456", "ARG")
            rejects(ReservationSearchText.BAD_COUNTRY, "RG", "123456", "1A")
            rejects(ReservationSearchText.BAD_TYPE, "FOO", "123456")

            assertEquals(
                "O número do documento tem caracteres demais (até 40).",
                ReservationSearchText.DOCUMENT_TOO_LONG,
            )

            state.documentType = "PASSPORT"
            state.documentNumber = "9".repeat(40)
            state.issuingCountry = " ar "
            state.searchByDocument()
            runCurrent()

            assertNull(state.fieldError)
            assertEquals(DocumentQuery("PASSPORT", "9".repeat(40), "AR"), source.documentCalls.single().query)

            state.documentType = "CPF"
            state.documentNumber = CPF
            state.issuingCountry = ""
            state.searchByDocument()
            runCurrent()

            assertEquals(DocumentQuery("CPF", CPF, null), source.documentCalls.last().query)
        }

    @Test
    fun documentSearchClearsInputAndShowsMasked() =
        runTest {
            val source =
                source().withReservations(2).apply { documentHolders["CPF" to CPF] = setOf(guestId(2)) }
            val state = started(source)

            state.documentNumber = "  $CPF "
            state.statuses = setOf("CONFIRMED")
            state.searchByDocument()

            // Antes da rede: o campo já está limpo e a faixa já é a mascarada.
            assertEquals("", state.documentNumber)
            assertEquals("CPF ***4725", state.searchedDocument)
            runCurrent()

            val call = source.documentCalls.single()
            assertEquals(DocumentQuery("CPF", CPF, null), call.query)
            assertEquals(setOf("CONFIRMED"), call.statuses)
            assertEquals(listOf("R-2"), state.codes())
            assertEquals("", state.documentNumber)
            assertEquals("CPF ***4725", state.searchedDocument)
        }

    @Test
    fun documentLoadMoreResendsQuery() =
        runTest {
            val source =
                source().withReservations(3).apply {
                    documentHolders["CPF" to CPF] = setOf(guestId(1), guestId(2), guestId(3))
                }
            val state = started(source, pageSize = 2)
            state.documentNumber = CPF
            state.statuses = setOf("CONFIRMED")
            state.searchByDocument()
            runCurrent()

            state.documentType = "RG"
            state.statuses = emptySet()
            loadMore(state)

            assertEquals(2, source.documentCalls.size)
            val (first, second) = source.documentCalls
            assertEquals(first.query, second.query)
            assertEquals(DocumentQuery("CPF", CPF, null), second.query)
            assertEquals(setOf("CONFIRMED"), second.statuses)
            assertEquals("2", second.cursor)
            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
        }

    @Test
    fun newFilterSearchDropsDocument() =
        runTest {
            val source =
                source().withReservations(3).apply {
                    documentHolders["CPF" to CPF] = setOf(guestId(1), guestId(2), guestId(3))
                }
            val state = started(source, pageSize = 2)
            state.documentNumber = CPF
            state.searchByDocument()
            runCurrent()
            assertEquals(1, source.documentCalls.size)

            state.search()
            runCurrent()
            assertNull(state.searchedDocument)
            val searches = source.searchCalls.size

            loadMore(state)

            assertEquals(1, source.documentCalls.size)
            assertEquals(searches + 1, source.searchCalls.size)
            assertEquals("2", source.searchCalls.last().cursor)
            assertEquals(listOf("R-1", "R-2", "R-3"), state.codes())
        }

    @Test
    fun holderForbiddenShowsDash() =
        runTest {
            val source =
                source().withReservations(3).apply {
                    holderFailures[guestId(1)] = problem(403, "PERMISSION_DENIED")
                    holderFailures[guestId(3)] = problem(404, "GUEST_NOT_FOUND")
                }
            val state = started(source)

            assertEquals(listOf(DASH, "Hóspede 2", DASH), state.table.rows.map { it.holder })
            assertNull(state.table.error)
        }

    @Test
    fun serverErrorShowsInTable() =
        runTest {
            val source = source().withReservations(1)
            val state = started(source)
            source.failures[FakeReservationSearchSource.Op.SEARCH] = problem(422, "GUEST_NAME_TOO_BROAD")

            state.name = "Silva"
            state.search()
            runCurrent()

            assertEquals(TOO_BROAD, state.table.error?.userMessage)
            assertNull(state.fieldError)
            assertNull(state.error)
        }

    @Test
    fun documentNumberNeverInToStringOrErrors() =
        runTest {
            val source = source().withReservations(1)
            source.failures[FakeReservationSearchSource.Op.SEARCH_BY_DOCUMENT] = problem(400, "DOCUMENT_NUMBER_INVALID")
            val state = started(source)

            state.documentType = "PASSPORT"
            state.documentNumber = CPF_DIGITS
            state.searchByDocument()
            runCurrent()
            assertNoNumber(state)

            state.documentType = "CPF"
            state.searchByDocument()
            runCurrent()

            assertEquals("ReservationSearchState(***)", state.toString())
            assertEquals(1, source.documentCalls.size)
            assertEquals("O número do documento é inválido.", state.table.error?.userMessage)
            assertNoNumber(state)
        }

    private fun assertNoNumber(state: ReservationSearchState) {
        val error = state.table.error
        val texts =
            listOf(
                state.toString(),
                state.table.toString(),
                state.fieldError.orEmpty(),
                state.searchedDocument.orEmpty(),
                error?.toString().orEmpty(),
                error?.message.orEmpty(),
                error?.userMessage.orEmpty(),
            )
        texts.forEach { assertFalse(CPF_DIGITS in it, it) }
    }

    @Test
    fun searchLeavesNoTermInLogs() =
        runTest {
            val records = mutableListOf<String>()
            val handler =
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        records += record.message.orEmpty() + record.parameters.orEmpty().joinToString() +
                            record.thrown?.toString().orEmpty()
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                }
            val root = Logger.getLogger("")
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val oldOut = System.out
            val oldErr = System.err
            root.addHandler(handler)
            System.setOut(PrintStream(out, true))
            System.setErr(PrintStream(err, true))
            try {
                val source =
                    source().withReservations(2) { "Hóspede $NAME_TERM $it" }.apply {
                        documentHolders["CPF" to CPF] = setOf(guestId(1))
                    }
                val state = started(source)
                state.name = NAME_TERM
                state.search()
                runCurrent()
                assertEquals(2, state.table.rows.size)

                state.documentNumber = CPF
                state.searchByDocument()
                runCurrent()
                assertEquals(1, state.table.rows.size)

                source.failures[FakeReservationSearchSource.Op.SEARCH_BY_DOCUMENT] =
                    problem(400, "DOCUMENT_NUMBER_INVALID")
                state.documentNumber = CPF
                state.searchByDocument()
                runCurrent()
                assertNotNull(state.table.error)
                state.close()
            } finally {
                root.removeHandler(handler)
                System.setOut(oldOut)
                System.setErr(oldErr)
            }
            val printed = out.toString() + err.toString() + records.joinToString("\n")
            for (term in listOf(NAME_TERM, CPF, CPF_DIGITS)) assertFalse(term in printed, "termo impresso")
        }

    @Test
    fun userSearchWhileDayHeldIsNotReplaced() =
        runTest {
            val source =
                source().withReservations(2).apply { documentHolders["CPF" to CPF] = setOf(guestId(2)) }
            val gate = source.hold(FakeReservationSearchSource.Op.BUSINESS_DAY)
            val state = state(source)
            state.start()
            runCurrent()

            state.name = "a"
            state.documentNumber = CPF
            state.searchByDocument()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(0, source.searchCalls.size)
            assertEquals(1, source.documentCalls.size)
            assertEquals("CPF ***4725", state.searchedDocument)
            assertNull(state.fieldError)
            assertEquals(listOf("R-2"), state.codes())
            assertEquals(BusinessDay(today, null), state.day)
            assertEquals("", state.fromText)
            assertEquals("", state.toText)
        }

    @Test
    fun retryAfterDayFailureStillLoadsDefaultPeriod() =
        runTest {
            val source =
                source().withReservations(1).apply {
                    failures[FakeReservationSearchSource.Op.BUSINESS_DAY] = problem(503, "X")
                }
            val state = started(source)
            assertNotNull(state.error)

            source.failures.remove(FakeReservationSearchSource.Op.BUSINESS_DAY)
            state.start()
            runCurrent()

            assertNull(state.error)
            assertEquals(ReservationFilter(from = today, to = inThirtyDays), source.searchCalls.single().filter)
            assertEquals(listOf("R-1"), state.codes())
        }

    @Test
    fun closeWithPageHeldReadsNoHolderNorRoomTypes() =
        runTest {
            val source = source().withReservations(1)
            val gate = source.hold(FakeReservationSearchSource.Op.SEARCH)
            val state = started(source)
            // A página presa é a de uma recarga da tela (fora do escopo do estado): o `close()` não a cancela.
            backgroundScope.launch { state.table.reload() }
            runCurrent()
            assertEquals(2, source.searchCalls.size)

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertTrue(source.holderCalls.isEmpty())
            assertEquals(0, source.roomTypesCalls)
            assertTrue(state.table.rows.isEmpty())
        }

    @Test
    fun closeDiscardsLateResultAndQuery() =
        runTest {
            val source =
                source().withReservations(5).apply {
                    documentHolders["CPF" to CPF] = (1..5).map(::guestId).toSet()
                }
            val state = started(source, pageSize = 2)
            state.documentNumber = CPF
            state.searchByDocument()
            runCurrent()
            assertEquals(listOf("R-1", "R-2"), state.codes())

            val gate = source.hold(FakeReservationSearchSource.Op.SEARCH_BY_DOCUMENT)
            loadMore(state)
            assertEquals(2, source.documentCalls.size)

            state.documentNumber = "123"
            state.close()
            assertEquals("", state.documentNumber)
            assertNull(state.searchedDocument)

            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("R-1", "R-2"), state.codes())
            assertNull(state.table.error)

            val searches = source.searchCalls.size
            loadMore(state)
            backgroundScope.launch { state.table.reload() }
            runCurrent()
            loadMore(state)

            assertEquals(2, source.documentCalls.size)
            assertEquals(searches, source.searchCalls.size)
            assertTrue(state.table.rows.isEmpty())

            // Depois do `close()`, nada mais sai nem é atribuído.
            state.number = "R-1"
            state.search()
            state.documentNumber = CPF
            state.searchByDocument()
            state.start()
            runCurrent()
            assertEquals(2, source.documentCalls.size)
            assertEquals(searches, source.searchCalls.size)
            assertNull(state.searchedDocument)
            assertNull(state.fieldError)
        }
}
