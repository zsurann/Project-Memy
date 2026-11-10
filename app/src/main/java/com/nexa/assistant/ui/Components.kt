package com.nexa.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
            .background(Brush.verticalGradient(listOf(MemyColors.BackgroundTop, MemyColors.Background)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.30f * intensity), Color.Transparent), Offset(cx, cy), r),
                    radius = r, center = Offset(cx, cy),
                )
            }
            blob(MemyColors.Violet, w * (0.25f + 0.15f * sin(a)), h * (0.16f + 0.06f * cos(b)), w * 0.85f)
            blob(MemyColors.Cyan, w * (0.88f + 0.1f * cos(a)), h * (0.46f + 0.08f * sin(b * 1.3f)), w * 0.7f)
            blob(MemyColors.Pink, w * (0.30f + 0.2f * cos(b)), h * (0.95f + 0.04f * sin(a)), w * 0.75f)
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
            .background(if (strong) MemyColors.GlassStrong else MemyColors.Glass)
            .border(1.dp, MemyColors.GlassBorder, shape)
            .padding(padding),
        content = content,
    )
}

/** Effetto "pressione": l'elemento si rimpicciolisce leggermente quando lo tocchi. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

private data class OrbPalette(val a: Color, val b: Color, val c: Color)

private fun paletteFor(phase: Phase) = when (phase) {
    Phase.IDLE -> OrbPalette(MemyColors.Violet, MemyColors.Indigo, MemyColors.Cyan)
    Phase.LISTENING, Phase.CONFIRMING -> OrbPalette(MemyColors.Cyan, MemyColors.Mint, MemyColors.Indigo)
    Phase.PROCESSING, Phase.THINKING -> OrbPalette(MemyColors.Pink, MemyColors.Violet, MemyColors.Indigo)
    Phase.SPEAKING -> OrbPalette(MemyColors.Violet, MemyColors.Cyan, MemyColors.Pink)
}

/**
 * La sfera di Memy: una forma liquida che respira, si deforma con la voce e cambia colore con lo stato.
 * Tutto il disegno (alone e onde compresi) resta dentro il riquadro [size].
 */
