package com.nexa.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.assistant.ChatLine
import com.nexa.assistant.NexaApp
import com.nexa.assistant.Phase
import com.nexa.assistant.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

private val toolLabels = mapOf(
    "web_search" to "Cerco sul web",
    "get_device_status" to "Controllo il telefono",
    "open_app" to "Apro l'app",
    "set_alarm" to "Imposto la sveglia",
    "set_timer" to "Avvio il timer",
    "find_contact" to "Cerco in rubrica",
    "make_call" to "Preparo la chiamata",
    "send_sms" to "Preparo il messaggio",
    "set_flashlight" to "Torcia",
    "set_volume" to "Regolo il volume",
    "media_control" to "Controllo la musica",
    "open_settings" to "Apro le impostazioni",
    "open_web" to "Apro il browser",
    "navigate_to" to "Apro le mappe",
    "create_calendar_event" to "Preparo l'evento",
)

private val suggestions = listOf(
    "Ciao Nexa, come stai?",
    "Che tempo fa domani?",
    "Svegliami alle 7",
    "Accendi la torcia",
    "Raccontami una curiosità",
    "Apri WhatsApp",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(app: NexaApp, onOrb: () -> Unit, onOpenSettings: () -> Unit) {
    val controller = app.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val level by controller.level.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var showKeyboard by remember { mutableStateOf(false) }
    val chatting = state.lines.isNotEmpty()

    val orbClick: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onOrb()
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            TopBar(
                modelLabel = settings.model.label,
                phase = state.phase,
                chatting = chatting,
                onNew = controller::newConversation,
                onSettings = onOpenSettings,
            )

            UpdateBanner(update, app)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = chatting,
                    transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(250)) },
                    label = "main",
                ) { isChatting ->
                    if (!isChatting) {
                        Hero(
                            name = settings.userName,
                            phase = state.phase,
                            level = level,
                            partial = state.partial,
                            onOrb = orbClick,
                            onSuggestion = { controller.submit(it) },
                        )
                    } else {
                        Conversation(state.lines, state.partial, state.toolInUse, state.phase)
                    }
                }
            }

            AnimatedVisibility(
                visible = state.pendingConfirmation != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ConfirmationCard(
                    prompt = state.pendingConfirmation.orEmpty(),
                    onYes = { controller.answerConfirmation(true) },
                    onNo = { controller.answerConfirmation(false) },
                )
            }

            Dock(
                phase = state.phase,
                level = level,
                showOrb = chatting,
                listenerName = state.listenerName,
                showKeyboard = showKeyboard,
                onToggleKeyboard = { showKeyboard = !showKeyboard },
                onOrb = orbClick,
                onSend = { controller.submit(it) },
            )
        }
    }
}

