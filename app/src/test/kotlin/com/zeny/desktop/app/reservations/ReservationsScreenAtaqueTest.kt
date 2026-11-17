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
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val RAW_CODE = "INTERNAL_ERROR"
private const val RAW_UUID = "0b5c1c0e-6a52-4d0e-9a6f-3f0d3b8f6a11"
private const val RAW_DETAIL = "{\"code\":\"INTERNAL_ERROR\",\"id\":\"$RAW_UUID\"}"
private const val SERVER_FAILED = "O servidor falhou ao processar o pedido. Tente de novo em instantes."

private fun attackDetailFor(item: ReservationListItemResponse) =
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
class ReservationsScreenAtaqueTest {
    private class Rig(
        val source: FakeReservationSearchSource,
        val search: ReservationSearchState,
        val reception: FakeReceptionSource,
    )

    private fun fakeSource(
        count: Int = 2,
        status: String = "CONFIRMED",
    ) = FakeReservationSearchSource().apply {
        repeat(count) { n ->
            val guest = Uuid.random().toString()
            reservations += searchItemOf(code = "R-${n + 1}", holderGuestId = guest, status = status)
            holders[guest] = HolderSummary("Hóspede ${n + 1}", null)
        }
    }

    private fun ComposeUiTest.screen(source: FakeReservationSearchSource): Rig {
        val reception = FakeReceptionSource()
        source.reservations.forEach { item ->
            reception.reservations[item.id.toString()] = attackDetailFor(item)
            reception.holders[item.holderGuestId.toString()] = HolderSummary("Hóspede", null)
            reception.histories[item.id.toString()] = emptyList()
            reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        }
        lateinit var search: ReservationSearchState
        setContent {
            val scope = rememberCoroutineScope()
            search = remember { ReservationSearchState(source, scope, pageSize = 2) }
            val detail = remember { ReservationDetailState(reception, scope) }
            ZenyTheme { ReservationsScreen(search, detail) }
        }
        search.start()
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

    private fun problem() = ApiError.Problem(500, RAW_CODE, RAW_DETAIL, emptyList())

    private fun assertNoRaw(all: List<String>) {
        assertTrue(all.none { it.contains(RAW_CODE) }, "code cru na tela")
        assertTrue(all.none { it.contains(RAW_UUID) }, "UUID na tela")
        assertTrue(all.none { it.contains("{") || it.contains("\"code\"") }, "JSON na tela")
    }

    @Test
    fun loadMoreButtonClickAppendsNextPageWhenFiltering() =
        runComposeUiTest {
            val source = fakeSource(count = 3)
            val gate = source.hold(FakeReservationSearchSource.Op.SEARCH)
            screen(source)
            // Com o filtro da tabela ligado o DataTable não pede a página sozinho: só o botão "Carregar mais".
            onNodeWithTag("table-filter").performTextInput("R")
            waitForIdle()
            onNodeWithTag("table-filter").assertTextEquals("Filtrar", "R")
            gate.complete(Unit)
            waitForIdle()
            assertEquals(listOf<String?>(null), source.searchCalls.map { it.cursor })
            onNodeWithText("R-3").assertDoesNotExist()
            onNodeWithTag("load-more").performClick()
            waitForIdle()
            assertEquals(listOf(null, "2"), source.searchCalls.map { it.cursor })
            onNodeWithText("R-3").assertExists()
            onNodeWithTag("load-more").assertDoesNotExist()
        }

    @Test
    fun passportSearchShowsOnlyMaskedNumberAndClearsField() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("doc-type-PASSPORT").performClick()
            onNodeWithTag("document-number").performTextInput("AB1234567")
            onNodeWithTag("issuing-country").performTextInput("AR")
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(
                "AB1234567",
                source.documentCalls
                    .single()
                    .query.number,
            )
            onNodeWithTag("document-number").assertTextEquals("Número do documento", "")
            assertTrue(texts().any { it.startsWith("Reservas do documento ") && it.endsWith("***4567") })
            assertTrue(texts().none { it.contains("AB1234567") })
        }

