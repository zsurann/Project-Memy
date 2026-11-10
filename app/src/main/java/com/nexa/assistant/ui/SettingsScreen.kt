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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.assistant.NexaApp
import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.data.SpeechEngine
import com.nexa.assistant.speech.Speaker

@Composable
fun SettingsScreen(app: NexaApp, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val ui by app.controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = settings.hasApiKey, onBack = onBack)

    Column(
        Modifier.fillMaxSize().background(NexaColors.Background).safeDrawingPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = settings.hasApiKey) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = NexaColors.TextSecondary)
            }
            Text("Impostazioni", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NexaColors.TextPrimary)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ------------------------------------------------ Chiave API
            Section("1. Chiave API di Claude", done = settings.hasApiKey) {
                Hint(
                    "Creala su console.anthropic.com → API Keys, e ricarica qualche euro di credito. " +
                        "La chiave resta cifrata solo su questo telefono."
                )
                var key by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (settings.hasApiKey) "Chiave salvata — incollane una nuova per cambiarla" else "sk-ant-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.settings.setApiKey(key); key = "" }, enabled = key.isNotBlank()) {
                        Text("Salva chiave")
                    }
                    OutlinedButton(onClick = { context.openUrl("https://console.anthropic.com/settings/keys") }) {
                        Text("Apri console")
                    }
                }
            }

            // ------------------------------------------------ Voce in ingresso
            Section("2. Voce: riconoscimento", done = ui.voiceModelInstalled || settings.speechEngine == SpeechEngine.SYSTEM) {
                SpeechEngine.entries.forEach { engine ->
                    Choice(
                        selected = settings.speechEngine == engine,
                        title = engine.label,
                        subtitle = engine.description,
                        onClick = { app.settings.setSpeechEngine(engine) },
                    )
                }
                if (settings.speechEngine == SpeechEngine.VOSK) {
                    val progress = ui.modelDownloadProgress
                    when {
                        progress != null -> {
                            Hint("Scarico la voce italiana… ${(progress * 100).toInt()}%")
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        ui.voiceModelInstalled -> Hint("Voce offline italiana installata ✓")
                        else -> Button(onClick = app.controller::downloadVoiceModel) {
                            Text("Scarica voce italiana (~50 MB)")
                        }
                    }
                }
            }

            // ------------------------------------------------ Voce in uscita
            Section("3. Voce: risposta", done = ui.speakerStatus == Speaker.Status.READY) {
                when (ui.speakerStatus) {
                    Speaker.Status.READY -> Hint("Sintesi vocale italiana pronta.")
                    Speaker.Status.LOADING -> Hint("Controllo la sintesi vocale…")
                    Speaker.Status.NO_ITALIAN, Speaker.Status.UNAVAILABLE -> Hint(
                        "Il motore di sintesi vocale del telefono non ha l'italiano. Installa un motore TTS con voce italiana " +
                            "(ad esempio \"sherpa-onnx TTS Engine\" o \"RHVoice\"), poi sceglilo come motore preferito " +
                            "nelle impostazioni di sintesi vocale e riapri Nexa.",
                        warning = true,
                    )
                }
                Text("Velocità: ${"%.2f".format(settings.speechRate)}×", color = NexaColors.TextSecondary)
                Slider(
                    value = settings.speechRate,
                    onValueChange = app.settings::setSpeechRate,
                    valueRange = 0.7f..1.6f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = app.controller::testVoice) { Text("Prova voce") }
                    OutlinedButton(onClick = { context.openTtsSettings() }) { Text("Impostazioni TTS") }
                }
            }

            // ------------------------------------------------ Cervello
            Section("4. Cervello (modello Claude)", done = true) {
                ClaudeModel.entries.forEach { model ->
                    Choice(
                        selected = settings.model == model,
                        title = model.label,
                        subtitle = model.description,
                        onClick = { app.settings.setModel(model) },
                    )
                }
            }

            // ------------------------------------------------ Permessi
            PermissionsSection(context)

            // ------------------------------------------------ OriginOS
            Section("6. Ottimizzazione per OriginOS (vivo)", done = context.ignoresBatteryOptimizations()) {
                Hint(
                    "OriginOS chiude spesso le app in background. Per far funzionare bene Nexa:\n" +
                        "• Batteria: consenti il consumo elevato in background (pulsante qui sotto).\n" +
                        "• Avvio automatico: i Manager → Gestione app → Avvio automatico → attiva Nexa.\n" +
                        "• Nelle app recenti, tieni premuta la scheda di Nexa e bloccala (lucchetto).\n" +
                        "• Riquadro rapido: apri le Impostazioni rapide, tocca modifica e aggiungi \"Ehi Nexa\"."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.requestIgnoreBattery() }) { Text("Batteria") }
                    OutlinedButton(onClick = { context.openAppDetails() }) { Text("Info app") }
                }
            }

            // ------------------------------------------------ Varie
            Section("7. Preferenze", done = true) {
                var name by remember(settings.userName) { mutableStateOf(settings.userName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Come ti chiami? (facoltativo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (name != settings.userName) {
                    Button(onClick = { app.settings.setUserName(name) }) { Text("Salva nome") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Continua ad ascoltare", color = NexaColors.TextPrimary)
                        Hint("Quando Nexa ti fa una domanda, riapre il microfono da sola.")
                    }
                    Switch(checked = settings.autoListenAfterReply, onCheckedChange = app.settings::setAutoListen)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionsSection(context: Context) {
    val perms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO to "Microfono (per ascoltarti)")
            add(Manifest.permission.READ_CONTACTS to "Contatti (per chiamare e scrivere per nome)")
            add(Manifest.permission.CALL_PHONE to "Telefono (per avviare chiamate)")
            add(Manifest.permission.SEND_SMS to "SMS (per inviare messaggi)")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS to "Notifiche")
            }
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    @Suppress("UNUSED_EXPRESSION") refresh
    val missing = perms.filter { !context.granted(it.first) }

    Section("5. Permessi", done = missing.isEmpty()) {
        perms.forEach { (perm, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = context.granted(perm)
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (ok) NexaColors.Listening else NexaColors.Warning,
                )
                Spacer(Modifier.padding(4.dp))
                Text(label, color = NexaColors.TextPrimary)
            }
        }
        if (missing.isNotEmpty()) {
            Button(onClick = { launcher.launch(missing.map { it.first }.toTypedArray()) }) {
                Text("Concedi permessi")
            }
            Hint(
                "Se un permesso viene negato più volte, concedilo da Info app → Permessi. " +
                    "Su Android recenti, per le app installate da APK può servire prima: Info app → ⋮ → \"Consenti impostazioni con restrizioni\"."
            )
        }
    }
}

@Composable
private fun Section(title: String, done: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NexaColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = NexaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (done) Icon(Icons.Filled.CheckCircle, contentDescription = "Fatto", tint = NexaColors.Listening)
        }
        content()
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, color = NexaColors.TextPrimary)
            Text(subtitle, color = NexaColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    Text(text, color = if (warning) NexaColors.Warning else NexaColors.TextSecondary, fontSize = 14.sp)
}

// ---------------------------------------------------------------- helper di sistema

private fun Context.granted(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

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
