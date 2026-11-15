package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.design.feedback.chipModel
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option

/** Textos puros dos recados (pt-BR); sem estado. Nenhum deles carrega o texto do recado. */
object TraceText {
    const val DEPARTMENTS_DENIED =
        "Sem permissão para ver os setores. " +
            "Peça ao administrador a permissão \"Consultar setores, colaboradores e turnos\"."
    const val UNKNOWN_CREATE_RESULT =
        "Não foi possível confirmar se o recado foi criado. Confira a lista antes de tentar de novo."

    private const val NO_LINK = "Sem vínculo"

    private val statusLabels = ExtensibleEnumLabels(mapOf("OPEN" to "Aberto", "RESOLVED" to "Resolvido"))
    private val statusTones = mapOf("OPEN" to StatusTone.WARNING, "RESOLVED" to StatusTone.SUCCESS)

    private val problemTexts =
        mapOf(
            "TRACE_INVALID" to
                "O recado está inválido: confira o texto (até 500 caracteres) e o vencimento " +
                "(não antes da data operacional).",
            "TRACE_ALREADY_RESOLVED" to "Este recado já foi resolvido.",
            "TRACE_NOT_FOUND" to "O recado não existe mais. Atualize a lista.",
            "UNKNOWN_DEPARTMENT" to "O setor escolhido não existe mais. Escolha outro.",
            "UNKNOWN_RESERVATION" to "A reserva do recado não existe mais.",
            "UNKNOWN_ROOM" to "A UH escolhida não existe mais. Escolha outra.",
            "ROOM_INACTIVE" to "A UH está inativa. Escolha outra.",
            // O contrato só confere o responsável quando muda e o formulário nunca o muda: texto defensivo.
            "UNKNOWN_ASSIGNEE" to "O responsável do recado não está mais vinculado a um colaborador.",
        )

    fun status(value: String): ChipModel = chipModel(value, statusLabels, statusTones)

    fun message(error: ApiError): String =
        when (error) {
            is ApiError.PreconditionFailed -> "Alguém alterou este recado. Confira e salve de novo."
            is ApiError.Problem -> error.code?.let { problemTexts[it] } ?: errorMessage(error)
            else -> errorMessage(error)
        }

    /** O vínculo do recado: "Reserva R-…" (ou só "Reserva", sem o código) ou "UH 203". Nunca o id nem o texto. */
    fun link(
        trace: TraceResponse,
        reservationCode: String?,
    ): String {
        val roomNumber = (trace.roomNumber as? Option.Some)?.value
        return when {
            trace.reservationId is Option.Some -> reservationCode?.let { "Reserva $it" } ?: "Reserva"
            roomNumber != null -> "UH $roomNumber"
            else -> NO_LINK
        }
    }
}
