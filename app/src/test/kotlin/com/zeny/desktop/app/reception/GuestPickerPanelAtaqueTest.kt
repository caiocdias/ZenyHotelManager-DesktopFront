package com.zeny.desktop.app.reception

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CPF_DIGITS = "52998224725"
private const val CPF_MASKED_INPUT = "529.982.247-25"
private val ana = GuestPick("30000000-0000-0000-0000-0000000000a1", HolderSummary("Ana Ficticia", "CPF ***.982.247-**"))
private val beto = GuestPick("30000000-0000-0000-0000-0000000000b2", HolderSummary("Beto Ficticio", "RG ****4321"))
private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

@OptIn(ExperimentalTestApi::class)
class GuestPickerPanelAtaqueTest {
    private fun ComposeUiTest.panel(
        source: FakeReceptionSource = FakeReceptionSource(),
        canSearch: Boolean = true,
        canCreate: Boolean = true,
        picked: MutableList<GuestPick> = mutableListOf(),
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                androidx.compose.runtime.remember {
                    GuestPickerState(source, scope, canSearch, canCreate) { picked += it }
                }
            ZenyTheme { GuestPickerPanel(state) }
        }
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } +
            node.config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
                ?.let { listOf(it) }
                .orEmpty() +
            node.children.flatMap { texts(it) }

    private fun ComposeUiTest.allTexts() = texts(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun ComposeUiTest.type(
        tag: String,
        text: String,
    ) {
        onNodeWithTag(tag).performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.fillCreate(
        number: String,
        birth: String = "10/05/1990",
    ) {
        type("field-fullName", "Carla Ficticia")
        type("field-documentNumber", number)
        type("field-birthDate", birth)
    }

    private fun ComposeUiTest.assertNumberGone() =
        assertFalse(allTexts().any { CPF_DIGITS in it || CPF_MASKED_INPUT in it }, "número digitado na árvore")

    @Test
    fun typedNumberLeavesTreeRightAfterDocumentSearch() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { guestsByDocument[CPF_DIGITS] = listOf(ana) }
            panel(source)
            onNodeWithText("Por documento").performClick()
            type("guest-document-number", CPF_DIGITS)
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            assertEquals(listOf(CPF_DIGITS), source.documentSearchNumbers)
            assertNumberGone()
            assertTrue(allTexts().any { it == "CPF ***.982.247-**" })
        }

    @Test
    fun maskedTypedNumberLeavesTreeAfterDocumentSearchError() =
        runComposeUiTest {
            val source =
                FakeReceptionSource().apply {
                    searchError = ApiError.Problem(500, "SOME_INTERNAL_CODE", null, emptyList())
                }
            panel(source)
            onNodeWithText("Por documento").performClick()
            type("guest-document-number", CPF_MASKED_INPUT)
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            assertNumberGone()
            onNodeWithTag("guest-error").assertExists()
            assertFalse(allTexts().any { "SOME_INTERNAL_CODE" in it })
        }

    @Test
    fun maskedTypedNumberLeavesTreeAfterSuccessfulCreate() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { createdGuest = ana }
            val picked = mutableListOf<GuestPick>()
            panel(source, picked = picked)
            fillCreate(CPF_MASKED_INPUT)
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            assertEquals(listOf(CPF_DIGITS), source.createdInputs.map { it.documentNumber })
            assertEquals(listOf(ana), picked)
            assertNumberGone()
        }

    @Test
    fun createCountryFieldOnlyForForeignTypes() =
        runComposeUiTest {
            panel()
            onNodeWithText("País emissor").assertDoesNotExist()
            onNodeWithTag("create-doc-type-PASSPORT").performClick()
            waitForIdle()
            onNodeWithText("País emissor").assertExists()
            onNodeWithTag("create-doc-type-FOREIGN_ID").performClick()
            waitForIdle()
            onNodeWithText("País emissor").assertExists()
            onNodeWithTag("create-doc-type-RNM").performClick()
            waitForIdle()
            onNodeWithText("País emissor").assertDoesNotExist()
        }

    @Test
    fun searchAloneHidesCreateAndCreateAloneHidesSearch() =
        runComposeUiTest {
            panel(canSearch = true, canCreate = false)
            onNodeWithTag("guest-search").assertExists()
            onNodeWithText("Cadastrar hóspede").assertDoesNotExist()
            onNodeWithTag("field-fullName").assertDoesNotExist()
        }

    @Test
    fun createAloneHidesSearchAndTabs() =
        runComposeUiTest {
            panel(canSearch = false, canCreate = true)
            onNodeWithTag("guest-search").assertDoesNotExist()
            onNodeWithText("Por nome").assertDoesNotExist()
            onNodeWithText("Por documento").assertDoesNotExist()
            onNodeWithText("Cadastrar hóspede").assertExists()
        }

    @Test
    fun searchButtonDisabledWhileSearchingSoTwoClicksMakeOneCall() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { holdSearch = true }
            panel(source)
            type("guest-query", "Ana")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithTag("guest-search").assertIsNotEnabled()
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            assertEquals(1, source.searchCalls.size)
            source.heldSearches.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("guest-search").assertIsEnabled()
        }

    @Test
    fun createButtonDisabledWhileCreatingSoTwoClicksMakeOneCall() =
        runComposeUiTest {
            val source =
                FakeReceptionSource().apply {
                    holdCreateGuest = true
                    createdGuest = ana
                }
            panel(source)
            fillCreate(CPF_DIGITS)
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            onNodeWithTag("guest-create").assertIsNotEnabled()
            onNodeWithTag("guest-create").performClick()
            waitForIdle()
            assertEquals(1, source.createGuestCalls)
            source.heldCreateGuests.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("guest-create").assertIsEnabled()
        }

    @Test
    fun pickSecondResultCallsPickWithThatResult() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { guestsByName["Ficticio"] = listOf(ana, beto) }
            val picked = mutableListOf<GuestPick>()
            panel(source, picked = picked)
            type("guest-query", "Ficticio")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithTag("guest-pick-1").performClick()
            waitForIdle()
            assertEquals(listOf(beto), picked)
            assertFalse(allTexts().any { uuid.containsMatchIn(it) })
        }

    @Test
    fun unknownProblemCodeOnCreateIsNeverShownRaw() =
        runComposeUiTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError = ApiError.Problem(409, "GUEST_WEIRD_CODE", null, emptyList())
                }
            panel(source)
            fillCreate(CPF_DIGITS)
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            assertFalse(allTexts().any { "GUEST_WEIRD_CODE" in it })
            onNodeWithTag("create-error").assertExists()
        }

    @Test
    fun networkFailureOnCreateShowsUnconfirmedMessageNotRawError() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { createGuestError = ApiError.Network(RuntimeException("boom")) }
            panel(source)
            fillCreate(CPF_DIGITS)
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            onNodeWithTag("create-error").assertExists()
            assertFalse(allTexts().any { "boom" in it || "RuntimeException" in it })
        }

    @Test
    fun documentNumberFieldErrorShowsOnNumberFieldOnly() =
        runComposeUiTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError =
                        ApiError.Problem(
                            422,
                            "VALIDATION_ERROR",
                            null,
                            listOf(FieldError("documents[0].number", "INVALID", "Número recusado.")),
                        )
                }
            panel(source)
            fillCreate(CPF_DIGITS)
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            onNodeWithTag("error-documentNumber", useUnmergedTree = true).assertExists()
            onNodeWithTag("error-fullName", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("error-nationality", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("error-birthDate", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun switchingTabsKeepsQueryAndNeverShowsDocumentNumberOnNameTab() =
        runComposeUiTest {
            panel()
            type("guest-query", "Ana")
            onNodeWithText("Por documento").performClick()
            waitForIdle()
            type("guest-document-number", CPF_DIGITS)
            onNodeWithText("Por nome").performClick()
            waitForIdle()
            assertFalse(allTexts().any { CPF_DIGITS in it }, "número visível na aba de nome")
            assertTrue(allTexts().any { it == "Ana" }, "consulta perdida na troca de aba")
            onNodeWithText("Por documento").performClick()
            waitForIdle()
            assertTrue(allTexts().any { it == CPF_DIGITS }, "número perdido na troca de aba")
        }

    @Test
    fun passportDocumentSearchWithoutCountryShowsFieldErrorAndMakesNoCall() =
        runComposeUiTest {
            val source = FakeReceptionSource()
            panel(source)
            onNodeWithText("Por documento").performClick()
            onNodeWithTag("doc-type-PASSPORT").performClick()
            type("guest-document-number", "AB123456")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            assertTrue(source.documentSearchCalls.isEmpty())
            onNodeWithText("Informe o país emissor.").assertExists()
        }

    @Test
    fun staleResultsOfEmptySearchShowNoPickButtons() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { guestsByName["Ana"] = listOf(ana) }
            panel(source)
            type("guest-query", "Ana")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithTag("guest-pick-0").assertExists()
            type("guest-query", "Zzz")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithTag("guest-pick-0").assertDoesNotExist()
            assertFalse(allTexts().any { "Ana Ficticia" in it })
        }
}
