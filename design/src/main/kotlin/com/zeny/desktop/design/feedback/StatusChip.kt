package com.zeny.desktop.design.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.design.theme.ZenyColors

/** Tom semântico de um aviso ou chip de status. */
enum class StatusTone { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

/** Cor de fundo do tom (par de [content], de contraste legível). */
internal fun StatusTone.container(): Color =
    when (this) {
        StatusTone.SUCCESS -> ZenyColors.Success
        StatusTone.WARNING -> ZenyColors.Warning
        StatusTone.DANGER -> ZenyColors.Danger
        StatusTone.INFO -> ZenyColors.Info
        StatusTone.NEUTRAL -> ZenyColors.Neutral
    }

/** Cor do texto sobre [container]. */
internal fun StatusTone.content(): Color =
    when (this) {
        StatusTone.SUCCESS -> ZenyColors.OnSuccess
        StatusTone.WARNING -> ZenyColors.OnWarning
        StatusTone.DANGER -> ZenyColors.OnDanger
        StatusTone.INFO -> ZenyColors.OnInfo
        StatusTone.NEUTRAL -> ZenyColors.OnNeutral
    }

data class ChipModel(
    val label: String,
    val tone: StatusTone,
)

/** Rótulo por [ExtensibleEnumLabels] ("Outro (VALOR)" no desconhecido); tom sem mapeamento é neutro. */
fun chipModel(
    value: String,
    labels: ExtensibleEnumLabels,
    tones: Map<String, StatusTone>,
): ChipModel = ChipModel(labels.label(value), tones[value] ?: StatusTone.NEUTRAL)

@Composable
fun StatusChip(
    model: ChipModel,
    modifier: Modifier = Modifier,
) {
    Text(
        text = model.label,
        style = MaterialTheme.typography.labelMedium,
        color = model.tone.content(),
        modifier =
            modifier
                .background(model.tone.container(), RoundedCornerShape(percent = CHIP_CORNER_PERCENT))
                .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

private const val CHIP_CORNER_PERCENT = 50
