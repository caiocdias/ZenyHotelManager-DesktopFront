package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val MANAGE = "reservations.reservations.manage"
private const val READ = "reservations.reservations.read"
private const val CHANGE_BUTTON = "open-reservation-change"
private val NOW = Instant.parse("2026-11-13T12:00:00Z")

/** A reserva do servidor para o id e a situação dados; só o que o detalhe e as ações leem. */
private fun detailOf(
    item: ReservationListItemResponse,
    id: Uuid,
) = ReservationDetailResponse(
    bookingCode = item.bookingCode,
    holderGuestId = item.holderGuestId,
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = item.arrival,
            bookingId = item.bookingId,
            cancellationTerms =
                CancellationTermsResponse(
                    24,
                    "Flexível",
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = item.code,
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = item.departure,
            folioId = item.folioId,
            guaranteeTerms =
                GuaranteeTermsResponse(name = "Política", policyId = Uuid.random(), type = "NONE", version = 1),
            guests = emptyList(),
            id = id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = item.ratePlanId,
            roomTotal = "300.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = item.status,
            total = "300.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

@OptIn(ExperimentalTestApi::class)
class PendingTabTest {
    private class Rig(
        val pending: FakePendingSource,
        val reception: FakeReceptionSource,
        val guaranteeId: String,
        val noShowId: String,
    )

    private val guestId = Uuid.random().toString()

    private fun rig(): Rig {
        val pending = FakePendingSource()
        val reception = FakeReceptionSource()
        pending.holders[guestId] = HolderSummary("Ana Souza", null)
        reception.holders[guestId] = HolderSummary("Ana Souza", null)
        reception.permissions = setOf(MANAGE, READ)
        val guarantee = pendingGuaranteeOf(code = "G-1", holderGuestId = guestId)
        val noShow = searchItemOf(code = "N-1", holderGuestId = guestId)
        pending.guarantees += guarantee
        pending.noShows += noShow
        listOf(
            guarantee.id to searchItemOf(code = "G-1", status = "CONFIRMED", holderGuestId = guestId),
            noShow.id to noShow,
        ).forEach { (id, item) ->
            reception.reservations[id.toString()] = detailOf(item, id)
            reception.histories[id.toString()] = emptyList()
            reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        }
        return Rig(pending, reception, guarantee.id.toString(), noShow.id.toString())
    }

    private fun ComposeUiTest.tab(
        rig: Rig,
        withDetail: Boolean = true,
        actions: ReservationsActions? = null,
    ): PendingListsState {
        lateinit var state: PendingListsState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { PendingListsState(rig.pending, scope, { NOW }) }
            val detail = if (withDetail) remember { ReservationDetailState(rig.reception, scope) } else null
            ZenyTheme { PendingTab(state, detail, actions) }
        }
        state.start()
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun changeActions() =
        ReservationsActions(
            setOf(MANAGE, READ),
            change = { error("não usado") },
            confirm = null,
            confirmation = null,
        )

    @Test
    fun rowClickOpensDetailBeside() =
        runComposeUiTest {
            val rig = rig()
            tab(rig)
            assertTrue(!hasTag("reservation-detail"))

            click("row-${rig.guaranteeId}")

            onNodeWithTag("reservation-detail").assertExists()
            assertEquals(listOf(rig.guaranteeId), rig.reception.reservationCalls.distinct())
            // O painel segue ao lado: o seletor e a linha continuam na tela.
            onNodeWithTag("pending-list-GUARANTEES").assertExists()
            onNodeWithTag("row-${rig.guaranteeId}").assertExists()
        }

    @Test
    fun detailShowsActionsFromReservationsActions() =
        runComposeUiTest {
            val rig = rig()
            tab(rig, actions = changeActions())

            click("row-${rig.guaranteeId}")

            onNodeWithText("Alterar").assertExists()
            assertTrue(hasTag(CHANGE_BUTTON))
        }

    @Test
    fun noActionsWithoutReservationsActions() =
        runComposeUiTest {
            val rig = rig()
            tab(rig, actions = null)

            click("row-${rig.guaranteeId}")

            onNodeWithTag("reservation-detail").assertExists()
            assertTrue(!hasTag(CHANGE_BUTTON))
        }

    @Test
    fun noDetailWithoutReceptionSource() =
        runComposeUiTest {
            val rig = rig()
            tab(rig, withDetail = false, actions = changeActions())

            click("row-${rig.guaranteeId}")

            assertTrue(!hasTag("reservation-detail"))
            assertTrue(!hasTag(CHANGE_BUTTON))
            assertEquals(emptyList(), rig.reception.reservationCalls)
            onNodeWithTag("row-${rig.guaranteeId}").assertExists()
        }

    @Test
    fun panelKeepsWorkingWithDetailOpen() =
        runComposeUiTest {
            val rig = rig()
            tab(rig)
            click("row-${rig.guaranteeId}")
            onNodeWithTag("reservation-detail").assertExists()

            click("pending-list-NO_SHOWS")
            onNodeWithTag("pending-list-NO_SHOWS").assertIsSelected()
            onNodeWithTag("row-${rig.noShowId}").assertExists()

            val calls = rig.pending.noShowCalls.size
            click("pending-refresh")
            assertEquals(calls + 1, rig.pending.noShowCalls.size)

            click("row-${rig.noShowId}")
            onNodeWithTag("reservation-detail").assertExists()
            assertEquals(rig.noShowId, rig.reception.reservationCalls.last())
        }
}
