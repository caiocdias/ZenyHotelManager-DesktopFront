package com.zeny.desktop.core.http

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ataque ao `CursorPager`: falha no meio, página vazia com cursor e listagem vazia. */
class CursorPagerAttackTest {
    private val calls = mutableListOf<String?>()

    /** Cada chamada ao `fetch` tira a próxima resposta da fila: uma página ou uma falha. */
    private fun pager(vararg answers: () -> Page<Int>): CursorPager<Int> {
        val queue = ArrayDeque(answers.toList())
        return CursorPager { cursor, _ ->
            calls += cursor
            queue.removeFirst()()
        }
    }

    private fun page(
        vararg items: Int,
        next: String?,
    ): () -> Page<Int> = { Page(items.toList(), next) }

    private fun failure(): () -> Page<Int> = { throw IOException("rede caiu") }

    @Test
    fun failedFetchDoesNotAdvanceAndTheRetryResendsTheSameCursor() =
        runTest {
            val pager = pager(page(1, next = "c1"), failure(), page(2, next = null))

            assertEquals(listOf(1), pager.next())
            assertFailsWith<IOException> { pager.next() }
            assertTrue(pager.hasMore)
            assertEquals(listOf(2), pager.next())

            assertEquals(listOf(null, "c1", "c1"), calls)
            assertFalse(pager.hasMore)
        }

    @Test
    fun failedFirstFetchRetriesFromTheStart() =
        runTest {
            val pager = pager(failure(), page(1, next = null))

            assertFailsWith<IOException> { pager.next() }
            assertTrue(pager.hasMore)
            assertEquals(listOf(1), pager.next())

            assertEquals(listOf<String?>(null, null), calls)
        }

    @Test
    fun emptyPageWithANewCursorKeepsGoing() =
        runTest {
            val pager = pager(page(next = "c1"), page(7, next = null))

            assertEquals(emptyList(), pager.next())
            assertTrue(pager.hasMore)
            assertEquals(listOf(7), pager.next())

            assertEquals(listOf(null, "c1"), calls)
        }

    @Test
    fun emptyListingEndsAfterTheFirstFetch() =
        runTest {
            val pager = pager(page(next = null))

            assertTrue(pager.hasMore)
            assertEquals(emptyList(), pager.next())
            assertFalse(pager.hasMore)
            assertEquals(emptyList(), pager.next())

            assertEquals(listOf<String?>(null), calls)
        }

    @Test
    fun emptyStringCursorStops() =
        runTest {
            val pager = pager(page(1, next = ""))

            assertEquals(listOf(1), pager.next())

            assertFalse(pager.hasMore)
            assertEquals(1, calls.size)
        }

    @Test
    fun repeatedCursorAfterSeveralPagesStopsWithoutAnotherFetch() =
        runTest {
            val pager = pager(page(1, next = "c1"), page(2, next = "c2"), page(3, next = "c2"))

            val all = buildList { while (pager.hasMore) addAll(pager.next()) }

            assertEquals(listOf(1, 2, 3), all)
            assertEquals(listOf(null, "c1", "c2"), calls)
        }
}
