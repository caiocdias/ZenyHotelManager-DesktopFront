package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * O painel com um despachante que roda cada corrotina na hora, na própria pilha: o `fetch` da tabela começa
 * dentro do `refresh` dela, antes de a atualização terminar de lançar as abas. A data das listas não pode
 * depender de o `fetch` esperar a vez.
 */
class DayPanelStateInlineFetchTest {
    private val today = BusinessDate.parse("2026-11-13")
    private val next = BusinessDate.parse("2026-11-14")

    private fun withInlinePanel(
        source: FakeReceptionSource,
        block: (DayPanelState) -> Unit,
    ) {
        val job = Job()
        val scope = CoroutineScope(Executor(Runnable::run).asCoroutineDispatcher() + job)
        val state = DayPanelState(source, scope, source.permissions)
        try {
            block(state)
        } finally {
            state.close()
            job.cancel()
        }
    }

    @Test
    fun endOfDayLoadsListsOfNewDate() {
        val source = FakeReceptionSource().apply { businessDay = BusinessDay(today, null) }
        withInlinePanel(source) { state ->
            assertEquals(DayList.entries.map { DayPageCall(it, today, null) }, source.dayPageCalls)

            source.businessDay = BusinessDay(next, null)
            source.dayPageCalls.clear()
            source.emit(RealtimeEvent.Message("end-of-day", "endofday.run-completed", "run-1", 1))

            assertEquals(BusinessDay(next, null), state.day)
            assertEquals(DayList.entries.map { DayPageCall(it, next, null) }, source.dayPageCalls)
        }
    }

    @Test
    fun refreshLoadsListsOfNewDate() {
        val source = FakeReceptionSource().apply { businessDay = BusinessDay(today, null) }
        withInlinePanel(source) { state ->
            source.businessDay = BusinessDay(next, null)
            source.dayPageCalls.clear()

            state.refresh()

            assertEquals(DayList.entries.map { DayPageCall(it, next, null) }, source.dayPageCalls)
        }
    }
}
