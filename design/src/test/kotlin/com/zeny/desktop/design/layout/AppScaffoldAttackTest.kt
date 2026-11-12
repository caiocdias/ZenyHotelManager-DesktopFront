package com.zeny.desktop.design.layout

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavStateAttackTest {
    @Test
    fun emptyItemsGiveEmptyState() {
        assertTrue(navState(emptyList(), "x").isEmpty())
    }

    @Test
    fun idMatchIsCaseSensitive() {
        val items = listOf(NavItem("a", "A"), NavItem("A", "Maiúsculo"))
        assertEquals(listOf(true, false), navState(items, "a").map { it.second })
    }

    @Test
    fun emptyStringSelectedMatchesOnlyAnEmptyId() {
        val items = listOf(NavItem("a", "A"), NavItem("", "Vazio"))
        assertEquals(listOf(false, true), navState(items, "").map { it.second })
    }
}

@OptIn(ExperimentalTestApi::class)
class AppScaffoldUiTest {
    private val items = listOf(NavItem("rooms", "Quartos"), NavItem("guests", "Hóspedes"))

    @Test
    fun clickingNavItemCallsOnSelectWithItsIdOnce() =
        runComposeUiTest {
            val picked = mutableListOf<String>()
            setContent {
                ZenyTheme { AppScaffold(items, "rooms", { picked += it }) { Text("conteúdo") } }
            }
            onNodeWithTag("nav-guests").performClick()
            waitForIdle()
            assertEquals(listOf("guests"), picked)
            onNodeWithText("conteúdo").assertExists()
        }

    @Test
    fun selectedItemIsMarkedSelectedAndOthersAreNot() =
        runComposeUiTest {
            setContent { ZenyTheme { AppScaffold(items, "guests", {}) { Text("c") } } }
            onNodeWithTag("nav-guests").assertIsSelected()
            onNodeWithTag("nav-rooms").assertIsNotSelected()
        }

    @Test
    fun topBarIsRendered() =
        runComposeUiTest {
            setContent { ZenyTheme { AppScaffold(items, null, {}, topBar = { Text("barra") }) { Text("c") } } }
            onNodeWithText("barra").assertExists()
        }
}
