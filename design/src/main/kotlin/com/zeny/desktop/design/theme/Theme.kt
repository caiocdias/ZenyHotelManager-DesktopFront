@file:Suppress("MagicNumber", "MatchingDeclarationName") // hexadecimais da paleta; arquivo agrupa o tema

package com.zeny.desktop.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta nomeada do Zeny: azul-petróleo de hotelaria, com cores de status de texto legível (WCAG AA). */
object ZenyColors {
    val Primary = Color(0xFF0B5563)
    val OnPrimary = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF8A6D3B)
    val OnSecondary = Color(0xFFFFFFFF)
    val Background = Color(0xFFF7F5F1)
    val Surface = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFF1C2427)
    val Outline = Color(0xFF6F7C80)

    val Success = Color(0xFF1B6B3A)
    val OnSuccess = Color(0xFFFFFFFF)
    val Warning = Color(0xFFFFE08A)
    val OnWarning = Color(0xFF4A3500)
    val Danger = Color(0xFFB3261E)
    val OnDanger = Color(0xFFFFFFFF)
    val Info = Color(0xFF1F5FA8)
    val OnInfo = Color(0xFFFFFFFF)
    val Neutral = Color(0xFF5B666A)
    val OnNeutral = Color(0xFFFFFFFF)
}

val ZenyColorScheme =
    lightColorScheme(
        primary = ZenyColors.Primary,
        onPrimary = ZenyColors.OnPrimary,
        secondary = ZenyColors.Secondary,
        onSecondary = ZenyColors.OnSecondary,
        background = ZenyColors.Background,
        onBackground = ZenyColors.OnSurface,
        surface = ZenyColors.Surface,
        onSurface = ZenyColors.OnSurface,
        error = ZenyColors.Danger,
        onError = ZenyColors.OnDanger,
        outline = ZenyColors.Outline,
    )

val ZenyTypography =
    Typography(
        headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )

@Composable
fun ZenyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenyColorScheme, typography = ZenyTypography, content = content)
}
