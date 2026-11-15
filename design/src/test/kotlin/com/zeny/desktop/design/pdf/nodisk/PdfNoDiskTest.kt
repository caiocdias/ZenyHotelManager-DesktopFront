package com.zeny.desktop.design.pdf.nodisk

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zeny.desktop.design.pdf.SamplePdf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Roda só na tarefa `noDiskTest` do design: JVM própria, com `java.io.tmpdir` e `user.home` em pastas novas do
 * build. A tarefa `test` exclui este pacote.
 */
class PdfNoDiskTest {
    private val zooms = listOf(1.0f, 2.0f)
    private val baseDpi = 96f
    private val sentinelTimeoutSeconds = 30L

    @Test
    fun pdfLifecycleCreatesNoFile() {
        val roots = listOf(System.getProperty("java.io.tmpdir"), System.getProperty("user.home")).map { Path.of(it) }
        assertTrue(
            roots.all { it.toAbsolutePath().toString().contains("no-disk-test") },
            "rode pela tarefa :design:noDiskTest, que isola $roots",
        )
        warmUpOutsidePdfBox()
        FileSystems.getDefault().newWatchService().use { watcher ->
            val dirs = roots.flatMap { root -> Files.walk(root).use { s -> s.filter { it.isDirectory() }.toList() } }
            val keys = dirs.associateBy { it.register(watcher, ENTRY_CREATE) }
            val before = snapshot(roots)

            pdfLifecycle()

            assertEquals(before, snapshot(roots), "o ciclo do PDF não deixa arquivo em tmpdir nem em user.home")
            val sentinels = roots.map { Files.createFile(it.resolve("sentinel-${UUID.randomUUID()}")) }.toSet()
            val created = createdUntil(watcher, keys, sentinels)
            assertEquals(emptySet(), created - sentinels, "nenhum arquivo criado durante o ciclo, nem apagado depois")
        }
    }

    /**
     * Aquecimento do que não é PDFBox, antes da primeira foto: o Skiko extrai o nativo em `user.home/.skiko` na
     * primeira carga, e o AWT grava a configuração de fontes em `user.home/.java/fonts` na primeira vez que lê as
     * fontes do sistema. Os dois são do Compose e do Java2D, não do PDF.
     */
    private fun warmUpOutsidePdfBox() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        image.createGraphics().apply {
            font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            drawString("aquecimento", 0, 8)
            dispose()
        }
        image.toComposeImageBitmap()
    }

    /** Gera o PDF (depois do install()), carrega só em memória, renderiza as 3 páginas em 2 zooms e fecha. */
    private fun pdfLifecycle() {
        val bytes = SamplePdf.threePages()
        Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache()).use { doc ->
            assertEquals(SamplePdf.PAGE_COUNT, doc.numberOfPages)
            val renderer = PDFRenderer(doc)
            for (zoom in zooms) {
                for (page in 0 until doc.numberOfPages) {
                    renderer.renderImageWithDPI(page, baseDpi * zoom).toComposeImageBitmap()
                }
            }
        }
    }

    /** Foto recursiva: caminho → tamanho e data de modificação (pastas só pelo caminho). */
    private fun snapshot(roots: List<Path>): Map<Path, String> =
        roots
            .flatMap { root -> Files.walk(root).use { it.toList() } }
            .associateWith { if (it.isDirectory()) "dir" else "${Files.size(it)}@${Files.getLastModifiedTime(it)}" }

    /** Junta os arquivos criados até os eventos de todas as [sentinels] chegarem, sem dormir. */
    private fun createdUntil(
        watcher: WatchService,
        keys: Map<WatchKey, Path>,
        sentinels: Set<Path>,
    ): Set<Path> {
        val created = mutableSetOf<Path>()
        while (!created.containsAll(sentinels)) {
            val key =
                watcher.poll(sentinelTimeoutSeconds, TimeUnit.SECONDS)
                    ?: fail("o evento das sentinelas não chegou em ${sentinelTimeoutSeconds}s")
            val dir = keys.getValue(key)
            for (event in key.pollEvents()) {
                if (event.kind() == OVERFLOW) fail("eventos perdidos (OVERFLOW): não dá para afirmar nada")
                created.add(dir.resolve(event.context() as Path))
            }
            key.reset()
        }
        return created
    }
}
