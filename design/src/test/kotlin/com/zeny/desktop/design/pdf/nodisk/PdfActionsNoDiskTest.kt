package com.zeny.desktop.design.pdf.nodisk

import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PrintOutcome
import com.zeny.desktop.design.pdf.PrintResult
import com.zeny.desktop.design.pdf.SamplePdf
import com.zeny.desktop.design.pdf.SaveResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Só na `noDiskTest`: imprimir (porta falsa que consome todas as páginas) e salvar cancelado não criam arquivo. */
class PdfActionsNoDiskTest {
    @Test
    fun printToFakePortAndCancelledSaveCreateNoFile() = NoDiskProbe.assertNoFileCreatedBy(::cycle)

    private fun cycle() {
        val bytes = SamplePdf.threePages()
        val ui = Executors.newSingleThreadExecutor()
        val io = Executors.newSingleThreadExecutor()
        try {
            val image = java.awt.image.BufferedImage(60, 60, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val actions =
                PdfActions(
                    bytes,
                    "ficha-1.pdf",
                    { null },
                    { pageable ->
                        for (i in 0 until pageable.numberOfPages) {
                            val g = image.createGraphics()
                            try {
                                pageable.getPrintable(i).print(g, pageable.getPageFormat(i), i)
                            } finally {
                                g.dispose()
                            }
                        }
                        PrintOutcome.PRINTED
                    },
                    ui.asCoroutineDispatcher(),
                    io.asCoroutineDispatcher(),
                )
            assertEquals(PrintResult.Printed, runBlocking { actions.print() })
            assertEquals(SaveResult.Cancelled, runBlocking { actions.save() })
            val cancelPrint =
                PdfActions(
                    bytes,
                    "a.pdf",
                    { null },
                    { PrintOutcome.CANCELLED },
                    ui.asCoroutineDispatcher(),
                    io.asCoroutineDispatcher(),
                )
            assertEquals(PrintResult.Cancelled, runBlocking { cancelPrint.print() })
        } finally {
            ui.shutdownNow()
            io.shutdownNow()
        }
    }
}
