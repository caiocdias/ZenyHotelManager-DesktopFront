package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.NewGuest
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

/** CPF fictício com dígitos verificadores válidos, a partir de nove dígitos-base. */
private fun cpfOf(base: String): String {
    fun digit(
        digits: String,
        weight: Int,
    ): Char {
        val sum = digits.mapIndexed { i, c -> (c - '0') * (weight - i) }.sum()
        val rest = sum % 11
        return if (rest < 2) '0' else ('0' + (11 - rest))
    }
    val first = digit(base, 10)
    val second = digit(base + first, 11)
    return base + first + second
}

private val validCpf = cpfOf("123456789")
private const val VALID_CNPJ = "11222333000181"
private val pickA = GuestPick("g-1", HolderSummary("Maria Teste", "CPF ***.456.789-**"))

@OptIn(ExperimentalCoroutinesApi::class)
class GuestPickerStateTest {
    private fun TestScope.state(
        source: FakeReceptionSource = FakeReceptionSource(),
        canSearch: Boolean = true,
        canCreate: Boolean = true,
        picked: MutableList<GuestPick> = mutableListOf(),
    ) = GuestPickerState(source, backgroundScope, canSearch, canCreate) { picked += it }

    @Suppress("LongParameterList") // preenchimento do formulário: um parâmetro por campo
    private fun GuestPickerState.fill(
        name: String = "Maria Teste",
        type: String = "CPF",
        number: String = validCpf,
        country: String = "",
        nationality: String = "BR",
        birth: String = "10/05/1990",
    ) {
        val byName = form.fields.associateBy { it.name }
        byName.getValue("fullName").onChange(name)
        byName.getValue("documentType").onChange(type)
        byName.getValue("documentNumber").onChange(number)
        byName.getValue("issuingCountry").onChange(country)
        byName.getValue("nationality").onChange(nationality)
        byName.getValue("birthDate").onChange(birth)
    }

    private fun GuestPickerState.field(name: String) = form.fields.first { it.name == name }

    @Test
    fun searchByNameListsPicks() =
        runTest {
            val source = FakeReceptionSource().apply { guestsByName["Maria"] = listOf(pickA) }
            val state = state(source)
            state.query = "Maria"
            state.searchByName()
            runCurrent()

            assertEquals(listOf(pickA), state.results)
            assertEquals(listOf("Maria"), source.searchCalls)
            assertNull(state.error)
            assertFalse(state.searching)
        }

    @Test
    fun shortNameAsksMore() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.query = " M "
            state.searchByName()
            runCurrent()

