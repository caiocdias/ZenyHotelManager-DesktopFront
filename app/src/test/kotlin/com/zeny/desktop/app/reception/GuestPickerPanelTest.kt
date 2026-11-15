package com.zeny.desktop.app.reception

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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

private const val PICKED_DOCUMENT = "CPF ***.456.789-**"
private const val TYPED_CPF = "12345678909"
private val maria = GuestPick("30000000-0000-0000-0000-000000000001", HolderSummary("Maria Teste", PICKED_DOCUMENT))
private val uuidShape = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

@OptIn(ExperimentalTestApi::class)
class GuestPickerPanelTest {
    private fun ComposeUiTest.panel(
        source: FakeReceptionSource = FakeReceptionSource(),
        canSearch: Boolean = true,
        canCreate: Boolean = true,
        picked: MutableList<GuestPick> = mutableListOf(),
    ): GuestPickerState {
        lateinit var state: GuestPickerState
        setContent {
            val scope = rememberCoroutineScope()
            state =
                androidx.compose.runtime.remember {
                    GuestPickerState(
                        source,
                        scope,
                        canSearch,
                        canCreate,
                    ) { picked += it }
                }
            ZenyTheme { GuestPickerPanel(state) }
        }
        return state
    }

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

    private fun ComposeUiTest.allTexts() = collectTexts(onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun ComposeUiTest.type(
        tag: String,
        text: String,
    ) {
        onNodeWithTag(tag).performTextInput(text)
        waitForIdle()
    }

    @Test
    fun searchByNameShowsResults() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { guestsByName["Maria"] = listOf(maria) }
            panel(source)
            type("guest-query", "Maria")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithText("Maria Teste").assertExists()
            onNodeWithText(PICKED_DOCUMENT).assertExists()
            onNodeWithText("Escolher").assertExists()
            assertEquals(listOf("Maria"), source.searchCalls)
        }

    @Test
    fun pickGuestByDocument() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { guestsByDocument[TYPED_CPF] = listOf(maria) }
            val picked = mutableListOf<GuestPick>()
            panel(source, picked = picked)
            onNodeWithText("Por documento").performClick()
            waitForIdle()
            onNodeWithText("País emissor").assertDoesNotExist()
            type("guest-document-number", TYPED_CPF)
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithText("Escolher").performClick()
            waitForIdle()
            assertEquals(listOf(maria), picked)
            assertFalse(allTexts().any { TYPED_CPF in it })
        }

    @Test
    fun countryOnlyForForeignTypes() =
        runComposeUiTest {
            panel()
            onNodeWithText("Por documento").performClick()
            onNodeWithTag("doc-type-PASSPORT").performClick()
            waitForIdle()
            onNodeWithTag("guest-issuing-country").assertExists()
            onNodeWithTag("doc-type-CPF").performClick()
            waitForIdle()
            onNodeWithTag("guest-issuing-country").assertDoesNotExist()
        }

    @Test
    fun createGuestFromPanel() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { createdGuest = maria }
            val picked = mutableListOf<GuestPick>()
            panel(source, picked = picked)
            type("field-fullName", "Maria Teste")
            type("field-documentNumber", TYPED_CPF)
            type("field-birthDate", "10/05/1990")
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            assertEquals(1, source.createGuestCalls)
            assertEquals(listOf(maria), picked)
            assertFalse(allTexts().any { TYPED_CPF in it || "123.456.789-09" in it })
            assertFalse(allTexts().any { "Maria Teste" in it })
        }

    @Test
    fun fieldErrorShownOnField() =
        runComposeUiTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError =
                        ApiError.Problem(
                            422,
                            "VALIDATION_ERROR",
                            null,
                            listOf(FieldError("profile.nationality", "INVALID", "Nacionalidade inválida.")),
                        )
                }
            panel(source)
            type("field-fullName", "Maria Teste")
            type("field-documentNumber", TYPED_CPF)
            type("field-birthDate", "10/05/1990")
            onNodeWithText("Cadastrar hóspede").performClick()
            waitForIdle()
            onNodeWithTag("error-nationality", useUnmergedTree = true).assertExists()
            onNodeWithText("Nacionalidade inválida.").assertExists()
        }

    @Test
    fun searchErrorIsHighlighted() =
        runComposeUiTest {
            panel()
            type("guest-query", "M")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            onNodeWithTag("guest-error").assertExists()
            onNodeWithText("Digite ao menos 2 letras do nome.").assertExists()
        }

    @Test
    fun actionsFollowPermissions() =
        runComposeUiTest {
            panel(canSearch = false, canCreate = false)
            onNodeWithTag("guest-search").assertDoesNotExist()
            onNodeWithText("Cadastrar hóspede").assertDoesNotExist()
        }

    @Test
    fun noUuidInPanel() =
        runComposeUiTest {
            val source = FakeReceptionSource().apply { guestsByName["Maria"] = listOf(maria) }
            panel(source)
            type("guest-query", "Maria")
            onNodeWithTag("guest-search").performClick()
            waitForIdle()
            assertTrue(allTexts().isNotEmpty())
            assertFalse(allTexts().any { uuidShape.containsMatchIn(it) })
        }
}
