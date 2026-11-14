package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.NewGuest
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.form.FormField
import com.zeny.desktop.design.form.FormState
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.Document
import com.zeny.desktop.design.input.DocumentInput
import com.zeny.desktop.design.input.DocumentKind
import com.zeny.desktop.design.input.InputResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Period

private const val MIN_NAME_LETTERS = 2
private const val ADULT_AGE = 18
private const val COUNTRY_LENGTH = 2
private const val CPF = "CPF"
private const val BRAZIL = "BR"
private val COUNTRY_REQUIRED = setOf("PASSPORT", "FOREIGN_ID")
private val COUNTRY_SHAPE = Regex("^[A-Za-z]{2}$")
private val NATIONALITY_SHAPE = Regex("^[A-Z]{2}$")

private const val SHORT_NAME = "Digite ao menos 2 letras do nome."
private const val NEED_COUNTRY = "Informe o país emissor."
private const val NEED_NUMBER = "Informe o número do documento."
private const val NEED_NAME = "Informe o nome completo."
private const val NEED_BIRTH = "Informe a data de nascimento."
private const val FUTURE_BIRTH = "A data de nascimento não pode ser futura."
private const val BAD_COUNTRY = "Use 2 letras, como AR."
private const val BAD_NATIONALITY = "Use 2 letras maiúsculas, como BR."
private const val BAD_TYPE = "Escolha o tipo do documento."
private const val NEED_CPF_KIND = "Informe um CPF, não um CNPJ."
private const val ADULT_BRAZILIAN_CPF = "Para brasileiro adulto, use o CPF."
private const val UNCONFIRMED_CREATE =
    "Não foi possível confirmar o cadastro. Busque pelo documento antes de cadastrar de novo."

/** Os tipos de documento oferecidos, na ordem da tela; o rótulo vem de [ReceptionText.document]. */
val GUEST_DOCUMENT_TYPES: List<String> = listOf("CPF", "RG", "CNH", "PASSPORT", "RNM", "FOREIGN_ID")

/**
 * Estado da busca de hóspede (por nome e por documento) e do cadastro mínimo, com validação no cliente.
 * Dado pessoal de risco alto: o número digitado só vai no corpo da requisição; na busca ele some do estado depois da
 * chamada e, no cadastro, só depois do sucesso (um erro de campo precisa dele para a correção). O `toString` não
 * mostra nada.
 *
 * Como o `RoomAllocationState`: as mutações correm no escopo da UI (uma thread só); [close] cancela só o que é daqui.
 */
