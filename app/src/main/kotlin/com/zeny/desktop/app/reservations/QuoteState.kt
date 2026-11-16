package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.QuoteUnavailableResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit

private const val DEFAULT_ADULTS = "2"
private const val MAX_NIGHTS = 365L
private const val MIN_ADULTS = 1
private const val MAX_ADULTS = 40
private const val MAX_CHILDREN = 20

private const val BAD_DATES = "Informe a chegada e a saída no formato dd/mm/aaaa."
private const val DEPARTURE_NOT_AFTER = "A saída precisa ser depois da chegada."
private const val TOO_LONG = "A estadia vai até 365 noites."
private const val BAD_ADULTS = "Informe de 1 a 40 adultos."
private const val BAD_AGES = "Informe as idades das crianças em números, separadas por vírgula."
private const val TOO_MANY_CHILDREN = "Informe até 20 crianças."

/** A estadia digitada, já lida; só existe se passou na validação local. */
private class Stay(
    val arrival: BusinessDate,
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
)

/** A estadia lida dos textos, ou o motivo em pt-BR de ela não ir à rede. */
private sealed interface Parsed {
    class Ok(
        val stay: Stay,
    ) : Parsed

    class Refused(
        val message: String,
    ) : Parsed
}

/**
 * Estado da cotação de estadia: os campos digitados, o resultado e os erros.
 *
 * - [quote] valida no cliente (o motivo vai para [validation], sem ir à rede) e só então pergunta ao servidor.
 * - Cada pedido tem uma geração: a resposta de um pedido velho, ou que chega depois do [close], é descartada.
 * - Um `STAY_INVALID` do servidor põe a mensagem de cada campo em [fieldErrors], pelo nome do contrato
 *   (`departure`, `adults`, `childAges[i]`).
 *
 * Como o `AvailabilityState`: as mutações correm no escopo da UI; [close] cancela só o que é daqui.
 */
class QuoteState(
    private val source: AvailabilitySource,
    scope: CoroutineScope,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    private var closed = false
    private var generation = 0
    private var request: Job? = null

    var arrivalText by mutableStateOf("")
    var departureText by mutableStateOf("")
    var adultsText by mutableStateOf(DEFAULT_ADULTS)

    /** As idades das crianças, separadas por vírgula; vazio quando não há crianças. */
    var childAgesText by mutableStateOf("")

    var offers by mutableStateOf<List<QuoteOfferResponse>>(emptyList())
        private set

    var unavailable by mutableStateOf<List<QuoteUnavailableResponse>>(emptyList())
        private set

    /** Verdadeiro depois de uma resposta, mesmo sem ofertas. */
    var quoted by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    /** O texto de pessoa da última falha do servidor. */
    var error by mutableStateOf<String?>(null)
        private set

    /** O motivo, em pt-BR, de o último pedido não ter ido à rede. */
    var validation by mutableStateOf<String?>(null)
        private set

    /** A mensagem do servidor por campo do contrato. */
    var fieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Chegada em [arrival], saída no dia seguinte; o resultado e os erros anteriores saem. */
    fun prefill(arrival: BusinessDate) {
        if (closed) return
        invalidateRequest()
        arrivalText = DateInput.format(arrival)
        departureText = DateInput.format(BusinessDate(arrival.value.plusDays(1)))
        clearOutcome()
    }

    fun quote() {
        if (closed) return
        val parsed = parse()
        if (parsed is Parsed.Refused) {
            invalidateRequest()
            clearOutcome()
            validation = parsed.message
            return
        }
        val stay = (parsed as Parsed.Ok).stay
        val mine = invalidateRequest()
        clearOutcome()
        loading = true
        request =
            ownScope.launch {
                try {
                    val response = source.quote(stay.arrival, stay.departure, stay.adults, stay.childAges)
                    if (mine != generation) return@launch
                    offers = response.offers
                    unavailable = response.unavailableList()
                    quoted = true
                    loading = false
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine != generation) return@launch
                    val failure = e as? ApiError ?: ApiError.Network(e)
                    error = failure.userMessage
                    fieldErrors =
                        (failure as? ApiError.Problem)?.fieldErrors.orEmpty().associate { it.field to it.message }
                    loading = false
                }
            }
    }

    /** A área saiu de cena: cancela o pedido e não cota mais. */
    fun close() {
        closed = true
        generation++
        jobs.cancel()
        loading = false
    }

    /** Descarta o pedido em andamento e devolve a geração do próximo. */
    private fun invalidateRequest(): Int {
        val next = ++generation
        request?.cancel()
        loading = false
        return next
    }

    private fun clearOutcome() {
        offers = emptyList()
        unavailable = emptyList()
        quoted = false
        error = null
        validation = null
        fieldErrors = emptyMap()
    }

    private fun parse(): Parsed {
        val arrival = (DateInput.parse(arrivalText) as? InputResult.Valid)?.value
        val departure = (DateInput.parse(departureText) as? InputResult.Valid)?.value
        val nights =
            if (arrival != null && departure != null) ChronoUnit.DAYS.between(arrival.value, departure.value) else 0L
        val adults = adultsText.trim().toIntOrNull()
        val ages = parseAges()
        val problem =
            when {
                arrival == null || departure == null -> BAD_DATES
                nights <= 0 -> DEPARTURE_NOT_AFTER
                nights > MAX_NIGHTS -> TOO_LONG
                adults == null || adults !in MIN_ADULTS..MAX_ADULTS -> BAD_ADULTS
                ages == null -> BAD_AGES
                ages.size > MAX_CHILDREN -> TOO_MANY_CHILDREN
                else -> null
            }
        return if (problem == null) {
            Parsed.Ok(Stay(checkNotNull(arrival), checkNotNull(departure), checkNotNull(adults), checkNotNull(ages)))
        } else {
            Parsed.Refused(problem)
        }
    }

    /** As idades separadas por vírgula; `null` se alguma não é um inteiro não negativo. */
    private fun parseAges(): List<Int>? {
        if (childAgesText.isBlank()) return emptyList()
        val ages = childAgesText.split(',').map { it.trim().toIntOrNull() }
        return if (ages.any { it == null || it < 0 }) null else ages.filterNotNull()
    }
}

private fun QuoteResponse.unavailableList(): List<QuoteUnavailableResponse> =
    ((unavailable as? Option.Some)?.value).orEmpty().filterNotNull()
