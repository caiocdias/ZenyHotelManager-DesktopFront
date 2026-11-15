package com.zeny.desktop.app.traces

import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.sdk.model.TraceResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val fallbackDept = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val fallbackOther = Department("40000000-0000-0000-0000-000000000002", "Manutenção")

private fun fallbackTrace() =
    TraceResponse(
        createdAt = "2026-11-14T10:00:00Z",
        createdBy = Uuid.random(),
        departmentId = Uuid.parse(fallbackDept.id),
        departmentName = fallbackDept.name,
        dueOn = "2026-11-14",
        id = Uuid.parse("50000000-0000-0000-0000-000000000001"),
        status = "OPEN",
        text = "Texto",
        version = 1,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class TraceFormDepartmentFallbackTest {
    @Test
    fun editKeepsCurrentDepartmentWhenListIsEmptyButNotDenied() =
        runTest {
            val trace = fallbackTrace()
            val source = FakeTracesSource().apply { seed(trace) }
            val id = trace.id.toString()
            val form = TraceFormState(source, backgroundScope, TraceTarget.Existing(id), emptyList(), false) {}
            form.start()
            runCurrent()
            assertEquals(listOf(fallbackDept), form.departmentOptions)
        }

    @Test
    fun editPutsCurrentDepartmentFirstWhenMissingFromList() =
        runTest {
            val trace = fallbackTrace()
            val source = FakeTracesSource().apply { seed(trace) }
            val id = trace.id.toString()
            val form =
                TraceFormState(source, backgroundScope, TraceTarget.Existing(id), listOf(fallbackOther), false) {}
            form.start()
            runCurrent()
            assertEquals(listOf(fallbackDept, fallbackOther), form.departmentOptions)
        }
}
