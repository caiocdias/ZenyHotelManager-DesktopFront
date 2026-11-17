package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.sdk.model.DepositShortfallResponse
import com.zeny.desktop.sdk.model.PendingGuaranteeResponse
import com.zeny.desktop.sdk.model.ReallocationRequiredResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import java.time.ZoneId

private const val UNKNOWN = "—"
private const val SEPARATOR = " · "

/**
 * Os textos das quatro listas de pendências: cada função devolve o par (detalhe, prazo) da linha. Valores em
 * dinheiro saem como o servidor mandou, por [ReceptionText.money]; instantes, no fuso do hotel (sem fuso, "—").
 */
object PendingText {
    private val kinds =
        ExtensibleEnumLabels(mapOf("PROVISIONAL" to "Pré-reserva", "UNGUARANTEED" to "Sem garantia"))

    /** "Pré-reserva · Cartão", mais " · exigido R$ … · recebido R$ …" se o depósito vem; prazo = `dueAt`. */
    fun guarantee(
        item: PendingGuaranteeResponse,
        zone: ZoneId?,
    ): Pair<String, String> {
        val required = present(item.requiredDeposit)
        val received = present(item.depositReceived)
        val detail =
            listOfNotNull(
                kinds.label(item.kind),
                BookingText.guarantee(item.guaranteeType),
                required?.let { "exigido ${ReceptionText.money(it)}" },
                // Sem o exigido, o recebido não tem a que se referir: o front não completa o que o servidor não mandou.
                received?.takeIf { required != null }?.let { "recebido ${ReceptionText.money(it)}" },
            ).joinToString(SEPARATOR)
        val due = (ReceptionText.instant(item.dueAt, zone) ?: UNKNOWN) + if (item.overdue) " (vencida)" else ""
        return detail to due
    }

    /** Os três valores do depósito como vieram (o front não confere a conta); o item não tem prazo. */
    fun deposit(
        item: DepositShortfallResponse,
        zone: ZoneId?,
    ): Pair<String, String> {
        val registered = ReceptionText.instant(item.guaranteedAt, zone) ?: UNKNOWN
        val detail =
            listOf(
                "Exigido ${ReceptionText.money(item.requiredDeposit)}",
                "recebido ${ReceptionText.money(item.depositReceived)}",
                "falta ${ReceptionText.money(item.missing)}",
                "garantia registrada em $registered",
            ).joinToString(SEPARATOR)
        return detail to UNKNOWN
    }

    /** O rótulo da situação, com " · chegada vencida" se `arrivalOverdue`; prazo = a data de chegada. */
    fun noShow(item: ReservationListItemResponse): Pair<String, String> {
        val detail =
            listOfNotNull(ReceptionText.status(item.status).label, "chegada vencida".takeIf { item.arrivalOverdue })
                .joinToString(SEPARATOR)
        return detail to ReceptionText.date(item.arrival)
    }

    /** "UH 208 fora de ordem na estadia" (sem número: "UH alocada fora de ordem na estadia"); prazo = a chegada. */
    fun reallocation(item: ReallocationRequiredResponse): Pair<String, String> {
        val number = present(item.assignment.roomNumber)
        val detail = "UH ${number ?: "alocada"} fora de ordem na estadia"
        return detail to ReceptionText.date(item.arrival)
    }

    // O servidor manda `null` explícito no que não tem valor (`Option.Some(null)`); campo ausente é `Undefined`.
    private fun present(option: Option<String?>): String? = (option as? Option.Some)?.value?.takeIf { it.isNotBlank() }
}