    @Test
    fun shortDocumentNumberRevealsNoCharacter() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("document-number").performTextInput("1234")
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(1, source.documentCalls.size)
            onNodeWithTag("searched-document").assertTextEquals("Reservas do documento CPF ***")
            assertTrue(texts().none { it.contains("1234") })
        }

    @Test
    fun documentResultsListedAndBandLeavesOnRegularSearch() =
        runComposeUiTest {
            val source = fakeSource(count = 2)
            source.documentHolders["CPF" to "52998224725"] =
                setOf(source.reservations[0].holderGuestId.toString())
            screen(source)
            onNodeWithTag("document-number").performTextInput("52998224725")
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            onNodeWithText("R-1").assertExists()
            onNodeWithText("R-2").assertDoesNotExist()
            assertTrue(texts().none { it.contains("52998224725") })
            onNodeWithTag("search-submit").performClick()
            waitForIdle()
            onNodeWithTag("searched-document").assertDoesNotExist()
            onNodeWithText("R-2").assertExists()
        }

    @Test
    fun documentSearchFailureShowsCatalogTextWithoutNumberOrRawData() =
        runComposeUiTest {
            val source = fakeSource()
            source.failures[FakeReservationSearchSource.Op.SEARCH_BY_DOCUMENT] = problem()
            screen(source)
            onNodeWithTag("document-number").performTextInput("52998224725")
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            val all = texts()
            assertTrue(all.any { it == SERVER_FAILED })
            assertTrue(all.none { it.contains("52998224725") })
            assertNoRaw(all)
        }

    @Test
    fun pageErrorShowsCatalogTextWithoutJsonUuidOrCode() =
        runComposeUiTest {
            val source = fakeSource()
            source.failures[FakeReservationSearchSource.Op.SEARCH] = problem()
            screen(source)
            onNodeWithTag("table-error").assertExists()
            val all = texts()
            assertTrue(all.any { it == SERVER_FAILED })
            assertNoRaw(all)
        }

    @Test
    fun forbiddenListShowsPermissionTextNotStatusCode() =
        runComposeUiTest {
            val source = fakeSource()
            source.failures[FakeReservationSearchSource.Op.SEARCH] =
                ApiError.Problem(403, "ACCESS_DENIED", RAW_DETAIL, emptyList())
            screen(source)
            val all = texts()
            assertTrue(all.any { it == "Você não tem permissão para esta ação." })
            assertNoRaw(all)
            assertTrue(all.none { it.contains("403") })
        }

    @Test
    fun businessDayErrorShowsCatalogTextWithoutRawData() =
        runComposeUiTest {
            val source = fakeSource()
            source.failures[FakeReservationSearchSource.Op.BUSINESS_DAY] = problem()
            screen(source)
            val all = texts()
            assertTrue(all.any { it == SERVER_FAILED })
            assertNoRaw(all)
            onNodeWithTag("search-submit").assertDoesNotExist()
        }

    @Test
    fun detailErrorShowsCatalogTextWithoutRawData() =
        runComposeUiTest {
            val rig = screen(fakeSource())
            rig.reception.reservationError = problem()
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            val all = texts()
            assertTrue(all.any { it == SERVER_FAILED })
            assertNoRaw(all)
        }

    @Test
    fun unknownStatusRendersLabelInRowAndDetailWithoutCrash() =
        runComposeUiTest {
            screen(fakeSource(status = "FUTURE_STATUS_X"))
            val label = ReceptionText.status("FUTURE_STATUS_X").label
            onNodeWithText("R-1").assertExists()
            assertTrue(texts().any { it == label })
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            // O rótulo do desconhecido é "Outro (VALOR)" (ExtensibleEnumLabels), na linha e no detalhe.
            assertTrue(texts().count { it == label } >= 2, "rótulo do desconhecido na linha e no detalhe")
        }

    @Test
    fun detailIsReadOnlyForInHouseReservation() =
        runComposeUiTest {
            screen(fakeSource(count = 1, status = "IN_HOUSE"))
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            val all = texts()
            listOf("Fazer check-in", "Trocar UH", "Desfazer check-in", "Ficha de registro").forEach { label ->
                assertFalse(all.contains(label), "$label apareceu")
            }
            listOf("open-check-in", "open-room-move", "open-undo-check-in", "open-registration-card").forEach {
                onNodeWithTag(it).assertDoesNotExist()
            }
        }

    @Test
    fun secondRowSwitchesDetailAndCloseButtonHidesIt() =
        runComposeUiTest {
            val rig = screen(fakeSource())
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithText("R-2").performClick()
            waitForIdle()
            assertEquals(
                rig.source.reservations[1]
                    .id
                    .toString(),
                rig.reception.reservationCalls.last(),
            )
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithText("Fechar").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertDoesNotExist()
        }

    @Test
    fun statusChipTogglesOffAndRemainingStatusGoesOnSubmit() =
        runComposeUiTest {
            val rig = screen(fakeSource())
            onNodeWithTag("status-IN_HOUSE").performClick()
            onNodeWithTag("status-CONFIRMED").performClick()
            onNodeWithTag("status-IN_HOUSE").performClick()
            onNodeWithTag("search-submit").performClick()
            waitForIdle()
            assertEquals(
                setOf("CONFIRMED"),
                rig.source.searchCalls
                    .last()
                    .filter.statuses,
            )
        }

    @Test
    fun fieldErrorClearsAfterValidSearch() =
        runComposeUiTest {
            screen(fakeSource())
            onNodeWithTag("search-name").performTextInput("A")
            onNodeWithTag("search-submit").performClick()
            waitForIdle()
            onNodeWithTag("field-error").assertExists()
            onNodeWithTag("search-name").performTextInput("b")
            onNodeWithTag("search-submit").performClick()
            waitForIdle()
            onNodeWithTag("field-error").assertDoesNotExist()
        }

    @Test
    fun documentWithoutNumberShowsFieldErrorAndNoBandOrCall() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            onNodeWithTag("field-error").assertExists()
            onNodeWithTag("searched-document").assertDoesNotExist()
            assertTrue(source.documentCalls.isEmpty())
        }

    @Test
    fun enterInCountryFieldSearchesByDocument() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("doc-type-PASSPORT").performClick()
            onNodeWithTag("document-number").performTextInput("AB1234567")
            onNodeWithTag("issuing-country").performTextInput("AR")
            onNodeWithTag("issuing-country").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(1, source.documentCalls.size)
        }

    @Test
    fun ctrlZUndoesTypingInDocumentFieldBeforeSearch() =
        runComposeUiTest {
            screen(fakeSource())
            onNodeWithTag("document-number").performClick()
            "529982247".forEach { onNodeWithTag("document-number").performTextInput(it.toString()) }
            waitForIdle()
            // Controle do teste do undo: o Ctrl+Z do teste chega mesmo ao UndoManager do campo.
            onNodeWithTag("document-number").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.Z)
                keyUp(Key.CtrlLeft)
            }
            waitForIdle()
            assertFalse(texts().any { it == "529982247" }, "o Ctrl+Z não desfez nada: o teste do undo seria vazio")
        }

    @Test
    fun undoInDocumentFieldAfterSearchDoesNotBringTheNumberBack() =
        runComposeUiTest {
            val source = fakeSource()
            screen(source)
            onNodeWithTag("document-number").performClick()
            // `pressKey` não gera texto no teste de desktop (o guarda abaixo pegou isso); um caractere por vez,
            // como a digitação real, é o que grava um snapshot de undo por edição.
            "529982247".forEach { onNodeWithTag("document-number").performTextInput(it.toString()) }
            waitForIdle()
            // Prova de que o teste não é vazio: o número foi mesmo digitado, tecla a tecla.
            assertTrue(texts().any { it == "529982247" }, "digitação por teclas não chegou ao campo")
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(1, source.documentCalls.size)
            onNodeWithTag("document-number").assertTextEquals("Número do documento", "")
            onNodeWithTag("document-number").performClick()
            repeat(6) {
                onNodeWithTag("document-number").performKeyInput {
                    keyDown(Key.CtrlLeft)
                    pressKey(Key.Z)
                    keyUp(Key.CtrlLeft)
                }
                waitForIdle()
                onNodeWithTag("document-number").assertTextEquals("Número do documento", "")
            }
            val all = texts()
            val number = "529982247"
            val leaked =
                (0..number.length - 5).flatMap { start ->
                    (start + 5..number.length).map { end -> number.substring(start, end) }
                }
            assertTrue(all.none { text -> leaked.any { text.contains(it) } }, "pedaço do número voltou à tela")
        }
}
