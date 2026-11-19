package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Os textos da conta. Os valores vêm do servidor e só são formatados em BRL, nunca calculados; nenhum id. */
@Suppress("TooManyFunctions") // um rótulo por enum e um texto por regra, num lugar só
object FolioText {
    const val NO_FOLIO = "Esta reserva ainda não tem conta."
    const val CLOSED = "Conta encerrada"
    const val LIMIT_EXCEEDED = "Limite excedido"
    const val NO_LIMIT = "Sem limite"
    const val NO_POSTINGS = "Nenhum lançamento nesta conta."
    const val NO_OPEN_FOLIOS = "Nenhuma conta aberta."
    const val REVERSED = "Estornado"
    const val CODES_FORBIDDEN = "Seu perfil não pode listar os códigos de lançamento."
    const val CODE_REQUIRED = "Escolha o código de lançamento."
    const val QUANTITY_RANGE = "A quantidade é um número inteiro de 1 a 9999."
    const val PRICE_REQUIRED = "Informe o valor unitário."
    const val PRICE_POSITIVE = "O valor unitário deve ser maior que zero."
    const val POSTING_UNCONFIRMED =
        "Não foi possível confirmar o lançamento. Lance de novo sem mudar nada; " +
            "se fechar, confira a conta antes de lançar de novo."
    const val POSTING_SERVER_UNCONFIRMED =
        "O servidor não confirmou o lançamento. Feche e confira a conta antes de lançar de novo."
    const val ROUTED_ELSEWHERE = "Lançado em outra conta pelo roteamento."
    const val REASON_REQUIRED = "Informe o motivo do estorno."
    const val REVERSAL_UNCONFIRMED =
        "Não foi possível confirmar o estorno. Estorne de novo sem mudar nada; se fechar, confira a conta antes de refazer."
    const val REVERSAL_SERVER_UNCONFIRMED =
        "O servidor não confirmou o estorno. Feche e confira a conta antes de refazer."
    const val APPROVAL_CANCELLED = "Aprovação cancelada. O estorno não foi feito."
    const val NO_PAYMENTS = "Nenhum pagamento nesta conta."
    const val PAYMENTS_FAILED = "Não foi possível ler os pagamentos."
    const val DEPOSIT = "Depósito"
    const val AMOUNT_REQUIRED = "Informe o valor."
    const val PAYMENT_UNCONFIRMED =
        "Não foi possível confirmar o recebimento. Receba de novo sem mudar nada; " +
            "se fechar, confira a conta antes de receber de novo."
    const val PAYMENT_SERVER_UNCONFIRMED =
        "O servidor não confirmou o recebimento. Feche e confira a conta antes de receber de novo."
    const val PAYMENT_RECORDED = "Pagamento registrado."
    const val REFUNDED = "Reembolsado"
    const val REFUND_UNCONFIRMED =
        "Não foi possível confirmar o reembolso. Reembolse de novo sem mudar nada; " +
            "se fechar, confira a conta antes de refazer."
    const val REFUND_SERVER_UNCONFIRMED =
        "O servidor não confirmou o reembolso. Feche e confira a conta antes de refazer."
    const val REFUND_APPROVAL_CANCELLED = "Aprovação cancelada. O reembolso não foi feito."
    const val REFUND_RECORDED = "Reembolso registrado."
    const val NO_PRE_AUTHORIZATIONS = "Nenhuma caução nesta conta."
    const val PRE_AUTHORIZATIONS_FAILED = "Não foi possível ler as cauções."
    const val VALIDITY_REQUIRED = "Informe a validade."
    const val VALIDITY_RANGE = "A validade vai de hoje até 89 dias."
    const val ZONE_MISSING = "Não foi possível ler o fuso do hotel. Feche e abra de novo."
    const val PRE_AUTHORIZATION_UNCONFIRMED =
        "Não foi possível confirmar o registro da caução. Registre de novo sem mudar nada; " +
            "se fechar, confira a conta antes de registrar de novo."
    const val PRE_AUTHORIZATION_SERVER_UNCONFIRMED =
        "O servidor não confirmou o registro da caução. Feche e confira a conta antes de registrar de novo."
    const val PRE_AUTHORIZATION_RECORDED = "Caução registrada."
    const val RELEASE_UNCONFIRMED =
        "Não foi possível confirmar a liberação. Libere de novo; " +
            "se fechar, confira a conta antes de liberar de novo."
    const val RELEASE_SERVER_UNCONFIRMED =
        "O servidor não confirmou a liberação. Feche e confira a conta antes de liberar de novo."
    const val RELEASE_DONE = "Sobra liberada."
    const val CAPTURE_UNCONFIRMED =
        "Não foi possível confirmar a captura. Capture de novo sem mudar nada; " +
            "se fechar, confira a conta antes de capturar de novo."
    const val CAPTURE_SERVER_UNCONFIRMED =
        "O servidor não confirmou a captura. Feche e confira a conta antes de capturar de novo."
    const val CAPTURE_DONE = "Caução capturada."
    const val ADJUSTMENT_REASON_REQUIRED = "Informe o motivo."
    const val ADJUSTMENT_UNCONFIRMED =
        "Não foi possível confirmar o ajuste. Ajuste de novo sem mudar nada; " +
            "se fechar, confira a conta antes de refazer."
    const val ADJUSTMENT_SERVER_UNCONFIRMED =
        "O servidor não confirmou o ajuste. Feche e confira a conta antes de refazer."
    const val ADJUSTMENT_APPROVAL_CANCELLED = "Aprovação cancelada. O ajuste não foi feito."
    const val ADJUSTMENT_DONE = "Ajuste lançado."
    const val REMOVAL_TEXT = "O servidor registra o motivo: pedido do cliente."
    const val REMOVAL_UNCONFIRMED =
        "Não foi possível confirmar a remoção da taxa. Remova de novo; " +
            "se fechar, confira a conta antes de refazer."
    const val REMOVAL_SERVER_UNCONFIRMED =
        "O servidor não confirmou a remoção da taxa. Feche e confira a conta antes de refazer."
    const val REMOVAL_DONE = "Taxa de serviço removida."
    const val TRANSFER_NOTHING_CHECKED = "Marque ao menos um lançamento."
    const val TRANSFER_TARGET_REQUIRED = "Escolha a conta de destino."
    const val TRANSFER_TOO_MANY_CHECKED = "Marque no máximo 100 lançamentos."
    const val TRANSFER_UNCONFIRMED =
        "Não foi possível confirmar a transferência. Transfira de novo sem mudar nada; " +
            "se fechar, confira a conta antes de refazer."
    const val TRANSFER_SERVER_UNCONFIRMED =
        "O servidor não confirmou a transferência. Feche e confira a conta antes de refazer."
    const val SPLIT_PARTS_INVALID = "Confira as partes da divisão."
    const val SPLIT_THIS_FOLIO = "Esta conta (fica)"
    const val SPLIT_UNCONFIRMED =
        "Não foi possível confirmar a divisão. Divida de novo sem mudar nada; " +
            "se fechar, confira a conta antes de refazer."
    const val SPLIT_SERVER_UNCONFIRMED =
        "O servidor não confirmou a divisão. Feche e confira a conta antes de refazer."
    const val NO_PAYER = "Sem pagador"
    const val PAYER_SET = "Empresa pagadora definida"
    const val PAYER_READING = "lendo a conta…"
    const val PAYER_UNREAD = "não foi possível ler"
    const val PAYER_CHOICE_FORBIDDEN = "Seu perfil não pode escolher a empresa pagadora."
    const val NO_COMPANIES = "Nenhuma empresa ativa encontrada."
    const val LIMIT_NEGATIVE = "O limite não pode ser negativo."
    const val ACCOUNT_REREAD = "Os valores foram relidos; confira e salve de novo."
    const val ACCOUNT_UNCONFIRMED_START = "Não foi possível confirmar a alteração."
    const val ACCOUNT_UNCONFIRMED =
        "$ACCOUNT_UNCONFIRMED_START A conta foi relida; confira antes de salvar de novo."
    const val ACCOUNT_SAVED = "Pagador e limite atualizados."
    const val REOPEN_REASON_REQUIRED = "Informe o motivo."
    const val REOPEN_APPROVAL_CANCELLED = "Aprovação cancelada. A conta não foi reaberta."
    const val REOPEN_UNCONFIRMED =
        "Não foi possível confirmar a reabertura. A conta foi relida; confira antes de tentar de novo."
    const val REOPEN_DONE = "Conta reaberta."
    private const val UNKNOWN = "—"

