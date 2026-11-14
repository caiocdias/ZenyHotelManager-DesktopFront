package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun cpfFrom(base: String): String {
    fun digit(
        digits: String,
        weight: Int,
    ): Char {
        val rest = digits.mapIndexed { i, c -> (c - '0') * (weight - i) }.sum() % 11
        return if (rest < 2) '0' else ('0' + (11 - rest))
    }
    val first = digit(base, 10)
    return base + first + digit(base + first, 11)
}

private val cpf = cpfFrom("987654321")
private val pick = GuestPick("g-9", HolderSummary("Joana Ficticia", "CPF ***.654.321-**"))

/** Ataques à task 7: dado pessoal, validação, erros do servidor, concorrência e cancelamento. */
@Suppress("LargeClass") // um teste por comportamento atacado: busca, cadastro, validação, erros e cancelamento
@OptIn(ExperimentalCoroutinesApi::class)
class GuestPickerStateAttackTest {
    private fun TestScope.state(
        source: FakeReceptionSource = FakeReceptionSource().apply { createdGuest = pick },
        canSearch: Boolean = true,
        canCreate: Boolean = true,
        picked: MutableList<GuestPick> = mutableListOf(),
    ) = GuestPickerState(source, backgroundScope, canSearch, canCreate) { picked += it }

    private fun GuestPickerState.set(
        name: String,
        value: String,
    ) = form.fields.first { it.name == name }.onChange(value)

    private fun GuestPickerState.field(name: String) = form.fields.first { it.name == name }

    @Suppress("LongParameterList")
    private fun GuestPickerState.fill(
        name: String = "Joana Ficticia",
        type: String = "CPF",
        number: String = cpf,
        country: String = "",
        nationality: String = "BR",
        birth: String = "10/05/1990",
    ) {
        set("fullName", name)
        set("documentType", type)
        set("documentNumber", number)
        set("issuingCountry", country)
        set("nationality", nationality)
        set("birthDate", birth)
    }

    @Test
    fun searchSendsTrimmedNumberAndUppercaseCountryAndClearsNumberOnSuccess() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.documentType = "PASSPORT"
            state.documentNumber = "  ab123456  "
            state.issuingCountry = " ar "
            state.searchByDocument()
            runCurrent()

