package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

private const val RECEIVE = "billing.payments.receive"
private const val FOLIO_CLOSED = "FOLIO_CLOSED"
private const val SETTLED = "PRE_AUTHORIZATION_SETTLED"

/** As situações em que a caução ainda tem o que liberar. */
private val RELEASABLE = setOf("ACTIVE", "PARTIALLY_CAPTURED", "EXPIRED")

/**
 * Uma caução na tabela, na ordem do servidor; o [id] serve às ações e nunca aparece. [status] é o rótulo da situação e
 * [statusCode] o valor do servidor; [card] é "<bandeira> final <últimos>" quando vem; [capturable], o capturável do
 * servidor só quando maior que zero; [released], o liberado formatado quando vem; [expiresAt], a validade em
 * `dd/MM/aaaa HH:mm` no fuso do hotel (sem o fuso, a data UTC com "(UTC)"). O `toString()` não mostra [card].
 */
data class PreAuthorizationRow(
    val id: String,
    val status: String,
    val statusCode: String,
    val card: String?,
    val amount: String,
    val captured: String,
    val capturable: Money?,
    val released: String?,
    val expiresAt: String,
) {
    override fun toString(): String =
        "PreAuthorizationRow(status=$statusCode, amount=$amount, captured=$captured, expiresAt=$expiresAt)"
}

/**
 * A seção "Cauções" da conta [folioId], criada pelo `FolioState` quando há a porta dos pagamentos no [wiring]. O
 * [reload] lê as cauções e a data operacional (o fuso do hotel, para a validade); a falha das cauções só diz isso
 * ([error]) e não esconde a conta, e a falha do fuso é tolerada (a validade cai para a data UTC e o registro diz que
 * não leu o fuso). O front não calcula nada: autorizado, capturado, capturável e liberado são os do servidor.
 *
 * "Registrar caução" ([startRegister], com `billing.payments.receive` e a conta aberta, [folioOpen]) e "Liberar"
 * ([startRelease], com `billing.payments.receive` numa caução ativa, capturada em parte ou vencida, inclusive com a
 * conta encerrada) abrem um diálogo por vez, nunca com outro diálogo do painel aberto ([otherDialogOpen]). O sucesso
 * fecha o diálogo, relê as cauções, chama [onChanged] (a conta e os pagamentos) e avisa o painel por [notify]. A recusa
 * `PRE_AUTHORIZATION_SETTLED` relê as cauções; a `FOLIO_CLOSED`, a conta. Fechar um diálogo sem resultado conhecido
 * relê os dois, para conferir. [close] cancela o que é daqui.
 */
