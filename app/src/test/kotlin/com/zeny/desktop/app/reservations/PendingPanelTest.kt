package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-11-13T12:00:00Z")

@OptIn(ExperimentalTestApi::class)
class PendingPanelTest {
    private class Rig(
        val source: FakePendingSource,
        val state: PendingListsState,
        val opened: MutableList<String>,
    )

    private val guestId = Uuid.random().toString()

    private fun source() =
        FakePendingSource().apply {
            holders[guestId] = HolderSummary("Ana Souza", null)
            guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId)
            noShows += searchItemOf(code = "N-1", holderGuestId = guestId)
        }

    private fun ComposeUiTest.panel(source: FakePendingSource): Rig {
        val opened = mutableListOf<String>()
        lateinit var state: PendingListsState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { PendingListsState(source, scope, { NOW }) }
            ZenyTheme { PendingPanel(state, onOpen = { opened += it }) }
        }
        state.start()
        waitForIdle()
        return Rig(source, state, opened)
    }

    private fun ComposeUiTest.choose(list: PendingList) {
        onNodeWithTag("pending-list-${list.name}").performClick()
        waitForIdle()
    }

    @Test
    fun chipsSwitchList() =
        runComposeUiTest {
            panel(source())
            onNodeWithTag("pending-list-GUARANTEES").assertIsSelected()
            onNodeWithText("G-1").assertExists()
            onNodeWithText("N-1").assertDoesNotExist()

            choose(PendingList.NO_SHOWS)
            onNodeWithTag("pending-list-NO_SHOWS").assertIsSelected()
            onNodeWithText("N-1").assertExists()
            onNodeWithText("G-1").assertDoesNotExist()
        }

    @Test
    fun rowClickOpensReservation() =
        runComposeUiTest {
            val source = source()
            val rig = panel(source)
            val guarantee = source.guarantees.single()
            val noShow = source.noShows.single()
            val guaranteeId = guarantee.id.toString()
            val noShowId = noShow.id.toString()
            onNodeWithTag("row-$guaranteeId").performClick()
            assertEquals(listOf(guaranteeId), rig.opened)

            choose(PendingList.NO_SHOWS)
            onNodeWithTag("row-$noShowId").performClick()
            assertEquals(listOf(guaranteeId, noShowId), rig.opened)
        }

    @Test
    fun emptyMessageFollowsList() =
        runComposeUiTest {
            panel(FakePendingSource())
            val messages =
                mapOf(
                    PendingList.GUARANTEES to "Nenhuma garantia pendente.",
                    PendingList.DEPOSITS to "Nenhum depósito em falta.",
                    PendingList.NO_SHOWS to "Nenhum no-show previsto.",
                    PendingList.REALLOCATION to "Nenhuma realocação necessária.",
                )
            messages.forEach { (list, message) ->
                choose(list)
                onNodeWithText(message).assertExists()
                messages.values.filter { it != message }.forEach { onNodeWithText(it).assertDoesNotExist() }
            }
        }

    @Test
    fun errorOfSelectedListIsShown() =
        runComposeUiTest {
            val source =
                source().apply {
                    failures[FakePendingSource.Op.NO_SHOWS] = ApiError.Problem(500, "X", null, emptyList())
                }
            panel(source)
            choose(PendingList.NO_SHOWS)
            onNodeWithTag("table-error").assertExists()
            onNodeWithText("Tentar de novo").assertExists()

            // A falha de uma lista não derruba as outras.
            choose(PendingList.GUARANTEES)
            onNodeWithText("G-1").assertExists()
            onNodeWithTag("table-error").assertDoesNotExist()

            // Com o servidor de volta, "Tentar de novo" traz a lista.
            source.failures.clear()
            choose(PendingList.NO_SHOWS)
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            onNodeWithText("N-1").assertExists()
            onNodeWithTag("table-error").assertDoesNotExist()
        }

    @Test
    fun refreshReloadsSelected() =
        runComposeUiTest {
            val source = source()
            panel(source)
            choose(PendingList.NO_SHOWS)
            val noShowCalls = source.noShowCalls.size
            val guaranteeCalls = source.guaranteeCalls.size

            onNodeWithTag("pending-refresh").performClick()
            waitForIdle()

            assertEquals(noShowCalls + 1, source.noShowCalls.size)
            assertEquals(guaranteeCalls, source.guaranteeCalls.size)
            onNodeWithText("N-1").assertExists()
        }
}
