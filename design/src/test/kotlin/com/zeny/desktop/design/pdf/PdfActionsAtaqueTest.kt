package com.zeny.desktop.design.pdf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.printing.PDFPageable
import java.awt.HeadlessException
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Pageable
import java.awt.print.Printable
import java.awt.print.PrinterException
import java.awt.print.PrinterJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Ataque ao "Salvar como…" e "Imprimir" (Task 5). */
class PdfActionsAtaqueTest {
    private val uiExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }
    private val ioExecutor = Executors.newFixedThreadPool(4) { Thread(it, "fake-io") }
    private val uiDispatcher = uiExecutor.asCoroutineDispatcher()
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val bytes = SamplePdf.threePages()
    private val folder: Path = Files.createTempDirectory("pdf-actions-ataque")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        uiExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    private fun actions(
        chooser: SaveLocationChooser = SaveLocationChooser { null },
        printer: PrintDialog = PrintDialog { PrintOutcome.CANCELLED },
        name: String = "ficha-1.pdf",
        data: ByteArray = bytes,
    ) = PdfActions(data, name, chooser, printer, uiDispatcher, ioDispatcher)

    // ---- Salvar: sobrescrita, destinos inválidos

    @Test
    fun saveOverExistingLargerFileReplacesBytesExactly() {
        val target = folder.resolve("existente.pdf")
        Files.write(target, ByteArray(bytes.size * 3) { 0x41 })
        assertEquals(SaveResult.Saved, runBlocking { actions(chooser = { target }).save() })
        assertContentEquals(bytes, Files.readAllBytes(target), "sem resto do arquivo antigo")
    }

    @Test
    fun saveOverExistingSmallerFileReplacesBytesExactly() {
        val target = folder.resolve("pequeno.pdf")
        Files.write(target, byteArrayOf(1, 2, 3))
        assertEquals(SaveResult.Saved, runBlocking { actions(chooser = { target }).save() })
        assertContentEquals(bytes, Files.readAllBytes(target))
    }

    @Test
    fun saveOverReadOnlyFileFailsAndKeepsOriginal() {
        val target = folder.resolve("somente-leitura.pdf")
        val original = byteArrayOf(9, 8, 7)
        Files.write(target, original)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
        try {
            val result = runBlocking { actions(chooser = { target }).save() }
            assertEquals(SaveResult.Failed("Não foi possível salvar o arquivo."), result)
            assertContentEquals(original, Files.readAllBytes(target), "o original continua intacto")
        } finally {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-rw-"))
        }
    }

    @Test
    fun saveIntoReadOnlyDirectoryFailsWithoutLeftovers() {
        val dir = Files.createDirectory(folder.resolve("ro-dir"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val result = runBlocking { actions(chooser = { dir.resolve("x.pdf") }).save() }
            assertEquals(SaveResult.Failed("Não foi possível salvar o arquivo."), result)
            assertEquals(0L, Files.list(dir).use { it.count() })
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
        }
    }

    @Test
    fun saveWhenChooserReturnsDirectoryFailsAndLeavesItIntact() {
        val dir = Files.createDirectory(folder.resolve("uma-pasta"))
        val result = runBlocking { actions(chooser = { dir }).save() }
        assertEquals(SaveResult.Failed("Não foi possível salvar o arquivo."), result)
        assertTrue(Files.isDirectory(dir))
    }

    @Test
    fun saveToDeviceThatFailsMidWriteIsFailed() {
        val full = Path.of("/dev/full")
        if (!Files.exists(full)) return
        val result = runBlocking { actions(chooser = { full }).save() }
        assertEquals(SaveResult.Failed("Não foi possível salvar o arquivo."), result)
    }

    @Test
    fun saveWithUnicodeFileNameWritesExactBytes() {
        val target = folder.resolve("ficha-José-ção-日本語-😀.pdf")
        assertEquals(SaveResult.Saved, runBlocking { actions(chooser = { target }).save() })
        assertContentEquals(bytes, Files.readAllBytes(target))
    }

    @Test
    fun saveWithTooLongFileNameFailsWithoutPath() {
        val target = folder.resolve("x".repeat(300) + ".pdf")
        val failed = runBlocking { actions(chooser = { target }).save() } as SaveResult.Failed
        assertEquals("Não foi possível salvar o arquivo.", failed.message)
        assertFalse(failed.message.contains("xxx"))
    }

    @Test
    fun chooserThrowingBecomesFailedNotCrash() {
        val result = runBlocking { actions(chooser = { throw IllegalStateException("/home/maria/segredo") }).save() }
        assertEquals(SaveResult.Failed("Não foi possível salvar o arquivo."), result)
    }

    @Test
    fun saveFailureMessageNeverContainsPathOrBytes() {
        val plain = Files.createFile(folder.resolve("segredo-da-maria"))
        val messages =
            listOf(
                runBlocking { actions(chooser = { plain.resolve("filho.pdf") }).save() },
                runBlocking { actions(chooser = { folder }).save() },
                runBlocking { actions(chooser = { throw IllegalStateException(folder.toString()) }).save() },
            ).map { (it as SaveResult.Failed).message }
        messages.forEach {
            assertFalse(it.contains("segredo"))
            assertFalse(it.contains(folder.toString()))
            assertFalse(it.contains("%PDF"))
        }
    }

    // ---- Salvar: concorrência

    @Test
    fun twoConcurrentSavesToSameTargetLeaveExactBytes() {
        val target = folder.resolve("concorrente.pdf")
        val barrier = CyclicBarrier(2)
        val chooser =
            SaveLocationChooser {
                target
            }
        val results =
            runBlocking {
                List(
                    2,
                ) { scope.async { actions(chooser = chooser).also { barrier.await(30, TimeUnit.SECONDS) }.save() } }
                    .awaitAll()
            }
        assertEquals(listOf<SaveResult>(SaveResult.Saved, SaveResult.Saved), results)
        assertContentEquals(bytes, Files.readAllBytes(target))
    }

    @Test
    fun twoConcurrentSavesOfDifferentBytesToSameTargetNeverInterleave() {
        // Dois PdfActions, bytes distintos, mesmo destino: o resultado tem de ser um dos dois, inteiro.
        val other = ByteArray(bytes.size * 2) { 0x5A }
        val target = folder.resolve("duelo.pdf")
        repeat(30) { _ ->
            val results =
                runBlocking {
                    listOf(
                        scope.async { actions(chooser = { target }, data = bytes).save() },
                        scope.async { actions(chooser = { target }, data = other).save() },
                    ).awaitAll()
                }
            assertTrue(results.all { it == SaveResult.Saved || it is SaveResult.Failed })
            val final = Files.readAllBytes(target)
            assertTrue(
                final.contentEquals(bytes) || final.contentEquals(other),
                "arquivo final misturado/truncado (${final.size} bytes)",
            )
        }
    }

    // ---- Imprimir: fake job

    private class FakeJob(
        var dialogResult: Boolean = true,
        var dialogError: RuntimeException? = null,
        var printError: Exception? = null,
    ) : PrinterJob() {
        var received: Pageable? = null
        var printCalls = 0
        var pagesAtPrint = -1
        val order = mutableListOf<Int>()

        override fun setPageable(document: Pageable) {
            received = document
        }

        override fun printDialog(): Boolean {
            dialogError?.let { throw it }
            return dialogResult
        }

        override fun print() {
            printCalls++
            val p = checkNotNull(received)
            pagesAtPrint = p.numberOfPages
            val image = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
            for (i in 0 until p.numberOfPages) {
                val g = image.createGraphics()
                try {
                    p.getPrintable(i).print(g, p.getPageFormat(i), i)
                    order += i
                } finally {
                    g.dispose()
                }
            }
            printError?.let { throw it }
        }

        override fun setPrintable(painter: Printable) = Unit

        override fun setPrintable(
            painter: Printable,
            format: PageFormat,
        ) = Unit

        override fun pageDialog(page: PageFormat): PageFormat = page

        override fun defaultPage(page: PageFormat): PageFormat = page

        override fun validatePage(page: PageFormat): PageFormat = page

        override fun setCopies(copies: Int) = Unit

        override fun getCopies(): Int = 1

        override fun getUserName(): String = "test"

        override fun setJobName(jobName: String) = Unit

        override fun getJobName(): String = "test"

        override fun cancel() = Unit

        override fun isCancelled(): Boolean = false
    }

    /**
     * `true` se o `PDDocument` por trás do [pageable] ainda está aberto. Lê o campo `document` do `PDFPageable`
     * por reflexão: renderizar não serve de detector, porque as páginas já carregadas continuam desenháveis.
     */
    private fun stillRenders(pageable: Pageable): Boolean {
        val field = PDFPageable::class.java.getDeclaredField("document")
        field.isAccessible = true
        val doc = field.get(pageable) as org.apache.pdfbox.pdmodel.PDDocument
        return !doc.document.isClosed
    }

    @Test
    fun controlClosedDocumentStopsRendering() {
        // Controle do detector: um PDDocument aberto renderiza, o mesmo fechado não.
        val doc = checkNotNull(loadPdf(bytes))
        val pageable = PDFPageable(doc)
        assertTrue(stillRenders(pageable))
        doc.close()
        assertFalse(stillRenders(pageable), "o detector de documento fechado precisa poder falhar")
    }

    @Test
    fun cancelPrintClosesPrintDocumentAndNeverPrints() {
        val job = FakeJob(dialogResult = false)
        assertEquals(PrintResult.Cancelled, runBlocking { actions(printer = SystemPrintDialog { job }).print() })
        assertEquals(0, job.printCalls)
        assertFalse(stillRenders(checkNotNull(job.received)), "documento da impressão fechado após cancelar")
    }

    @Test
    fun confirmedPrintSendsAllPagesInOrderAndClosesDocument() {
        val job = FakeJob()
        assertEquals(PrintResult.Printed, runBlocking { actions(printer = SystemPrintDialog { job }).print() })
        assertEquals(SamplePdf.PAGE_COUNT, job.pagesAtPrint)
        assertEquals((0 until SamplePdf.PAGE_COUNT).toList(), job.order)
        assertFalse(stillRenders(checkNotNull(job.received)), "documento fechado após imprimir")
    }

    @Test
    fun printerExceptionIsFailedAndClosesDocument() {
        val job = FakeJob(printError = PrinterException("falha em /dev/usb/lp0 do hóspede Maria"))
        val failed = runBlocking { actions(printer = SystemPrintDialog { job }).print() } as PrintResult.Failed
        assertEquals("Não foi possível imprimir.", failed.message)
        assertFalse(failed.message.contains("Maria"))
        assertFalse(stillRenders(checkNotNull(job.received)), "documento fechado após falha")
    }

    @Test
    fun runtimeExceptionInPrintIsFailedAndClosesDocument() {
        val job = FakeJob(printError = IllegalStateException("boom"))
        val result = runBlocking { actions(printer = SystemPrintDialog { job }).print() }
        assertEquals(PrintResult.Failed("Não foi possível imprimir."), result)
        assertFalse(stillRenders(checkNotNull(job.received)))
    }

    @Test
    fun headlessExceptionFromJobFactoryIsFailed() {
        val result =
            runBlocking { actions(printer = SystemPrintDialog { throw HeadlessException("sem tela") }).print() }
        assertEquals(PrintResult.Failed("Não foi possível imprimir."), result)
    }

    @Test
    fun headlessExceptionFromDialogOnEdtIsFailedAndClosesDocument() {
        val job = FakeJob(dialogError = HeadlessException("sem tela"))
        val result = runBlocking { actions(printer = SystemPrintDialog { job }).print() }
        assertEquals(PrintResult.Failed("Não foi possível imprimir."), result)
        assertEquals(0, job.printCalls)
        assertFalse(stillRenders(checkNotNull(job.received)))
    }

    @Test
    fun printWithBrokenBytesNeverReachesPrinter() {
        val calls = AtomicInteger()
        val result =
            runBlocking {
                actions(
                    printer = { calls.incrementAndGet().let { PrintOutcome.PRINTED } },
                    data = byteArrayOf(1, 2),
                ).print()
            }
        assertEquals(PrintResult.Failed("Não foi possível imprimir."), result)
        assertEquals(0, calls.get())
    }

    @Test
    fun twoConcurrentPrintsEachGetTheirOwnOpenDocumentAndCloseIt() {
        val jobs = CopyOnWriteArrayList<FakeJob>()
        val results =
            runBlocking {
                List(2) {
                    scope.async {
                        actions(printer = SystemPrintDialog { FakeJob().also { jobs += it } }).print()
                    }
                }.awaitAll()
            }
        assertEquals(listOf<PrintResult>(PrintResult.Printed, PrintResult.Printed), results)
        assertEquals(2, jobs.size)
        assertTrue(jobs.all { it.order == (0 until SamplePdf.PAGE_COUNT).toList() })
        assertNotEquals(jobs[0].received, jobs[1].received, "cada impressão tem o seu documento")
        assertTrue(jobs.none { stillRenders(checkNotNull(it.received)) })
    }

    @Test
    fun saveAndPrintAtTheSameTimeBothComplete() {
        val target = folder.resolve("junto.pdf")
        val job = FakeJob()
        val a = actions(chooser = { target }, printer = SystemPrintDialog { job })
        val (saved, printed) =
            runBlocking {
                val s = scope.async { a.save() }
                val p = scope.async { a.print() }
                s.await() to p.await()
            }
        assertEquals(SaveResult.Saved, saved)
        assertEquals(PrintResult.Printed, printed)
        assertContentEquals(bytes, Files.readAllBytes(target))
    }

    // ---- suggestedPdfName

    private val fb = "ficha-9.pdf"

    @Test
    fun windowsReservedNamesAreNotSuggested() {
        val reserved = listOf("CON", "con.pdf", "NUL.pdf", "PRN", "AUX.pdf", "COM1", "LPT9.pdf", "nul")
        for (raw in reserved) {
            val out = suggestedPdfName(raw, fb)
            val stem = out.substringBefore('.').uppercase()
            assertFalse(
                stem in setOf("CON", "PRN", "AUX", "NUL") || Regex("(COM|LPT)[1-9]").matches(stem),
                "nome reservado do Windows sugerido: '$raw' -> '$out'",
            )
        }
    }

    @Test
    fun suggestedNameNeverContainsDoubleDotsNorEndsWithDotOrSpace() {
        val inputs = listOf(".", "a.", "a. ", "name.pdf.", "name.pdf ", " name ", ".pdf", "..pdf", "a . ", "x.PDF.")
        for (raw in inputs) {
            val out = suggestedPdfName(raw, fb)
            assertFalse(out.contains(".."), "'$raw' -> '$out' contém ..")
            assertTrue(out.endsWith(".pdf", ignoreCase = true), "'$raw' -> '$out'")
            assertFalse(out.startsWith(" "), "'$raw' -> '$out'")
        }
    }

    @Test
    fun suggestedNameTraversalVariantsKeepOnlyLastSegment() {
        assertEquals("passwd.pdf", suggestedPdfName("..\\..\\windows\\passwd", fb))
        assertEquals("a.pdf", suggestedPdfName("/abs/olute/a.pdf", fb))
        assertEquals("a.pdf", suggestedPdfName("x/y\\a.pdf", fb))
        assertEquals(fb, suggestedPdfName("/", fb))
        assertEquals(fb, suggestedPdfName("a/..", fb))
        assertEquals(fb, suggestedPdfName("\u0085.pdf", fb), "NEL é controle C1")
        assertEquals(fb, suggestedPdfName("a\u007Fb", fb), "DEL")
    }

    @Test
    fun suggestedNameFitsInFilesystemLimitOf255Bytes() {
        // ext4 e afins limitam o nome a 255 BYTES UTF-8, não a 255 chars.
        for (ch in listOf("é", "日", "😀")) {
            val out = suggestedPdfName(ch.repeat(300) + ".pdf", fb)
            assertTrue(out.toByteArray(Charsets.UTF_8).size <= 255, "'$ch' x300 -> ${out.toByteArray().size} bytes")
        }
    }

    @Test
    fun suggestedNameFromLongUnicodeCanBeCreatedOnDisk() {
        val out = suggestedPdfName("é".repeat(300), fb)
        val target = folder.resolve(out)
        assertEquals(SaveResult.Saved, runBlocking { actions(chooser = { target }).save() })
    }

    @Test
    fun blankFallbackAndBlankRawStillGiveSafeName() {
        assertEquals("ficha.pdf", suggestedPdfName("  ", "   "))
        assertEquals("ficha.pdf", suggestedPdfName(null, ""))
        assertEquals("ficha.pdf", suggestedPdfName("", "a‮b"))
    }

    @Test
    fun suggestedNameKeepsUnicodeLetters() {
        assertEquals("ficha-José-日本.pdf", suggestedPdfName("ficha-José-日本", fb))
    }
}
