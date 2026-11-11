package com.nexa.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.assistant.ChatLine
import com.nexa.assistant.MemyApp
import com.nexa.assistant.Phase
import com.nexa.assistant.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

private val toolLabels = mapOf(
    "web_search" to "Cerco sul web",
    "get_device_status" to "Controllo il telefono",
    "open_app" to "Apro l'app",
    "list_apps" to "Guardo le app installate",
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
    "read_screen" to "Leggo lo schermo",
    "look_at_screen" to "Guardo lo schermo",
    "tap_element" to "Tocco lo schermo",
    "tap_position" to "Tocco lo schermo",
    "type_text" to "Scrivo",
    "scroll" to "Scorro",
    "press_button" to "Premo un tasto",
    "wait" to "Aspetto",
    "read_notifications" to "Leggo le notifiche",
    "reply_to_notification" to "Preparo la risposta",
    "open_notification" to "Apro la notifica",
    "dismiss_notifications" to "Cancello le notifiche",
    "get_now_playing" to "Controllo cosa suona",
    "read_calendar" to "Guardo il calendario",
    "create_calendar_event" to "Creo l'evento",
    "read_sms" to "Leggo i messaggi",
    "read_call_log" to "Guardo le chiamate",
    "get_location" to "Trovo la posizione",
    "set_reminder" to "Creo il promemoria",
    "list_reminders" to "Guardo i promemoria",
    "delete_reminder" to "Elimino il promemoria",
    "remember" to "Me lo segno",
    "forget" to "Dimentico",
    "set_brightness" to "Regolo la luminosità",
    "set_sound_mode" to "Cambio la suoneria",
    "set_do_not_disturb" to "Non disturbare",
    "copy_to_clipboard" to "Copio",
    "play_music" to "Avvio la musica",
    "open_camera" to "Apro la fotocamera",
    "compose_email" to "Preparo l'email",
    "share_text" to "Condivido",
)

private val suggestions = listOf(
    "Ciao Memy, come stai?",
    "Che tempo fa domani?",
    "Leggimi le notifiche",
    "Cosa ho in calendario?",
    "Ricordami di chiamare mamma alle 18",
    "Apri WhatsApp",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(app: MemyApp, onOrb: () -> Unit, onOpenSettings: () -> Unit) {
    val controller = app.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val level by controller.level.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val chatting = state.lines.isNotEmpty()
    val typing = WindowInsets.isImeVisible

    val orbClick: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onOrb()
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar(
                modelLabel = settings.model.label,
                phase = state.phase,
                chatting = chatting,
                onNew = controller::newConversation,
                onSettings = onOpenSettings,
            )

            UpdateBanner(update, app)

            // La sfera resta sempre in alto: grande all'inizio, più piccola quando c'è una conversazione.
            val orbSize by animateDpAsState(
                when {
                    typing -> 84.dp
                    chatting -> 150.dp
                    else -> 262.dp
                },
                spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow), label = "orbSize",
            )
            if (!chatting && !typing) Spacer(Modifier.weight(0.35f))
            Box(Modifier.size(orbSize).pressable(onClick = orbClick), contentAlignment = Alignment.Center) {
                MemyOrb(phase = state.phase, level = level, size = orbSize)
            }
            StatusLabel(
                phase = state.phase,
                name = settings.userName,
                chatting = chatting,
                partial = if (chatting) "" else state.partial,
                listenerName = state.listenerName,
                wakeWord = settings.wakeWord,
                bargeIn = settings.bargeIn,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = chatting,
                    transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(250)) },
                    label = "main",
                ) { isChatting ->
                    if (isChatting) Conversation(state.lines, state.partial)
                    else if (!typing) Suggestions { controller.submit(it) }
                }
            }

            AnimatedVisibility(
                visible = state.pendingConfirmation != null,
                enter = slideInVertically(spring(dampingRatio = 0.8f)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ConfirmationCard(
                    prompt = state.pendingConfirmation.orEmpty(),
                    onYes = { controller.answerConfirmation(true) },
                    onNo = { controller.answerConfirmation(false) },
                )
            }

            Dock(phase = state.phase, onMic = orbClick, onStop = controller::stopAll, onSend = { controller.submit(it) })
        }
    }
}