@Composable
private fun TopBar(modelLabel: String, phase: Phase, chatting: Boolean, onNew: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GradientText("Nexa", MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when (phase) {
                    Phase.IDLE -> NexaColors.Mint
                    Phase.LISTENING, Phase.CONFIRMING -> NexaColors.Cyan
                    else -> NexaColors.Pink
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text("Claude $modelLabel", style = MaterialTheme.typography.labelMedium, color = NexaColors.TextSecondary)
            }
        }
        AnimatedVisibility(chatting, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.EditNote, contentDescription = "Nuova conversazione", tint = NexaColors.TextSecondary)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Impostazioni", tint = NexaColors.TextSecondary)
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..12 -> "Buongiorno"
    in 13..17 -> "Buon pomeriggio"
    else -> "Buonasera"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    name: String,
    phase: Phase,
    level: Float,
    partial: String,
    onOrb: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(260.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOrb),
            contentAlignment = Alignment.Center,
        ) {
            NexaOrb(phase = phase, level = level, size = 260.dp)
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = if (partial.isNotBlank()) partial else null,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "heroText",
        ) { p ->
            if (p != null) {
                Text(
                    "“$p”", style = MaterialTheme.typography.titleMedium, color = NexaColors.TextPrimary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${greeting()}${if (name.isNotBlank()) ", $name" else ""}",
                        style = MaterialTheme.typography.displaySmall, color = NexaColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (phase == Phase.LISTENING) "Ti ascolto…" else "Tocca la sfera e parlami.",
                        style = MaterialTheme.typography.bodyLarge, color = NexaColors.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            suggestions.forEachIndexed { i, s ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(120L * i); visible = true }
                val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(500), label = "chip$i")
                Box(
                    Modifier.graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * 24f }
                        .clip(RoundedCornerShape(50))
                        .background(NexaColors.Glass)
                        .border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(50))
                        .clickable { onSuggestion(s) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(s, style = MaterialTheme.typography.labelLarge, color = NexaColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun Conversation(lines: List<ChatLine>, partial: String, tool: String?, phase: Phase) {
    val listState = rememberLazyListState()
    val itemCount = lines.size + (if (partial.isNotBlank()) 1 else 0) + (if (tool != null) 1 else 0)
    LaunchedEffect(itemCount, lines.lastOrNull()?.text?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            Box(Modifier.animateItem()) { MessageBubble(line) }
        }
        if (tool != null && (phase == Phase.THINKING || phase == Phase.SPEAKING)) {
            item(key = "tool") {
                Box(Modifier.animateItem()) { ToolChip(tool) }
            }
        }
        if (partial.isNotBlank()) {
            item(key = "partial") {
                Box(Modifier.animateItem().fillMaxWidth()) {
                    Text(
                        partial,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexaColors.TextSecondary,
                        modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(22.dp)).border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val p by animateFloatAsState(if (appeared) 1f else 0f, tween(380), label = "bubble")
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = p; translationY = (1f - p) * 30f; scaleX = 0.96f + 0.04f * p; scaleY = scaleX
        }
    ) {
        when {
            line.isNotice -> Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                    .background(NexaColors.Amber.copy(alpha = 0.12f))
                    .border(1.dp, NexaColors.Amber.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = NexaColors.Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(line.text, style = MaterialTheme.typography.bodyMedium, color = NexaColors.Amber)
            }
            line.fromUser -> Text(
                line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(NexaColors.UserBubble)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
            else -> Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 4.dp).size(28.dp).clip(CircleShape).background(NexaColors.AccentGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp))
                        .background(NexaColors.GlassStrong)
                        .border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (line.streaming && line.text.isBlank()) TypingDots()
                    else Text(line.text, style = MaterialTheme.typography.bodyLarge, color = NexaColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ToolChip(tool: String) {
    Box(Modifier.fillMaxWidth().padding(start = 36.dp)) {
        Pill {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tool == "web_search") Icons.Filled.Language else Icons.Filled.Tune, null,
                    tint = NexaColors.Cyan, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(toolLabels[tool] ?: "Al lavoro", style = MaterialTheme.typography.labelMedium, color = NexaColors.TextPrimary)
                Spacer(Modifier.width(8.dp))
                TypingDots(color = NexaColors.Cyan)
            }
        }
    }
}

@Composable
private fun ConfirmationCard(prompt: String, onYes: () -> Unit, onNo: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), strong = true) {
        Text("Serve la tua conferma", style = MaterialTheme.typography.labelMedium, color = NexaColors.Cyan)
        Spacer(Modifier.height(6.dp))
        Text(prompt, style = MaterialTheme.typography.bodyLarge, color = NexaColors.TextPrimary)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton("Sì, procedi", onYes, Modifier.weight(1f))
            OutlineButton("Annulla", onNo, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Dock(
    phase: Phase,
    level: Float,
    showOrb: Boolean,
    listenerName: String,
    showKeyboard: Boolean,
    onToggleKeyboard: () -> Unit,
    onOrb: () -> Unit,
    onSend: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = statusText(phase, listenerName),
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith fadeOut(tween(150)) },
            label = "status",
        ) { text ->
            Text(text, style = MaterialTheme.typography.labelMedium, color = NexaColors.TextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(if (showKeyboard) Icons.Filled.Close else Icons.Filled.Keyboard, "Scrivi", onToggleKeyboard)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val orbScale by animateFloatAsState(if (showOrb) 1f else 0f, tween(450), label = "dockOrb")
                if (orbScale > 0.01f) {
                    Box(
                        Modifier.size(104.dp).graphicsLayer { scaleX = orbScale; scaleY = orbScale; alpha = orbScale }
                            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOrb),
                        contentAlignment = Alignment.Center,
                    ) { NexaOrb(phase = phase, level = level, size = 104.dp) }
                } else Spacer(Modifier.height(56.dp))
            }
            Spacer(Modifier.size(48.dp))
        }
        AnimatedVisibility(showKeyboard, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ComposerField(onSend)
        }
    }
}

private fun statusText(phase: Phase, listener: String) = when (phase) {
    Phase.IDLE -> "Tocca la sfera per parlare"
    Phase.LISTENING -> if (listener.isNotBlank()) "Ti ascolto · $listener" else "Ti ascolto…"
    Phase.PROCESSING -> "Trascrivo…"
    Phase.THINKING -> "Sto pensando…"
    Phase.SPEAKING -> "Tocca per interrompermi e parlare"
    Phase.CONFIRMING -> "Rispondi “sì” o “no”"
}

@Composable
private fun ComposerField(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text); text = "" } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp)).background(NexaColors.GlassStrong)
            .border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(28.dp))
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Scrivi a Nexa…", style = MaterialTheme.typography.bodyLarge, color = NexaColors.TextTertiary)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NexaColors.TextPrimary),
                cursorBrush = SolidColor(NexaColors.Cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (text.isNotBlank()) NexaColors.AccentGradient else SolidColor(NexaColors.Glass))
                .clickable(enabled = text.isNotBlank()) { send() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Invia", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun UpdateBanner(update: UpdateManager.State, app: NexaApp) {
    val scope = rememberCoroutineScope()
    val visible = update is UpdateManager.State.Available || update is UpdateManager.State.Downloading ||
        update is UpdateManager.State.NeedsPermission || update is UpdateManager.State.Installing
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), strong = true, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, null, tint = NexaColors.Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val title = when (update) {
                        is UpdateManager.State.Available -> "Nuova versione ${update.release.versionName}"
                        is UpdateManager.State.Downloading -> "Scarico l'aggiornamento… ${(update.progress * 100).toInt()}%"
                        is UpdateManager.State.NeedsPermission -> "Consenti a Nexa di installare aggiornamenti"
                        is UpdateManager.State.Installing -> "Installazione in corso…"
                        else -> ""
                    }
                    Text(title, style = MaterialTheme.typography.labelLarge, color = NexaColors.TextPrimary)
                    if (update is UpdateManager.State.Available && update.release.notes.isNotBlank()) {
                        Text(update.release.notes, style = MaterialTheme.typography.bodyMedium, color = NexaColors.TextSecondary, maxLines = 2)
                    }
                    if (update is UpdateManager.State.Downloading) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth(), color = NexaColors.Cyan)
                    }
                }
                when (update) {
                    is UpdateManager.State.Available ->
                        SmallGradientButton("Aggiorna") { scope.launch { app.updates.downloadAndInstall(update.release) } }
                    is UpdateManager.State.NeedsPermission ->
                        SmallGradientButton("Consenti") { app.updates.openInstallPermissionSettings() }
                    else -> Unit
                }
            }
        }
    }
}
