package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val FULL_DOCUMENT = "52998224725"

private fun detailFor(item: ReservationListItemResponse) =
    ReservationDetailResponse(
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
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = item.id,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = item.ratePlanId,
                roomTotal = "300.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = item.status,
                total = "300.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

@OptIn(ExperimentalTestApi::class)
class ReservationsScreenTest {
    private class Rig(
        val source: FakeReservationSearchSource,
        val search: ReservationSearchState,
        val reception: FakeReceptionSource,
    )

    private fun fakeSource(count: Int = 2) =
        FakeReservationSearchSource().apply {
            repeat(count) { n ->
                val guest = Uuid.random().toString()
                val item = searchItemOf(code = "R-${n + 1}", holderGuestId = guest)
                reservations += item
                holders[guest] = HolderSummary("Hóspede ${n + 1}", null)
            }
        }

    private fun ComposeUiTest.screen(
        source: FakeReservationSearchSource,
        withDetail: Boolean = true,
        start: Boolean = true,
    ): Rig {
        val reception = FakeReceptionSource()
        source.reservations.forEach { item ->
            reception.reservations[item.id.toString()] = detailFor(item)
            reception.holders[item.holderGuestId.toString()] = HolderSummary("Hóspede", null)
            reception.histories[item.id.toString()] = emptyList()
            reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        }
        lateinit var search: ReservationSearchState
        setContent {
            val scope = rememberCoroutineScope()
            search = remember { ReservationSearchState(source, scope, pageSize = 2) }
            val detail = remember { if (withDetail) ReservationDetailState(reception, scope) else null }
            ZenyTheme { ReservationsScreen(search, detail) }
        }
        if (start) search.start()
        waitForIdle()
        return Rig(source, search, reception)
    }

    private fun ComposeUiTest.texts(): List<String> = collectTexts(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun collectTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } +
            node.config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
                ?.let { listOf(it) }
                .orEmpty() +
            node.children.flatMap { collectTexts(it) }

    @Test
    fun showsRowsWithHolderAndStatus() =
        runComposeUiTest {
            screen(fakeSource())
            onNodeWithText("Reservas").assertExists()
            onNodeWithText("Data operacional: 13/11/2026").assertExists()
            onNode(hasText("R-1") and hasText("Hóspede 1") and hasText("Confirmada")).assertExists()
            onNode(hasText("R-2") and hasText("Hóspede 2") and hasText("Confirmada")).assertExists()
        }

    @Test
    fun statusChipFiltersOnSubmit() =
        runComposeUiTest {
            val rig = screen(fakeSource())
            onNodeWithTag("status-IN_HOUSE").performClick()
            onNodeWithTag("search-submit").performClick()
            waitForIdle()
            assertEquals(
                setOf("IN_HOUSE"),
                rig.source.searchCalls
                    .last()
                    .filter.statuses,
            )
        }

    @Test
    fun loadMoreAppendsNextPage() =
        runComposeUiTest {
            val source = fakeSource(count = 3)
            screen(source)
            waitForIdle()
            assertEquals(listOf(null, "2"), source.searchCalls.map { it.cursor })
            onNodeWithText("R-1").assertExists()
            onNodeWithText("R-3").assertExists()
        }

    @Test
    fun documentAppearsMasked() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("document-number").performTextInput(FULL_DOCUMENT)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(
                FULL_DOCUMENT,
                source.documentCalls
                    .single()
                    .query.number,
            )
            onNodeWithTag("document-number").assertTextEquals("Número do documento", "")
            onNodeWithTag("searched-document").assertTextEquals("Reservas do documento CPF ***4725")
            assertTrue(texts().none { it.contains(FULL_DOCUMENT) })
        }

    @Test
    fun switchingToBrazilianTypeDropsTheCountry() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("doc-type-PASSPORT").performClick()
            onNodeWithTag("issuing-country").performTextInput("AR")
            onNodeWithTag("doc-type-CPF").performClick()
            onNodeWithTag("document-number").performTextInput(FULL_DOCUMENT)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(
                null,
                source.documentCalls
                    .single()
                    .query.issuingCountry,
            )
        }

    @Test
    fun countryFieldOnlyForForeignTypes() =
        runComposeUiTest {
            screen(fakeSource())
            onNodeWithTag("issuing-country").assertDoesNotExist()
            onNodeWithTag("doc-type-PASSPORT").performClick()
            onNodeWithTag("issuing-country").assertExists()
            onNodeWithTag("doc-type-FOREIGN_ID").performClick()
            onNodeWithTag("issuing-country").assertExists()
            onNodeWithTag("doc-type-RG").performClick()
            onNodeWithTag("issuing-country").assertDoesNotExist()
        }

    @Test
    fun noSearchBeforeStart() =
        runComposeUiTest {
            val source = fakeSource()
            val gate = source.hold(FakeReservationSearchSource.Op.BUSINESS_DAY)
            screen(source)
            waitForIdle()
            assertTrue(source.searchCalls.isEmpty())
            assertTrue(source.documentCalls.isEmpty())
            onNodeWithText("Carregando…").assertExists()
            gate.complete(Unit)
            waitForIdle()
            assertEquals(1, source.searchCalls.size)
        }

    @Test
    fun rowClickOpensReadOnlyDetail() =
        runComposeUiTest {
            screen(fakeSource())
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("open-check-in").assertDoesNotExist()
            onNodeWithTag("open-room-move").assertDoesNotExist()
            onNodeWithTag("open-undo-check-in").assertDoesNotExist()
            onNodeWithTag("open-registration-card").assertDoesNotExist()
        }

    @Test
    fun rowClickWithoutDetailDoesNothing() =
        runComposeUiTest {
            screen(fakeSource(), withDetail = false)
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertDoesNotExist()
        }

    @Test
    fun fieldErrorShown() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            val before = source.searchCalls.size
            onNodeWithTag("search-name").performTextInput("A")
            onNodeWithTag("search-submit").performClick()
            waitForIdle()
            onNodeWithTag("field-error").assertExists()
            assertTrue(texts().any { it == ReservationSearchText.SHORT_NAME })
            assertEquals(before, source.searchCalls.size)
        }

    @Test
    fun enterInFilterFieldSearchesAndInDocumentFieldSearchesByDocument() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            val before = source.searchCalls.size
            onNodeWithTag("search-number").performTextInput("R-2")
            onNodeWithTag("search-number").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(before + 1, source.searchCalls.size)
            assertTrue(source.documentCalls.isEmpty())
            onNodeWithTag("document-number").performTextInput(FULL_DOCUMENT)
            onNodeWithTag("document-number").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(1, source.documentCalls.size)
        }

    @Test
    fun businessDayErrorOffersRetry() =
        runComposeUiTest {
            val source = fakeSource()
            source.failures[FakeReservationSearchSource.Op.BUSINESS_DAY] = ApiError.Network(IOException("sem rede"))
            screen(source)
            onNodeWithText("Tentar de novo").assertExists()
            assertTrue(source.searchCalls.isEmpty())
            source.failures.clear()
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            onNodeWithText("R-1").assertExists()
            assertFalse(source.searchCalls.isEmpty())
        }
}
