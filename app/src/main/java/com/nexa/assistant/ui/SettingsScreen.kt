package com.nexa.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.assistant.NexaApp
import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.data.SttEngine
import com.nexa.assistant.data.VoiceEngine
import com.nexa.assistant.data.Voices
import com.nexa.assistant.speech.VoicePlayer
import com.nexa.assistant.update.UpdateManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: NexaApp, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val ui by app.controller.state.collectAsStateWithLifecycle()
    val systemVoice by app.controller.voice.systemStatus.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleOnDevice = remember { app.controller.googleDevice.isReady() }

    BackHandler(enabled = settings.hasApiKey, onBack = onBack)

    AuroraBackground(intensity = 0.6f) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = settings.hasApiKey) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = if (settings.hasApiKey) NexaColors.TextPrimary else NexaColors.TextTertiary)
                }
                Text("Impostazioni", style = MaterialTheme.typography.headlineSmall, color = NexaColors.TextPrimary)
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!settings.hasApiKey) {
                    GlassCard(Modifier.fillMaxWidth(), strong = true) {
                        GradientText("Benvenuto in Nexa", MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Hint("Per iniziare serve solo la chiave di Claude. Tutto il resto è facoltativo e puoi configurarlo dopo.")
                    }
                }

                // ------------------------------------------------ Chiavi
                Section(Icons.Filled.Key, "Chiavi dei servizi", "Restano cifrate solo su questo telefono") {
                    SecretField(
                        title = "Claude (Anthropic) — obbligatoria",
                        saved = settings.hasApiKey,
                        placeholder = "sk-ant-…",
                        onSave = app.settings::setApiKey,
                        helpLabel = "Crea chiave",
                        onHelp = { context.openUrl("https://console.anthropic.com/settings/keys") },
                        required = true,
                    )
                    SecretField(
                        title = "Google Cloud — voce e ascolto di alta qualità",
                        saved = settings.hasGoogleKey,
                        placeholder = "AIza…",
                        onSave = app.settings::setGoogleKey,
                        helpLabel = "Crea chiave",
                        onHelp = { context.openUrl("https://console.cloud.google.com/apis/credentials") },
                        note = "Nel progetto attiva \"Cloud Text-to-Speech API\" e \"Cloud Speech-to-Text API\".",
                    )
                    SecretField(
                        title = "OpenAI — facoltativa",
                        saved = settings.hasOpenAiKey,
                        placeholder = "sk-…",
                        onSave = app.settings::setOpenAiKey,
                        helpLabel = "Crea chiave",
                        onHelp = { context.openUrl("https://platform.openai.com/api-keys") },
                    )
                    SecretField(
                        title = "ElevenLabs — facoltativa",
                        saved = settings.hasElevenKey,
                        placeholder = "sk_…",
                        onSave = app.settings::setElevenKey,
                        helpLabel = "Crea chiave",
                        onHelp = { context.openUrl("https://elevenlabs.io/app/settings/api-keys") },
                    )
                }

                // ------------------------------------------------ Cervello
                Section(Icons.Filled.Psychology, "Cervello", "Il modello Claude che ragiona e risponde") {
                    ClaudeModel.entries.forEach { m ->
                        Choice(settings.model == m, m.label, m.description) { app.settings.setModel(m) }
                    }
                    SwitchRow(
                        "Ricerca sul web",
                        "Notizie, meteo, risultati e fatti aggiornati (Opus e Sonnet).",
                        settings.webSearch, app.settings::setWebSearch,
                    )
                }

                // ------------------------------------------------ Ascolto
                Section(Icons.Filled.Mic, "Ascolto", "Come Nexa capisce quello che dici") {
                    SttEngine.entries.forEach { e ->
                        val badge = when (e) {
                            SttEngine.GOOGLE_DEVICE -> if (googleOnDevice) "disponibile" else "non installato"
                            SttEngine.GOOGLE_CLOUD -> if (settings.hasGoogleKey) "pronto" else "serve chiave"
                            SttEngine.OPENAI -> if (settings.hasOpenAiKey) "pronto" else "serve chiave"
                            SttEngine.VOSK -> if (ui.voiceModelInstalled) "installato" else "da scaricare"
                            SttEngine.AUTO -> null
                        }
                        Choice(settings.sttEngine == e, e.label, e.description, badge) { app.settings.setSttEngine(e) }
                    }
                    if (!googleOnDevice) {
                        Hint("Su questo telefono non c'è il riconoscimento di Google (normale sui modelli cinesi). Per la massima precisione usa Google Cloud.")
                    }
                    if (settings.sttEngine == SttEngine.VOSK || settings.sttEngine == SttEngine.AUTO) {
                        val progress = ui.modelDownloadProgress
                        when {
                            progress != null -> {
                                Hint("Scarico il modello offline italiano… ${(progress * 100).toInt()}%")
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = NexaColors.Cyan)
                            }
                            !ui.voiceModelInstalled -> OutlineButton("Scarica modello offline (~50 MB)", app.controller::downloadVoiceModel, Modifier.fillMaxWidth())
                        }
                    }
                }

                // ------------------------------------------------ Voce
                Section(Icons.Filled.GraphicEq, "Voce", "Come suona Nexa quando ti risponde") {
                    VoiceEngine.entries.forEach { e ->
                        val badge = when (e) {
                            VoiceEngine.GOOGLE_CLOUD -> if (settings.hasGoogleKey) "pronta" else "serve chiave"
                            VoiceEngine.OPENAI -> if (settings.hasOpenAiKey) "pronta" else "serve chiave"
                            VoiceEngine.ELEVENLABS -> if (settings.hasElevenKey) "pronta" else "serve chiave"
                            VoiceEngine.SYSTEM -> when (systemVoice) {
                                VoicePlayer.SystemStatus.READY -> "pronta"
                                VoicePlayer.SystemStatus.LOADING -> "…"
                                else -> "manca l'italiano"
                            }
                        }
                        Choice(settings.voiceEngine == e, e.label, e.description, badge) { app.settings.setVoiceEngine(e) }
                    }
                    when (settings.voiceEngine) {
                        VoiceEngine.GOOGLE_CLOUD -> VoicePicker(Voices.google, settings.googleVoice, app.settings::setGoogleVoice)
                        VoiceEngine.OPENAI -> VoicePicker(Voices.openai, settings.openAiVoice, app.settings::setOpenAiVoice)
                        VoiceEngine.ELEVENLABS -> {
                            PlainField("ID voce ElevenLabs", settings.elevenVoiceId, app.settings::setElevenVoiceId)
                            Hint("Scegli una voce italiana dalla Voice Library di ElevenLabs e incolla qui il suo ID.")
                        }
                        VoiceEngine.SYSTEM -> if (systemVoice != VoicePlayer.SystemStatus.READY) {
                            Hint("Il telefono non ha una voce italiana: installa un motore di sintesi vocale o usa una voce cloud.", warning = true)
                        }
                    }
                    Text("Velocità  ${"%.2f".format(settings.speechRate)}×", style = MaterialTheme.typography.labelMedium, color = NexaColors.TextSecondary)
                    Slider(
                        value = settings.speechRate, onValueChange = app.settings::setSpeechRate, valueRange = 0.75f..1.4f,
                        colors = SliderDefaults.colors(thumbColor = NexaColors.Cyan, activeTrackColor = NexaColors.Violet),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("Ascolta la voce", app.controller::testVoice, Modifier.weight(1f))
                        OutlineButton("Sintesi di sistema", { context.openTtsSettings() }, Modifier.weight(1f))
                    }
                }

                // ------------------------------------------------ Conversazione
                Section(Icons.Filled.Forum, "Conversazione", "Personalizza il dialogo") {
                    PlainField("Come ti chiami?", settings.userName, app.settings::setUserName)
                    SwitchRow(
                        "Conversazione continua",
                        "Dopo ogni risposta Nexa torna ad ascoltarti, come in una vera chiacchierata.",
                        settings.continuousConversation, app.settings::setContinuous,
                    )
                }

                // ------------------------------------------------ Permessi
                PermissionsSection(context)

                // ------------------------------------------------ OriginOS
                Section(Icons.Filled.BatteryChargingFull, "Ottimizzazione OriginOS", "Perché vivo non chiuda Nexa") {
                    Hint(
                        "• Batteria: consenti il consumo in background (pulsante qui sotto).\n" +
                            "• i Manager → Gestione app → Avvio automatico → attiva Nexa.\n" +
                            "• Nelle app recenti, blocca Nexa con il lucchetto.\n" +
                            "• Impostazioni rapide → modifica → aggiungi \"Ehi Nexa\"."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(
                            if (context.ignoresBatteryOptimizations()) "Batteria ✓" else "Batteria",
                            { context.requestIgnoreBattery() }, Modifier.weight(1f),
                        )
                        OutlineButton("Info app", { context.openAppDetails() }, Modifier.weight(1f))
                    }
                }

                // ------------------------------------------------ Aggiornamenti
                Section(Icons.Filled.SystemUpdate, "Aggiornamenti", "Versione ${app.updates.currentVersionName}") {
                    SwitchRow(
                        "Aggiornamento automatico",
                        "Quando pubblico una nuova versione su GitHub, Nexa la scarica e la installa da sola.",
                        settings.autoUpdate, app.settings::setAutoUpdate,
                    )
                    val status = when (val u = update) {
                        UpdateManager.State.Idle -> null
                        UpdateManager.State.Checking -> "Controllo…"
                        UpdateManager.State.UpToDate -> "Hai già l'ultima versione."
                        is UpdateManager.State.Available -> "Disponibile la versione ${u.release.versionName}."
                        is UpdateManager.State.Downloading -> "Download ${(u.progress * 100).toInt()}%"
                        UpdateManager.State.Installing -> "Installazione in corso…"
                        is UpdateManager.State.NeedsPermission -> "Consenti a Nexa di installare app, poi riprova."
                        is UpdateManager.State.Failed -> u.message
                    }
                    status?.let { Hint(it, warning = update is UpdateManager.State.Failed) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("Controlla ora", {
                            scope.launch { app.updates.check()?.let { app.updates.downloadAndInstall(it) } }
                        }, Modifier.weight(1f))
                        OutlineButton("Permesso installazione", app.updates::openInstallPermissionSettings, Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun PermissionsSection(context: Context) {
    val perms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO to "Microfono")
            add(Manifest.permission.READ_CONTACTS to "Contatti")
            add(Manifest.permission.CALL_PHONE to "Telefono")
            add(Manifest.permission.SEND_SMS to "SMS")
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS to "Notifiche")
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    @Suppress("UNUSED_EXPRESSION") refresh
    val missing = perms.filter { !context.granted(it.first) }

    Section(Icons.Filled.Lock, "Permessi", if (missing.isEmpty()) "Tutto concesso" else "${missing.size} da concedere") {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            perms.forEach { (perm, label) ->
                val ok = context.granted(perm)
                Pill {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (ok) NexaColors.Mint else NexaColors.TextTertiary, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = NexaColors.TextPrimary)
                    }
                }
            }
        }
        if (missing.isNotEmpty()) {
            GradientButton("Concedi permessi", { launcher.launch(missing.map { it.first }.toTypedArray()) }, Modifier.fillMaxWidth())
            Hint("Se un permesso è bloccato: Info app → ⋮ → \"Consenti impostazioni con restrizioni\".")
        }
    }
}

