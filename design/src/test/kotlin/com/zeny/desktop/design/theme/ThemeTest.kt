package com.zeny.desktop.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val l1 = maxOf(a.luminance(), b.luminance())
        val l2 = minOf(a.luminance(), b.luminance())
        return (l1 + 0.05f) / (l2 + 0.05f)
    }

    @Test
    fun textContrastIsReadable() {
        val s = ZenyColorScheme
        val pairs =
            mapOf(
                "onSurface/surface" to (s.onSurface to s.surface),
                "onPrimary/primary" to (s.onPrimary to s.primary),
                "success" to (ZenyColors.OnSuccess to ZenyColors.Success),
                "warning" to (ZenyColors.OnWarning to ZenyColors.Warning),
                "danger" to (ZenyColors.OnDanger to ZenyColors.Danger),
                "info" to (ZenyColors.OnInfo to ZenyColors.Info),
                "neutral" to (ZenyColors.OnNeutral to ZenyColors.Neutral),
            )
        pairs.forEach { (name, p) ->
            val ratio = contrast(p.first, p.second)
            assertTrue(ratio >= 4.5f, "contraste de $name é $ratio")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun themeProvidesZenyScheme() =
        runComposeUiTest {
            var primary: Color? = null
            setContent { ZenyTheme { primary = MaterialTheme.colorScheme.primary } }
            waitForIdle()
            assertEquals(ZenyColorScheme.primary, primary)
        }
}
