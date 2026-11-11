package com.zeny.desktop.core.http

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursorPagerTest {
    private val calls = mutableListOf<Pair<String?, Int>>()

    private fun pager(vararg pages: Page<Int>): CursorPager<Int> {
        val queue = ArrayDeque(pages.toList())
        return CursorPager { cursor, limit ->
            calls += cursor to limit
            queue.removeFirst()
        }
    }

    @Test
    fun passesCursorAndStops() =
        runTest {
            val pager = pager(Page(listOf(1, 2), "c1"), Page(listOf(3), null))

            assertTrue(pager.hasMore)
            assertEquals(listOf(1, 2), pager.next())
            assertTrue(pager.hasMore)
            assertEquals(listOf(3), pager.next())
            assertFalse(pager.hasMore)
            assertEquals(emptyList(), pager.next())

            assertEquals(listOf<Pair<String?, Int>>(null to 50, "c1" to 50), calls)
        }

    @Test
    fun repeatedCursorStops() =
        runTest {
            val pager = pager(Page(listOf(1), "c1"), Page(listOf(2), "c1"))

            pager.next()
            assertEquals(listOf(2), pager.next())

            assertFalse(pager.hasMore)
            assertEquals(emptyList(), pager.next())
            assertEquals(2, calls.size)
        }

    @Test
    fun blankCursorStops() =
        runTest {
            val pager = pager(Page(listOf(1), " "))

            assertEquals(listOf(1), pager.next())

            assertFalse(pager.hasMore)
            assertEquals(emptyList(), pager.next())
            assertEquals(1, calls.size)
        }

    @Test
    fun customPageSizeGoesAsLimit() =
        runTest {
            val pager = CursorPager(pageSize = 10) { _, limit -> Page(listOf(limit), null) }

            assertEquals(listOf(10), pager.next())
        }
}
