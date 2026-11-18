package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.StayGuestResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val resUuid = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val otherResUuid = Uuid.parse("20000000-0000-0000-0000-000000000002")
private val holderId = Uuid.parse("30000000-0000-0000-0000-000000000001")
private val firstGuest = Uuid.parse("30000000-0000-0000-0000-000000000011")
private val secondGuest = Uuid.parse("30000000-0000-0000-0000-000000000012")
private val ratePlan = Uuid.parse("40000000-0000-0000-0000-000000000001")

private fun stayGuest(
    id: Uuid,
    primary: Boolean,
) = StayGuestResponse(guestId = id, primary = primary)

private fun detailWith(
    id: Uuid,
    guests: List<StayGuestResponse>,
) = ReservationDetailResponse(
    bookingCode = "B-1",
    holderGuestId = holderId,
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = "2026-11-13",
            bookingId = Uuid.random(),
            cancellationTerms =
                CancellationTermsResponse(
                    24,
                    "Flexível",
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = "R-${id.toString().takeLast(1)}",
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = "2026-11-15",
            folioId = Uuid.random(),
            guaranteeTerms =
                GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
            guests = guests,
            id = id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = ratePlan,
            roomTotal = "500.00",
            roomTypeId = Uuid.parse(typeId(1)),
            segment = "LEISURE",
            status = "CHECKED_IN",
            total = "500.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun twoGuests() = listOf(stayGuest(firstGuest, true), stayGuest(secondGuest, false))

private fun baseSource(guests: List<StayGuestResponse>) =
    FakeReceptionSource().apply {
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        types += roomType(typeId(1), "Standard")
        reservations[resUuid.toString()] = detailWith(resUuid, guests)
        reservations[otherResUuid.toString()] = detailWith(otherResUuid, emptyList())
        holders[holderId.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
        holders[firstGuest.toString()] = HolderSummary("Ana Lima", "CPF ***.***.***-11")
        holders[secondGuest.toString()] = HolderSummary("Bruno Lima", "RG ***22")
        ratePlans[ratePlan.toString()] = "Tarifa Balcão"
    }

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailGuestsTest {
    private val id = resUuid.toString()

    private fun TestScope.opened(
        source: FakeReceptionSource,
        openId: String = id,
    ) = ReservationDetailState(source, backgroundScope).also {
        it.open(openId)
        runCurrent()
    }

    @Test
    fun stayGuestsListedWithMaskedDocument() =
        runTest {
            val view = assertNotNull(opened(baseSource(twoGuests())).view)

            assertEquals(
                listOf(
                    GuestLine("Ana Lima", "CPF ***.***.***-11", true),
                    GuestLine("Bruno Lima", "RG ***22", false),
                ),
                view.stayGuests,
            )
            assertNull(view.stayGuestsError)
        }

    @Test
    fun exposesOpenReservation() =
        runTest {
            val state = opened(baseSource(twoGuests()))

            assertEquals(resUuid, state.reservation?.reservation?.id)
            state.close()
            assertNull(state.reservation)
        }

    @Test
    fun reservationIsNullWhileAnotherIdLoadsAndStaleAnswerNeverLands() =
        runTest {
            val source = baseSource(twoGuests())
            val state = opened(source)
            source.holdReservation = true
            state.open(otherResUuid.toString())
            runCurrent()

            assertNull(state.reservation)
            source.heldReservations.forEach { it.second.complete(Unit) }
            runCurrent()
            assertEquals(otherResUuid, state.reservation?.reservation?.id)
        }

    @Test
    fun stayGuestForbiddenShowsDash() =
        runTest {
            val source = baseSource(twoGuests())
            source.holderErrors[firstGuest.toString()] = ApiError.Problem(403, null, null, emptyList())
            source.holderErrors[secondGuest.toString()] = ApiError.Problem(404, null, null, emptyList())
            val view = assertNotNull(opened(source).view)

            assertEquals(listOf(GuestLine("—", null, true), GuestLine("—", null, false)), view.stayGuests)
            assertNull(view.stayGuestsError)
        }

    @Test
    fun stayGuestErrorKeepsDetail() =
        runTest {
            val source = baseSource(twoGuests())
            source.holderErrors[secondGuest.toString()] = ApiError.Problem(500, null, null, emptyList())
            val state = opened(source)
            val view = assertNotNull(state.view)

            assertNull(state.error)
            assertEquals("Maria Souza", view.holder)
            assertTrue(view.stayGuests.isEmpty())
            assertNotNull(view.stayGuestsError)
        }

    @Test
    fun viewToStringStillHidesData() =
        runTest {
            val state = opened(baseSource(twoGuests()))
            val text = assertNotNull(state.view).toString() + state.toString() + GuestLine("Ana Lima", "CPF 1", true)

            assertFalse("Ana" in text || "Lima" in text || "CPF" in text || "Maria" in text)
        }
}

@OptIn(ExperimentalTestApi::class)
class ReservationDetailGuestsScreenTest {
    private fun ComposeUiTest.openWith(guests: List<StayGuestResponse>) {
        val source = baseSource(guests)
        source.pages[DayList.ARRIVALS to null] =
            Page(listOf(listItem()), null)
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope, source.permissions) }
            val detail = remember { ReservationDetailState(source, scope) }
            ZenyTheme { ReceptionScreen(panel, detail) }
        }
        waitForIdle()
        onNodeWithTag("row-$resUuid").performClick()
        waitForIdle()
    }

    @Test
    fun noStayGuestsHidesSection() =
        runComposeUiTest {
            openWith(emptyList())
            onNodeWithText("Histórico").assertExists()
            onNodeWithText("Hóspedes da estadia").assertDoesNotExist()
        }

    @Test
    fun stayGuestsSectionShowsMasked() =
        runComposeUiTest {
            openWith(twoGuests())
            onNodeWithText("Hóspedes da estadia").assertExists()
            onNodeWithText("Ana Lima · CPF ***.***.***-11 (principal)").assertExists()
            onNodeWithText("Bruno Lima · RG ***22").assertExists()
        }
}

private fun listItem() =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-1",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "R-1",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = holderId,
        id = resUuid,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = Uuid.random(),
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )
