package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeExpiringSource : ExpiringPreAuthorizationSource {
    var pages: suspend (BusinessDate, String?) -> Page<CardPreAuthorizationResponse> =
        { _, _ -> Page(emptyList(), null) }
    val calls = mutableListOf<Triple<BusinessDate, String?, Int>>()

    override suspend fun page(
        to: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<CardPreAuthorizationResponse> {
        calls += Triple(to, cursor, limit)
        return pages(to, cursor)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra
class ExpiringPreAuthorizationsStateTest {
    private val source = FakeExpiringSource()
    private val payments = FakePaymentSource()

    private fun TestScope.state() = ExpiringPreAuthorizationsState(source, payments, backgroundScope)

    private fun TestScope.entered(): ExpiringPreAuthorizationsState =
        state().also {
            it.enter()
            runCurrent()
        }

    @Test
    fun entersWithTheBusinessDayPlusSevenAndReads() =
        runTest {
            source.pages = { _, _ ->
                Page(
                    listOf(
                        preAuthorizationOf(amount = "300.00", capturable = "250.00", status = "PARTIALLY_CAPTURED"),
                        preAuthorizationOf(amount = "80.00", capturable = "80.00", status = "EXPIRED"),
                    ),
                    "c-2",
                )
            }
            val state = entered()

            assertEquals("25/11/2026", state.untilText)
            assertNull(state.untilError)
            val first = Triple<BusinessDate, String?, Int>(BusinessDate.parse("2026-11-25"), null, 50)
            assertEquals(listOf(first), source.calls)
            assertEquals(
                listOf(
                    listOf("19/11/2026 23:59", "Capturada em parte", "R$ 300,00", "R$ 250,00"),
                    listOf("19/11/2026 23:59", "Vencida", "R$ 80,00", "R$ 80,00"),
                ),
                state.rows.map { listOf(it.expires, it.status, it.amount, it.capturable) },
            )
            assertTrue(state.hasMore)
            assertFalse(state.loading)
        }

    @Test
    fun loadMoreFollowsTheCursor() =
        runTest {
            val first = preAuthorizationOf(amount = "10.00")
            val second = preAuthorizationOf(amount = "20.00")
            source.pages = { _, cursor ->
                if (cursor == null) Page(listOf(first), "c-2") else Page(listOf(second), null)
            }
            val state = entered()

            state.loadMore()
            runCurrent()

            assertEquals(listOf("R$ 10,00", "R$ 20,00"), state.rows.map { it.amount })
            assertFalse(state.hasMore)
            assertEquals(listOf<String?>(null, "c-2"), source.calls.map { it.second })
        }

    @Test
    fun searchRereadsFromTheFirstPage() =
        runTest {
            source.pages = { _, cursor ->
                if (cursor == null) {
                    Page(listOf(preAuthorizationOf(amount = "10.00")), "c-2")
                } else {
                    Page(emptyList(), null)
                }
            }
            val state = entered()
            state.loadMore()
            runCurrent()

            source.pages = { _, _ -> Page(listOf(preAuthorizationOf(amount = "99.00")), null) }
            state.untilText = "30/11/2026"
            state.search()
            runCurrent()

            assertEquals(listOf("R$ 99,00"), state.rows.map { it.amount })
            assertEquals(BusinessDate.parse("2026-11-30"), source.calls.last().first)
            assertNull(source.calls.last().second)
            assertFalse(state.hasMore)
        }

    @Test
    fun searchDropsThePageInFlight() =
        runTest {
            val late = LateAnswer<Page<CardPreAuthorizationResponse>>()
            var slow = true
            source.pages = { _, _ ->
                if (slow) {
                    slow = false
                    late.await()
                } else {
                    Page(listOf(preAuthorizationOf(amount = "2.00")), null)
                }
            }
            val state = state()
            state.enter()
            runCurrent()
            assertTrue(state.loading)

            state.search()
            runCurrent()
            late.answer(Page(listOf(preAuthorizationOf(amount = "1.00")), "c-late"))
            runCurrent()

            assertEquals(listOf("R$ 2,00"), state.rows.map { it.amount })
            assertFalse(state.hasMore)
            assertFalse(state.loading)
        }

    @Test
    fun searchWithoutADateKeepsTheEnterInFlight() =
        runTest {
            val lateDay = LateAnswer<BusinessDay>()
            payments.businessDay = { lateDay.await() }
            val state = state()
            state.enter()
            runCurrent()
            assertTrue(lateDay.asked)

            state.search()
            runCurrent()
            assertEquals("Informe até quando.", state.untilError)

            lateDay.answer(BusinessDay(BusinessDate.parse("2026-11-18"), ZoneId.of("America/Sao_Paulo")))
            runCurrent()

            assertEquals("25/11/2026", state.untilText)
            assertNull(state.untilError)
            assertEquals(1, source.calls.size)
        }

    @Test
    fun invalidUntilDoesNotRead() =
        runTest {
            val state = entered()
            val reads = source.calls.size

            state.untilText = "31/02/2026"
            state.search()
            runCurrent()
            assertEquals("Data inválida.", state.untilError)

            state.untilText = ""
            state.search()
            runCurrent()
            assertEquals("Informe até quando.", state.untilError)
            assertEquals(reads, source.calls.size)

            state.untilText = "30/11/2026"
            state.search()
            runCurrent()
            assertNull(state.untilError)
            assertEquals(reads + 1, source.calls.size)
        }

    @Test
    fun businessDayFailureWaitsForTheDate() =
        runTest {
            payments.businessDay = { throw ApiError.Network(IllegalStateException("fora")) }
            val state = entered()

            assertEquals("", state.untilText)
            assertEquals("Informe até quando.", state.untilError)
            assertTrue(source.calls.isEmpty())
            assertNull(state.error)

            state.untilText = "30/11/2026"
            state.search()
            runCurrent()
            assertNull(state.untilError)
            assertEquals(listOf(BusinessDate.parse("2026-11-30")), source.calls.map { it.first })
        }

    @Test
    fun everyEnterRereads() =
        runTest {
            val state = entered()
            state.untilText = "30/11/2026"

            state.enter()
            runCurrent()

            assertEquals(
                listOf(BusinessDate.parse("2026-11-25"), BusinessDate.parse("2026-11-30")),
                source.calls.map { it.first },
            )
            assertEquals("30/11/2026", state.untilText)
            assertEquals(listOf<String?>(null, null), source.calls.map { it.second })
        }

    @Test
    fun malformedFieldsShowDashes() =
        runTest {
            source.pages = { _, _ ->
                Page(
                    listOf(
                        preAuthorizationOf(
                            amount = "abc",
                            capturable = "1e3",
                            status = "NOVA_SITUACAO",
                            expiresAt = "ontem",
                        ),
                    ),
                    null,
                )
            }
            val state = entered()

            val row = state.rows.single()
            assertEquals("—", row.amount)
            assertEquals("—", row.capturable)
            assertEquals("—", row.expires)
            assertEquals("Outro (NOVA_SITUACAO)", row.status)
        }

    @Test
    fun rowsNeverCarryCardData() =
        runTest {
            source.pages = { _, _ ->
                Page(listOf(preAuthorizationOf(cardBrand = "VISA", cardLast4 = "4242")), null)
            }
            val state = entered()

            val row = state.rows.single()
            val text = "$row $state"
            listOf("VISA", "4242", "AUT123").forEach { assertFalse(text.contains(it), it) }
            assertFalse(row.toString().contains(row.folioId))
            assertEquals(
                setOf("folioId", "expires", "status", "amount", "capturable"),
                ExpiringRow::class.java.declaredFields
                    .map { it.name }
                    .filterNot { it.startsWith("\$") }
                    .toSet(),
            )
        }

    @Test
    fun readFailureShowsTextAndRetries() =
        runTest {
            var fail = true
            source.pages = { _, _ ->
                if (fail) throw ApiError.Network(IllegalStateException("fora"))
                Page(listOf(preAuthorizationOf()), null)
            }
            val state = entered()

            assertEquals("Não foi possível falar com o servidor. Confira o endereço e a rede.", state.error)
            assertTrue(state.rows.isEmpty())
            assertFalse(state.loading)

            fail = false
            state.loadMore()
            runCurrent()

            assertNull(state.error)
            assertEquals(1, state.rows.size)
            assertEquals(listOf<String?>(null, null), source.calls.map { it.second })
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val late = LateAnswer<Page<CardPreAuthorizationResponse>>()
            source.pages = { _, _ -> late.await() }
            val state = state()
            state.enter()
            runCurrent()
            assertTrue(late.asked)

            state.close()
            late.answer(Page(listOf(preAuthorizationOf()), "c-late"))
            runCurrent()

            assertTrue(state.rows.isEmpty())
            assertFalse(state.hasMore)

            state.enter()
            state.search()
            runCurrent()
            assertEquals(1, source.calls.size)
        }
}
