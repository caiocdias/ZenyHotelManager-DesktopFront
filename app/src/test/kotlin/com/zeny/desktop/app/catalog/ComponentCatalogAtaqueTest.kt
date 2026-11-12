package com.zeny.desktop.app.catalog

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CPF_LEN = 11
private const val CNPJ_LEN = 14
private const val MOD = 11
private const val MIN_COLORS = 8
private const val DOMINANT_MAX = 0.95

class ComponentCatalogAtaqueTest {
    private val noProp: (String) -> String? = { null }
    private val noEnv: (String) -> String? = { null }

    private fun env(value: String?): (String) -> String? = { if (it == "ZENY_DEV") value else null }

    private fun prop(value: String?): (String) -> String? = { if (it == "zeny.dev") value else null }

    @Test
    fun devModeRejectsLookalikeEnvValues() {
        listOf("1 ", " 1", "01", "true", "TRUE", "yes", "on", "", " ", "11", "1\n", "0").forEach {
            assertFalse(isDevMode(env(it), noProp), "ZENY_DEV='$it' nao deve ativar o modo dev")
        }
    }

    @Test
    fun devModeRejectsLookalikePropertyValues() {
        listOf("TRUE", "True", "true ", " true", "1", "yes", "", "false", "on").forEach {
            assertFalse(isDevMode(noEnv, prop(it)), "zeny.dev='$it' nao deve ativar o modo dev")
        }
    }

    @Test
    fun devModeIgnoresTheWrongKeyForEachSource() {
        // "1" na propriedade e "true" no ambiente nao valem; so o par certo de cada fonte.
        assertFalse(isDevMode(noEnv, prop("1")))
        assertFalse(isDevMode(env("true"), noProp))
        assertFalse(isDevMode({ if (it == "ZENY_DEBUG") "1" else null }, noProp))
        assertFalse(isDevMode(noEnv, { if (it == "zeny.debug") "true" else null }))
    }

    @Test
    fun devModeEitherSourceEnablesAndInvalidOneDoesNotBlockTheOther() {
        assertTrue(isDevMode(env("1"), prop("false")))
        assertTrue(isDevMode(env("0"), prop("true")))
        assertTrue(isDevMode(env("1"), prop("true")))
    }

    @Test
    fun devModeDefaultsReadTheRealProcessAndStayOffHere() {
        // Sem ZENY_DEV nem -Dzeny.dev no processo de teste, o padrao e falso.
        if (System.getenv("ZENY_DEV") == null && System.getProperty("zeny.dev") == null) {
            assertFalse(isDevMode())
        }
    }

    private fun mod11(
        digits: List<Int>,
        weights: List<Int>,
    ): Int {
        val r = digits.zip(weights).sumOf { (d, w) -> d * w } % MOD
        return if (r < 2) 0 else MOD - r
    }

    private fun validCpf(s: String): Boolean {
        if (s.length != CPF_LEN || s.toSet().size == 1) return false
        val d = s.map { it - '0' }
        return mod11(d.take(9), (10 downTo 2).toList()) == d[9] && mod11(d.take(10), (11 downTo 2).toList()) == d[10]
    }

    private fun validCnpj(s: String): Boolean {
        if (s.length != CNPJ_LEN || s.toSet().size == 1) return false
        val d = s.map { it - '0' }
        val w1 = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val w2 = listOf(6) + w1
        return mod11(d.take(12), w1) == d[12] && mod11(d.take(13), w2) == d[13]
    }

    private fun catalogSources(): List<File> =
        File("src/main/kotlin/com/zeny/desktop/app/catalog").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun catalogSourcesExist() {
        assertTrue(catalogSources().isNotEmpty(), "fontes do catalogo nao encontradas em ${File(".").absolutePath}")
    }