    private val postingTypes =
        ExtensibleEnumLabels(
            mapOf(
                "REGULAR" to "Lançamento",
                "REVERSAL" to "Estorno",
                "ADJUSTMENT" to "Ajuste",
                "TRANSFER_OUT" to "Transferência (saída)",
                "TRANSFER_IN" to "Transferência (entrada)",
                "SERVICE_CHARGE" to "Taxa de serviço",
            ),
        )

    private val statementKinds = ExtensibleEnumLabels(mapOf("POSTING" to "Lançamento", "PAYMENT" to "Pagamento"))

    private val paymentTypes = ExtensibleEnumLabels(mapOf("PAYMENT" to "Recebimento", "REFUND" to "Reembolso"))

    private val preAuthorizationStatuses =
        ExtensibleEnumLabels(
            mapOf(
                "ACTIVE" to "Ativa",
                "PARTIALLY_CAPTURED" to "Capturada em parte",
                "CAPTURED" to "Capturada",
                "RELEASED" to "Liberada",
                "EXPIRED" to "Vencida",
            ),
        )

    private val expiresFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
    private val utcDateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu")

    private val paymentMethods = ExtensibleEnumLabels(PaymentMethod.entries.associate { it.name to method(it) })

    /** O decimal do servidor em BRL (`R$ 1.234,56`); um valor fora do formato do contrato vira "—". */
    fun money(decimal: String): String =
        try {
            MoneyInput.format(Money.parse(decimal))
        } catch (_: IllegalArgumentException) {
            UNKNOWN
        }