@Suppress("TooManyFunctions") // busca por nome, por documento, cadastro e as validações do formulário
class GuestPickerState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    val canSearch: Boolean,
    val canCreate: Boolean,
    private val onPicked: (GuestPick) -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var query by mutableStateOf("")
    var documentType by mutableStateOf(CPF)
    var documentNumber by mutableStateOf("")
    var issuingCountry by mutableStateOf("")
    var results by mutableStateOf<List<GuestPick>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** A data operacional lida no `create()`; `null` se a leitura falhou (o servidor decide a idade e a data). */
    private var businessDate: BusinessDate? = null

    /** O formulário de cadastro; depois de um sucesso é trocado por um novo (sem campo tocado nem erro velho). */
    var form by mutableStateOf(newForm())
        private set

    var creating by mutableStateOf(false)
        private set

    /** O erro do cadastro que não é de campo (rede, resposta incompatível, recusa sem campo). */
    var createError by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var closed = false

    fun searchByName() {
        if (!canSearch || closed) return
        val name = query.trim()
        if (name.length < MIN_NAME_LETTERS) {
            fail(SHORT_NAME)
            return
        }
        search(clearNumber = false) { source.searchGuests(name) }
    }

    fun searchByDocument() {
        if (!canSearch || closed) return
        val number = documentNumber.trim()
        val country = issuingCountry.trim().uppercase().takeIf { it.isNotEmpty() }
        val type = documentType
        when {
            number.isEmpty() -> fail(NEED_NUMBER)
            type in COUNTRY_REQUIRED && country == null -> fail(NEED_COUNTRY)
            else -> search(clearNumber = true) { source.searchGuestsByDocument(type, number, country) }
        }
    }

    fun pick(pick: GuestPick) = onPicked(pick)

    fun create() {
        if (!canCreate || creating || closed) return
        creating = true
        createError = null
        form.clearServerErrors()
        ownScope.launch {
            try {
                businessDate = readBusinessDate()
                if (form.validate()) submit()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                showCreateFailure(apiErrorOf(e))
            } finally {
                creating = false
            }
        }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    override fun toString(): String = "GuestPickerState(***)"

    private fun fail(message: String) {
        searchJob?.cancel()
        searchGeneration++
        searching = false
        results = emptyList()
        error = message
    }

    /** A última busca vale: uma resposta de busca antiga nunca sobrescreve a atual. O número sai do estado ao fim. */
    private fun search(
        clearNumber: Boolean,
        call: suspend () -> List<GuestPick>,
    ) {
        searchJob?.cancel()
        val mine = ++searchGeneration
        searching = true
        error = null
        searchJob =
            ownScope.launch {
                try {
                    val found = call()
                    if (mine == searchGeneration) results = found
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine == searchGeneration) {
                        results = emptyList()
                        error = ActionText.error(apiErrorOf(e))
                    }
                } finally {
                    if (clearNumber) documentNumber = ""
                    if (mine == searchGeneration) searching = false
                }
            }
    }

    private suspend fun submit() {
        val document = (DocumentInput.parse(text("documentNumber")) as? InputResult.Valid)?.value
        val number =
            if (text("documentType") == CPF &&
                document != null
            ) {
                document.value
            } else {
                text("documentNumber").trim()
            }
        val birth = (DateInput.parse(text("birthDate")) as InputResult.Valid).value
        val input =
            NewGuest(
                fullName = text("fullName").trim(),
                documentType = text("documentType"),
                documentNumber = number,
                issuingCountry =
                    text("issuingCountry")
                        .trim()
                        .uppercase()
                        .takeIf { it.isNotEmpty() },
                nationality = text("nationality").trim(),
                birthDate = birth,
            )
        val pick = source.createGuest(input)
        resetForm()
        onPicked(pick)
    }

    private fun showCreateFailure(failure: ApiError) {
        when {
            failure is ApiError.Problem && failure.fieldErrors.isNotEmpty() -> {
                form.applyServerErrors(failure)
            }

            // O cadastro não tem chave de idempotência: pode ter sido gravado.
            failure is ApiError.Network || failure is ApiError.IncompatibleResponse -> {
                createError = UNCONFIRMED_CREATE
            }

            else -> {
                createError = ActionText.error(failure)
            }
        }
    }

    private suspend fun readBusinessDate(): BusinessDate? =
        try {
            source.businessDay().date
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            null
        }

    private fun text(name: String): String = form.fields.first { it.name == name }.text

    @Suppress("LongParameterList") // um parâmetro por campo do formulário
    private fun newForm(
        name: String = "",
        type: String = CPF,
        number: String = "",
        country: String = "",
        nationality: String = BRAZIL,
        birth: String = "",
    ) = FormState(
        listOf(
            FormField("fullName", "Nome completo", name, setOf("profile.fullName")) { requiredName(it) },
            FormField("documentType", "Tipo de documento", type, setOf("documents[0].type")) { documentTypeError(it) },
            FormField(
                "documentNumber",
                "Número do documento",
                number,
                setOf("documents[0].number"),
            ) { numberError(it) },
            FormField(
                "issuingCountry",
                "País emissor",
                country,
                setOf("documents[0].issuingCountry"),
            ) { countryError(it) },
            FormField(
                "nationality",
                "Nacionalidade",
                nationality,
                setOf("profile.nationality"),
            ) { nationalityError(it) },
            FormField("birthDate", "Data de nascimento", birth, setOf("profile.birthDate")) { birthError(it) },
        ),
    )

    /** Um formulário limpo: só o nome e o número saem; tipo, país, nacionalidade e nascimento ficam. */
    private fun resetForm() {
        form =
            newForm(
                type = text("documentType"),
                country = text("issuingCountry"),
                nationality = text("nationality"),
                birth = text("birthDate"),
            )
    }

    private fun requiredName(text: String) = if (text.isBlank()) NEED_NAME else null

    private fun documentTypeError(type: String): String? {
        val today = businessDate
        val birth = (DateInput.parse(text("birthDate")) as? InputResult.Valid)?.value
        val adultBrazilian =
            today != null &&
                birth != null &&
                text("nationality").trim() == BRAZIL &&
                Period.between(birth.value, today.value).years >= ADULT_AGE
        return when {
            type !in GUEST_DOCUMENT_TYPES -> BAD_TYPE
            adultBrazilian && type != CPF -> ADULT_BRAZILIAN_CPF
            else -> null
        }
    }

    private fun numberError(text: String): String? =
        when {
            text.isBlank() -> NEED_NUMBER
            text("documentType") != CPF -> null
            else -> cpfError(DocumentInput.parse(text))
        }

    private fun cpfError(parsed: InputResult<Document>): String? =
        when (parsed) {
            is InputResult.Invalid -> parsed.message
            InputResult.Empty -> NEED_NUMBER
            is InputResult.Valid -> if (parsed.value.kind == DocumentKind.CPF) null else NEED_CPF_KIND
        }

    private fun countryError(text: String): String? {
        val country = text.trim()
        return when {
            country.isEmpty() -> if (text("documentType") in COUNTRY_REQUIRED) NEED_COUNTRY else null
            country.length != COUNTRY_LENGTH || !COUNTRY_SHAPE.matches(country) -> BAD_COUNTRY
            else -> null
        }
    }

    private fun nationalityError(text: String) = if (NATIONALITY_SHAPE.matches(text.trim())) null else BAD_NATIONALITY

    private fun birthError(text: String): String? =
        when (val parsed = DateInput.parse(text)) {
            InputResult.Empty -> {
                NEED_BIRTH
            }

            is InputResult.Invalid -> {
                parsed.message
            }

            is InputResult.Valid -> {
                if (businessDate?.let { parsed.value.value > it.value } ==
                    true
                ) {
                    FUTURE_BIRTH
                } else {
                    null
                }
            }
        }
}

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
