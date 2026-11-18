package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.BookingResponse
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ataques da M3 E06 ao `NewReservationDialog`: o "Total" é um só (o da oferta antes de criar, o do servidor depois), a
 * resposta de criação sem reserva não derruba a tela nem cria de novo.
 */
@OptIn(ExperimentalTestApi::class)
class NewReservationDialogEtapaAtaqueTest {
    private var closes = 0
    private val guests = FakeReceptionSource().apply { businessDay = BusinessDay(etStay.arrival, ZoneId.of("UTC")) }

    private fun ComposeUiTest.dialog(
        source: FakeNewReservationSource,
        approve: suspend (ApprovableAction<BookingResponse>) -> ApiResponse<BookingResponse>? = { it.run(null) },
    ): NewReservationState {
        lateinit var state: NewReservationState
        setContent {
            val scope = rememberCoroutineScope()
            state =
                remember {
                    NewReservationState(
                        source,
                        guests,
                        scope,
                        etOffer(),
                        etStay,
                        setOf("guests.profiles.read", "guests.profiles.manage"),
                        approve,
                    ) {}
                }
            ZenyTheme { NewReservationDialog(state, onConfirmation = null) { closes++ } }
        }
        waitForIdle()
        state.picker.pick(etGuest)
        waitForIdle()
        return state
    }

    private fun source() = FakeNewReservationSource().apply { response = etResponse() }

    private fun ComposeUiTest.submit() {
        onNodeWithTag("new-reservation-submit").performClick()
        waitForIdle()
    }

    // --- Task 5: um "Total" só ------------------------------------------------------------------------------------

    @Test
    fun beforeCreatingTheOnlyTotalIsTheOffers() =
        runComposeUiTest {
            dialog(source())

            onAllNodesWithText("Total", substring = true).assertCountEquals(1)
            onNodeWithTag("new-reservation-summary").assertTextContains(ET_OFFER_TOTAL_TEXT, substring = true)
            onNodeWithTag("new-reservation-done").assertDoesNotExist()
        }

    @Test
    fun afterCreatingTheOnlyTotalIsTheServersEvenWithANegotiatedRate() =
        runComposeUiTest {
            val server = source()
            val state = dialog(server)
            state.nightlyRateText = "250,00"
            waitForIdle()

            submit()

            assertNotNull(state.done)
            onAllNodesWithText("Total", substring = true).assertCountEquals(1)
            onNodeWithTag("new-reservation-done").assertTextContains(ET_SERVER_TOTAL_TEXT, substring = true)
            onAllNodesWithText(ET_OFFER_TOTAL_TEXT, substring = true).assertCountEquals(0)
            onNodeWithTag("new-reservation-summary").assertExists()
        }

    @Test
    fun afterCreatingWithTheSameTotalAsTheOfferStillShowsOneTotal() =
        runComposeUiTest {
            val server = FakeNewReservationSource().apply { response = etResponse(total = "300.00") }
            dialog(server)

            submit()

            onAllNodesWithText("Total", substring = true).assertCountEquals(1)
            onNodeWithTag("new-reservation-done").assertTextContains(ET_OFFER_TOTAL_TEXT, substring = true)
        }

    // M4 (M4 E01): o nome dizia "antes de criar" e o teste também criava, sem afirmar que a repetição é a mesma
    // intenção.
    @Test
    fun afterAnUnknownResultTheOffersTotalStaysAndTheRetryCreatesOnceWithTheSameKey() =
        runComposeUiTest {
            val server = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = dialog(server)

            submit()

            assertTrue(state.unconfirmed)
            assertNull(state.done)
            onAllNodesWithText("Total", substring = true).assertCountEquals(1)
            onNodeWithTag("new-reservation-summary").assertTextContains(ET_OFFER_TOTAL_TEXT, substring = true)
            onAllNodesWithText(ET_SERVER_TOTAL_TEXT, substring = true).assertCountEquals(0)
            onNodeWithTag("new-reservation-done").assertDoesNotExist()
            onNodeWithTag("new-reservation-submit").assertTextEquals("Tentar de novo")

            submit()

            assertNotNull(state.done)
            onAllNodesWithText("Total", substring = true).assertCountEquals(1)
            onNodeWithTag("new-reservation-done").assertTextContains(ET_SERVER_TOTAL_TEXT, substring = true)
            assertEquals(2, server.runs.size)
            val keys = server.runs.map { it.key }.toSet()
            assertEquals(1, keys.size, "a repetição leva a mesma Idempotency-Key")
            assertTrue(server.runs.all { it.token == null })
            onNodeWithTag("new-reservation-submit").assertDoesNotExist()
        }

