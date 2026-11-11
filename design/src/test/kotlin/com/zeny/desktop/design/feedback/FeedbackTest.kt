package com.zeny.desktop.design.feedback

import androidx.compose.material3.SnackbarVisuals
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FeedbackTest {
    private val labels = ExtensibleEnumLabels(mapOf("DIRTY" to "Suja"))
    private val tones = mapOf("DIRTY" to StatusTone.WARNING)

    @Test
    fun chipShowsLabelOrOutro() {
        assertEquals(ChipModel("Suja", StatusTone.WARNING), chipModel("DIRTY", labels, tones))
        assertEquals(
            ChipModel("Outro (TELEPATHY)", StatusTone.NEUTRAL),
            chipModel("TELEPATHY", labels, tones),
        )
    }

    @Test
    fun errorMessageNeverEchoesCause() {
        val message = errorMessage(ApiError.Network(IOException("boom")))
        assertEquals("Não foi possível falar com o servidor. Confira o endereço e a rede.", message)
        assertFalse(message.contains("boom"))
    }

    @Test
    fun toastQueueKeepsOrder() =
        runTest {
            val state = ToastState()
            launch { state.show("primeiro", StatusTone.SUCCESS) }
            launch { state.show("segundo", StatusTone.DANGER) }
            runCurrent()

            val first = state.hostState.currentSnackbarData!!
            assertEquals("primeiro" to StatusTone.SUCCESS, first.visuals.toToast())
            first.dismiss()
            runCurrent()

            val second = state.hostState.currentSnackbarData!!
            assertEquals("segundo" to StatusTone.DANGER, second.visuals.toToast())
            second.dismiss()
            runCurrent()
        }

    private fun SnackbarVisuals.toToast(): Pair<String, StatusTone> = message to (this as ToastVisuals).tone
}
