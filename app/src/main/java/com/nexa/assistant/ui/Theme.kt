package com.nexa.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object NexaColors {
    val Background = Color(0xFF0B0F1A)
    val Surface = Color(0xFF151B2B)
    val SurfaceHigh = Color(0xFF1E2640)
    val Accent = Color(0xFF7C9CFF)
    val AccentWarm = Color(0xFFB98CFF)
    val Listening = Color(0xFF4FD1A5)
    val Warning = Color(0xFFFFB454)
    val TextPrimary = Color(0xFFEFF2FA)
    val TextSecondary = Color(0xFF9AA4BF)
}

@Composable
fun NexaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NexaColors.Accent,
            secondary = NexaColors.AccentWarm,
            background = NexaColors.Background,
            surface = NexaColors.Surface,
            surfaceVariant = NexaColors.SurfaceHigh,
            onPrimary = Color(0xFF0B0F1A),
            onBackground = NexaColors.TextPrimary,
            onSurface = NexaColors.TextPrimary,
            onSurfaceVariant = NexaColors.TextSecondary,
        ),
        content = content,
    )
}