    /** A data de negócio do servidor em `dd/MM/aaaa`; fora do formato, "—". */
    fun date(iso: String): String =
        try {
            DateInput.format(BusinessDate.parse(iso))
        } catch (_: IllegalArgumentException) {
            UNKNOWN
        }

    /** A quantidade como o servidor a devolve, sem casas quando é inteira (`2`), com vírgula quando não (`1,5`). */
    fun quantity(value: Double): String =
        BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
            .replace('.', ',')

    /** O lançamento que o servidor roteou para a conta [number]. */
    fun routedTo(number: String): String = "Lançado na conta $number pelo roteamento."

    /** O código de lançamento na escolha: "<code> — <name>". */
    fun code(
        code: String,
        name: String,
    ): String = "$code — $name"

    /** O tipo do lançamento (`x-extensible-enum`): o desconhecido aparece como "Outro (VALOR)". */
    fun postingType(value: String): String = postingTypes.label(value)

    /** O tipo da linha do extrato (`x-extensible-enum`): o desconhecido aparece como "Outro (VALOR)". */
    fun statementKind(value: String): String = statementKinds.label(value)

    /** O tipo do pagamento (`x-extensible-enum`): o desconhecido aparece como "Outro (VALOR)". */
    fun paymentType(value: String): String = paymentTypes.label(value)

    /** A situação da caução (`x-extensible-enum`): a desconhecida aparece como "Outro (VALOR)". */
    fun preAuthorizationStatus(value: String): String = preAuthorizationStatuses.label(value)

    /**
     * A validade da caução (um instante do servidor) em `dd/MM/aaaa HH:mm` no fuso do hotel; sem o fuso, só a data UTC
     * com "(UTC)"; fora do formato, "—".
     */
    fun expiresAt(
        iso: String,
        zone: ZoneId?,
    ): String =
        try {
            val instant = Instant.parse(iso)
            if (zone == null) {
                "${utcDateFormat.format(instant.atZone(ZoneOffset.UTC))} (UTC)"
            } else {
                expiresFormat.format(instant.atZone(zone))
            }
        } catch (_: DateTimeParseException) {
            UNKNOWN
        }

