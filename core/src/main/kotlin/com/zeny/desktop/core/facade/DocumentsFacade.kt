package com.zeny.desktop.core.facade

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.ResponseFormatException
import com.zeny.desktop.sdk.client.FrontdeskApi
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

/** Teto do PDF da ficha: acima disso a resposta não é uma ficha. */
private const val MAX_PDF_BYTES = 20L * 1024 * 1024
private val PDF_MAGIC = "%PDF-".encodeToByteArray()
private const val PDF_EXTENSION = ".pdf"
private const val PERCENT_ESCAPE_LENGTH = 2
private const val HEX_RADIX = 16
private const val EXTENDED_PARTS = 3

/**
 * Um PDF recebido do servidor, só em memória. `fileName` é o nome do servidor já saneado, ou `null`. Os bytes e o nome
 * ficam fora do `toString`.
 */
class PdfDocument(
    val bytes: ByteArray,
    val fileName: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is PdfDocument && fileName == other.fileName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (fileName?.hashCode() ?: 0)

    override fun toString(): String = "PdfDocument(${bytes.size} bytes)"
}

/**
 * Documentos binários do servidor. Um `id` que não é UUID lança `IllegalArgumentException` antes da rede, sem o valor
 * na mensagem.
 */
class DocumentsFacade internal constructor(
    private val frontdesk: FrontdeskApi,
    private val executor: ApiExecutor,
) {
    /**
     * A ficha de registro da reserva em PDF. Resposta que não é PDF (tipo, assinatura `%PDF-`, corpo vazio ou acima de
     * 20 MB) é `ApiError.IncompatibleResponse`, sem os bytes na causa.
     */
    suspend fun registrationCard(reservationId: String): PdfDocument {
        val id = Uuid.parseOrNull(reservationId) ?: throw IllegalArgumentException("id de reserva inválido")
        val response =
            executor.executeBytes(MAX_PDF_BYTES) {
                frontdesk.prepareGetRegistrationCardPdf(id, ContentType.Application.Pdf, it.headers)
            }
        val typeOk =
            response.contentType
                ?.takeIf(::wellFormedParameters)
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?.match(ContentType.Application.Pdf) == true
        val magicOk =
            response.bytes.size >= PDF_MAGIC.size && PDF_MAGIC.indices.all { response.bytes[it] == PDF_MAGIC[it] }
        if (!typeOk || !magicOk) {
            throw ApiError.IncompatibleResponse(ResponseFormatException(IllegalStateException("não é um PDF")))
        }
        return PdfDocument(response.bytes, fileNameOf(response.contentDisposition))
    }
}

/** Cada parâmetro depois do `;` precisa de nome e de `=`: o parser do Ktor deixa passar `; =`. */
private fun wellFormedParameters(header: String): Boolean =
    header.split(';').drop(1).all { it.isBlank() || it.substringBefore('=', "").isNotBlank() }

/** O nome do `Content-Disposition` (`filename*` primeiro), saneado; `null` se ausente ou inválido. */
private fun fileNameOf(header: String?): String? {
    val disposition = header?.let { runCatching { ContentDisposition.parse(it) }.getOrNull() } ?: return null
    val raw =
        disposition.parameter("filename*")?.let(::decodeExtended)
            ?: disposition.parameter("filename")
    return raw?.let(::sanitize)
}

/** `UTF-8''ficha-R%C3%A9.pdf`: o charset, a língua opcional e o valor com `%XX`; `null` se malformado. */
private fun decodeExtended(value: String): String? {
    val parts = value.split('\'', limit = EXTENDED_PARTS)
    return if (parts.size == EXTENDED_PARTS) {
        runCatching { percentDecode(parts[2], charset(parts[0].ifBlank { "UTF-8" })) }.getOrNull()
    } else {
        null
    }
}

/** Lança `IllegalArgumentException` num `%XX` truncado ou não hexadecimal. */
private fun percentDecode(
    text: String,
    charset: java.nio.charset.Charset,
): String {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        if (text[i] == '%') {
            val hex = text.substring(i + 1, i + 1 + PERCENT_ESCAPE_LENGTH)
            out.write(hex.toInt(HEX_RADIX))
            i += 1 + PERCENT_ESCAPE_LENGTH
        } else {
            out.write(text[i].toString().encodeToByteArray())
            i++
        }
    }
    return out.toByteArray().toString(charset)
}

/** Só o último segmento, sem `..` nem caractere de controle, terminando em `.pdf`. */
private fun sanitize(raw: String): String? {
    val last = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val invalid =
        last.isEmpty() || last.contains("..") ||
            last.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    return when {
        invalid -> null
        last.endsWith(PDF_EXTENSION, ignoreCase = true) -> last
        else -> last + PDF_EXTENSION
    }
}
