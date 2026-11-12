package com.zeny.desktop.app.catalog

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val SAMPLE_STEP = 7

@OptIn(ExperimentalTestApi::class)
class ComponentCatalogTest {
    @Test
    fun devModeOnlyWithFlag() {
        val noProp: (String) -> String? = { null }
        assertTrue(isDevMode(env = { if (it == "ZENY_DEV") "1" else null }, prop = noProp))
        assertFalse(isDevMode(env = { if (it == "ZENY_DEV") "0" else null }, prop = noProp))
        assertFalse(isDevMode(env = { if (it == "ZENY_DEV") "true" else null }, prop = noProp))
        assertFalse(isDevMode(env = { null }, prop = noProp))
        assertTrue(isDevMode(env = { null }, prop = { if (it == "zeny.dev") "true" else null }))
        assertFalse(isDevMode(env = { null }, prop = { if (it == "zeny.dev") "false" else null }))
    }

    @Test
    fun catalogRendersEverySection() =
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            setContent { ZenyTheme { ComponentCatalog() } }
            waitForIdle()
            CATALOG_SECTIONS.forEach { title ->
                onNodeWithTag("section-$title").assertExists("seção ausente: $title")
            }
            onNodeWithTag("section-${CATALOG_SECTIONS.first()}").assertIsDisplayed()
        }

    @Test
    fun writesCatalogScreenshot() =
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            setContent { ZenyTheme { ComponentCatalog() } }
            waitForIdle()
            val image = onRoot().captureToImage().toAwtImage()
            val file = File("build/catalog/catalog.png")
            file.parentFile.mkdirs()
            ImageIO.write(image, "png", file)

            val read = ImageIO.read(file)
            assertEquals(WIDTH, read.width)
            assertEquals(HEIGHT, read.height)
            val colors = HashSet<Int>()
            for (x in 0 until WIDTH step SAMPLE_STEP) {
                for (y in 0 until HEIGHT step SAMPLE_STEP) colors += read.getRGB(x, y)
            }
            assertTrue(colors.size > 1, "o PNG tem uma cor só")
        }
}
