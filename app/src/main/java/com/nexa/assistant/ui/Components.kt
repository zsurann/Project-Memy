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
            blob(Color(0xFF6D3BFF), w * (0.28f + 0.14f * sin(a)), h * (0.20f + 0.06f * cos(b)), w * 0.95f)
            blob(Color(0xFF00C2FF).copy(alpha = 0.7f), w * (0.82f + 0.1f * cos(a)), h * (0.40f + 0.08f * sin(b * 1.3f)), w * 0.75f)
            blob(MemyColors.Pink.copy(alpha = 0.6f), w * (0.48f + 0.18f * cos(b)), h * (0.94f + 0.04f * sin(a)), w * 0.85f)
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

/**
 * La sfera di Memy: un nucleo di luce colorata che respira.
 * - a riposo ruota lentamente;
 * - in ascolto si allarga con la tua voce;
 * - mentre pensa i colori girano più in fretta e tre punti le orbitano attorno;
 * - mentre parla manda onde verso l'esterno.
 * Tutto il disegno (alone e onde compresi) resta dentro il riquadro [size].
 */
@Composable
fun MemyOrb(phase: Phase, level: Float, size: Dp, modifier: Modifier = Modifier) {
    val lvl by animateFloatAsState(level, spring(stiffness = 300f), label = "lvl")
    val listening by animateFloatAsState(if (phase == Phase.LISTENING || phase == Phase.CONFIRMING) 1f else 0f, tween(500), label = "listening")
    val thinking by animateFloatAsState(if (phase == Phase.THINKING || phase == Phase.PROCESSING) 1f else 0f, tween(500), label = "thinking")
    val speaking by animateFloatAsState(if (phase == Phase.SPEAKING) 1f else 0f, tween(500), label = "speaking")

    val t = rememberInfiniteTransition(label = "orb")
    // Periodi diversi e senza scatti: ogni valore torna esattamente al punto di partenza.
    val slow by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "slow")
    val fast by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "fast")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_900, easing = LinearEasing)), label = "ripple")
    val breathe by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val half = this.size.minDimension / 2f
        val r = half * (0.46f + 0.012f * breathe + lvl * (0.085f * listening + 0.045f * speaking))

        // Onde verso l'esterno mentre parla
        if (speaking > 0.01f) {
            for (k in 0..2) {
                val p = (ripple + k / 3f) % 1f
                drawCircle(
                    Color(0xFF8CAAFF).copy(alpha = (1f - p) * 0.38f * speaking),
                    radius = r + p * (half - r) * 0.96f, center = center,
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
        }

        // Alone
        drawCircle(
            Brush.radialGradient(
                0f to MemyColors.Violet.copy(alpha = 0.55f + 0.2f * lvl),
                0.45f to Color(0xFF7C4DFF).copy(alpha = 0.22f),
                1f to Color.Transparent,
                center = center, radius = half,
            ),
            radius = half, center = center,
        )

        // Nucleo: macchie di colore che ruotano e si sommano come luce
        val body = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
        clipPath(body) {
            drawCircle(Color(0xFF1B1446), r, center)
            val colors = listOf(MemyColors.Violet, MemyColors.Cyan, MemyColors.Pink, MemyColors.Indigo)
            colors.forEachIndexed { i, color ->
                // Mentre pensa la rotazione accelera in modo continuo (somma di due angoli, nessuno scatto).
                val angle = slow * (1 + i) + fast * thinking + i * 1.7f
                val c = Offset(center.x + cos(angle) * r * 0.36f, center.y + sin(slow * (2 + i) + fast * thinking + i * 2.3f) * r * 0.36f)
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.6f), color.copy(alpha = 0f)), c, r * 1.05f),
                    r * 1.05f, c, blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
                )
            }
            // Riflesso in alto a sinistra e bordo più scuro, per dare volume
            val hl = Offset(center.x - r * 0.35f, center.y - r * 0.4f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent), hl, r * 0.9f), r * 0.9f, hl)
            drawCircle(Brush.radialGradient(0.7f to Color.Transparent, 1f to Color(0xFF07071A).copy(alpha = 0.35f), center = center, radius = r), r, center)
        }
        drawCircle(Color.White.copy(alpha = 0.16f), r, center, style = Stroke(width = 1.dp.toPx()))

        // Tre punti in orbita mentre pensa
        if (thinking > 0.01f) {
            val orbit = minOf(r * 1.32f, half * 0.94f)
            for (i in 0..2) {
                val a = fast + i * (2 * PI.toFloat() / 3f)
                drawCircle(
                    Color.White.copy(alpha = 0.9f * thinking), radius = half * 0.022f,
                    center = Offset(center.x + cos(a) * orbit, center.y + sin(a) * orbit),
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
