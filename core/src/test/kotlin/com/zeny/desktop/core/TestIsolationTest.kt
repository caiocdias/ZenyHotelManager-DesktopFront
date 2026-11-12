package com.zeny.desktop.core

import java.io.File
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestIsolationTest {
    private val buildTmp = File(System.getProperty("user.dir"), "build/tmp").canonicalFile

    private fun insideBuildTmp(path: String?): Boolean {
        val file = File(assertNotNull(path, "system property ausente")).canonicalFile
        return generateSequence(file) { it.parentFile }.any { it == buildTmp }
    }

    @Test
    fun javaIoTmpdirPointsInsideCoreBuildTmp() {
        val value = System.getProperty("java.io.tmpdir")
        assertTrue(insideBuildTmp(value), "java.io.tmpdir fora de core/build/tmp: $value")
    }

    @Test
    fun prefsUserRootPointsInsideCoreBuildTmp() {
        val value = System.getProperty("java.util.prefs.userRoot")
        assertTrue(insideBuildTmp(value), "java.util.prefs.userRoot fora de core/build/tmp: $value")
    }

    @Test
    fun tempFilesLandInsideCoreBuildTmp() {
        val f = File.createTempFile("isolation", ".tmp")
        try {
            assertTrue(insideBuildTmp(f.absolutePath), "arquivo temporário fora de core/build/tmp: $f")
        } finally {
            f.delete()
        }
    }

    @Test
    fun preferencesUserRootWritesUnderCoreBuildTmp() {
        val node = Preferences.userRoot().node("zeny-isolation-test")
        try {
            node.put("k", "v")
            node.flush()
            assertEquals("v", node.get("k", null))
            val root = File(assertNotNull(System.getProperty("java.util.prefs.userRoot"))).canonicalFile
            val written = root.walkTopDown().any { it.isFile && it.path.contains("zeny-isolation-test") }
            assertTrue(written, "Preferences não gravou sob $root")
        } finally {
            node.removeNode()
            Preferences.userRoot().flush()
        }
    }
}
