package com.nexa.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object MemyColors {
    val Background = Color(0xFF07071A)
    val BackgroundTop = Color(0xFF0B0A26)
    val Glass = Color(0x0FFFFFFF)
    val GlassStrong = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x1CFFFFFF)
    val Violet = Color(0xFF8B5CF6)
    val Indigo = Color(0xFF6366F1)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFFF4FD8)
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val TextPrimary = Color(0xFFF2F1FF)
    val TextSecondary = Color(0xFFA09FC4)
    val TextTertiary = Color(0xFF6B7090)

    val AccentGradient = Brush.linearGradient(listOf(Violet, Cyan))
    val UserBubble = Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF3AA0FF)))
}

private val Sans = FontFamily.SansSerif

private val MemyTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.3.sp),
)

@Composable
fun MemyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MemyColors.Violet,
            onPrimary = Color.White,
            secondary = MemyColors.Cyan,
            background = MemyColors.Background,
            surface = Color(0xFF12142A),
            surfaceVariant = Color(0xFF1C1F3A),
            surfaceContainerHighest = Color(0xFF23264A),
            onBackground = MemyColors.TextPrimary,
            onSurface = MemyColors.TextPrimary,
            onSurfaceVariant = MemyColors.TextSecondary,
            outline = MemyColors.GlassBorder,
        ),
        typography = MemyTypography,
        content = content,
    )
}