            assertEquals(listOf("ab123456"), source.documentSearchNumbers)
            assertEquals(listOf<Pair<String, String?>>("PASSPORT" to "AR"), source.documentSearchCalls)
            assertEquals("", state.documentNumber)
        }

    @Test
    fun blankCountryOnCpfSearchSendsNull() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.documentNumber = cpf
            state.issuingCountry = "   "
            state.searchByDocument()
            runCurrent()

            assertEquals(listOf<Pair<String, String?>>("CPF" to null), source.documentSearchCalls)
        }

    @Test
    fun failedValidationOfSearchKeepsNumberAndMakesNoCall() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.documentType = "PASSPORT"
            state.documentNumber = "AB123456"
            state.searchByDocument()
            runCurrent()

            assertEquals("Informe o país emissor.", state.error)
            assertEquals("AB123456", state.documentNumber)
            assertTrue(source.documentSearchCalls.isEmpty())
            assertFalse(state.searching)
        }

    @Test
    fun searchErrorNeverEchoesTheTypedNumberOrExceptionMessage() =
        runTest {
            val source = FakeReceptionSource().apply { searchError = IOException("falha com o documento $cpf") }
            val state = state(source)
            state.documentNumber = cpf
            state.searchByDocument()
            runCurrent()

            val shown = state.error
            assertNotNull(shown)
            assertFalse(shown.contains(cpf))
            assertFalse(state.toString().contains(cpf))
            assertTrue(state.results.isEmpty())
        }

    @Test
    fun createErrorNeverEchoesTheTypedNumberOrExceptionMessage() =
        runTest {
            for (failure in listOf<Exception>(
                IOException("falha $cpf"),
                IllegalStateException("estado $cpf"),
                ApiError.Network(IOException("cause $cpf")),
                ApiError.IncompatibleResponse(IOException("cause $cpf")),
            )) {
                val source = FakeReceptionSource().apply { createGuestError = failure }
                val state = state(source)
                state.fill()
                state.create()
                runCurrent()

                val shown = state.createError
                assertNotNull(shown)
                assertFalse(shown.contains(cpf), failure.toString())
                assertNull(state.form.formError)
                assertFalse(state.toString().contains(cpf))
            }
        }

    @Test
    fun latestSearchWinsOverSlowerOlderOne() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    holdSearch = true
                    guestsByName["Ana"] = listOf(GuestPick("old", HolderSummary("Ana Antiga", null)))
                    guestsByName["Maria"] = listOf(pick)
                }
            val state = state(source)
            state.query = "Ana"
            state.searchByName()
            runCurrent()
            state.query = "Maria"
            state.searchByName()
            runCurrent()
            source.heldSearches.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(listOf(pick), state.results)
            assertNull(state.error)
            assertFalse(state.searching)
        }

    @Test
    fun invalidSearchCancelsInFlightSearchAndClearsResults() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    holdSearch = true
                    guestsByName["Maria"] = listOf(pick)
                }
            val state = state(source)
            state.query = "Maria"
            state.searchByName()
            runCurrent()
            assertTrue(state.searching)
            state.query = "M"
            state.searchByName()
            runCurrent()
            source.heldSearches.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals("Digite ao menos 2 letras do nome.", state.error)
            assertTrue(state.results.isEmpty())
            assertFalse(state.searching)
        }

    @Test
    fun failedSearchClearsOldResults() =
        runTest {
            val source = FakeReceptionSource().apply { guestsByName["Maria"] = listOf(pick) }
            val state = state(source)
            state.query = "Maria"
            state.searchByName()
            runCurrent()
            assertEquals(1, state.results.size)

            source.searchError = ApiError.Network(IOException("x"))
            state.searchByName()
            runCurrent()

            assertTrue(state.results.isEmpty())
            assertNotNull(state.error)
        }

    @Test
    fun closeDuringSearchIsNotAnErrorAndClearsTheNumber() =
        runTest {
            val source = FakeReceptionSource().apply { holdSearch = true }
            val state = state(source)
            state.documentNumber = cpf
            state.searchByDocument()
            runCurrent()
            state.close()
            runCurrent()

            assertNull(state.error)
            assertEquals("", state.documentNumber)
            assertFalse(state.searching)
        }

    @Test
    fun closeDuringCreateIsNotAnErrorAndMakesNoFurtherCall() =
        runTest {
            val source = FakeReceptionSource().apply { holdCreateGuest = true }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()
            state.close()
            runCurrent()

            assertNull(state.createError)
            assertNull(state.form.formError)
            assertFalse(state.creating)
        }

    @Test
    fun closeWhileReadingBusinessDayNeverCreates() =
        runTest {
            val source = FakeReceptionSource().apply { holdBusinessDay = true }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()
            state.close()
            runCurrent()

            assertEquals(0, source.createGuestCalls)
            assertNull(state.createError)
            assertFalse(state.creating)
        }

    @Test
    fun afterCloseNothingCallsTheSource() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.close()
            state.query = "Maria"
            state.searchByName()
            state.documentNumber = cpf
            state.searchByDocument()
            state.fill()
            state.create()
            runCurrent()

            assertTrue(source.searchCalls.isEmpty())
            assertTrue(source.documentSearchCalls.isEmpty())
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun parentScopeCancellationIsNotAnError() =
        runTest {
            val source = FakeReceptionSource().apply { holdCreateGuest = true }
            val picked = mutableListOf<GuestPick>()
            val job = kotlinx.coroutines.Job()
            val state =
                GuestPickerState(
                    source,
                    kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + job),
                    true,
                    true,
                ) { picked += it }
            state.fill()
            state.create()
            runCurrent()
            job.cancel()
            runCurrent()

            assertNull(state.createError)
            assertTrue(picked.isEmpty())
        }

    @Test
    fun failedCreateNeverPicks() =
        runTest {
            val picked = mutableListOf<GuestPick>()
            val source =
                FakeReceptionSource().apply {
                    createGuestError = ApiError.Problem(409, "GUEST_DOCUMENT_ALREADY_EXISTS", null, emptyList())
                }
            val state = state(source, picked = picked)
            state.fill()
            state.create()
            runCurrent()

            assertTrue(picked.isEmpty())
            assertNotNull(state.createError)
            assertEquals(cpf, state.field("documentNumber").text)
            assertEquals("Joana Ficticia", state.field("fullName").text)
        }

    @Test
    fun invalidCreatePicksNothing() =
        runTest {
            val picked = mutableListOf<GuestPick>()
            val state = state(picked = picked)
            state.fill(number = "123")
            state.create()
            runCurrent()

            assertTrue(picked.isEmpty())
            assertNotNull(state.field("documentNumber").error)
        }

    @Test
    fun serverFieldErrorsGoToEachMappedField() =
        runTest {
            val paths =
                mapOf(
                    "profile.fullName" to "fullName",
                    "profile.nationality" to "nationality",
                    "profile.birthDate" to "birthDate",
                    "documents[0].type" to "documentType",
                    "documents[0].issuingCountry" to "issuingCountry",
                    "documents[0].number" to "documentNumber",
                )
            for ((path, name) in paths) {
                val source =
                    FakeReceptionSource().apply {
                        createGuestError =
                            ApiError.Problem(
                                422,
                                "VALIDATION_ERROR",
                                null,
                                listOf(FieldError(path, "X", "Erro em $name.")),
                            )
                    }
                val state = state(source)
                state.fill()
                state.create()
                runCurrent()

                assertEquals("Erro em $name.", state.field(name).error, path)
                assertNull(state.form.formError, path)
                assertNull(state.createError, path)
            }
        }

    @Test
    fun serverErrorWithUnknownFieldGoesToFormErrorNotToCreateError() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError =
                        ApiError.Problem(
                            422,
                            "VALIDATION_ERROR",
                            null,
                            listOf(FieldError("outro.campo", "X", "Algo geral.")),
                        )
                }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()

            assertEquals("Algo geral.", state.form.formError)
            assertFalse(state.creating)
        }

    @Test
    fun problemWithoutFieldErrorsShowsFriendlyCreateError() =
        runTest {
            val source =
                FakeReceptionSource().apply { createGuestError = ApiError.Problem(500, "BOOM", null, emptyList()) }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()

            val shown = assertNotNull(state.createError)
            assertFalse(shown.contains("BOOM"))
            assertFalse(shown.contains("Busque pelo documento"))
        }

    @Test
    fun serverErrorIsClearedWhenUserEditsTheField() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError =
                        ApiError.Problem(
                            422,
                            "V",
                            null,
                            listOf(FieldError("documents[0].number", "X", "Número recusado.")),
                        )
                }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()
            assertEquals("Número recusado.", state.field("documentNumber").error)

            state.set("documentNumber", cpfFrom("111444777"))
            assertNull(state.field("documentNumber").error)
        }

    @Test
    fun retryAfterFieldErrorWithSameDataCallsAgain() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pick
                    createGuestError =
                        ApiError.Problem(422, "V", null, listOf(FieldError("profile.nationality", "X", "Recusada.")))
                }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()
            source.createGuestError = null
            state.create()
            runCurrent()

            assertEquals(2, source.createGuestCalls)
        }

    @Test
    fun staleServerErrorsDoNotLingerAfterSuccessfulCreate() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pick
                    createGuestError =
                        ApiError.Problem(
                            422,
                            "V",
                            null,
                            listOf(
                                FieldError("profile.birthDate", "X", "Data recusada."),
                                FieldError("outro.campo", "X", "Mensagem geral."),
                            ),
                        )
                }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()
            source.createGuestError = null
            state.create()
            runCurrent()

            assertNull(state.field("birthDate").error, "erro do servidor velho continua no campo após o sucesso")
            assertNull(state.form.formError, "erro geral velho continua após o sucesso")
        }

    @Test
    fun networkErrorAfterFieldErrorKeepsCreateErrorAndNoStaleBanner() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError = ApiError.Network(IOException("x"))
                }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()

            assertNull(state.form.formError)
            assertEquals(cpf, state.field("documentNumber").text)
        }

    @Test
    fun createErrorClearsOnNextAttempt() =
        runTest {
            val source = FakeReceptionSource().apply { createGuestError = ApiError.Network(IOException("x")) }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()
            assertNotNull(state.createError)

            source.createGuestError = null
            source.createdGuest = pick
            state.create()
            runCurrent()
            assertNull(state.createError)
        }

    @Test
    fun formAfterSuccessfulCreateDoesNotShowRequiredOnFreshFields() =
        runTest {
            val state = state()
            state.fill()
            state.create()
            runCurrent()

            assertEquals("", state.field("fullName").text)
            assertEquals("", state.field("documentNumber").text)
            // Formulário recém-limpo que o usuário reabre não pode já mostrar "obrigatório".
            assertNull(state.field("fullName").error, "nome")
            assertNull(state.field("documentNumber").error, "número")
        }

    @Test
    fun otherFieldsKeepValuesAfterSuccessfulCreate() =
        runTest {
            val state = state()
            state.fill(birth = "10/05/1990")
            state.create()
            runCurrent()

            assertEquals("10/05/1990", state.field("birthDate").text)
            assertEquals("BR", state.field("nationality").text)
            assertEquals("CPF", state.field("documentType").text)
        }

    @Test
    fun cnpjIsRejectedWithKindMessage() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val state = state(source)
            state.fill(number = "11.222.333/0001-81")
            state.create()
            runCurrent()

            assertEquals("Informe um CPF, não um CNPJ.", state.field("documentNumber").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun cnpjNumberIsAcceptedAsRgDigitsForMinor() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val state = state(source)
            state.fill(type = "RG", number = "11222333000181", birth = "14/11/2012")
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
            assertEquals("11222333000181", source.createdInputs.single().documentNumber)
        }

    @Test
    fun invalidCpfShowsParserMessageAndNoCall() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.fill(number = "111.111.111-11")
            state.create()
            runCurrent()

            assertNotNull(state.field("documentNumber").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun blankNumberIsRequired() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.fill(number = "  ")
            state.create()
            runCurrent()

            assertEquals("Informe o número do documento.", state.field("documentNumber").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun birthdayExactlyTodayIsNotFutureAndNewbornMayUseRg() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val state = state(source)
            state.fill(type = "RG", number = "123456789", birth = "13/11/2026")
            state.create()
            runCurrent()

            assertNull(state.field("birthDate").error)
            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun birthdayTomorrowIsFuture() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.fill(birth = "14/11/2026")
            state.create()
            runCurrent()

            assertEquals("A data de nascimento não pode ser futura.", state.field("birthDate").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun unreadableBirthDateIsRejected() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            for (text in listOf("31/02/2000", "abc", "1990-05-10x", "00/00/0000")) {
                state.fill(birth = text)
                state.create()
                runCurrent()
                assertNotNull(state.field("birthDate").error, text)
            }
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun futureDateIsNotCheckedWhenBusinessDayFailsAndServerDecides() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pick
                    businessDayError = IOException("fora")
                }
            val state = state(source)
            state.fill(birth = "01/01/2099")
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun businessDayFailureWithForeignCancellationStillCreatesAndSkipsRule() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pick
                    businessDayError = CancellationException("estranha")
                }
            val state = state(source)
            state.fill(type = "RG", number = "123456789")
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
            assertNull(state.createError)
        }

    @Test
    fun ageRuleUsesOperationalDateNotJvmClock() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pick
                    businessDay =
                        com.zeny.desktop.core.facade
                            .BusinessDay(BusinessDate.parse("2030-01-01"), null)
                }
            val state = state(source)
            // 2012: menor em 2026, adulto (17+) em 2030-01-01 só se nascido até 2012-01-01.
            state.fill(type = "RG", number = "123456789", birth = "01/01/2012")
            state.create()
            runCurrent()

            assertEquals(0, source.createGuestCalls)
            assertEquals("Para brasileiro adulto, use o CPF.", state.field("documentType").error)
        }

    @Test
    fun foreignNationalAdultWithRgIsNotBlockedByAgeRule() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val state = state(source)
            state.fill(type = "RG", number = "123456789", nationality = "AR")
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun adultBrazilianWithCnhPassportOrRnmIsBlocked() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            for (type in listOf("CNH", "RNM")) {
                state.fill(type = type, number = "123456789")
                state.create()
                runCurrent()
                assertEquals("Para brasileiro adulto, use o CPF.", state.field("documentType").error, type)
            }
            state.fill(type = "PASSPORT", number = "AB123456", country = "BR")
            state.create()
            runCurrent()
            assertEquals("Para brasileiro adulto, use o CPF.", state.field("documentType").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun countryShapeIsValidated() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            for (bad in listOf("A", "ARG", "A1", "1A")) {
                state.fill(type = "PASSPORT", number = "AB123456", country = bad, nationality = "AR")
                state.create()
                runCurrent()
                assertNotNull(state.field("issuingCountry").error, bad)
            }
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun foreignIdNeedsCountryOnCreate() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.fill(type = "FOREIGN_ID", number = "X1234567", nationality = "AR")
            state.create()
            runCurrent()

            assertEquals("Informe o país emissor.", state.field("issuingCountry").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun unknownDocumentTypeIsRejected() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.fill(type = "XYZ", number = "123456789", nationality = "AR")
            state.create()
            runCurrent()

            assertNotNull(state.field("documentType").error)
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun nationalityDefaultsToBrazilAndRejectsBlankAndDigits() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            assertEquals("BR", state.field("nationality").text)
            for (bad in listOf("", " ", "B1", "Br", "B", "BRA")) {
                state.fill(nationality = bad)
                state.create()
                runCurrent()
                assertNotNull(state.field("nationality").error, "'$bad'")
            }
            assertEquals(0, source.createGuestCalls)
        }

    @Test
    fun createWithoutPermissionDoesNotTouchFormOrReadBusinessDay() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source, canCreate = false)
            state.fill(number = "x")
            state.create()
            runCurrent()

            assertEquals(0, source.businessDayCalls)
            assertFalse(state.creating)
            assertNull(state.field("documentNumber").error)
        }

    @Test
    fun searchOnlyPermissionStillBlocksCreateAndViceVersa() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val onlySearch = state(source, canSearch = true, canCreate = false)
            onlySearch.fill()
            onlySearch.create()
            val onlyCreate = state(source, canSearch = false, canCreate = true)
            onlyCreate.query = "Maria"
            onlyCreate.searchByName()
            onlyCreate.fill()
            onlyCreate.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
            assertTrue(source.searchCalls.isEmpty())
        }

    @Test
    fun searchAndCreateRunIndependently() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pick
                    holdSearch = true
                }
            val state = state(source)
            state.query = "Maria"
            state.searchByName()
            runCurrent()
            state.fill()
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
            assertTrue(state.searching)
        }

    @Test
    fun createAgainIsPossibleAfterCompletion() =
        runTest {
            val picked = mutableListOf<GuestPick>()
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val state = state(source, picked = picked)
            state.fill()
            state.create()
            runCurrent()
            state.fill(name = "Outra Pessoa", number = cpfFrom("111444777"))
            state.create()
            runCurrent()

            assertEquals(2, source.createGuestCalls)
            assertEquals(2, picked.size)
        }

    @Test
    fun pickDoesNotClearOrSearch() =
        runTest {
            val picked = mutableListOf<GuestPick>()
            val source = FakeReceptionSource()
            val state = state(source, picked = picked)
            state.query = "Maria"
            state.pick(pick)

            assertEquals(listOf(pick), picked)
            assertTrue(source.searchCalls.isEmpty())
        }

    @Test
    fun resultsAndFormToStringNeverLeakData() =
        runTest {
            val source = FakeReceptionSource().apply { guestsByName["Joana"] = listOf(pick) }
            val state = state(source)
            state.query = "Joana Ficticia"
            state.documentNumber = cpf
            state.fill()
            state.searchByName()
            runCurrent()

            val text = state.toString() + state.form.toString() + state.form.fields.joinToString { it.toString() }
            assertFalse(text.contains(cpf))
            assertFalse(text.contains("Joana"))
        }

    @Test
    fun createdGuestInputToStringHidesData() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pick }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()

            val text = source.createdInputs.single().toString()
            assertFalse(text.contains(cpf))
            assertFalse(text.contains("Joana"))
        }
}