    /** O que a liberação faz, com o capturável que o servidor devolveu, já formatado (sem ele, só o texto). */
    fun release(capturable: String?): String =
        if (capturable == null) {
            "A sobra será liberada na maquininha."
        } else {
            "A sobra de $capturable será liberada na maquininha."
        }

    /** A forma do pagamento lida do servidor (`x-extensible-enum`): a desconhecida aparece como "Outro (VALOR)". */
    fun paymentMethod(value: String): String = paymentMethods.label(value)

    /** A forma oferecida no recebimento. */
    fun method(method: PaymentMethod): String =
        when (method) {
            PaymentMethod.CASH -> "Dinheiro"
            PaymentMethod.DEBIT_CARD -> "Cartão de débito"
            PaymentMethod.CREDIT_CARD -> "Cartão de crédito"
            PaymentMethod.PIX -> "Pix"
            PaymentMethod.BANK_TRANSFER -> "Transferência"
            PaymentMethod.OTHER -> "Outra"
        }

    /** O texto ao lado do campo com o problema de formato; nunca repete o que foi digitado. */
    fun problem(problem: PaymentProblem): String =
        when (problem) {
            PaymentProblem.AMOUNT -> "O valor deve ser maior que zero."
            PaymentProblem.TENDERED -> "O valor entregue não pode ser menor que o valor."
            PaymentProblem.CARD_BRAND -> "A bandeira tem só letras e espaços, até 20."
            PaymentProblem.CARD_LAST4 -> "Informe os 4 últimos dígitos do cartão."
            PaymentProblem.NSU -> "O NSU tem até 20 letras e números, no máximo 12 dígitos."
            PaymentProblem.AUTHORIZATION_CODE -> "A autorização tem até 20 letras e números, no máximo 12 dígitos."
            PaymentProblem.REASON -> "Informe o motivo, com até 200 caracteres."
        }

    /** O aviso do recebimento feito, com o troco que o servidor devolveu quando é maior que zero. */
    fun received(change: String?): String =
        if (change == null) PAYMENT_RECORDED else "$PAYMENT_RECORDED Troco: $change."

    /** O reembolsável que o servidor devolveu, já formatado. */
    fun refundable(value: String): String = "Reembolsável: $value"

    /** O restante do lançamento que o servidor devolveu, já formatado. */
    fun remaining(value: String): String = "Resta: $value"

    /** A linha do ajuste ou do estorno: a descrição do lançamento que ele corrige. */
    fun corrects(description: String): String = "Corrige: $description"

    /** O aviso da transferência feita, com o número da conta de destino. */
    fun transferredTo(number: String): String = "Transferido para a conta $number."

    /** O aviso da divisão feita, com o número de partes enviadas (a própria conta conta). */
    fun splitAmong(parts: Int): String = "Dividido entre $parts contas."

    /** O aviso da taxa removida, com o motivo que o servidor registrou (sem ele, só que foi removida). */
    fun serviceChargeRemoved(reason: String?): String =
        if (reason == null) REMOVAL_DONE else "Taxa de serviço removida: $reason"

    /** O capturável que o servidor devolveu, já formatado. */
    fun capturable(value: String): String = "Capturável: $value"

    /** O dono da conta pelo rótulo do tipo, nunca pelo id. */
    fun owner(type: FolioOwnerDto.Type?): String =
        when (type) {
            FolioOwnerDto.Type.RESERVATION -> "Reserva"
            FolioOwnerDto.Type.GROUP -> "Grupo"
            FolioOwnerDto.Type.EVENT -> "Evento"
            FolioOwnerDto.Type.COMPANY -> "Empresa"
            FolioOwnerDto.Type.WALK_IN -> "Avulsa"
            null -> UNKNOWN
        }

    fun status(status: FolioResponse.Status): String =
        when (status) {
            FolioResponse.Status.OPEN -> "Aberta"
            FolioResponse.Status.CLOSED -> CLOSED
        }

    fun audience(audience: StatementAudience): String =
        when (audience) {
            StatementAudience.INTERNAL -> "Interno"
            StatementAudience.GUEST -> "Para o hóspede"
        }
}
