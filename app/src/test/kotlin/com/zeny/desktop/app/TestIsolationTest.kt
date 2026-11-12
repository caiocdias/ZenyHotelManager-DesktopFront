package com.zeny.desktop.app

import java.io.File
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Os testes do app não tocam as preferências nem o /tmp reais da máquina (Task 3). */
class TestIsolationTest {
    private fun underAppBuildTmp(path: String): Boolean =
        File(path).canonicalFile.invariantSeparatorsPath.contains("/app/build/tmp/")

    @Test
    fun userRootPointsUnderAppBuildTmp() {
        val value = assertNotNull(System.getProperty("java.util.prefs.userRoot"))

        assertTrue(underAppBuildTmp(value), "java.util.prefs.userRoot=$value")
    }

    @Test
    fun tmpdirPointsUnderAppBuildTmp() {
        val value = assertNotNull(System.getProperty("java.io.tmpdir"))

        assertTrue(underAppBuildTmp(value), "java.io.tmpdir=$value")
    }

    @Test
    fun createdTempFilesLiveUnderTheIsolatedTmp() {
        val file = File.createTempFile("zeny-isolation", ".tmp")
        try {
            assertTrue(underAppBuildTmp(file.absolutePath), file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun preferencesUserRootWritesUnderTheIsolatedDirectory() {
        val root = File(assertNotNull(System.getProperty("java.util.prefs.userRoot")))
        val node = Preferences.userRoot().node("zeny-isolation-attack")
        try {
            node.put("probe", "valor-isolado")
            node.flush()

            assertEquals("valor-isolado", node.get("probe", null))
            val written = root.walkTopDown().filter { it.isFile }.toList()
            assertTrue(written.any { it.readText().contains("valor-isolado") }, "nada gravado sob $root")
        } finally {
            node.removeNode()
            Preferences.userRoot().flush()
        }
    }
}
