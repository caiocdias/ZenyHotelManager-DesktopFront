package com.zeny.desktop.core.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Ataque ao `jarDirectory`: classe de JAR em caminho com espaços, de `classes/` e sem `codeSource`. */
class JarDirectoryAttackTest {
    private class Anchor

    private fun anchorWith(domain: ProtectionDomain?): Class<*> {
        val name = Anchor::class.java.name
        val bytes =
            assertNotNull(Anchor::class.java.classLoader.getResourceAsStream(name.replace('.', '/') + ".class"))
                .use { it.readBytes() }
        val loader =
            object : ClassLoader(Anchor::class.java.classLoader) {
                override fun loadClass(
                    className: String,
                    resolve: Boolean,
                ): Class<*> =
                    if (className == name) {
                        synchronized(getClassLoadingLock(className)) {
                            findLoadedClass(className) ?: defineClass(className, bytes, 0, bytes.size, domain)
                        }
                    } else {
                        super.loadClass(className, resolve)
                    }
            }
        return loader.loadClass(name)
    }

    private fun anchorAt(file: File): Class<*> =
        anchorWith(ProtectionDomain(CodeSource(file.toURI().toURL(), null as Array<Certificate>?), null))

    private fun tempRoot(): Path = Files.createTempDirectory("zeny jar attack ")

    @Test
    fun jarInDirectoryWithSpacesAndAccentsReturnsItsParent() {
        val dir = tempRoot().resolve("Meu Hotel (cópia) #1").also { it.createDirectories() }
        val jar = dir.resolve("zeny desktop.jar").also { it.writeBytes(byteArrayOf(0)) }
        assertEquals(dir.toRealPath(), jarDirectory(anchorAt(jar.toFile()))?.toRealPath())
    }

    @Test
    fun classesDirectoryReturnsNull() {
        val dir = tempRoot().resolve("build com espaço/classes").also { it.createDirectories() }
        assertNull(jarDirectory(anchorAt(dir.toFile())))
    }

    @Test
    fun missingCodeSourceReturnsNull() {
        assertNull(jarDirectory(anchorWith(ProtectionDomain(null, null))))
    }

    @Test
    fun nonexistentJarPathReturnsNullWithoutThrowing() {
        val ghost = tempRoot().resolve("não existe.jar").toFile()
        assertNull(jarDirectory(anchorAt(ghost)))
    }

    @Test
    fun jdkClassWithoutCodeSourceReturnsNull() {
        assertNull(jarDirectory(String::class.java))
        assertNull(jarDirectory(IntArray::class.java))
    }
}
