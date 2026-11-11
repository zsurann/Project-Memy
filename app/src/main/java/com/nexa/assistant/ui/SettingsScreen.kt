package com.nexa.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.assistant.AssistantRole
import com.nexa.assistant.MemyApp
import com.nexa.assistant.MemyWakeService
import com.nexa.assistant.access.Capabilities
import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.data.SttEngine
import com.nexa.assistant.speech.EdgeVoice
import com.nexa.assistant.speech.VoicePlayer
import com.nexa.assistant.speech.WakeSensitivity
import com.nexa.assistant.update.UpdateManager
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(app: MemyApp, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val ui by app.controller.state.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    val memories by app.memory.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleOnDevice = remember { app.controller.googleDevice.isReady() }

    // Ricontrolla permessi e "poteri" ogni volta che si torna dalle impostazioni di sistema.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    BackHandler(enabled = settings.hasApiKey) {
        app.controller.voice.stopPreview()
        onBack()
    }

    AuroraBackground(intensity = 0.6f) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (settings.hasApiKey) {
                    CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", {
                        app.controller.voice.stopPreview(); onBack()
                    }, size = 42.dp, tint = MemyColors.TextPrimary)
                    Spacer(Modifier.width(14.dp))
                }
                Text("Impostazioni", style = MaterialTheme.typography.headlineSmall, color = MemyColors.TextPrimary)
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!settings.hasApiKey) {
                    GlassCard(Modifier.fillMaxWidth(), strong = true) {
                        GradientText("Benvenuto in Memy", MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Hint("Per iniziare serve solo la chiave di Claude. Tutto il resto è facoltativo e puoi configurarlo dopo.")
                    }
                }

                // ------------------------------------------------ Chiavi
                SectionCard(
                    Icons.Filled.Key, "Chiavi",
                    when {
                        !settings.hasApiKey -> "Serve la chiave di Claude per iniziare"
                        settings.hasGroqKey -> "Claude ✓ · Groq ✓"
                        else -> "Claude ✓ · Groq (gratis) da aggiungere"
                    },
                    initiallyExpanded = !settings.hasApiKey, attention = !settings.hasApiKey,
                ) {
                    SecretField(
                        title = "Claude (Anthropic) — il cervello di Memy",
                        saved = settings.hasApiKey,
                        placeholder = "sk-ant-…",
                        onSave = app.settings::setApiKey,
                        onHelp = { context.openUrl("https://console.anthropic.com/settings/keys") },
                        required = true,
                    )
                    SecretField(
                        title = "Groq — ascolto con Whisper (gratis)",
                        saved = settings.hasGroqKey,
                        placeholder = "gsk_…",
                        onSave = app.settings::setGroqKey,
                        onHelp = { context.openUrl("https://console.groq.com/keys") },
                        note = "Crea un account gratuito su console.groq.com (non serve la carta) e genera una API key. " +
                            "Il piano gratuito permette 2.000 frasi al giorno.",
                    )
                    Hint("Le chiavi restano cifrate solo su questo telefono. La voce di Memy è gratuita e non richiede chiavi.")
                }

                // ------------------------------------------------ Cervello
                SectionCard(
                    Icons.Filled.Psychology, "Cervello",
                    "${settings.model.label} · ricerca web ${if (settings.webSearch) "attiva" else "spenta"}",
                ) {
                    ClaudeModel.entries.forEach { m ->
                        Choice(settings.model == m, m.label, m.description) { app.settings.setModel(m) }
                    }
                    SwitchRow(
                        "Ricerca sul web",
                        "Notizie, meteo, risultati e fatti aggiornati, con tutti i modelli.",
                        settings.webSearch, app.settings::setWebSearch,
                    )
                }

                // ------------------------------------------------ Voce
                VoiceSection(app)

                // ------------------------------------------------ Ascolto
                val canHear = settings.hasGroqKey || ui.asrModelInstalled || googleOnDevice
                SectionCard(
                    Icons.Filled.Mic, "Ascolto",
                    if (canHear) settings.sttEngine.label else "Aggiungi Groq o scarica Parakeet per farti sentire",
                    attention = !canHear,
                ) {
                    SttEngine.entries.forEach { e ->
                        val badge = when (e) {
                            SttEngine.WHISPER -> if (settings.hasGroqKey) "pronto" else "serve chiave gratuita"
                            SttEngine.GOOGLE_DEVICE -> if (googleOnDevice) "disponibile" else "non installato"
                            SttEngine.PARAKEET -> if (ui.asrModelInstalled) "installato" else "da scaricare"
                            SttEngine.AUTO -> null
                        }
                        Choice(settings.sttEngine == e, e.label, e.description, badge) { app.settings.setSttEngine(e) }
                    }
                    Hint(
                        "Consiglio: usa Whisper (il più preciso) e tieni scaricato anche Parakeet: mentre parli mostra il " +
                            "testo in tempo reale e ti capisce anche quando non c'è internet."
                    )
                    val progress = ui.modelDownloadProgress
                    when {
                        progress != null -> {
                            Hint("Scarico il modello Parakeet… ${(progress * 100).toInt()}% (circa 490 MB: tieni Memy aperta)")
                            LinearProgressIndicator(
                                progress = { progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                color = MemyColors.Cyan, trackColor = MemyColors.Glass,
                            )
                        }
                        !ui.asrModelInstalled -> {
                            Hint(
                                "Parakeet è il modello di riconoscimento vocale più preciso che può girare sul telefono: " +
                                    "capisce bene l'italiano, mette la punteggiatura e funziona anche senza internet. " +
                                    "Si scarica una volta sola (meglio con il Wi-Fi)."
                            )
                            GradientButton("Scarica Parakeet (~490 MB)", app.controller::downloadAsrModel, Modifier.fillMaxWidth())
                        }
                        else -> Text(
                            "Rimuovi il modello", style = MaterialTheme.typography.labelMedium, color = MemyColors.TextTertiary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { app.controller.deleteAsrModel() }.padding(vertical = 4.dp),
                        )
                    }
                }

                // ------------------------------------------------ Poteri
                PowersSection(context, tick)

                // ------------------------------------------------ Conversazione e memoria
                SectionCard(
                    Icons.Filled.Forum, "Conversazione e memoria",
                    if (memories.isEmpty()) "Nessun ricordo salvato" else "${memories.size} ricord${if (memories.size == 1) "o" else "i"} salvat${if (memories.size == 1) "o" else "i"}",
                ) {
                    PlainField("Come ti chiami?", settings.userName, app.settings::setUserName)
                    SwitchRow(
                        "Conversazione continua",
                        "Dopo ogni risposta Memy torna ad ascoltarti, come in una vera chiacchierata.",
                        settings.continuousConversation, app.settings::setContinuous,
                    )
                    Text("Cosa ricorda Memy di te", style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
                    if (memories.isEmpty()) {
                        Hint("Ancora niente. Dille ad esempio: \"Ricordati che mia sorella si chiama Giulia\".")
                    } else memories.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MemyColors.Glass).padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(m, style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextPrimary, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                            Box(Modifier.size(40.dp).clip(CircleShape).clickable { app.memory.remove(m) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, "Dimentica", tint = MemyColors.TextTertiary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                WakeSection(app, context, tick)

                // ------------------------------------------------ OriginOS
                SectionCard(
                    Icons.Filled.BatteryChargingFull, "Sempre pronta",
                    if (context.ignoresBatteryOptimizations()) "Batteria ottimizzata per Memy ✓" else "Evita che il telefono chiuda Memy",
                ) {
                    Hint(
                        "OriginOS chiude spesso le app in background. Per evitarlo:\n" +
                            "• Batteria: consenti il consumo in background (pulsante qui sotto).\n" +
                            "• i Manager → Gestione app → Avvio automatico → attiva Memy.\n" +
                            "• Nelle app recenti, blocca Memy con il lucchetto.\n" +
                            "• Impostazioni rapide → modifica → aggiungi \"Ehi Memy\"."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("Batteria", { context.requestIgnoreBattery() }, Modifier.weight(1f))
                        OutlineButton("Info app", { context.openAppDetails() }, Modifier.weight(1f))
                    }
                }

                // ------------------------------------------------ Aggiornamenti
                SectionCard(Icons.Filled.SystemUpdate, "Aggiornamenti", "Versione ${app.updates.currentVersionName}") {
                    SwitchRow(
                        "Aggiornamento automatico",
                        "Quando esce una nuova versione, Memy la scarica (con il Wi-Fi) e la installa da sola.",
                        settings.autoUpdate, app.settings::setAutoUpdate,
                    )
                    val status = when (val u = update) {
                        UpdateManager.State.Idle -> null
                        UpdateManager.State.Checking -> "Controllo…"
                        UpdateManager.State.UpToDate -> "Hai già l'ultima versione."
                        is UpdateManager.State.Available -> "Disponibile la versione ${u.release.versionName}."
                        is UpdateManager.State.Downloading -> "Download ${(u.progress * 100).toInt()}%"
                        UpdateManager.State.Installing -> "Installazione in corso…"
                        is UpdateManager.State.NeedsPermission -> "Consenti a Memy di installare app, poi riprova."
                        is UpdateManager.State.Failed -> u.message
                    }
                    status?.let { Hint(it, warning = update is UpdateManager.State.Failed) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("Controlla ora", {
                            scope.launch { app.updates.check()?.let { app.updates.downloadAndInstall(it) } }
                        }, Modifier.weight(1f))
                        OutlineButton("Permesso", app.updates::openInstallPermissionSettings, Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- voce

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSection(app: MemyApp) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val catalog by app.controller.voice.catalog.collectAsStateWithLifecycle()
    val preview by app.controller.voice.preview.collectAsStateWithLifecycle()
    val voice = app.controller.voice
    var showAll by remember { mutableStateOf(false) }

    SectionCard(Icons.Filled.GraphicEq, "Voce", "${settings.voiceName} · gratuita") {
        Hint("Voci neurali di Microsoft Edge: naturali, gratuite e senza limiti. Tocca ▶ per ascoltare un'anteprima in italiano.")

        val italian = catalog.voices.filter { it.isItalian }
        val multilingual = catalog.voices.filter { !it.isItalian }

        Text("Voci italiane", style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
        italian.forEach { v ->
            VoiceRow(v, v.id == settings.voiceId, preview?.takeIf { it.voiceId == v.id }, { app.settings.setVoice(v.id, v.name) }) { voice.togglePreview(v) }
        }

        if (multilingual.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showAll = !showAll }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Voci multilingue (${multilingual.size})", style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
                    Hint("Parlano anche italiano, con un leggero accento straniero.")
                }
                val rotation by animateFloatAsState(if (showAll) 180f else 0f, tween(250), label = "multi")
                Icon(
                    Icons.Filled.KeyboardArrowDown, null, tint = MemyColors.TextSecondary,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            AnimatedVisibility(showAll, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    multilingual.forEach { v ->
                        VoiceRow(v, v.id == settings.voiceId, preview?.takeIf { it.voiceId == v.id }, { app.settings.setVoice(v.id, v.name) }) { voice.togglePreview(v) }
                    }
                }
            }
        }
        if (catalog.loading) Hint("Aggiorno l'elenco delle voci…")
        catalog.error?.let { Hint(it, warning = true) }

        Spacer(Modifier.height(2.dp))
        Text(
            "Velocità  ${if (settings.voiceRate >= 0) "+" else ""}${settings.voiceRate}%",
            style = MaterialTheme.typography.labelMedium, color = MemyColors.TextSecondary,
        )
        Slider(
            value = settings.voiceRate.toFloat(), onValueChange = { app.settings.setVoiceRate((it / 5).toInt() * 5) },
            valueRange = -30f..40f, colors = sliderColors(),
        )
        Text(
            "Tono  ${if (settings.voicePitch >= 0) "+" else ""}${settings.voicePitch}",
            style = MaterialTheme.typography.labelMedium, color = MemyColors.TextSecondary,
        )
        Slider(
            value = settings.voicePitch.toFloat(), onValueChange = { app.settings.setVoicePitch((it / 2).toInt() * 2) },
            valueRange = -20f..20f, colors = sliderColors(),
        )
        if (settings.voiceRate != 0 || settings.voicePitch != 0) {
            Text(
                "Ripristina", style = MaterialTheme.typography.labelMedium, color = MemyColors.Cyan,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    app.settings.setVoiceRate(0); app.settings.setVoicePitch(0)
                }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = MemyColors.Cyan, activeTrackColor = MemyColors.Violet, inactiveTrackColor = MemyColors.Glass,
)

private val personalityIt = mapOf(
    "Friendly" to "cordiale", "Positive" to "solare", "Warm" to "calda", "Confident" to "sicura", "Caring" to "premurosa",
    "Pleasant" to "piacevole", "Authentic" to "autentica", "Expressive" to "espressiva", "Calm" to "calma", "Clear" to "chiara",
    "Bright" to "brillante", "Cheerful" to "allegra", "Approachable" to "accogliente", "Engaging" to "coinvolgente",
    "Casual" to "informale", "Sincere" to "sincera", "Passion" to "appassionata", "Considerate" to "attenta",
    "Comfort" to "rassicurante", "Lively" to "vivace", "Rational" to "razionale", "Upbeat" to "vivace",
)

private fun describe(v: EdgeVoice): String {
    val gender = if (v.gender == "Female") "donna" else "uomo"
    val origin = when {
        v.isItalian -> null
        v.locale.startsWith("en-US") -> "americana"
        v.locale.startsWith("en-AU") -> "australiana"
        v.locale.startsWith("fr") -> "francese"
        v.locale.startsWith("de") -> "tedesca"
        v.locale.startsWith("pt") -> "brasiliana"
        v.locale.startsWith("ko") -> "coreana"
        else -> v.locale
    }
    val traits = v.personalities.mapNotNull { personalityIt[it] }.take(2)
    return (listOf(gender) + traits + listOfNotNull(origin)).distinct().joinToString(" · ")
}

@Composable
private fun VoiceRow(
    voice: EdgeVoice,
    selected: Boolean,
    preview: VoicePlayer.Preview?,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    val border by animateColorAsState(if (selected) MemyColors.Violet else MemyColors.GlassBorder, label = "voiceBorder")
    val bg by animateColorAsState(if (selected) MemyColors.Violet.copy(alpha = 0.16f) else Color.Transparent, label = "voiceBg")
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, border, shape).clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = if (voice.gender == "Female") listOf(MemyColors.Pink, MemyColors.Violet) else listOf(MemyColors.Cyan, MemyColors.Indigo)
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, "Selezionata", tint = Color.White, modifier = Modifier.size(20.dp))
            else Text(voice.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(voice.name, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(describe(voice), style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.pressable(onClick = onPreview).size(40.dp).clip(CircleShape)
                .background(if (preview != null) MemyColors.AccentGradient else SolidColor(MemyColors.GlassStrong)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preview?.loading == true -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                preview != null -> EqualizerBars(playing = true)
                else -> Icon(Icons.Filled.PlayArrow, "Ascolta anteprima", tint = MemyColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- poteri

@OptIn(ExperimentalLayoutApi::class)
/** "Ehi Memy" sempre in ascolto e Memy come assistente predefinito del telefono. */
@Composable
private fun WakeSection(app: MemyApp, context: Context, tick: Int) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val isAssistant = remember(tick) { AssistantRole.isHeld(context) }
    val enable = {
        app.settings.setWakeWord(true)
        MemyWakeService.sync(context)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || context.granted(Manifest.permission.RECORD_AUDIO)) enable()
    }
    SectionCard(
        Icons.Filled.RecordVoiceOver, "Ehi Memy",
        when {
            settings.wakeWord && isAssistant -> "Sempre in ascolto · assistente del telefono ✓"
            settings.wakeWord -> "Sempre in ascolto ✓"
            else -> "Chiamala a voce, anche ad app chiusa"
        },
    ) {
        SwitchRow(
            "«Ehi Memy» sempre attiva",
            "Dì «Ehi Memy» in qualsiasi momento, anche con l'app chiusa o lo schermo spento, e poi il comando.",
            settings.wakeWord,
        ) { on ->
            if (!on) {
                app.settings.setWakeWord(false)
                MemyWakeService.sync(context)
            } else {
                val needed = buildList {
                    if (!context.granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (needed.isEmpty()) enable() else launcher.launch(needed.toTypedArray())
            }
        }
        if (settings.wakeWord) {
            Text("Sensibilità", style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeSensitivity.entries.forEach { s ->
                    val selected = settings.wakeSensitivity == s
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).clip(shape)
                            .background(if (selected) MemyColors.Glass else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, if (selected) MemyColors.Mint else MemyColors.GlassBorder, shape)
                            .clickable { app.settings.setWakeSensitivity(s) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (selected) MemyColors.TextPrimary else MemyColors.TextSecondary)
                    }
                }
            }
            Hint(
                "Se Memy non ti sente scegli \"Alta\"; se si attiva da sola scegli \"Bassa\". " +
                    "L'ascolto avviene tutto sul telefono: nessun audio viene inviato finché non dici «Ehi Memy». " +
                    "Resta una notifica fissa e il consumo di batteria aumenta un po'."
            )
        }
        PowerRow(
            "Assistente del telefono",
            if (isAssistant) "Memy è l'assistente predefinito: si apre con il gesto o il tasto dell'assistente."
            else "Scegli Memy come app assistente digitale al posto di quella del telefono.",
            isAssistant,
        ) {
            if (!AssistantRole.openSettings(context)) context.openAppDetails()
        }
        if (!isAssistant) {
            Hint("Nella schermata che si apre: App assistente digitale → Memy. Su alcuni telefoni la voce si trova in Impostazioni → App → App predefinite.")
        }
    }
}

@Composable
private fun PowersSection(context: Context, tick: Int) {
    val perms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO to "Microfono")
            add(Manifest.permission.READ_CONTACTS to "Contatti")
            add(Manifest.permission.CALL_PHONE to "Telefono")
            add(Manifest.permission.SEND_SMS to "Invio SMS")
            add(Manifest.permission.READ_SMS to "Lettura SMS")
            add(Manifest.permission.READ_CALL_LOG to "Chiamate recenti")
            add(Manifest.permission.READ_CALENDAR to "Calendario")
            add(Manifest.permission.ACCESS_FINE_LOCATION to "Posizione")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS to "Notifiche")
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    // Leggere tick/refresh fa ricalcolare lo stato quando si torna nell'app o si risponde a una richiesta.
    val stamp = tick + refresh
    val accessibility = remember(stamp) { Capabilities.accessibilityEnabled(context) }
    val notifications = remember(stamp) { Capabilities.notificationsEnabled(context) }
    val writeSettings = remember(stamp) { Capabilities.canWriteSettings(context) }
    val dnd = remember(stamp) { Capabilities.canControlDnd(context) }
    val missing = remember(stamp) { perms.filter { !context.granted(it.first) } }
    val active = listOf(accessibility, notifications, writeSettings, dnd).count { it }

    SectionCard(
        Icons.Filled.AutoAwesome, "Poteri",
        if (accessibility) "$active su 4 attivi · Memy può usare le app" else "Attiva il controllo dello schermo",
        attention = !accessibility,
    ) {
        Hint("Più poteri concedi, più cose Memy può fare da sola. Agisce solo quando glielo chiedi e chiede conferma prima di inviare, pagare o eliminare.")
        PowerRow(
            "Controllo dello schermo", "Legge lo schermo e usa qualsiasi app al posto tuo: scrive messaggi, cerca, tocca, scorre.",
            accessibility,
        ) { context.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (accessibility && com.nexa.assistant.access.MemyAccessibilityService.instance == null) {
            Hint("Risulta attivo, ma il telefono lo ha sospeso: tocca \"Attivo\", spegni Memy e riaccendila. Per evitarlo, blocca Memy con il lucchetto nelle app recenti.", warning = true)
        }
        if (!accessibility) {
            Hint("Nelle impostazioni di accessibilità cerca \"Memy\" tra le app installate o scaricate e attivala. Se l'interruttore è bloccato: Info app → ⋮ → \"Consenti impostazioni con restrizioni\".")
            OutlineButton("Apri Info app", { context.openAppDetails() }, Modifier.fillMaxWidth())
        }
        PowerRow(
            "Notifiche", "Legge le notifiche e risponde ai messaggi di WhatsApp, Telegram e simili.",
            notifications,
        ) { context.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        PowerRow(
            "Impostazioni di sistema", "Regola la luminosità dello schermo.",
            writeSettings,
        ) { context.safeStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        PowerRow(
            "Non disturbare", "Attiva il silenzioso e la modalità Non disturbare.",
            dnd,
        ) { context.safeStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }

        Text("Permessi", style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            perms.forEach { (perm, label) ->
                val ok = perm !in missing.map { it.first }
                Pill {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (ok) MemyColors.Mint else MemyColors.TextTertiary, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MemyColors.TextPrimary)
                    }
                }
            }
        }
        if (missing.isNotEmpty()) {
            GradientButton("Concedi i permessi mancanti", {
                val extra = if (Manifest.permission.ACCESS_FINE_LOCATION in missing.map { it.first }) arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION) else emptyArray()
                val calendar = if (Manifest.permission.READ_CALENDAR in missing.map { it.first }) arrayOf(Manifest.permission.WRITE_CALENDAR) else emptyArray()
                launcher.launch(missing.map { it.first }.toTypedArray() + extra + calendar)
            }, Modifier.fillMaxWidth())
            Hint("Se un permesso viene negato più volte, concedilo da Info app → Permessi.")
        }
    }
}

@Composable
private fun PowerRow(title: String, subtitle: String, enabled: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, MemyColors.GlassBorder, shape).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (enabled) MemyColors.Mint else MemyColors.TextTertiary))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        if (enabled) {
            Text(
                "Attivo", style = MaterialTheme.typography.labelMedium, color = MemyColors.Mint,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MemyColors.Mint.copy(alpha = 0.14f)).clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        } else SmallGradientButton("Attiva", onOpen)
    }
}

// ---------------------------------------------------------------- componenti

@Composable
private fun Choice(selected: Boolean, title: String, subtitle: String, badge: String? = null, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MemyColors.Violet else MemyColors.GlassBorder, label = "choiceBorder")
    val bg by animateColorAsState(if (selected) MemyColors.Violet.copy(alpha = 0.16f) else Color.Transparent, label = "choiceBg")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .border(2.dp, if (selected) MemyColors.Cyan else MemyColors.TextTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val dot by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "dot")
            Box(
                Modifier.size(10.dp).graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot }
                    .clip(CircleShape).background(MemyColors.Cyan)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
                badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = MemyColors.TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MemyColors.Glass).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextSecondary)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MemyColors.TextSecondary)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MemyColors.Violet, checkedThumbColor = Color.White,
                uncheckedTrackColor = MemyColors.Glass, uncheckedBorderColor = MemyColors.GlassBorder, uncheckedThumbColor = MemyColors.TextSecondary,
            ),
        )
    }
}

