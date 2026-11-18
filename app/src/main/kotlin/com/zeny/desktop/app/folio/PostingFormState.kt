package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.PostingInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val FORBIDDEN = 403
private const val FOLIO_CLOSED = "FOLIO_CLOSED"
private const val MIN_QUANTITY = 1
private const val MAX_QUANTITY = 9999
private const val DESCRIPTION_MAX = 200

/** O que os diálogos de escrita da conta ("Lançar" e "Estornar") mostram e fazem em comum. */
interface FolioWriteForm {
    val busy: Boolean

    /** O resultado ficou desconhecido: repetir é a mesma intenção ("Tentar de novo"). */
    val unconfirmed: Boolean

    /** O servidor guardou um `5xx` sob a chave: confirmar vira no-op, as entradas travam, e só resta fechar. */
    val closeOnly: Boolean

    /** A validação recusada ou o resultado do último envio que não deu certo. */
    val message: String?

    val canConfirm: Boolean

    /** O Esc fecha fora de um envio em curso e sem resultado a repetir (com [closeOnly], fecha). */
    val closesOnEscape: Boolean

    fun confirm()
}

/** Um código de lançamento oferecido: o [id] vai no pedido e nunca aparece; o [label] é "<code> — <name>". */
data class CodeOption(
    val id: String,
    val label: String,
)

/**
 * O diálogo "Lançar" da conta [folioId]: o código (os ativos, sem os de `kind` `SERVICE_CHARGE`, que o servidor lança
 * sozinho), a quantidade inteira (1 a 9999, padrão 1), o valor unitário (`MoneyField`, decimal exato, maior que zero)
 * e a descrição opcional (até 200 caracteres). O `403` na leitura dos códigos diz que o perfil não pode listá-los, e
 * não há o que confirmar.
 *
 * O lançamento leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`), e as entradas ficam travadas até lá; resposta definitiva gera outra. O `5xx` guardado sob
 * a chave deixa o estado [closeOnly]: só resta fechar, e quem fecha relê a conta para conferir. O sucesso vai a
 * [onChanged] com a resposta (ela diz em que conta o servidor lançou); o `FOLIO_CLOSED` mostra a mensagem e chama
 * [onFolioClosed], para a conta ser relida. As mutações correm no escopo da UI; [close] cancela o que é daqui.
 */
