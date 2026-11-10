package com.nexa.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.assistant.ChatLine
import com.nexa.assistant.NexaController
import com.nexa.assistant.Phase

private val toolLabels = mapOf(
    "get_device_status" to "Controllo il telefono…",
    "open_app" to "Apro l'app…",
    "set_alarm" to "Imposto la sveglia…",
    "set_timer" to "Avvio il timer…",
    "find_contact" to "Cerco in rubrica…",
    "make_call" to "Preparo la chiamata…",
    "send_sms" to "Preparo l'SMS…",
    "set_flashlight" to "Torcia…",
    "set_volume" to "Regolo il volume…",
    "media_control" to "Controllo la musica…",
    "open_settings" to "Apro le impostazioni…",
    "open_web" to "Apro il browser…",
    "navigate_to" to "Apro le mappe…",
    "create_calendar_event" to "Preparo l'evento…",
)

@Composable
fun HomeScreen(
    controller: NexaController,
    onMic: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexaColors.Background)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        // Intestazione
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = controller::newConversation) {
                Icon(Icons.Filled.Add, contentDescription = "Nuova conversazione", tint = NexaColors.TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            Text("Nexa", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = NexaColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Impostazioni", tint = NexaColors.TextSecondary)
            }
        }

        // Conversazione
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.lines.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Ciao! Tocca il microfono e parlami.", color = NexaColors.TextPrimary, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "\"Metti una sveglia domani alle 7\"",
                        "\"Accendi la torcia\"",
                        "\"Chiama la mamma\"",
                        "\"Apri WhatsApp\"",
                        "\"Che ore sono?\"",
                    ).forEach { Text(it, color = NexaColors.TextSecondary, fontSize = 15.sp) }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.lines) { ChatBubble(it) }
                if (state.partial.isNotBlank()) {
                    item { ChatBubble(ChatLine(fromUser = true, text = state.partial + "…")) }
                }
            }
        }

        // Conferma azione delicata
        state.pendingConfirmation?.let { prompt ->
            Column(
                Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp))
                    .background(NexaColors.SurfaceHigh).padding(16.dp),
            ) {
                Text(prompt, color = NexaColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { controller.answerConfirmation(true) }) { Text("Sì, procedi") }
                    OutlinedButton(onClick = { controller.answerConfirmation(false) }) { Text("No, annulla") }
                }
            }
        }

        // Stato + microfono
        Text(
            text = when (state.phase) {
                Phase.IDLE -> "Tocca per parlare"
                Phase.LISTENING -> "Ti ascolto…"
                Phase.THINKING -> state.toolInUse?.let { toolLabels[it] } ?: "Ci penso…"
                Phase.SPEAKING -> "Sto rispondendo — tocca per interrompere"
                Phase.CONFIRMING -> "Dì \"sì\" o \"no\""
            },
            color = NexaColors.TextSecondary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
        MicOrb(phase = state.phase, onClick = onMic, modifier = Modifier.align(Alignment.CenterHorizontally))

        // Comando scritto (utile quando non si può parlare)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Oppure scrivi un comando…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    controller.submit(typed); typed = ""
                }),
            )
            IconButton(onClick = { controller.submit(typed); typed = "" }, enabled = typed.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Invia", tint = NexaColors.Accent)
            }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val color = when {
        line.isNotice -> NexaColors.Warning.copy(alpha = 0.15f)
        line.fromUser -> NexaColors.Accent.copy(alpha = 0.22f)
        else -> NexaColors.Surface
    }
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = line.text,
            color = if (line.isNotice) NexaColors.Warning else NexaColors.TextPrimary,
            fontSize = 16.sp,
            modifier = Modifier
                .align(if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(color)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun MicOrb(phase: Phase, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (phase == Phase.IDLE) 1f else 1.12f,
        animationSpec = infiniteRepeatable(tween(if (phase == Phase.LISTENING) 600 else 1100), RepeatMode.Reverse),
        label = "pulse",
    )
    val colors = when (phase) {
        Phase.LISTENING, Phase.CONFIRMING -> listOf(NexaColors.Listening, NexaColors.Accent)
        Phase.THINKING -> listOf(NexaColors.AccentWarm, NexaColors.Accent)
        Phase.SPEAKING -> listOf(NexaColors.Accent, NexaColors.AccentWarm)
        Phase.IDLE -> listOf(NexaColors.Accent, Color(0xFF5468C9))
    }
    Box(
        modifier = modifier.padding(16.dp).size(96.dp).scale(pulse).clip(CircleShape)
            .background(Brush.linearGradient(colors)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (phase == Phase.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
            contentDescription = if (phase == Phase.IDLE) "Parla" else "Ferma",
            tint = NexaColors.Background,
            modifier = Modifier.size(40.dp),
        )
    }
}

