package com.nexa.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexa.assistant.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Sfondo scuro con "aurora" di colori che si muove lentamente. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, intensity: Float = 1f, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "a")
    val b by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(37_000, easing = LinearEasing)), label = "b")
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NexaColors.BackgroundTop, NexaColors.Background)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.32f * intensity), Color.Transparent), Offset(cx, cy), r),
                    radius = r, center = Offset(cx, cy),
                )
            }
            blob(NexaColors.Violet, w * (0.25f + 0.15f * sin(a)), h * (0.18f + 0.06f * cos(b)), w * 0.85f)
            blob(NexaColors.Cyan, w * (0.85f + 0.1f * cos(a)), h * (0.45f + 0.08f * sin(b * 1.3f)), w * 0.7f)
            blob(NexaColors.Pink, w * (0.35f + 0.2f * cos(b)), h * (0.92f + 0.04f * sin(a)), w * 0.75f)
        }
        content()
    }
}

/** Pannello effetto vetro. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(if (strong) NexaColors.GlassStrong else NexaColors.Glass)
            .border(1.dp, NexaColors.GlassBorder, shape)
            .padding(padding),
        content = content,
    )
}

private data class OrbPalette(val a: Color, val b: Color, val c: Color)

private fun paletteFor(phase: Phase) = when (phase) {
    Phase.IDLE -> OrbPalette(NexaColors.Violet, NexaColors.Indigo, NexaColors.Cyan)
    Phase.LISTENING, Phase.CONFIRMING -> OrbPalette(NexaColors.Cyan, NexaColors.Mint, NexaColors.Indigo)
    Phase.PROCESSING, Phase.THINKING -> OrbPalette(NexaColors.Pink, NexaColors.Violet, NexaColors.Indigo)
    Phase.SPEAKING -> OrbPalette(NexaColors.Violet, NexaColors.Cyan, NexaColors.Pink)
}

/**
 * La sfera di Nexa: una forma liquida che respira, si deforma con la voce e cambia colore con lo stato.
 */
@Composable
fun NexaOrb(phase: Phase, level: Float, size: Dp, modifier: Modifier = Modifier) {
    val pal = paletteFor(phase)
    val ca by animateColorAsState(pal.a, tween(700), label = "ca")
    val cb by animateColorAsState(pal.b, tween(700), label = "cb")
    val cc by animateColorAsState(pal.c, tween(700), label = "cc")
    val lvl by animateFloatAsState(level, spring(stiffness = 300f), label = "lvl")
    val active = phase != Phase.IDLE
    val energy by animateFloatAsState(if (active) 1f else 0.35f, tween(600), label = "energy")

    val t = rememberInfiniteTransition(label = "orb")
    val time by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "time")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (phase == Phase.THINKING || phase == Phase.PROCESSING) 1_400 else 14_000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(2_600), RepeatMode.Reverse), label = "breathe")

    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val base = this.size.minDimension * 0.30f * breathe * (1f + lvl * 0.18f)

        // Alone esterno
        val halo = this.size.minDimension / 2f
        drawCircle(
            Brush.radialGradient(
                0f to ca.copy(alpha = 0.40f * energy + lvl * 0.25f),
                0.55f to cb.copy(alpha = 0.10f + lvl * 0.08f),
                1f to Color.Transparent,
                center = center, radius = halo,
            ),
            radius = halo, center = center,
        )

        // Forma liquida
        val path = Path()
        val points = 96
        val amp = 0.035f + 0.05f * energy + lvl * 0.16f
        for (i in 0..points) {
            val th = (i.toFloat() / points) * 2f * PI.toFloat()
            val wobble = sin(3 * th + time * 2f) * 0.5f + sin(5 * th - time * 3f) * 0.3f + sin(2 * th + time) * 0.4f
            val r = base * (1f + amp * wobble)
            val x = center.x + r * cos(th)
            val y = center.y + r * sin(th)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Corpo: colore di base + macchie di colore che fluttuano dentro la forma
        drawPath(path, Brush.radialGradient(listOf(cb, ca), center, base * 1.2f))
        clipPath(path) {
            fun blob(color: Color, angle: Float, dist: Float, r: Float) {
                val c = Offset(center.x + cos(angle) * base * dist, center.y + sin(angle) * base * dist)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)), c, base * r), base * r, c)
            }
            val sp = spin * (PI.toFloat() / 180f)
            blob(cc, sp + time, 0.55f, 1.0f)
            blob(ca, -sp * 0.7f + 2.1f, 0.6f, 0.95f)
            blob(NexaColors.Pink.copy(alpha = 0.7f), time * 1.5f + 4.2f, 0.65f, 0.75f * (0.6f + energy * 0.4f))
            // Riflesso in alto a sinistra e ombra sul bordo, per dare volume
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), Offset(center.x - base * 0.35f, center.y - base * 0.45f), base * 0.75f),
                base * 0.75f, Offset(center.x - base * 0.35f, center.y - base * 0.45f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), center, base * 1.25f),
                base * 1.3f, center,
            )
        }
        drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(width = 1.2.dp.toPx()))

        // Anello che gira mentre pensa
        if (phase == Phase.THINKING || phase == Phase.PROCESSING) {
            rotate(spin, center) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, cc, Color.White), center),
                    startAngle = 0f, sweepAngle = 110f, useCenter = false,
                    topLeft = Offset(center.x - base * 1.32f, center.y - base * 1.32f),
                    size = androidx.compose.ui.geometry.Size(base * 2.64f, base * 2.64f),
                    style = Stroke(width = base * 0.06f, cap = StrokeCap.Round),
                )
            }
        }
        // Onde concentriche mentre ascolta
        if (phase == Phase.LISTENING || phase == Phase.CONFIRMING) {
            for (k in 0..2) {
                val p = ((time / (2 * PI.toFloat())) * 3f + k / 3f) % 1f
                drawCircle(
                    ca.copy(alpha = (1f - p) * 0.35f),
                    radius = base * (1.15f + p * 0.9f), center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/** Tre puntini che saltellano: Nexa sta pensando. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = NexaColors.TextSecondary) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(520, delayMillis = i * 140), RepeatMode.Reverse),
                label = "d$i",
            )
            Box(
                Modifier
                    .graphicsLayer { translationY = -y * 4.dp.toPx(); alpha = 0.45f + 0.55f * y }
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** Testo con sfumatura di colore (per il logo "Nexa"). */
@Composable
fun GradientText(text: String, style: TextStyle, brush: Brush = NexaColors.AccentGradient, modifier: Modifier = Modifier) {
    Text(text, style = style.copy(brush = brush), modifier = modifier)
}

/** Piccola "pastiglia" con icona, usata per stati e strumenti. */
@Composable
fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(NexaColors.GlassStrong)
            .border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}


@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) NexaColors.AccentGradient else androidx.compose.ui.graphics.SolidColor(NexaColors.Glass))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else NexaColors.TextTertiary)
    }
}

@Composable
fun SmallGradientButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(NexaColors.AccentGradient)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(16.dp))
            .background(NexaColors.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = NexaColors.TextPrimary)
    }
}

@Composable
fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(NexaColors.Glass)
            .border(1.dp, NexaColors.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, description, tint = NexaColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}