// ---------------------------------------------------------------- componenti

@Composable
private fun Section(icon: ImageVector, title: String, subtitle: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(NexaColors.AccentGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = NexaColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NexaColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, subtitle: String, badge: String? = null, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) NexaColors.Violet else NexaColors.GlassBorder, label = "choiceBorder")
    val bg by animateColorAsState(if (selected) NexaColors.Violet.copy(alpha = 0.16f) else Color.Transparent, label = "choiceBg")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .border(2.dp, if (selected) NexaColors.Cyan else NexaColors.TextTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val dot by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, label = "dot")
            Box(
                Modifier.size(10.dp).graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot }
                    .clip(CircleShape).background(NexaColors.Cyan)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = NexaColors.TextPrimary)
                badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = NexaColors.TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(NexaColors.Glass).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NexaColors.TextSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePicker(options: List<com.nexa.assistant.data.VoiceOption>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = o.id == selected
            Text(
                o.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (sel) Color.White else NexaColors.TextPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) NexaColors.AccentGradient else SolidColor(NexaColors.Glass))
                    .border(1.dp, if (sel) Color.Transparent else NexaColors.GlassBorder, RoundedCornerShape(50))
                    .clickable { onSelect(o.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = NexaColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NexaColors.TextSecondary)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = NexaColors.Violet, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun SecretField(
    title: String,
    saved: Boolean,
    placeholder: String,
    onSave: (String) -> Unit,
    helpLabel: String,
    onHelp: () -> Unit,
    note: String? = null,
    required: Boolean = false,
) {
    var value by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(!saved && required) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = NexaColors.TextPrimary, modifier = Modifier.weight(1f))
            if (saved) Icon(Icons.Filled.CheckCircle, "Salvata", tint = NexaColors.Mint, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(editing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InputBox(value, { value = it }, placeholder, secret = true)
                note?.let { Hint(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton("Salva", { onSave(value); value = ""; editing = false }, Modifier.weight(1f), enabled = value.isNotBlank())
                    OutlineButton(helpLabel, onHelp, Modifier.weight(1f))
                }
            }
        }
        if (!editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (saved) "Cambia" else "Aggiungi", style = MaterialTheme.typography.labelMedium, color = NexaColors.Cyan,
                    modifier = Modifier.clickable { editing = true },
                )
                if (saved) {
                    Text("Rimuovi", style = MaterialTheme.typography.labelMedium, color = NexaColors.TextTertiary, modifier = Modifier.clickable { onSave("") })
                } else {
                    Text(helpLabel, style = MaterialTheme.typography.labelMedium, color = NexaColors.TextSecondary, modifier = Modifier.clickable(onClick = onHelp))
                }
            }
        }
    }
}

@Composable
private fun PlainField(label: String, current: String, onSave: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NexaColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { InputBox(value, { value = it }, label, secret = false) }
            AnimatedVisibility(value != current) { SmallGradientButton("Salva") { onSave(value) } }
        }
    }
}

@Composable
private fun InputBox(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x33000000))
            .border(1.dp, NexaColors.GlassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = NexaColors.TextTertiary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NexaColors.TextPrimary),
            cursorBrush = SolidColor(NexaColors.Cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (warning) NexaColors.Amber else NexaColors.TextSecondary)
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

private fun Context.openTtsSettings() {
    safeStart(Intent("com.android.settings.TTS_SETTINGS")) || safeStart(Intent(Settings.ACTION_SETTINGS))
}

private fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

@SuppressLint("BatteryLife")
private fun Context.requestIgnoreBattery() {
    safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) ||
        safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