@Suppress("LongParameterList", "TooManyFunctions") // o que o painel passa; ler, abrir, fechar e o resultado
class PreAuthorizationsState(
    private val wiring: FolioWiring,
    scope: CoroutineScope,
    private val folioId: String,
    private val folioOpen: () -> Boolean,
    private val otherDialogOpen: () -> Boolean,
    private val notify: (String) -> Unit,
    private val onChanged: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var closed = false
    private var generation = 0
    private var zone: ZoneId? = null

    var rows by mutableStateOf<List<PreAuthorizationRow>>(emptyList())
        private set

    /** A primeira leitura terminou (bem ou mal): antes dela, a tela não afirma que não há nenhuma. */
    var loaded by mutableStateOf(false)
        private set

    /** A falha da leitura das cauções; a conta continua à vista. */
    var error by mutableStateOf<String?>(null)
        private set

    /** O diálogo "Registrar caução" aberto. */
    var register by mutableStateOf<PreAuthorizationFormState?>(null)
        private set

    /** A confirmação "Liberar" aberta. */
    var release by mutableStateOf<ReleaseState?>(null)
        private set

    private val canWrite = RECEIVE in wiring.permissions && wiring.payments != null

    val dialogOpen: Boolean get() = register != null || release != null

    /** "Registrar caução": com `billing.payments.receive` e a conta aberta. */
    val canRegister: Boolean get() = !closed && canWrite && folioOpen()

    /** "Liberar" na linha: com `billing.payments.receive`, numa caução com o que liberar (conta aberta ou não). */
    fun canRelease(row: PreAuthorizationRow): Boolean = !closed && canWrite && row.statusCode in RELEASABLE

    /** Relê as cauções e o fuso do hotel; a leitura anterior em voo é descartada. */
    fun reload() {
        val source = wiring.payments
        if (closed || source == null) return
        val mine = ++generation
        ownScope.launch {
            val hotelZone = readZone(source)
            val read =
                try {
                    source.preAuthorizations(folioId)
                } catch (
                    // A conta já está à vista: a falha das cauções só diz isso.
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            currentCoroutineContext().ensureActive()
            if (mine != generation) return@launch
            zone = hotelZone
            rows = read?.map { rowOf(it, hotelZone) }.orEmpty()
            error = if (read == null) FolioText.PRE_AUTHORIZATIONS_FAILED else null
            loaded = true
        }
    }

    /** Abre "Registrar caução"; sem o fuso, o diálogo diz isso e o fuso é relido para a próxima vez. */
    fun startRegister() {
        val source = wiring.payments
        if (!canRegister || !noDialogOpen() || source == null) return
        val hotelZone = zone
        register = PreAuthorizationFormState(source, ownScope, folioId, hotelZone, clock, ::registered, ::refused)
        if (hotelZone == null) reload()
    }

    /** "Voltar" ou "Fechar" do registro; sem resultado conhecido, as cauções e a conta são relidas. */
    fun finishRegister() {
        val form = register ?: return
        if (form.busy) return
        form.close()
        register = null
        if (form.unconfirmed || form.closeOnly) changed()
    }

    /** Abre "Liberar" para a caução [row]; fora das regras ou com outro diálogo aberto, nada. */
    fun startRelease(row: PreAuthorizationRow) {
        val source = wiring.payments
        if (!canRelease(row) || !noDialogOpen() || source == null) return
        release = ReleaseState(source, ownScope, folioId, row, ::released, ::refused)
    }

    /** "Voltar" ou "Fechar" da liberação; sem resultado conhecido, as cauções e a conta são relidas. */
    fun finishRelease() {
        val form = release ?: return
        if (form.busy) return
        form.close()
        release = null
        if (form.unconfirmed || form.closeOnly) changed()
    }

    fun close() {
        closed = true
        jobs.cancel()
        register?.close()
        release?.close()
    }

    /** Um diálogo por vez no painel: nem o daqui nem outro do `FolioState`. */
    private fun noDialogOpen(): Boolean = !dialogOpen && !otherDialogOpen()

    private suspend fun readZone(source: PaymentSource): ZoneId? =
        try {
            source.businessDay().zone
        } catch (
            // Sem o fuso, a validade cai para a data UTC e o registro diz que não o leu.
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            null
        }

    private fun registered() {
        register?.close()
        register = null
        changed()
        notify(FolioText.PRE_AUTHORIZATION_RECORDED)
    }

    private fun released() {
        release?.close()
        release = null
        changed()
        notify(FolioText.RELEASE_DONE)
    }

    /** A recusa definitiva (o texto já está no diálogo): a caução liquidada relê as cauções; conta fechada, a conta. */
    private fun refused(code: String) {
        when (code) {
            SETTLED -> reload()
            FOLIO_CLOSED -> onChanged()
        }
    }

    private fun changed() {
        if (closed) return
        reload()
        onChanged()
    }

    override fun toString(): String = "PreAuthorizationsState(loaded=$loaded, rows=${rows.size})"
}

private fun rowOf(
    response: CardPreAuthorizationResponse,
    zone: ZoneId?,
): PreAuthorizationRow {
    val brand = (response.cardBrand as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    val last4 = (response.cardLast4 as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    return PreAuthorizationRow(
        id = response.id.toString(),
        status = FolioText.preAuthorizationStatus(response.status),
        statusCode = response.status,
        card = listOfNotNull(brand, last4?.let { "final $it" }).joinToString(" ").takeIf { it.isNotEmpty() },
        amount = FolioText.money(response.amount),
        captured = FolioText.money(response.capturedAmount),
        capturable = moneyOrNull(response.capturableAmount)?.takeIf { it.amount.signum() > 0 },
        released = (response.releasedAmount as? Option.Some)?.value?.let(FolioText::money),
        expiresAt = FolioText.expiresAt(response.expiresAt, zone),
    )
}