    // --- Resultado desconhecido: a única saída é o botão ----------------------------------------------------------

    // O Esc em si (`onDismissRequest`, com `dismissOnClickOutside = false`) não chega ao `runComposeUiTest` nem a uma
    // `ImageComposeScene` (tentado: o evento não vira "dispensar"); a condição `unconfirmed` do Esc fica sem trava sem
    // um ponto de injeção na produção. O que este teste fixa é o que é observável: o botão Cancelar fecha.
    // M4 (M4 E01): o nome prometia "nada mais age" e o teste só clicava no Cancelar. Agora as entradas estão travadas,
    // o único outro botão é "Tentar de novo", e fechar não manda nada.
    @Test
    fun afterAnUnknownResultTheInputsAreLockedOnlyRetryRemainsAndCancelClosesWithoutSending() =
        runComposeUiTest {
            val server = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = dialog(server)
            submit()
            assertTrue(state.unconfirmed)
            assertFalse(state.busy)
            assertFalse(state.closeOnly)

            listOf(
                "new-reservation-requests",
                "new-reservation-swap-holder",
                "new-reservation-origin-PHONE",
                "new-reservation-segment-EVENT",
            ).forEach { onNodeWithTag(it).assertIsNotEnabled() }
            state.specialRequests = "mudou"
            state.nightlyRateText = "1,00"
            assertEquals("", state.specialRequests)
            assertEquals("", state.nightlyRateText)
            onNodeWithTag("new-reservation-submit").assertTextEquals("Tentar de novo")
            onNodeWithTag("new-reservation-submit").assertIsEnabled()
            onNodeWithText("Criar reserva").assertDoesNotExist()
            onNodeWithTag("new-reservation-close").assertTextEquals("Cancelar")

            assertEquals(0, closes)
            onNodeWithTag("new-reservation-close").assertIsEnabled()
            onNodeWithTag("new-reservation-close").performClick()
            waitForIdle()
            assertEquals(1, closes)
            assertEquals(1, server.runs.size)
            assertEquals(1, server.calls.size)
        }

    // O critério do B-M2a pede as entradas desabilitadas com o resultado desconhecido ou só fechar. O `MoneyField` da
    // diária não recebe `enabled` (`NewReservationDialog.kt`, `Form`): o campo aparece habilitado, e só o estado
    // ignora o que se digita.
    @Test
    fun afterAStoredFailureTheNegotiatedRateFieldIsDisabledLikeTheOtherInputs() =
        runComposeUiTest {
            val server = source().apply { outcomes += ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) }
            val state = dialog(server)
            submit()
            assertTrue(state.closeOnly)

            onNodeWithTag("new-reservation-requests").assertIsNotEnabled()
            onNodeWithTag("new-reservation-rate").assertIsNotEnabled()
        }

    // --- Resposta da criação sem reserva ---------------------------------------------------------------------------

    @Test
    fun aCreationAnswerWithoutReservationKeepsTheDialogUpWithoutDuplicating() =
        runComposeUiTest {
            val server = FakeNewReservationSource().apply { response = etResponse().copy(reservations = emptyList()) }
            val state = dialog(server)

            submit()

            assertNull(state.done)
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertTrue(state.closeOnly)
            onNodeWithTag("new-reservation-dialog").assertExists()
            onNodeWithTag("new-reservation-error").assertTextContains(
                "O servidor confirmou a criação, mas não devolveu a reserva. Feche e confira na busca de reservas.",
            )
            onNodeWithTag("new-reservation-submit").assertDoesNotExist()
            onNodeWithTag("new-reservation-close").assertIsEnabled()
            assertEquals(1, server.runs.size)
            onNodeWithText("NoSuchElement", substring = true).assertDoesNotExist()
            onNodeWithText("empty", substring = true).assertDoesNotExist()
            assertEquals(1, server.calls.size)
            onNodeWithTag("new-reservation-close").performClick()
            waitForIdle()
            assertEquals(1, closes)
            assertEquals(1, server.runs.size)
        }
}