    @Test
    fun everyDocumentInCatalogIsAGeneratedValidOne() {
        val text = catalogSources().joinToString("\n") { it.readText() }
        val onlyDigits = Regex("""(?<!\d)\d{11}(?!\d)|(?<!\d)\d{14}(?!\d)""").findAll(text).map { it.value }.toList()
        val formatted =
            Regex("""\d{3}\.\d{3}\.\d{3}-\d{2}|\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}""")
                .findAll(text)
                .map { it.value.filter(Char::isDigit) }
                .toList()
        val docs = onlyDigits + formatted
        val cpfs = docs.filter { it.length == CPF_LEN }
        val cnpjs = docs.filter { it.length == CNPJ_LEN }
        assertTrue(cnpjs.isNotEmpty(), "esperava ao menos um CNPJ de exemplo")
        cnpjs.forEach { assertTrue(validCnpj(it), "CNPJ $it sem DV valido") }
        // Os onze digitos podem ser CPF ou telefone; todo CPF candidato valido e aceito, o resto e telefone.
        assertTrue(cpfs.any(::validCpf), "esperava ao menos um CPF de exemplo valido")
    }

    @Test
    fun catalogDocumentsAreNotTrivialRepeatsNorKnownRealLookingSequences() {
        listOf("52998224725", "11222333000181").forEach {
            assertTrue(it.toSet().size > 2, "documento de exemplo degenerado: $it")
        }
        assertTrue(validCpf("52998224725"))
        assertTrue(validCnpj("11222333000181"))
        assertFalse(validCpf("52998224724"), "o validador do teste precisa rejeitar DV errado")
        assertFalse(validCnpj("11222333000182"), "o validador do teste precisa rejeitar DV errado")
    }

    @Test
    fun catalogHasNoRealLookingPersonalData() {
        val text = catalogSources().joinToString("\n") { it.readText() }
        assertFalse(Regex("""[\w.+-]+@[\w-]+\.[\w.]+""").containsMatchIn(text), "e-mail literal no catalogo")
        assertFalse(Regex("""https?://""").containsMatchIn(text), "URL literal no catalogo")
        assertFalse(Regex("""\b\d{5}-?\d{3}\b""").containsMatchIn(text), "CEP literal no catalogo")
        assertFalse(Regex("""(?i)senha|password|token|secret""").containsMatchIn(text), "segredo literal no catalogo")
    }

    @Test
    fun catalogIsReferencedOnlyBehindTheDevFlagInMain() {
        val main = File("src/main/kotlin/com/zeny/desktop/app/Main.kt").readText()
        val guard = Regex("""if\s*\(\s*dev\s*\)\s*ComponentCatalog\(\)""")
        assertTrue(guard.containsMatchIn(main), "Main sem o guard do catalogo")
        assertTrue(main.contains("val dev = isDevMode()"), "o guard deve vir de isDevMode()")
        val others =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" && !it.path.contains("/catalog/") && it.name != "Main.kt" }
                .filter { it.readText().contains("ComponentCatalog") }
                .toList()
        assertEquals(emptyList(), others, "outro arquivo de producao referencia o catalogo")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun screenshotHasRealContentNotJustOneBackgroundColor() =
        runDesktopComposeUiTest(1280, 800) {
            setContent { ZenyTheme { ComponentCatalog() } }
            waitForIdle()
            val img = onRoot().captureToImage().toAwtImage()
            assertEquals(1280, img.width)
            assertEquals(800, img.height)
            val counts = HashMap<Int, Int>()
            var total = 0
            for (x in 0 until img.width step 3) {
                for (y in 0 until img.height step 3) {
                    counts.merge(img.getRGB(x, y), 1, Int::plus)
                    total++
                }
            }
            assertTrue(counts.size >= MIN_COLORS, "poucas cores: ${counts.size}")
            val dominant = counts.values.max().toDouble() / total
            assertTrue(dominant < DOMINANT_MAX, "uma cor ocupa ${(dominant * 100).toInt()}% da captura")
        }
}
