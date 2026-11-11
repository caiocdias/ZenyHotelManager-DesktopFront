package com.zeny.desktop.design.feedback

import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackAttackTest {
    private val labels = ExtensibleEnumLabels(mapOf("OK" to "Pronta", "" to "Vazio"))

    @Test
    fun chipWithKnownLabelButNoToneIsNeutral() {
        assertEquals(ChipModel("Pronta", StatusTone.NEUTRAL), chipModel("OK", labels, emptyMap()))
    }

    @Test
    fun chipToneLookupIsCaseSensitive() {
        val model = chipModel("ok", labels, mapOf("OK" to StatusTone.SUCCESS))
        assertEquals(ChipModel("Outro (ok)", StatusTone.NEUTRAL), model)
    }

    @Test
    fun chipForUnknownValueKeepsItsMappedTone() {
        assertEquals(
            ChipModel("Outro (NEW)", StatusTone.DANGER),
            chipModel("NEW", labels, mapOf("NEW" to StatusTone.DANGER)),
        )
    }

    @Test
    fun chipForEmptyValueUsesItsLabel() {
        assertEquals("Vazio", chipModel("", labels, emptyMap()).label)
    }

    @Test
    fun everyToneHasDistinctContainerAndDifferentContent() {
        val containers = StatusTone.entries.map { it.container() }
        assertEquals(StatusTone.entries.size, containers.toSet().size)
        StatusTone.entries.forEach { assertFalse(it.container() == it.content(), "tom $it") }
    }

    @Test
    fun errorMessageIsUserMessageForEveryErrorKindAndNeverTheCause() {
        val errors =
            listOf(
                ApiError.Network(IOException("segredo-rede")),
                ApiError.IncompatibleResponse(IllegalStateException("segredo-json")),
                ApiError.Problem(422, "VALIDATION_FAILED", "detalhe-interno", listOf(FieldError("f", "C", "m"))),
                ApiError.Unexpected(500),
                ApiError.PasswordChangeRequired,
                ApiError.SessionEnded("SECRET_SESSION"),
                ApiError.PreconditionFailed("detalhe-etag"),
            )
        errors.forEach { error ->
            val text = errorMessage(error)
            assertEquals(error.userMessage, text)
            listOf("segredo", "SECRET", "detalhe-").forEach { assertFalse(text.contains(it), "$error vazou $it") }
        }
    }

    @Test
    fun toastDefaultsToInfoTone() =
        runTest {
            val state = ToastState()
            launch { state.show("oi") }
            runCurrent()
            val visuals = state.hostState.currentSnackbarData!!.visuals as ToastVisuals
            assertEquals("oi", visuals.message)
            assertEquals(StatusTone.INFO, visuals.tone)
            state.hostState.currentSnackbarData!!.dismiss()
            runCurrent()
        }

    @Test
    fun toastsKeepOrderForManyMessagesOneAtATime() =
        runTest {
            val state = ToastState()
            val order = listOf("a", "b", "c", "d", "e")
            order.forEach { launch { state.show(it) } }
            runCurrent()
            val seen = mutableListOf<String>()
            repeat(order.size) {
                val data = assertNotNull(state.hostState.currentSnackbarData)
                seen += data.visuals.message
                data.dismiss()
                runCurrent()
            }
            assertEquals(order, seen)
            assertNull(state.hostState.currentSnackbarData)
        }

    @Test
    fun secondToastStaysQueuedWhileFirstIsShown() =
        runTest {
            val state = ToastState()
            launch { state.show("um") }
            launch { state.show("dois") }
            runCurrent()
            runCurrent()
            assertEquals(
                "um",
                state.hostState.currentSnackbarData!!
                    .visuals.message,
            )
            state.hostState.currentSnackbarData!!.dismiss()
            runCurrent()
            assertEquals(
                "dois",
                state.hostState.currentSnackbarData!!
                    .visuals.message,
            )
            state.hostState.currentSnackbarData!!.dismiss()
            runCurrent()
        }

    @Test
    fun dismissedToastResultIsDismissed() =
        runTest {
            val state = ToastState()
            var result: SnackbarResult? = null
            launch { result = state.hostState.showSnackbar(ToastVisuals("x", StatusTone.INFO)) }
            runCurrent()
            state.hostState.currentSnackbarData!!.dismiss()
            runCurrent()
            assertEquals(SnackbarResult.Dismissed, result)
        }
}

@OptIn(ExperimentalTestApi::class)
class ConfirmDialogUiTest {
    @Test
    fun confirmButtonCallsOnConfirmExactlyOnce() =
        runComposeUiTest {
            var confirms = 0
            var dismisses = 0
            setContent {
                ZenyTheme {
                    ConfirmDialog("Apagar", "Certeza?", "Sim", "Não", true, { confirms++ }, { dismisses++ })
                }
            }
            onNodeWithText("Apagar").assertExists()
            onNodeWithText("Certeza?").assertExists()
            onNodeWithText("Sim").performClick()
            waitForIdle()
            assertEquals(1, confirms)
            assertEquals(0, dismisses)
        }

    @Test
    fun dismissButtonCallsOnDismissExactlyOnce() =
        runComposeUiTest {
            var confirms = 0
            var dismisses = 0
            setContent {
                ZenyTheme { ConfirmDialog("T", "M", onConfirm = { confirms++ }, onDismiss = { dismisses++ }) }
            }
            onNodeWithText("Cancelar").performClick()
            waitForIdle()
            assertEquals(0, confirms)
            assertEquals(1, dismisses)
        }

    @Test
    fun defaultLabelsAreConfirmarAndCancelar() =
        runComposeUiTest {
            setContent { ZenyTheme { ConfirmDialog("T", "M", onConfirm = {}, onDismiss = {}) } }
            onNodeWithText("Confirmar").assertExists()
            onNodeWithText("Cancelar").assertExists()
        }
}
