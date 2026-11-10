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

object NexaColors {
    val Background = Color(0xFF05060D)
    val BackgroundTop = Color(0xFF0A0B1E)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x22FFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    val Violet = Color(0xFF8B5CF6)
    val Indigo = Color(0xFF6366F1)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFEC4899)
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val TextPrimary = Color(0xFFF5F6FF)
    val TextSecondary = Color(0xFFA3A8C3)
    val TextTertiary = Color(0xFF6B7090)

    val AccentGradient = Brush.linearGradient(listOf(Violet, Indigo, Cyan))
    val UserBubble = Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF4F46E5)))
}

private val Sans = FontFamily.SansSerif

private val NexaTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.3.sp),
)

@Composable
fun NexaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NexaColors.Violet,
            onPrimary = Color.White,
            secondary = NexaColors.Cyan,
            background = NexaColors.Background,
            surface = Color(0xFF12142A),
            surfaceVariant = Color(0xFF1C1F3A),
            surfaceContainerHighest = Color(0xFF23264A),
            onBackground = NexaColors.TextPrimary,
            onSurface = NexaColors.TextPrimary,
            onSurfaceVariant = NexaColors.TextSecondary,
            outline = NexaColors.GlassBorder,
        ),
        typography = NexaTypography,
        content = content,
    )
}
