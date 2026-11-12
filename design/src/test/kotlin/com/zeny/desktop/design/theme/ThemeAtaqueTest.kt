package com.zeny.desktop.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeAtaqueTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val l1 = maxOf(a.luminance(), b.luminance())
        val l2 = minOf(a.luminance(), b.luminance())
        return (l1 + 0.05f) / (l2 + 0.05f)
    }

    private fun assertReadable(
        name: String,
        fg: Color,
        bg: Color,
    ) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= 4.5f, "contraste de $name e $ratio, abaixo de 4,5")
    }

    @Test
    fun secondaryTextOnSurfaceIsReadable() {
        assertReadable("onSurfaceVariant/surface", ZenyColorScheme.onSurfaceVariant, ZenyColorScheme.surface)
    }

    @Test
    fun secondaryTextOnBackgroundIsReadable() {
        assertReadable("onSurfaceVariant/background", ZenyColorScheme.onSurfaceVariant, ZenyColorScheme.background)
    }

    @Test
    fun bodyTextOnBackgroundIsReadable() {
        assertReadable("onBackground/background", ZenyColorScheme.onBackground, ZenyColorScheme.background)
    }

    @Test
    fun errorAndSecondaryPairsAreReadable() {
        assertReadable("onError/error", ZenyColorScheme.onError, ZenyColorScheme.error)
        assertReadable("onSecondary/secondary", ZenyColorScheme.onSecondary, ZenyColorScheme.secondary)
    }

    @Test
    fun statusColorsAsTextOnSurfaceAreReadable() {
        // Success, Danger, Info e Neutral podem ser usadas como texto colorido sobre a superficie branca.
        mapOf(
            "Success" to ZenyColors.Success,
            "Danger" to ZenyColors.Danger,
            "Info" to ZenyColors.Info,
            "Neutral" to ZenyColors.Neutral,
        ).forEach { (name, c) -> assertReadable("$name/surface", c, ZenyColorScheme.surface) }
    }

    @Test
    fun warningTextColorOnSurfaceIsReadable() {
        // O texto do Warning (OnWarning) tambem precisa ler sobre a superficie e sobre o fundo.
        assertReadable("OnWarning/surface", ZenyColors.OnWarning, ZenyColorScheme.surface)
        assertReadable("OnWarning/background", ZenyColors.OnWarning, ZenyColorScheme.background)
    }

    @Test
    fun statusColorsAreDistinctFromEachOther() {
        val cores =
            listOf(ZenyColors.Success, ZenyColors.Warning, ZenyColors.Danger, ZenyColors.Info, ZenyColors.Neutral)
        assertEquals(cores.size, cores.toSet().size, "cores de status repetidas")
    }

    @Test
    fun errorColorIsTheDangerStatus() {
        assertEquals(ZenyColors.Danger, ZenyColorScheme.error)
    }

    @Test
    fun schemeIsLightMaterial() {
        assertTrue(ZenyColorScheme.surface.luminance() > 0.5f, "surface clara")
        assertTrue(ZenyColorScheme.background.luminance() > 0.5f, "background claro")
        assertTrue(ZenyColorScheme.onSurface.luminance() < 0.2f, "texto escuro")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun themeProvidesZenyTypography() =
        runComposeUiTest {
            var body: Any? = null
            setContent { ZenyTheme { body = MaterialTheme.typography.bodyMedium } }
            waitForIdle()
            assertEquals(ZenyTypography.bodyMedium, body)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun themeOutsideZenyThemeDiffersFromZeny() =
        runComposeUiTest {
            var inside: Color? = null
            var outside: Color? = null
            setContent {
                outside = MaterialTheme.colorScheme.primary
                ZenyTheme { inside = MaterialTheme.colorScheme.primary }
            }
            waitForIdle()
            assertNotEquals(outside, inside, "o tema padrao nao pode ser igual ao do Zeny")
            assertEquals(ZenyColors.Primary, inside)
        }
}