@Composable
fun MemyOrb(phase: Phase, level: Float, size: Dp, modifier: Modifier = Modifier) {
    val pal = paletteFor(phase)
    val ca by animateColorAsState(pal.a, tween(700), label = "ca")
    val cb by animateColorAsState(pal.b, tween(700), label = "cb")
    val cc by animateColorAsState(pal.c, tween(700), label = "cc")
    val lvl by animateFloatAsState(level, spring(stiffness = 300f), label = "lvl")
    val energy by animateFloatAsState(if (phase != Phase.IDLE) 1f else 0.35f, tween(600), label = "energy")
    val thinking = phase == Phase.THINKING || phase == Phase.PROCESSING
    val listening = phase == Phase.LISTENING || phase == Phase.CONFIRMING
    val ringAlpha by animateFloatAsState(if (thinking) 1f else 0f, tween(400), label = "ring")
    val waveAlpha by animateFloatAsState(if (listening) 1f else 0f, tween(400), label = "wave")

    val t = rememberInfiniteTransition(label = "orb")
    val time by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "time")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "spin")
    val fastSpin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "fast")
    val breathe by t.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val half = this.size.minDimension / 2f
        val base = half * 0.56f * breathe * (1f + lvl * 0.14f)

        // Alone
        drawCircle(
            Brush.radialGradient(
                0f to ca.copy(alpha = 0.38f * energy + lvl * 0.22f),
                0.6f to cb.copy(alpha = 0.10f + lvl * 0.08f),
                1f to Color.Transparent,
                center = center, radius = half,
            ),
            radius = half, center = center,
        )

        // Onde concentriche mentre ascolta
        if (waveAlpha > 0.01f) {
            for (k in 0..2) {
                val p = ((time / (2 * PI.toFloat())) * 3f + k / 3f) % 1f
                drawCircle(
                    ca.copy(alpha = (1f - p) * 0.38f * waveAlpha),
                    radius = base * (1.12f + p * 0.58f), center = center,
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            }
        }

        // Forma liquida
        val path = Path()
        val points = 96
        val amp = 0.03f + 0.045f * energy + lvl * 0.13f
        for (i in 0..points) {
            val th = (i.toFloat() / points) * 2f * PI.toFloat()
            val wobble = sin(3 * th + time * 2f) * 0.5f + sin(5 * th - time * 3f) * 0.3f + sin(2 * th + time) * 0.4f
            val r = base * (1f + amp * wobble)
            val x = center.x + r * cos(th)
            val y = center.y + r * sin(th)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(path, Brush.radialGradient(listOf(cb, ca), center, base * 1.2f))
        clipPath(path) {
            fun blob(color: Color, angle: Float, dist: Float, r: Float) {
                val c = Offset(center.x + cos(angle) * base * dist, center.y + sin(angle) * base * dist)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)), c, base * r), base * r, c)
            }
            val sp = spin * (PI.toFloat() / 180f)
            blob(cc, sp + time, 0.55f, 1.0f)
            blob(ca, -sp * 0.7f + 2.1f, 0.6f, 0.95f)
            blob(MemyColors.Pink.copy(alpha = 0.7f), time * 1.5f + 4.2f, 0.65f, 0.75f * (0.6f + energy * 0.4f))
            // Riflesso e ombra, per dare volume
            val hl = Offset(center.x - base * 0.35f, center.y - base * 0.45f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), hl, base * 0.75f), base * 0.75f, hl)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), center, base * 1.25f), base * 1.3f, center)
        }
        drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(width = 1.2.dp.toPx()))

        // Anello che gira mentre pensa
        if (ringAlpha > 0.01f) {
            val r = base * 1.3f
            rotate(fastSpin, center) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, cc.copy(alpha = ringAlpha), Color.White.copy(alpha = ringAlpha)), center),
                    startAngle = 0f, sweepAngle = 120f, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = base * 0.055f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Tre puntini che saltellano: Memy sta pensando. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MemyColors.TextSecondary, dot: Dp = 7.dp) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(
                0f, 1f, infiniteRepeatable(tween(520, delayMillis = i * 140, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d$i",
            )
            Box(
                Modifier
                    .graphicsLayer { translationY = -y * 3.dp.toPx(); alpha = 0.4f + 0.6f * y }
                    .size(dot)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** Barre di un equalizzatore: animate quando [playing]. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(380, 520, 300, 460).forEachIndexed { i, ms ->
            val h by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b$i")
            val shown by animateFloatAsState(if (playing) h else 0.25f, tween(150), label = "bs$i")
            Box(Modifier.width(3.dp).height(16.dp * shown).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** Testo con sfumatura di colore (per il logo "Memy"). */
@Composable
fun GradientText(text: String, style: TextStyle, modifier: Modifier = Modifier, brush: Brush = MemyColors.AccentGradient) {
    Text(text, style = style.copy(brush = brush), modifier = modifier)
}

/** Piccola "pastiglia", usata per stati ed etichette. */
@Composable
fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MemyColors.GlassStrong)
            .border(1.dp, MemyColors.GlassBorder, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .pressable(enabled, onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) MemyColors.AccentGradient else SolidColor(MemyColors.Glass))
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else MemyColors.TextTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SmallGradientButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(MemyColors.AccentGradient)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MemyColors.GlassBorder, RoundedCornerShape(16.dp))
            .background(MemyColors.Glass)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    tint: Color = MemyColors.TextSecondary,
) {
    Box(
        modifier
            .pressable(onClick = onClick)
            .size(size).clip(CircleShape)
            .background(MemyColors.Glass)
            .border(1.dp, MemyColors.GlassBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** Solo per gli screenshot di prova: apre tutte le sezioni. */
internal var previewExpandAllSections = false

/** Sezione richiudibile delle impostazioni, con icona, titolo e riepilogo. */
@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    attention: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded || previewExpandAllSections) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(280), label = "chevron")
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MemyColors.Glass)
            .border(1.dp, if (attention) MemyColors.Amber.copy(alpha = 0.45f) else MemyColors.GlassBorder, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MemyColors.AccentGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MemyColors.TextPrimary)
                Text(
                    summary, style = MaterialTheme.typography.bodyMedium,
                    color = if (attention) MemyColors.Amber else MemyColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown, if (expanded) "Chiudi" else "Apri", tint = MemyColors.TextSecondary,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(250, delayMillis = 60)),
            exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing)) + fadeOut(tween(150)),
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
