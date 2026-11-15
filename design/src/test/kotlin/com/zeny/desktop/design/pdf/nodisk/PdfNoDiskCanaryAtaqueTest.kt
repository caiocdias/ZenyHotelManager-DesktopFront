package com.zeny.desktop.design.pdf.nodisk

import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ataque à Task 3: controle positivo do detector da `noDiskTest`. O cache de stream que grava em arquivo,
 * escolhido de propósito, TEM de aparecer como criação em java.io.tmpdir; sem isso, o "nenhum evento" do
 * PdfNoDiskTest não provaria nada sobre o cache de stream só em memória.
 */
class PdfNoDiskCanaryAtaqueTest {
    @Test
    fun watcherSeesTheTempFileStreamCache() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        assertTrue(tmp.toAbsolutePath().toString().contains("no-disk-test"), "use :design:noDiskTest")
        FileSystems.getDefault().newWatchService().use { watcher ->
            tmp.register(watcher, ENTRY_CREATE)
            PDDocument(IOUtils.createTempFileOnlyStreamCache()).use { doc ->
                val stream = COSStream(IOUtils.createTempFileOnlyStreamCache().create())
                stream.createRawOutputStream().use { it.write(ByteArray(1024) { 7 }) }
                doc.document.trailer.setItem(
                    org.apache.pdfbox.cos.COSName
                        .getPDFName("Canary"),
                    stream,
                )
            }
            val key = watcher.poll(10, TimeUnit.SECONDS)
            val names = key?.pollEvents()?.map { it.context().toString() }.orEmpty()
            assertTrue(names.any { it.startsWith("tmp") || it.contains("pdfbox", true) }, "eventos vistos: $names")
        }
        Files.list(tmp).use { /* nada a limpar: o PDFBox apaga o próprio arquivo ao fechar */ }
    }
}