            assertEquals("Digite ao menos 2 letras do nome.", state.error)
            assertTrue(source.searchCalls.isEmpty())
        }

    @Test
    fun searchByDocumentSendsTypeNumberCountry() =
        runTest {
            val source = FakeReceptionSource().apply { guestsByDocument["AB123456"] = listOf(pickA) }
            val state = state(source)
            state.documentType = "PASSPORT"
            state.documentNumber = "AB123456"
            state.issuingCountry = "AR"
            state.searchByDocument()
            runCurrent()

            assertEquals(listOf<Pair<String, String?>>("PASSPORT" to "AR"), source.documentSearchCalls)
            assertEquals(listOf(pickA), state.results)
        }

    @Test
    fun documentTypeDefaultsToCpf() =
        runTest {
            assertEquals("CPF", state().documentType)
        }

    @Test
    fun passportNeedsCountry() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            for (type in listOf("PASSPORT", "FOREIGN_ID")) {
                state.documentType = type
                state.documentNumber = "AB123456"
                state.issuingCountry = ""
                state.searchByDocument()
                runCurrent()
                assertEquals("Informe o país emissor.", state.error)
            }
            assertTrue(source.documentSearchCalls.isEmpty())
        }

    @Test
    fun searchByDocumentNeedsNumber() =
        runTest {
            val source = FakeReceptionSource()
            val state = state(source)
            state.searchByDocument()
            runCurrent()

            assertEquals("Informe o número do documento.", state.error)
            assertTrue(source.documentSearchCalls.isEmpty())
        }

    @Test
    fun numberClearedAfterSearchAndCreate() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source)
            state.documentNumber = validCpf
            state.searchByDocument()
            runCurrent()
            assertEquals("", state.documentNumber)

            source.searchError = IOException("fora do ar")
            state.documentNumber = validCpf
            state.searchByDocument()
            runCurrent()
            assertEquals("", state.documentNumber)
            assertNotNull(state.error)

            // No cadastro o número fica até o sucesso: um erro de campo precisa dele para a correção.
            source.createGuestError = ApiError.Problem(422, "VALIDATION", null, emptyList())
            state.fill()
            state.create()
            runCurrent()
            assertEquals(validCpf, state.field("documentNumber").text)

            source.createGuestError = null
            state.create()
            runCurrent()
            assertEquals("", state.field("documentNumber").text)
            assertEquals("", state.field("fullName").text)
        }

    @Test
    fun createValidatesBeforeCalling() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source)
            val bad =
                mapOf<String, () -> Unit>(
                    "CPF inválido" to { state.fill(number = "11111111112") },
                    "CNPJ no lugar do CPF" to { state.fill(number = VALID_CNPJ) },
                    "data futura" to { state.fill(birth = "01/01/2027") },
                    "nacionalidade fora de 2 letras" to { state.fill(nationality = "BRA") },
                    "nacionalidade minúscula" to { state.fill(nationality = "br") },
                    "nome vazio" to { state.fill(name = " ") },
                    "data vazia" to { state.fill(birth = "") },
                    "brasileiro adulto com RG" to { state.fill(type = "RG", number = "123456789") },
                    "passaporte sem país" to {
                        state.fill(type = "PASSPORT", number = "AB123456", nationality = "AR")
                    },
                )
            for ((name, setup) in bad) {
                setup()
                state.create()
                runCurrent()
                assertEquals(0, source.createGuestCalls, name)
                assertFalse(state.creating, name)
            }
        }

    @Test
    fun adultBrazilianWithRgGetsMessageOnDocumentType() =
        runTest {
            val state = state(FakeReceptionSource().apply { createdGuest = pickA })
            state.fill(type = "RG", number = "123456789")
            state.create()
            runCurrent()

            assertEquals("Para brasileiro adulto, use o CPF.", state.field("documentType").error)
        }

    @Test
    fun minorBrazilianMayUseRg() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source)
            state.fill(type = "RG", number = "123456789", birth = "14/11/2010")
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun eighteenthBirthdayCountsAsAdult() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source)
            state.fill(type = "RG", number = "123456789", birth = "13/11/2008")
            state.create()
            runCurrent()
            assertEquals(0, source.createGuestCalls)

            state.fill(type = "RG", number = "123456789", birth = "14/11/2008")
            state.create()
            runCurrent()
            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun ageRuleSkippedWhenBusinessDayFails() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pickA
                    businessDayError = IOException("fora do ar")
                }
            val state = state(source)
            state.fill(type = "RG", number = "123456789")
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun foreignAdultMayUsePassport() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source)
            state.fill(type = "PASSPORT", number = "AB123456", country = "ar", nationality = "AR")
            state.create()
            runCurrent()

            assertEquals(
                NewGuest("Maria Teste", "PASSPORT", "AB123456", "AR", "AR", BusinessDate.parse("1990-05-10")),
                source.createdInputs.single(),
            )
        }

    @Test
    fun createNetworkErrorSuggestsSearch() =
        runTest {
            val expected = "Não foi possível confirmar o cadastro. Busque pelo documento antes de cadastrar de novo."
            for (error in listOf<Exception>(
                ApiError.Network(IOException("x")),
                ApiError.IncompatibleResponse(IOException("y")),
            )) {
                val source = FakeReceptionSource().apply { createGuestError = error }
                val state = state(source)
                state.fill()
                state.create()
                runCurrent()

                assertEquals(expected, state.createError)
                assertFalse(state.creating)
            }
        }

    @Test
    fun withoutPermissionsNoCalls() =
        runTest {
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source, canSearch = false, canCreate = false)
            state.query = "Maria"
            state.searchByName()
            state.documentNumber = validCpf
            state.searchByDocument()
            state.fill()
            state.create()
            runCurrent()

            assertTrue(source.searchCalls.isEmpty())
            assertTrue(source.documentSearchCalls.isEmpty())
            assertEquals(0, source.createGuestCalls)
            assertEquals(0, source.businessDayCalls)
        }

    @Test
    fun createSendsNewGuestAndPicks() =
        runTest {
            val picked = mutableListOf<GuestPick>()
            val source = FakeReceptionSource().apply { createdGuest = pickA }
            val state = state(source, picked = picked)
            state.fill(
                name = " Maria Teste ",
                number =
                    validCpf.let {
                        "${it.take(3)}.${it.substring(3, 6)}.${it.substring(6, 9)}-${it.takeLast(2)}"
                    },
            )
            state.create()
            runCurrent()

            assertEquals(
                NewGuest("Maria Teste", "CPF", validCpf, null, "BR", BusinessDate.parse("1990-05-10")),
                source.createdInputs.single(),
            )
            assertEquals(listOf(pickA), picked)
            assertNull(state.createError)
        }

    @Test
    fun serverFieldErrorGoesToField() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createGuestError =
                        ApiError.Problem(
                            422,
                            "VALIDATION_ERROR",
                            null,
                            listOf(
                                FieldError(
                                    "documents[0].number",
                                    "DOCUMENT_NUMBER_INVALID",
                                    "Número de documento inválido.",
                                ),
                            ),
                        )
                }
            val state = state(source)
            state.fill()
            state.create()
            runCurrent()

            assertEquals("Número de documento inválido.", state.field("documentNumber").error)
            assertNull(state.createError)
        }

    @Test
    fun doubleCreateSendsOnce() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pickA
                    holdCreateGuest = true
                }
            val state = state(source)
            state.fill()
            state.create()
            state.create()
            runCurrent()
            state.create()
            runCurrent()

            assertEquals(1, source.createGuestCalls)
            assertTrue(state.creating)
            source.heldCreateGuests.single().complete(Unit)
            runCurrent()
            assertFalse(state.creating)
            assertEquals(1, source.createGuestCalls)
        }

    @Test
    fun pickCallsOnPicked() =
        runTest {
            val picked = mutableListOf<GuestPick>()
            state(picked = picked).pick(pickA)

            assertEquals(listOf(pickA), picked)
        }

    @Test
    fun pickerToStringHidesData() =
        runTest {
            val state = state()
            state.query = "Maria Teste"
            state.documentNumber = validCpf
            state.fill()

            val text = state.toString()
            assertFalse(text.contains("Maria"))
            assertFalse(text.contains(validCpf))
        }

    @Test
    fun foreignCancellationBecomesErrorAndStaysUsable() =
        runTest {
            val source =
                FakeReceptionSource().apply {
                    createdGuest = pickA
                    searchError = CancellationException("estranha")
                    createGuestError = CancellationException("estranha")
                }
            val state = state(source)
            state.query = "Maria"
            state.searchByName()
            runCurrent()
            assertNotNull(state.error)
            assertFalse(state.searching)

            state.fill()
            state.create()
            runCurrent()
            assertNotNull(state.createError)
            assertFalse(state.creating)

            source.createGuestError = null
            state.create()
            runCurrent()
            assertEquals(2, source.createGuestCalls)
        }
}
