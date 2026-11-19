package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.facade.PreAuthorizationInput
import com.zeny.desktop.core.facade.problems
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import kotlinx.coroutines.CoroutineScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A última data aceita é hoje mais isso: o fim dela fica sempre dentro de 90 dias de agora. */
private const val MAX_VALIDITY_DAYS = 89L
private val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59)

/**
 * O diálogo "Registrar caução" da conta [folioId]: o valor autorizado, a validade (a data "válida até" do comprovante,
 * `dd/mm/aaaa`), a autorização (obrigatória) e, opcionais, o NSU, a bandeira e os 4 últimos dígitos. Não há campo de
 * número de cartão nem de CVV. As regras de formato são as de `PreAuthorizationInput.problems()`, cada uma com o texto
 * dela em [errors]; a validade vale de hoje até 89 dias depois de hoje no fuso [zone] do hotel ("hoje" pelo [clock]) e
 * vai como o fim desse dia (23:59:59) no fuso do hotel, em UTC; fora disso, [validityError]. Sem o fuso, o diálogo diz
 * que não leu o fuso e não confirma. Com problema, nada vai à rede.
 *
 * O envio, a `Idempotency-Key` por intenção, a classificação da falha e o fechamento são do ajudante comum
 * `FolioWrite`: o sucesso vai a [onRegistered]; a recusa definitiva, depois do texto do catálogo, a [onRefused] com o
 * `code`. O `toString()` não mostra o digitado.
 */
@Suppress("LongParameterList") // a porta, a conta, o fuso e o relógio da validade e os dois avisos para o holder
class PreAuthorizationFormState(
    private val source: PaymentSource,
    scope: CoroutineScope,
    private val folioId: String,
    private val zone: ZoneId?,
    private val clock: Clock,
    private val onRegistered: () -> Unit,
    onRefused: (code: String) -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(
            scope,
            FolioText.PRE_AUTHORIZATION_UNCONFIRMED,
            FolioText.PRE_AUTHORIZATION_SERVER_UNCONFIRMED,
            openShift = null,
            onRefused = onRefused,
        ).also { if (zone == null) it.message = FolioText.ZONE_MISSING }

    private var amountText by mutableStateOf("")
    private var expiresText by mutableStateOf("")
    private var authorizationText by mutableStateOf("")
    private var nsuText by mutableStateOf("")
    private var brandText by mutableStateOf("")
    private var last4Text by mutableStateOf("")

    /** A entrada enviada com a intenção em curso. */
    private var sent: PreAuthorizationInput? = null

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    /** O fuso que faltou, a validação recusada ou o resultado do último envio que não deu certo. */
    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend && zone != null

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    /** Os problemas de formato do último envio recusado, cada um com o texto ao lado do campo. */
    var errors by mutableStateOf<Map<PaymentProblem, String>>(emptyMap())
        private set

    /** O texto da validade recusada (vazia, fora do formato ou fora de hoje até 89 dias). */
    var validityError by mutableStateOf<String?>(null)
        private set

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = write.locked

    /** O valor autorizado (`MoneyField`, `1.234,56`). */
    var amount: String
        get() = amountText
        set(value) = edit(PaymentProblem.AMOUNT) { amountText = value }

    /** A data "válida até" do comprovante (`DateField`, `dd/mm/aaaa`). */
    var expiresOn: String
        get() = expiresText
        set(value) {
            if (locked) return
            expiresText = value
            write.edited()
            validityError = null
        }

    var authorizationCode: String
        get() = authorizationText
        set(value) = edit(PaymentProblem.AUTHORIZATION_CODE) { authorizationText = value }

    var nsu: String
        get() = nsuText
        set(value) = edit(PaymentProblem.NSU) { nsuText = value }

    var cardBrand: String
        get() = brandText
        set(value) = edit(PaymentProblem.CARD_BRAND) { brandText = value }

    var cardLast4: String
        get() = last4Text
        set(value) = edit(PaymentProblem.CARD_LAST4) { last4Text = value }

    /**
     * Com o resultado desconhecido, a repetição reenvia a entrada da intenção em curso sem conferi-la de novo: a
     * validade não é recusada porque o dia do hotel virou no meio, e o texto "confira a conta" fica.
     */
    override fun confirm() {
        val hotelZone = zone
        if (!canConfirm || hotelZone == null) return
        val input = sent.takeIf { write.unconfirmed } ?: checkedInput(hotelZone) ?: return
        sent = input
        write.send({
            source.registerPreAuthorization(folioId, input, it)
            WriteOutcome.Done(Unit)
        }) { onRegistered() }
    }

    fun close() = write.close()

    /** A entrada digitada, sem problema de formato nem de validade; senão `null`, com os textos nos campos. */
    private fun checkedInput(zone: ZoneId): PreAuthorizationInput? {
        val input = typedInput(expiresAt(zone))
        errors = input?.problems().orEmpty().associateWith(FolioText::problem)
        return input?.takeIf { errors.isEmpty() && validityError == null }
    }

    private fun edit(
        problem: PaymentProblem,
        change: () -> Unit,
    ) {
        if (locked) return
        change()
        write.edited()
        errors = errors - problem
    }

    /** O fim do dia digitado no fuso do hotel; fora de hoje até 89 dias (ou sem data legível), nulo com o texto. */
    private fun expiresAt(zone: ZoneId): Instant? {
        val parsed = DateInput.parse(expiresText)
        val today = LocalDate.now(clock.withZone(zone))
        val date = (parsed as? InputResult.Valid)?.value?.value
        validityError =
            when {
                parsed is InputResult.Invalid -> parsed.message
                parsed is InputResult.Empty -> FolioText.VALIDITY_REQUIRED
                date == null || date < today || date > today.plusDays(MAX_VALIDITY_DAYS) -> FolioText.VALIDITY_RANGE
                else -> null
            }
        if (date == null || validityError != null) return null
        return date.atTime(END_OF_DAY).atZone(zone).toInstant()
    }

    /** A entrada digitada; `null` sem um valor legível ou sem a validade, com o texto do valor em [message]. */
    private fun typedInput(expiresAt: Instant?): PreAuthorizationInput? {
        val amount = MoneyInput.parse(amountText)
        write.message =
            when (amount) {
                is InputResult.Invalid -> amount.message
                is InputResult.Empty -> FolioText.AMOUNT_REQUIRED
                else -> null
            }
        if (amount !is InputResult.Valid || expiresAt == null) return null
        return PreAuthorizationInput(
            amount = amount.value,
            expiresAt = expiresAt,
            authorizationCode = authorizationText.trim(),
            nsu = nsuText.present(),
            cardBrand = brandText.present(),
            cardLast4 = last4Text.present(),
        )
    }

    override fun toString(): String =
        "PreAuthorizationFormState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

private fun String.present(): String? = trim().takeIf { it.isNotEmpty() }
