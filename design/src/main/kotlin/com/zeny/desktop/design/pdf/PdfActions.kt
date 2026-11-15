package com.zeny.desktop.design.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPageable
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.print.Pageable
import java.awt.print.PrinterJob
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Escolhe onde salvar. `null` é cancelado. Roda na thread de UI (EDT). */
fun interface SaveLocationChooser {
    fun choose(suggestedName: String): Path?
}

/** Diálogo "Salvar como…" nativo (`FileDialog` em modo `SAVE`); o diálogo cuida de sobrescrever. */
class FileDialogSaveChooser(
    private val parent: Frame?,
) : SaveLocationChooser {
    override fun choose(suggestedName: String): Path? {
        val dialog = FileDialog(parent, SAVE_TITLE, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val result = saveDialogResult(dialog.directory, dialog.file)
        dialog.dispose()
        return result
    }

    private companion object {
        const val SAVE_TITLE = "Salvar ficha de registro"
    }
}

/** Mapeia o resultado do `FileDialog` para o caminho escolhido; sem arquivo (cancelado) ou inválido, `null`. */
fun saveDialogResult(
    directory: String?,
    file: String?,
): Path? {
    if (file.isNullOrEmpty()) return null
    return try {
        if (directory.isNullOrEmpty()) Path.of(file) else Path.of(directory, file)
    } catch (_: InvalidPathException) {
        null
    }
}

enum class PrintOutcome { PRINTED, CANCELLED }

/** Porta da impressão: mostra o diálogo e imprime. `PrinterException` sobe para o [PdfActions]. */
fun interface PrintDialog {
    fun print(pageable: Pageable): PrintOutcome
}

/**
 * Impressão pelo diálogo do sistema. O `printDialog()` roda na EDT (direto se já estiver nela); o `print()`
 * roda na thread que chamou, fora da EDT.
 */
class SystemPrintDialog(
    private val jobFactory: () -> PrinterJob = PrinterJob::getPrinterJob,
) : PrintDialog {
    override fun print(pageable: Pageable): PrintOutcome {
        val job = jobFactory()
        job.setPageable(pageable)
        if (!onEdt { job.printDialog() }) return PrintOutcome.CANCELLED
        job.print()
        return PrintOutcome.PRINTED
    }

    private fun onEdt(block: () -> Boolean): Boolean {
        if (EventQueue.isDispatchThread()) return block()
        var result = false
        try {
            EventQueue.invokeAndWait { result = block() }
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
        return result
    }
}

sealed interface SaveResult {
    data object Saved : SaveResult

    data object Cancelled : SaveResult

    data class Failed(
        val message: String,
    ) : SaveResult
}

sealed interface PrintResult {
    data object Printed : PrintResult

    data object Cancelled : PrintResult

    data class Failed(
        val message: String,
    ) : PrintResult
}

/**
 * "Salvar como…" e "Imprimir" da ficha (ADR 0009): o disco só recebe o arquivo no destino que o usuário
 * escolheu; a impressão usa um `PDDocument` próprio, carregado em memória e fechado ao fim. O diálogo de
 * salvar roda no [uiDispatcher]; a escrita e o `print()` no [ioDispatcher]. Os bytes e o nome nunca saem
 * daqui em log, mensagem ou [toString].
 */
class PdfActions(
    private val bytes: ByteArray,
    private val suggestedName: String,
    private val chooser: SaveLocationChooser,
    private val printer: PrintDialog,
    private val uiDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) {
    // Dois "Salvar como…" (do mesmo PdfActions ou de outro, no mesmo destino) não se misturam: o segundo espera.
    suspend fun save(): SaveResult = saveLock.withLock { saveLocked() }

    private suspend fun saveLocked(): SaveResult {
        val name = suggestedPdfName(suggestedName, FALLBACK_NAME)
        return try {
            val target = withContext(uiDispatcher) { chooser.choose(name) } ?: return SaveResult.Cancelled
            withContext(ioDispatcher) { Files.write(target, bytes) }
            SaveResult.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SaveResult.Failed(SAVE_ERROR)
        }
    }

    suspend fun print(): PrintResult =
        try {
            withContext(ioDispatcher) { printOwnCopy() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PrintResult.Failed(PRINT_ERROR)
        }

    private fun printOwnCopy(): PrintResult {
        val document: PDDocument = loadPdf(bytes) ?: return PrintResult.Failed(PRINT_ERROR)
        document.use {
            return when (printer.print(PDFPageable(it))) {
                PrintOutcome.PRINTED -> PrintResult.Printed
                PrintOutcome.CANCELLED -> PrintResult.Cancelled
            }
        }
    }

    override fun toString() = "PdfActions(size=${bytes.size})"

    private companion object {
        val saveLock = Mutex()
        const val FALLBACK_NAME = "ficha.pdf"
        const val SAVE_ERROR = "Não foi possível salvar o arquivo."
        const val PRINT_ERROR = "Não foi possível imprimir."
    }
}

private const val PDF_EXTENSION = ".pdf"
private const val MAX_NAME_BYTES = 255
private val WINDOWS_ILLEGAL = setOf(':', '<', '>', '"', '|', '?', '*')
private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

/**
 * Nome sugerido ao diálogo: só o último segmento, sem `..` nem caractere de controle (inclusive Unicode de
 * formato, separador de linha e de parágrafo), sem ponto nem espaço no fim, sem nome reservado do Windows
 * (CON, NUL, COM1...), com `:<>"|?*` trocados por `_`, com `.pdf` e no máximo 255 bytes UTF-8. Se [raw] é
 * ausente ou inválido, usa o [fallback], saneado do mesmo jeito (e `ficha.pdf` se ele também for inválido).
 */
fun suggestedPdfName(
    raw: String?,
    fallback: String,
): String = sanitizePdfName(raw) ?: sanitizePdfName(fallback) ?: "ficha$PDF_EXTENSION"

private fun sanitizePdfName(raw: String?): String? {
    val segment =
        raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
    if (segment.isEmpty() || segment.contains("..") || segment.any(::isForbidden)) return null
    val last = segment.map { if (it in WINDOWS_ILLEGAL) '_' else it }.joinToString("").trimEnd('.', ' ')
    val hasExtension = last.endsWith(PDF_EXTENSION, ignoreCase = true)
    val base = (if (hasExtension) last.dropLast(PDF_EXTENSION.length) else last).trimEnd('.', ' ')
    val extension = if (hasExtension) last.takeLast(PDF_EXTENSION.length) else PDF_EXTENSION
    val capped = capBase(base, MAX_NAME_BYTES - extension.length).trimEnd('.', ' ')
    val reserved = capped.substringBefore('.').trimEnd().uppercase() in WINDOWS_RESERVED
    return if (capped.isEmpty() || reserved) null else capped + extension
}

/** Corta em [maxBytes] UTF-8 sem partir um ponto de código. */
private fun capBase(
    base: String,
    maxBytes: Int,
): String {
    var bytes = 0
    var end = 0
    while (end < base.length) {
        val cp = base.codePointAt(end)
        val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(cp)
    }
    return base.substring(0, end)
}

private fun isForbidden(c: Char): Boolean =
    c.isISOControl() ||
        when (Character.getType(c)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true

            else -> false
        }