@Composable
private fun TopBar(modelLabel: String, phase: Phase, chatting: Boolean, onNew: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GradientText(
                "Memy", MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                brush = Brush.linearGradient(listOf(Color(0xFFC4B5FD), Color(0xFF67E8F9))),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot by animateColorAsState(
                    when (phase) {
                        Phase.IDLE -> MemyColors.Mint
                        Phase.LISTENING, Phase.CONFIRMING -> MemyColors.Cyan
                        else -> MemyColors.Pink
                    },
                    tween(400), label = "dot",
                )
                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text("Claude $modelLabel", style = MaterialTheme.typography.labelMedium, color = MemyColors.TextTertiary)
            }
        }
        AnimatedVisibility(chatting, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            CircleIconButton(Icons.Filled.Add, "Nuova conversazione", onNew, Modifier.padding(end = 10.dp), size = 40.dp)
        }
        CircleIconButton(Icons.Outlined.Settings, "Impostazioni", onSettings, size = 40.dp)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..12 -> "Buongiorno"
    in 13..17 -> "Buon pomeriggio"
    else -> "Buonasera"
}

/** Sotto la sfera: cosa sta facendo Memy, con un passaggio morbido da uno stato all'altro. */
@Composable
private fun StatusLabel(
    phase: Phase, name: String, chatting: Boolean, partial: String, listenerName: String, wakeWord: Boolean, bargeIn: Boolean,
) {
    val title = when (phase) {
        Phase.IDLE -> if (chatting) "" else "${greeting()}${if (name.isNotBlank()) ", $name" else ""}"
        Phase.LISTENING -> "Ti ascolto…"
        Phase.PROCESSING -> "Trascrivo…"
        Phase.THINKING -> "Ci penso…"
        Phase.SPEAKING -> ""
        Phase.CONFIRMING -> "Rispondi sì o no"
    }
    val subtitle = when (phase) {
        Phase.IDLE -> if (wakeWord) "Tocca la sfera o dì «Ehi Memy»" else "Tocca la sfera e parlami"
        Phase.LISTENING -> partial.ifBlank { if (listenerName.isNotBlank()) "con $listenerName" else "" }
        Phase.THINKING -> if (bargeIn && chatting) "Parlami se vuoi fermarmi o cambiare qualcosa" else ""
        Phase.SPEAKING -> if (bargeIn) "Parla o tocca la sfera per interrompermi" else "Tocca la sfera per interrompermi"
        else -> ""
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 2.dp, bottom = 10.dp)
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(tween(350)) + slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith fadeOut(tween(180))
            },
            label = "title",
        ) { text ->
            if (text.isNotEmpty()) {
                Text(
                    text, style = if (chatting) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    color = MemyColors.TextPrimary, textAlign = TextAlign.Center,
                )
            }
        }
        AnimatedContent(
            targetState = subtitle,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
            label = "subtitle",
        ) { text ->
            if (text.isNotEmpty()) {
                Text(
                    text, style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextSecondary,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(onSuggestion: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp),
    ) {
        suggestions.forEachIndexed { i, s ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(90L * i); visible = true }
            val p by animateFloatAsState(if (visible) 1f else 0f, tween(520, easing = FastOutSlowInEasing), label = "chip$i")
            Box(
                Modifier.graphicsLayer { alpha = p; translationY = (1f - p) * 28f }
                    .pressable { onSuggestion(s) }
                    .clip(RoundedCornerShape(50))
                    .background(MemyColors.Glass)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(s, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Conversation(lines: List<ChatLine>, partial: String) {
    val listState = rememberLazyListState()
    val itemCount = lines.size + if (partial.isNotBlank()) 1 else 0
    val last = lines.lastOrNull()
    // Resta agganciata al fondo mentre il testo arriva.
    LaunchedEffect(itemCount, last?.text?.length, last?.tool, partial.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1, scrollOffset = 100_000)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(lines, key = { it.id }) { line -> MessageBubble(line) }
        if (partial.isNotBlank()) {
            item(key = "partial") {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        partial,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MemyColors.TextSecondary,
                        modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(22.dp)).border(1.dp, MemyColors.GlassBorder, RoundedCornerShape(22.dp))
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
    val p by animateFloatAsState(
        if (appeared) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "bubble",
    )
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = p.coerceIn(0f, 1f); translationY = (1f - p) * 36f
        }
    ) {
        when {
            line.isNotice -> Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                    .background(MemyColors.Amber.copy(alpha = 0.12f))
                    .border(1.dp, MemyColors.Amber.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = MemyColors.Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MemyColors.Amber)
            }
            line.fromUser -> Text(
                line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(MemyColors.UserBubble)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
            else -> AssistantBubble(line, Modifier.align(Alignment.CenterStart))
        }
    }
}

/** Risposta di Memy. Mentre lavora, dentro la stessa bolla compare cosa sta facendo: niente più elementi sovrapposti. */
@Composable
private fun AssistantBubble(line: ChatLine, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)
    Row(modifier, verticalAlignment = Alignment.Top) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(shape)
                .background(MemyColors.Glass)
                .border(1.dp, MemyColors.GlassBorder, shape)
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tool = line.tool
            if (line.streaming && tool != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (tool == "web_search") Icons.Filled.Language else Icons.Filled.Tune, null,
                        tint = MemyColors.Cyan, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        toolLabels[tool] ?: "Al lavoro", style = MaterialTheme.typography.labelMedium, color = MemyColors.Cyan,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(10.dp))
                    TypingDots(color = MemyColors.Cyan, dot = 5.dp)
                }
            }
            when {
                line.text.isNotBlank() -> Text(line.text, style = MaterialTheme.typography.bodyLarge, color = MemyColors.TextPrimary)
                line.streaming && tool == null -> TypingDots(Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun ConfirmationCard(prompt: String, onYes: () -> Unit, onNo: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), strong = true) {
        Text("Serve la tua conferma", style = MaterialTheme.typography.labelMedium, color = MemyColors.Cyan)
        Spacer(Modifier.height(6.dp))
        Text(prompt, style = MaterialTheme.typography.bodyLarge, color = MemyColors.TextPrimary)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton("Sì, procedi", onYes, Modifier.weight(1f))
            OutlineButton("Annulla", onNo, Modifier.weight(1f))
        }
    }
}

/** Barra in basso: scrivi a Memy, oppure tocca il microfono per parlarle. Mentre lavora il pulsante diventa "ferma". */
@Composable
private fun Dock(phase: Phase, onMic: () -> Unit, onStop: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text); text = "" } }
    val busy = phase == Phase.THINKING || phase == Phase.SPEAKING || phase == Phase.PROCESSING
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp)
            .clip(shape).background(MemyColors.Glass).border(1.dp, MemyColors.GlassBorder, shape)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Scrivi a Memy…", style = MaterialTheme.typography.bodyLarge, color = MemyColors.TextTertiary)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MemyColors.TextPrimary),
                cursorBrush = SolidColor(MemyColors.Cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val mode = when {
            text.isNotBlank() -> 0
            busy -> 1
            else -> 2
        }
        val listening = phase == Phase.LISTENING || phase == Phase.CONFIRMING
        val scale by animateFloatAsState(if (listening) 1.1f else 1f, spring(dampingRatio = 0.5f), label = "mic")
        Box(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                .pressable { when (mode) { 0 -> send(); 1 -> onStop(); else -> onMic() } }
                .size(44.dp).clip(CircleShape)
                .background(if (mode == 1) SolidColor(MemyColors.Pink.copy(alpha = 0.22f)) else MemyColors.AccentGradient),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.6f)) },
                label = "dockIcon",
            ) { m ->
                when (m) {
                    0 -> Icon(Icons.AutoMirrored.Filled.Send, "Invia", tint = Color.White, modifier = Modifier.size(20.dp))
                    1 -> Icon(Icons.Filled.Stop, "Ferma", tint = MemyColors.Pink, modifier = Modifier.size(22.dp))
                    else -> Icon(Icons.Filled.Mic, if (listening) "Smetti di ascoltare" else "Parla", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(update: UpdateManager.State, app: MemyApp) {
    val scope = rememberCoroutineScope()
    val visible = update is UpdateManager.State.Available || update is UpdateManager.State.Downloading ||
        update is UpdateManager.State.NeedsPermission || update is UpdateManager.State.Installing
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), strong = true, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, null, tint = MemyColors.Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val title = when (update) {
                        is UpdateManager.State.Available -> "Nuova versione ${update.release.versionName}"
                        is UpdateManager.State.Downloading -> "Scarico l'aggiornamento… ${(update.progress * 100).toInt()}%"
                        is UpdateManager.State.NeedsPermission -> "Consenti a Memy di installare aggiornamenti"
                        is UpdateManager.State.Installing -> "Installazione in corso…"
                        else -> ""
                    }
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
                    if (update is UpdateManager.State.Available && update.release.notes.isNotBlank()) {
                        Text(
                            update.release.notes, style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (update is UpdateManager.State.Downloading) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { update.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = MemyColors.Cyan, trackColor = MemyColors.Glass,
                        )
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
