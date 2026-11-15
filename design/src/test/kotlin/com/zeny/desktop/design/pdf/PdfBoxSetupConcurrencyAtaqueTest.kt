package com.zeny.desktop.design.pdf

import org.apache.pdfbox.pdmodel.font.FontMappers
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ataque à Task 3: install() concorrente continua único e seguro. */
class PdfBoxSetupConcurrencyAtaqueTest {
    private val threads = 16

    @Test
    fun concurrentInstallKeepsOneMapperAndNoImageIoCache() {
        PdfBoxSetup.install()
        val before = FontMappers.instance()
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val seen =
                (1..threads)
                    .map {
                        pool.submit<Any> {
                            barrier.await(30, TimeUnit.SECONDS)
                            repeat(50) { PdfBoxSetup.install() }
                            FontMappers.instance()
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            assertTrue(seen.all { it === before }, "todas as threads veem o mesmo mapper")
        } finally {
            pool.shutdownNow()
        }
        assertSame(before, FontMappers.instance())
        assertTrue(!ImageIO.getUseCache(), "cache do ImageIO segue desligado")
    }

    @Test
    fun concurrentFirstUseOfFreshMapperParsesTheFontOnce() {
        val mapper = BundledFontMapper()
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val fonts =
                (1..threads)
                    .map {
                        pool.submit<Any> {
                            barrier.await(30, TimeUnit.SECONDS)
                            mapper.getTrueTypeFont("Arial", null).font
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, fonts.map { System.identityHashCode(it) }.toSet().size, "uma única instância da fonte")
        } finally {
            pool.shutdownNow()
        }
    }
}
