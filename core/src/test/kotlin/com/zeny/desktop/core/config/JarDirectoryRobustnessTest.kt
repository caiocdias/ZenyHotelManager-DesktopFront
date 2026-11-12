package com.zeny.desktop.core.config

import java.net.URI
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JarDirectoryRobustnessTest {
    private class Anchor

    private fun anchorFrom(location: String): Class<*> {
        val name = Anchor::class.java.name
        val bytes =
            assertNotNull(Anchor::class.java.classLoader.getResourceAsStream(name.replace('.', '/') + ".class"))
                .use { it.readBytes() }
        val domain = ProtectionDomain(CodeSource(URI(location).toURL(), null as Array<Certificate>?), null)
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

    @Test
    fun jarDirectoryNeverThrows() {
        assertNull(jarDirectory(anchorFrom("jar:file:/tmp/app.jar!/")))
        assertNull(jarDirectory(anchorFrom("file://servidor/pasta/app.jar")))
        assertNull(jarDirectory(anchorFrom("http://servidor/app.jar")))
    }
}
