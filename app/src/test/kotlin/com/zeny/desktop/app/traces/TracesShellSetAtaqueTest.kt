package com.zeny.desktop.app.traces

import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "frontdesk.traces.read"
private const val MANAGE = "frontdesk.traces.manage"
private const val RESOLVE = "frontdesk.traces.resolve"
private val DEPARTMENT = Department(Uuid.parse("40000000-0000-0000-0000-000000000001").toString(), "Governança")

private fun openTrace(n: Int) =
    TraceResponse(
        assigneeName = Option.Some("Maria"),
        createdAt = "2026-11-14T10:00:00Z",
        createdBy = Uuid.random(),
        departmentId = Uuid.parse(DEPARTMENT.id),
        departmentName = DEPARTMENT.name,
        dueOn = "2026-11-14",
        id = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0')),
        reservationId = Option.Undefined,
        roomId = Option.Undefined,
        roomNumber = Option.Undefined,
        status = "OPEN",
        text = "texto sigiloso $n",
        version = 1,
    )

/**
 * Ataques aos recados com o conjunto da casca (M4 E06): o conjunto do construtor é a única fonte das ações, e quem só
 * lê recados vê a lista carregada sem nenhuma ação, mesmo com setores carregados e a porta disposta a tudo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TracesShellSetAtaqueTest {
    @Test
    fun readOnlySetLoadsTheListWithoutAnyAction() =
        runTest {
            val source =
                FakeTracesSource().apply {
                    departments = listOf(DEPARTMENT)
                    seed(openTrace(1))
                    seed(openTrace(2))
                }
            val state = TracesListState(source, backgroundScope, setOf(READ))
            state.start()
            runCurrent()

            assertNull(state.error)
            assertEquals(2, state.table.rows.size, "a lista não carregou só com a leitura")
            assertTrue(state.table.rows.all { it.open })
            assertFalse(state.canCreate, "criar recado sem frontdesk.traces.manage")
            state.table.rows.forEach { row ->
                assertFalse(state.canEdit(row), "editar recado sem frontdesk.traces.manage")
                assertFalse(state.canResolve(row), "resolver recado sem frontdesk.traces.resolve")
            }
            state.close()
        }

    @Test
    fun theFakeSetNeverGrantsWhatTheConstructorSetDenies() =
        runTest {
            // O falso guarda um conjunto completo; o estado que nasce só com a leitura não o vê.
            val source =
                FakeTracesSource().apply {
                    permissions = setOf(READ, MANAGE, RESOLVE)
                    departments = listOf(DEPARTMENT)
                    seed(openTrace(1))
                }
            val state = TracesListState(source, backgroundScope, setOf(READ))
            state.start()
            runCurrent()
            val row = state.table.rows.single()

            assertEquals(setOf(READ), state.permissions)
            assertFalse(state.canCreate)
            assertFalse(state.canEdit(row))
            assertFalse(state.canResolve(row))
            state.close()
        }

    @Test
    fun manageAloneCreatesAndEditsButDoesNotResolveAfterStart() =
        runTest {
            val source =
                FakeTracesSource().apply {
                    departments = listOf(DEPARTMENT)
                    seed(openTrace(1))
                }
            val state = TracesListState(source, backgroundScope, setOf(READ, MANAGE))
            state.start()
            runCurrent()
            val row = state.table.rows.single()

            assertTrue(state.canCreate, "com manage e setores carregados, criar fica disponível")
            assertTrue(state.canEdit(row))
            assertFalse(state.canResolve(row), "resolver sem frontdesk.traces.resolve")
            state.close()
        }

    @Test
    fun reservationSectionUsesTheSameConstructorSet() =
        runTest {
            val source =
                FakeTracesSource().apply {
                    permissions = setOf(READ, MANAGE, RESOLVE)
                    departments = listOf(DEPARTMENT)
                }
            val reservation = "20000000-0000-0000-0000-000000000001"
            val state = TracesListState(source, backgroundScope, setOf(READ, RESOLVE), reservation)
            state.start()
            runCurrent()
            val row = TraceRow("t-1", "—", DEPARTMENT.name, "", "", TraceText.status("OPEN"), null, true, null)

            assertFalse(state.canCreate)
            assertFalse(state.canEdit(row))
            assertTrue(state.canResolve(row))
            state.close()
        }
}
