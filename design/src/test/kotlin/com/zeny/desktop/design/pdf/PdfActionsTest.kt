package com.zeny.desktop.design.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Pageable
import java.awt.print.Printable
import java.awt.print.PrinterException
import java.awt.print.PrinterJob
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfActionsTest {
    private val uiExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
    private val uiDispatcher = uiExecutor.asCoroutineDispatcher()
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val bytes = SamplePdf.threePages()
    private val folder: Path = Files.createTempDirectory("pdf-actions-test")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        uiExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        extra.forEach { it.shutdownNow() }
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toList()

    private fun actions(
        chooser: SaveLocationChooser = SaveLocationChooser { null },
        printer: PrintDialog = PrintDialog { PrintOutcome.CANCELLED },
        name: String = "ficha-1.pdf",
    ) = PdfActions(bytes, name, chooser, printer, uiDispatcher, ioDispatcher)

    // ---- Salvar como…

    @Test
    fun saveWritesExactBytes() {
        val target = folder.resolve("saida.pdf")
        val result = runBlocking { actions(chooser = { target }).save() }
        assertEquals(SaveResult.Saved, result)
        assertEquals(sha(bytes), sha(Files.readAllBytes(target)))
    }

    @Test
    fun cancelSaveWritesNothing() {
        val before = Files.list(folder).use { it.count() }
        val result = runBlocking { actions(chooser = { null }).save() }
        assertEquals(SaveResult.Cancelled, result)
        assertEquals(before, Files.list(folder).use { it.count() })
    }

    @Test
    fun saveFailureShowsMessageWithoutPath() {
        val plain = Files.createFile(folder.resolve("arquivo-comum"))
        val target = plain.resolve("filho.pdf")
        val result = runBlocking { actions(chooser = { target }).save() }
        val failed = result as SaveResult.Failed
        assertEquals("Não foi possível salvar o arquivo.", failed.message)
        assertFalse(failed.message.contains("arquivo-comum"))
        assertFalse(failed.message.contains(folder.toString()))
    }

    @Test
    fun saveSuggestsSanitizedName() {
        val seen = AtomicReference<String>()
        runBlocking { actions(chooser = { seen.set(it).let { null } }, name = "../../etc/passwd").save() }
        assertEquals("passwd.pdf", seen.get())
    }

    @Test
    fun dialogRunsOnUiDispatcher() {
        val dialogThread = AtomicReference<String>()
        val target = folder.resolve("threads.pdf")
        runBlocking { actions(chooser = { dialogThread.set(Thread.currentThread().name).let { target } }).save() }
        assertTrue(dialogThread.get().startsWith("fake-ui"), dialogThread.get())
    }

    @Test
    fun saveDialogResultMapping() {
        assertNull(saveDialogResult(null, null))
        assertNull(saveDialogResult("/tmp", null))
        assertNull(saveDialogResult("/tmp", ""))
        assertEquals(Path.of("/tmp", "a.pdf"), saveDialogResult("/tmp", "a.pdf"))
        assertEquals(Path.of("a.pdf"), saveDialogResult(null, "a.pdf"))
        assertNull(saveDialogResult("/tmp", "a\u0000b.pdf"))
    }

    @Test
    fun suggestedNameSanitizesAndFallsBack() {
        val fb = "ficha-9.pdf"
        assertEquals("ficha-1.pdf", suggestedPdfName("ficha-1.pdf", fb))
        assertEquals("ficha-1.pdf", suggestedPdfName("ficha-1", fb))
        assertEquals("ficha-1.PDF", suggestedPdfName("ficha-1.PDF", fb))
        assertEquals("passwd.pdf", suggestedPdfName("../../etc/passwd", fb))
        assertEquals("x.pdf", suggestedPdfName("C:\\Users\\a\\x.pdf", fb))
        assertEquals(fb, suggestedPdfName(null, fb))
        assertEquals(fb, suggestedPdfName("", fb))
        assertEquals(fb, suggestedPdfName("   ", fb))
        assertEquals(fb, suggestedPdfName("..", fb))
        assertEquals(fb, suggestedPdfName("a..b.pdf", fb))
        assertEquals(fb, suggestedPdfName("a\nb.pdf", fb))
        assertEquals(fb, suggestedPdfName("a\u0000b.pdf", fb))
        assertEquals(fb, suggestedPdfName("a\u202Eb.pdf", fb), "Cf (override bidi)")
        assertEquals(fb, suggestedPdfName("a\u200Bb.pdf", fb), "Cf (zero width)")
        assertEquals(fb, suggestedPdfName("a\u2028b.pdf", fb), "Zl")
        assertEquals(fb, suggestedPdfName("a\u2029b.pdf", fb), "Zp")
        assertEquals(fb, suggestedPdfName("dir/", fb))
        assertEquals("ficha.pdf", suggestedPdfName(null, "../ficha"), "o fallback também é saneado")
        assertEquals("ficha.pdf", suggestedPdfName(null, "\u0000"), "fallback inválido vira ficha.pdf")
    }

    @Test
    fun suggestedNameReplacesWindowsIllegalCharacters() {
        assertEquals("a_b_c_d_e_f_g_.pdf", suggestedPdfName("a:b<c>d\"e|f?g*.pdf", "f.pdf"))
        assertEquals("x_y.pdf", suggestedPdfName("x:y", "f.pdf"))
    }

    @Test
    fun reservedNamesFallBackAndSimilarNamesSurvive() {
        assertEquals("f.pdf", suggestedPdfName("CON", "f.pdf"))
        assertEquals("f.pdf", suggestedPdfName("com1.pdf", "f.pdf"))
        assertEquals("f.pdf", suggestedPdfName("Lpt9", "f.pdf"))
        assertEquals("ficha.pdf", suggestedPdfName("nul", "AUX"))
        assertEquals("console.pdf", suggestedPdfName("console", "f.pdf"))
        assertEquals("COM10.pdf", suggestedPdfName("COM10", "f.pdf"))
    }

    @Test
    fun suggestedNameTrimsTrailingDotsAndSpaces() {
        assertEquals("a.pdf", suggestedPdfName("a.", "f.pdf"))
        assertEquals("name.pdf", suggestedPdfName("name.pdf.", "f.pdf"))
        assertEquals("a.pdf", suggestedPdfName("a .pdf", "f.pdf"))
        assertEquals("f.pdf", suggestedPdfName(".", "f.pdf"))
        assertEquals("f.pdf", suggestedPdfName(".pdf", "f.pdf"))
    }

    @Test
    fun suggestedNameIsCappedAt255BytesKeepingExtension() {
        for (ch in listOf("a", "é", "日", "\uD83D\uDE00")) {
            val out = suggestedPdfName(ch.repeat(400) + ".pdf", "f.pdf")
            assertTrue(out.toByteArray(Charsets.UTF_8).size <= 255, ch)
            assertTrue(out.endsWith(".pdf"))
            assertTrue(out.length > 10)
        }
        assertEquals(255, suggestedPdfName("c".repeat(400), "f.pdf").length)
        assertEquals("c".repeat(251) + ".pdf", suggestedPdfName("c".repeat(251) + ".pdf", "f.pdf"))
    }

    // ---- Imprimir

    private class FakeJob(
        var dialogResult: Boolean = true,
        var printError: PrinterException? = null,
        var onPrint: (Pageable) -> Unit = {},
    ) : PrinterJob() {
        var received: Pageable? = null
        var pagesAtPrint = -1
        var printCalls = 0
        var dialogThread: Thread? = null
        var printThread: Thread? = null

        override fun setPageable(document: Pageable) {
            received = document
        }

        override fun printDialog(): Boolean {
            dialogThread = Thread.currentThread()
            return dialogResult
        }

        override fun print() {
            printCalls++
            printThread = Thread.currentThread()
            val p = checkNotNull(received)
            pagesAtPrint = p.numberOfPages
            onPrint(p)
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

    @Test
    fun cancelPrintDoesNothing() {
        val job = FakeJob(dialogResult = false)
        val result = runBlocking { actions(printer = SystemPrintDialog { job }).print() }
        assertEquals(PrintResult.Cancelled, result)
        assertEquals(0, job.printCalls)
    }

    @Test
    fun printPassesAllPages() {
        val job = FakeJob()
        val result = runBlocking { actions(printer = SystemPrintDialog { job }).print() }
        assertEquals(PrintResult.Printed, result)
        assertEquals(1, job.printCalls)
        assertEquals(SamplePdf.PAGE_COUNT, job.pagesAtPrint)
        assertNotNull(job.printThread)
        assertTrue(
            job.printThread?.name?.startsWith("fake-io") == true,
            "print() roda na thread que chamou, fora da EDT",
        )
    }

    @Test
    fun printFailureShowsMessage() {
        val job = FakeJob(printError = PrinterException("impressora quebrada em /dev/lp0"))
        val result = runBlocking { actions(printer = SystemPrintDialog { job }).print() }
        val failed = result as PrintResult.Failed
        assertEquals("Não foi possível imprimir.", failed.message)
    }

    @Test
    fun printFailureFromPortShowsMessage() {
        val result = runBlocking { actions(printer = { throw PrinterException("x") }).print() }
        assertEquals(PrintResult.Failed("Não foi possível imprimir."), result)
    }

    @Test
    fun printOfUnreadableBytesFails() {
        val broken =
            PdfActions(byteArrayOf(1, 2, 3), "a.pdf", { null }, { PrintOutcome.PRINTED }, uiDispatcher, ioDispatcher)
        assertEquals(PrintResult.Failed("Não foi possível imprimir."), runBlocking { broken.print() })
    }

    @Test
    fun printDialogRunsOnEdt() {
        val job = FakeJob()
        runBlocking { actions(printer = SystemPrintDialog { job }).print() }
        assertFalse(job.dialogThread === job.printThread)
        assertTrue(job.dialogThread?.name?.startsWith("AWT-EventQueue") == true, job.dialogThread?.name)
    }

    @Test
    fun printDialogFromEdtRunsDirectly() {
        val job = FakeJob()
        val outcome = AtomicReference<PrintOutcome>()
        EventQueue.invokeAndWait {
            outcome.set(
                SystemPrintDialog { job }.print(
                    object : Pageable {
                        override fun getNumberOfPages() = 1

                        override fun getPageFormat(pageIndex: Int) = PageFormat()

                        override fun getPrintable(pageIndex: Int) = Printable { _, _, _ -> Printable.NO_SUCH_PAGE }
                    },
                ),
            )
        }
        assertEquals(PrintOutcome.PRINTED, outcome.get())
        assertTrue(job.dialogThread?.name?.startsWith("AWT-EventQueue") == true)
    }

    /** Imprime a página [index] numa imagem: prova que o `PDDocument` da impressão ainda está aberto. */
    private fun renderPage(
        pageable: Pageable,
        index: Int,
    ): Int {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics() as Graphics2D
        try {
            return pageable.getPrintable(index).print(g, pageable.getPageFormat(index), index)
        } finally {
            g.dispose()
        }
    }

    @Test
    fun printWhileNavigatingIsSafe() {
        val state = PdfDocumentState(bytes, scope, documentDispatcher())
        runBlocking { assertTrue(state.open()) }
        val printing = CountDownLatch(1)
        val navigated = CountDownLatch(1)
        val rendered = AtomicReference<Int>()
        val job =
            FakeJob(onPrint = { pageable ->
                printing.countDown()
                assertTrue(navigated.await(30, TimeUnit.SECONDS))
                rendered.set(renderPage(pageable, SamplePdf.PAGE_COUNT - 1))
            })
        val result =
            runBlocking {
                val print = scope.async { actions(printer = SystemPrintDialog { job }).print() }
                withContext(uiDispatcher) { assertTrue(printing.await(30, TimeUnit.SECONDS)) }
                state.next()
                state.next()
                state.zoomIn()
                state.previous()
                navigated.countDown()
                print.await()
            }
        assertEquals(PrintResult.Printed, result)
        assertEquals(Printable.PAGE_EXISTS, rendered.get())
        state.close()
    }

    @Test
    fun closeDuringPrintDoesNotBreakPrint() {
        val state = PdfDocumentState(bytes, scope, documentDispatcher())
        runBlocking { assertTrue(state.open()) }
        val printing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val rendered = AtomicReference<Int>()
        val job =
            FakeJob(onPrint = { pageable ->
                printing.countDown()
                assertTrue(closed.await(30, TimeUnit.SECONDS))
                rendered.set(renderPage(pageable, 0))
            })
        val result =
            runBlocking {
                val print = scope.async { actions(printer = SystemPrintDialog { job }).print() }
                withContext(uiDispatcher) { assertTrue(printing.await(30, TimeUnit.SECONDS)) }
                state.close()
                closed.countDown()
                print.await()
            }
        assertEquals(PrintResult.Printed, result)
        assertEquals(Printable.PAGE_EXISTS, rendered.get())
    }

    @Test
    fun toStringNeverShowsBytesOrName() {
        val text = actions(name = "segredo-da-maria.pdf").toString()
        assertFalse(text.contains("segredo"))
        assertFalse(text.contains("%PDF"))
    }

    private val extra = mutableListOf<ExecutorService>()

    private fun documentDispatcher(): CoroutineDispatcher {
        val e = Executors.newSingleThreadExecutor()
        extra += e
        return e.asCoroutineDispatcher()
    }
}