@Composable
private fun SecretField(
    title: String,
    saved: Boolean,
    placeholder: String,
    onSave: (String) -> Unit,
    onHelp: () -> Unit,
    note: String? = null,
    required: Boolean = false,
) {
    var value by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(!saved && required) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, MemyColors.GlassBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MemyColors.TextPrimary, modifier = Modifier.weight(1f))
            if (saved) Icon(Icons.Filled.CheckCircle, "Salvata", tint = MemyColors.Mint, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(editing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InputBox(value, { value = it }, placeholder, secret = true)
                note?.let { Hint(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton("Salva", { onSave(value); value = ""; editing = false }, Modifier.weight(1f), enabled = value.isNotBlank())
                    OutlineButton("Crea chiave", onHelp, Modifier.weight(1f))
                }
            }
        }
        if (!editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    if (saved) "Cambia" else "Aggiungi", style = MaterialTheme.typography.labelMedium, color = MemyColors.Cyan,
                    modifier = Modifier.clickable { editing = true },
                )
                if (saved) {
                    Text("Rimuovi", style = MaterialTheme.typography.labelMedium, color = MemyColors.TextTertiary, modifier = Modifier.clickable { onSave("") })
                } else {
                    Text("Crea chiave", style = MaterialTheme.typography.labelMedium, color = MemyColors.TextSecondary, modifier = Modifier.clickable(onClick = onHelp))
                }
            }
        }
    }
}

@Composable
private fun PlainField(label: String, current: String, onSave: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MemyColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { InputBox(value, { value = it }, "Il tuo nome", secret = false) }
            if (value != current) SmallGradientButton("Salva") { onSave(value) }
        }
    }
}

@Composable
private fun InputBox(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x33000000))
            .border(1.dp, MemyColors.GlassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MemyColors.TextTertiary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MemyColors.TextPrimary),
            cursorBrush = SolidColor(MemyColors.Cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (warning) MemyColors.Amber else MemyColors.TextSecondary)
}

// ---------------------------------------------------------------- helper di sistema

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun Context.safeStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) {
    false
}

private fun Context.openUrl(url: String) {
    safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun Context.openAppDetails() {
    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

private fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

@SuppressLint("BatteryLife")
private fun Context.requestIgnoreBattery() {
    safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) ||
        safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