class PostingFormState(
    private val source: FolioSource,
    scope: CoroutineScope,
    private val folioId: String,
    private val onChanged: (PostingResponse) -> Unit,
    private val onFolioClosed: () -> Unit = {},
) : FolioWriteForm {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var codes by mutableStateOf<List<CodeOption>>(emptyList())
        private set

    var codesLoading by mutableStateOf(false)
        private set

    /** A falha da leitura dos códigos; o `403` diz que o perfil não pode listá-los. */
    var codesError by mutableStateOf<String?>(null)
        private set

    private var codesForbidden by mutableStateOf(false)

    /** A leitura dos códigos falhou por um motivo que repetir pode resolver (o `403` não). */
    val codesRetryable: Boolean get() = codesError != null && !codesForbidden

    private var codeValue by mutableStateOf<String?>(null)
    private var quantityText by mutableStateOf(MIN_QUANTITY.toString())
    private var priceText by mutableStateOf("")
    private var descriptionText by mutableStateOf("")

    override var busy by mutableStateOf(false)
        private set

    override var unconfirmed by mutableStateOf(false)
        private set

    override var closeOnly by mutableStateOf(false)
        private set

    override var message by mutableStateOf<String?>(null)
        private set

    // `closed` é estado observável: `canConfirm` o lê, e o Compose só recompõe o que leu estado.
    private var closed by mutableStateOf(false)
    private var intent: Intent? = null

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = closed || busy || unconfirmed || closeOnly

    override val canConfirm: Boolean get() = !closed && !busy && !closeOnly && codes.isNotEmpty()

    override val closesOnEscape: Boolean get() = !busy && (!unconfirmed || closeOnly)

    /** O código escolhido; só um da lista. */
    var codeId: String?
        get() = codeValue
        set(value) {
            if (!locked && codes.any { it.id == value }) codeValue = value
        }

    /** A quantidade digitada; a conferência (inteira, de 1 a 9999) é no envio. */
    var quantity: String
        get() = quantityText
        set(value) {
            if (!locked) quantityText = value
        }

    /** O valor unitário digitado (`MoneyField`, `1.234,56`). */
    var unitPrice: String
        get() = priceText
        set(value) {
            if (!locked) priceText = value
        }

    /** A descrição opcional, até 200 caracteres; o excesso é recusado. */
    var description: String
        get() = descriptionText
        set(value) {
            if (!locked && value.length <= DESCRIPTION_MAX) descriptionText = value
        }

    /** O valor bem formado, mas zero ou negativo. */
    val unitPriceError: String?
        get() = typedPrice()?.takeIf { it.amount.signum() <= 0 }?.let { FolioText.PRICE_POSITIVE }

    /** Lê os códigos de lançamento; de novo, tenta outra vez depois de uma falha. */
    fun start() {
        if (closed || codesLoading) return
        codesLoading = true
        codesError = null
        ownScope.launch {
            try {
                val offered = source.transactionCodes().filter(::offered).map(::optionOf)
                currentCoroutineContext().ensureActive()
                codes = offered
                codesForbidden = false
                if (offered.none { it.id == codeValue }) codeValue = null
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                val error = apiErrorOf(e)
                codes = emptyList()
                codeValue = null
                codesForbidden = error is ApiError.Problem && error.status == FORBIDDEN
                codesError = if (codesForbidden) FolioText.CODES_FORBIDDEN else error.userMessage
            } finally {
                codesLoading = false
            }
        }
    }

    override fun confirm() {
        if (!canConfirm) return
        val refused = refusal()
        if (refused != null) {
            message = refused
            return
        }
        val input =
            PostingInput(
                transactionCodeId = checkNotNull(codeValue),
                quantity = checkNotNull(typedQuantity()),
                unitPrice = checkNotNull(typedPrice()),
                description = descriptionText.trim().takeIf { it.isNotEmpty() },
            )
        busy = true
        message = null
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val response = source.post(folioId, input, current)
                currentCoroutineContext().ensureActive()
                intent = null
                unconfirmed = false
                onChanged(response)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(apiErrorOf(e))
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) FolioText.POSTING_SERVER_UNCONFIRMED else FolioText.POSTING_UNCONFIRMED
            return
        }
        unconfirmed = false
        intent = null
        message = failure.userMessage
        if ((failure as? ApiError.Problem)?.code == FOLIO_CLOSED) onFolioClosed()
    }

    /** O texto que impede o envio, ou `null` quando as entradas valem. */
    private fun refusal(): String? {
        val price = MoneyInput.parse(priceText)
        return when {
            codeValue == null -> FolioText.CODE_REQUIRED

            typedQuantity() == null -> FolioText.QUANTITY_RANGE

            // O texto do formato é o mesmo que o MoneyField mostra embaixo do campo.
            price is InputResult.Invalid -> price.message

            price is InputResult.Empty -> FolioText.PRICE_REQUIRED

            typedPrice()?.amount?.signum() != 1 -> FolioText.PRICE_POSITIVE

            else -> null
        }
    }

    private fun typedQuantity(): Int? = quantityText.trim().toIntOrNull()?.takeIf { it in MIN_QUANTITY..MAX_QUANTITY }

    private fun typedPrice(): Money? = (MoneyInput.parse(priceText) as? InputResult.Valid)?.value

    override fun toString(): String = "PostingFormState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

/** Só os ativos que se lançam à mão: a taxa de serviço é do servidor. */
private fun offered(code: TransactionCodeResponse): Boolean =
    code.active && code.kind != TransactionCodeResponse.Kind.SERVICE_CHARGE

private fun optionOf(code: TransactionCodeResponse) =
    CodeOption(code.id.toString(), FolioText.code(code.code, code.name))

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
