package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.suggestedPdfName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val CARD_UNAVAILABLE = "REGISTRATION_CARD_UNAVAILABLE"
private const val RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND"
private const val UNAVAILABLE_TEXT = "A ficha só existe para reservas confirmadas, hospedadas ou encerradas."
private const val NOT_FOUND_TEXT = "A reserva não foi encontrada. Atualize o painel."
private const val OPEN_ERROR_TEXT = "Não foi possível abrir a ficha."

/** O que o visualizador mostra: um documento já aberto (nunca vazio) e as ações de salvar e imprimir. */
data class CardViewer(
    val document: PdfDocumentState,
    val actions: PdfActions,
)

/**
 * Estado da ficha de registro de uma reserva. Cada download cria um [PdfDocumentState] novo (ele é de uso único),
 * aberto aqui antes de expor o [viewer]; PDF que não abre vira [error]. O [close] fecha o documento, descarta um
 * resultado tardio e fecha o documento tardio. Os bytes nunca saem daqui em [toString].
 */
class RegistrationCardState(
    private val source: RegistrationCards,
    scope: CoroutineScope,
    private val reservationId: String,
    private val reservationCode: String,
    private val newDocument: (ByteArray, CoroutineScope) -> PdfDocumentState,
    private val newActions: (bytes: ByteArray, suggestedName: String) -> PdfActions,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    val title: String = "Ficha de registro — $reservationCode"

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var viewer by mutableStateOf<CardViewer?>(null)
        private set

    private var closed = false

    fun start() {
        if (closed || loading || viewer != null) return
        loading = true
        error = null
        ownScope.launch {
            try {
                val card = source.registrationCard(reservationId)
                currentCoroutineContext().ensureActive()
                show(card.bytes, card.fileName)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                error = failureText(e as? ApiError ?: ApiError.Network(e))
            } finally {
                loading = false
            }
        }
    }

    /** Baixa de novo, com um documento novo; o anterior (se houve) é fechado. */
    fun retry() {
        if (closed || loading) return
        viewer?.document?.close()
        viewer = null
        start()
    }

    fun close() {
        closed = true
        jobs.cancel()
        viewer?.document?.close()
        viewer = null
    }

    private suspend fun show(
        bytes: ByteArray,
        fileName: String?,
    ) {
        val document = newDocument(bytes, ownScope)
        var shown = false
        try {
            val opened = document.open()
            currentCoroutineContext().ensureActive()
            if (opened) {
                val name = suggestedPdfName(fileName, "ficha-$reservationCode.pdf")
                viewer = CardViewer(document, newActions(bytes, name))
                shown = true
            } else {
                error = OPEN_ERROR_TEXT
            }
        } finally {
            if (!shown) document.close()
        }
    }

    private fun failureText(failure: ApiError): String =
        when {
            failure is ApiError.Problem && failure.code == CARD_UNAVAILABLE -> UNAVAILABLE_TEXT
            failure is ApiError.Problem && failure.code == RESERVATION_NOT_FOUND -> NOT_FOUND_TEXT
            else -> errorMessage(failure)
        }

    override fun toString() = "RegistrationCardState(loading=$loading, viewer=${viewer != null})"
}
