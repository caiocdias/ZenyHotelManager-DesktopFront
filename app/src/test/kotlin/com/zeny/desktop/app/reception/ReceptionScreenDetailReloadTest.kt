package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import de.quati.kotlin.util.Option
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val OFFLINE_MESSAGE = "Não foi possível falar com o servidor. Confira o endereço e a rede."
private const val RAW_CODE = "RAW_RELOAD_CODE"

private val reloadPlanId = Uuid.parse("40000000-0000-0000-0000-000000000001")
private val reloadReservationId = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val reloadGuestId = Uuid.parse("30000000-0000-0000-0000-000000000001")

private fun reloadListItem() =
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
        holderGuestId = reloadGuestId,
        id = reloadReservationId,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = reloadPlanId,
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

private fun reloadDetail() =
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = reloadGuestId,
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
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = reloadReservationId,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = reloadPlanId,
                roomTotal = "500.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

/** A releitura do detalhe que falha com a reserva à vista: o detalhe fica, e a falha aparece por cima dele. */
@OptIn(ExperimentalTestApi::class)
class ReceptionScreenDetailReloadTest {
    private fun source() =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
            types += roomType(typeId(1), "Standard")
            pages[DayList.ARRIVALS to null] = Page(listOf(reloadListItem()), null)
            ratePlans[reloadPlanId.toString()] = "Tarifa Balcão"
            reservations[reloadReservationId.toString()] = reloadDetail()
            holders[reloadGuestId.toString()] = HolderSummary("Hóspede 1", "CPF ***.***.***-11")
        }

    private fun ComposeUiTest.screenWithOpenDetail(source: FakeReceptionSource) {
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(source, scope) }
            val detail = remember { ReservationDetailState(source, scope) }
            ZenyTheme { ReceptionScreen(panel, detail) }
        }
        waitForIdle()
        onNodeWithTag("row-$reloadReservationId").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.inDetail(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("reservation-detail")))

    private fun ComposeUiTest.inDetailError(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("detail-error")))

    @Test
    fun failedReloadShowsErrorOverStaleDetail() =
        runComposeUiTest {
            val source = source()
            screenWithOpenDetail(source)
            onNodeWithTag("detail-error").assertDoesNotExist()

            source.reservationError = ApiError.Network(IOException("offline"))
            onNodeWithText("Atualizar").performClick()
            waitForIdle()

            onNode(hasTestTag("detail-error") and hasAnyAncestor(hasTestTag("reservation-detail"))).assertExists()
            inDetailError(OFFLINE_MESSAGE).assertExists()
            inDetailError("Tentar de novo").assertExists()
            inDetail("Hóspede 1").assertExists()
            inDetail("R-1").assertExists()
        }

    @Test
    fun retryFromErrorBandRecovers() =
        runComposeUiTest {
            val source = source()
            screenWithOpenDetail(source)
            source.reservationError = ApiError.Network(IOException("offline"))
            onNodeWithText("Atualizar").performClick()
            waitForIdle()
            val reads = source.reservationCalls.size

            source.reservationError = null
            inDetailError("Tentar de novo").performClick()
            waitForIdle()

            assertEquals(reads + 1, source.reservationCalls.size)
            onNodeWithTag("detail-error").assertDoesNotExist()
            onNodeWithText(OFFLINE_MESSAGE).assertDoesNotExist()
            inDetail("Hóspede 1").assertExists()
        }

    @Test
    fun errorBandNeverShowsRawCode() =
        runComposeUiTest {
            val source = source()
            screenWithOpenDetail(source)
            source.reservationError = ApiError.Problem(500, RAW_CODE, "{\"type\":\"urn:zeny\"}", emptyList())
            onNodeWithText("Atualizar").performClick()
            waitForIdle()

            onNodeWithTag("detail-error").assertExists()
            onNodeWithText(RAW_CODE, substring = true).assertDoesNotExist()
            onNodeWithText("urn:zeny", substring = true).assertDoesNotExist()
        }
}
