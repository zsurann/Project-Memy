package com.nexa.assistant.tools

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.nexa.assistant.access.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Impostazioni di sistema e scorciatoie verso altre app. */
fun systemTools(context: Context): List<MemyTool> {
    val ctx = context.applicationContext
    return listOf(
        ListAppsTool(ctx), BrightnessTool(ctx), SoundModeTool(ctx), DoNotDisturbTool(ctx), ClipboardTool(ctx),
        PlayMusicTool(ctx), OpenCameraTool(ctx), ComposeEmailTool(ctx), ShareTextTool(ctx),
    )
}

class ListAppsTool(private val ctx: Context) : MemyTool {
    override val name = "list_apps"
    override val description = "Elenca le app installate sul telefono."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString() }.distinct().sorted()
        return ToolResult.ok(names.joinToString(", "))
    }
}

class BrightnessTool(private val ctx: Context) : MemyTool {
    override val name = "set_brightness"
    override val description = "Imposta la luminosità dello schermo in percentuale, oppure attiva la luminosità automatica."
    override val properties = mapOf(
        "percent" to prop("integer", "Luminosità da 0 a 100"),
        "auto" to prop("boolean", "true per la luminosità automatica"),
    )
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        if (!Capabilities.canWriteSettings(ctx)) {
            return ToolResult.error("Manca il permesso di modificare le impostazioni di sistema. Di' all'utente di attivarlo in Memy: Impostazioni → Poteri → Impostazioni di sistema.")
        }
        val cr = ctx.contentResolver
        if (input.bool("auto") == true) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ToolResult.ok("Luminosità automatica attivata.")
        }
        val percent = (input.int("percent") ?: return ToolResult.error("Indica la percentuale")).coerceIn(0, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceIn(1, 255))
        return ToolResult.ok("Luminosità impostata al $percent%.")
    }
}

class SoundModeTool(private val ctx: Context) : MemyTool {
    override val name = "set_sound_mode"
    override val description = "Imposta la modalità della suoneria: normal (suona), vibrate (solo vibrazione), silent (silenzioso)."
    override val properties = mapOf("mode" to enumProp("Modalità", listOf("normal", "vibrate", "silent")))
    override val required = listOf("mode")
    override suspend fun execute(input: ToolInput): ToolResult {
        val am = ctx.getSystemService(AudioManager::class.java)
        val mode = input.requireString("mode")
        return try {
            am.ringerMode = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "normal" -> AudioManager.RINGER_MODE_NORMAL
                else -> return ToolResult.error("Modalità sconosciuta: usa normal, vibrate o silent.")
            }
            ToolResult.ok("Modalità suoneria: $mode.")
        } catch (e: SecurityException) {
            ToolResult.error("Per il silenzioso serve l'accesso a Non disturbare. Di' all'utente di attivarlo in Memy: Impostazioni → Poteri → Non disturbare.")
        }
    }
}

class DoNotDisturbTool(private val ctx: Context) : MemyTool {
    override val name = "set_do_not_disturb"
    override val description = "Attiva o disattiva la modalità Non disturbare."
    override val properties = mapOf("on" to prop("boolean", "true per attivare, false per disattivare"))
    override val required = listOf("on")
    override suspend fun execute(input: ToolInput): ToolResult {
        val on = input.bool("on") ?: return ToolResult.error("Parametro 'on' mancante")
        if (!Capabilities.canControlDnd(ctx)) {
            return ToolResult.error("Manca l'accesso a Non disturbare. Di' all'utente di attivarlo in Memy: Impostazioni → Poteri → Non disturbare.")
        }
        ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return ToolResult.ok(if (on) "Non disturbare attivato." else "Non disturbare disattivato.")
    }
}

class ClipboardTool(private val ctx: Context) : MemyTool {
    override val name = "copy_to_clipboard"
    override val description = "Copia un testo negli appunti del telefono, pronto da incollare."
    override val properties = mapOf("text" to prop("string", "Testo da copiare"))
    override val required = listOf("text")
    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.Main) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Memy", input.requireString("text")))
        ToolResult.ok("Testo copiato negli appunti.")
    }
}

class PlayMusicTool(private val ctx: Context) : MemyTool {
    override val name = "play_music"
    override val description =
        "Chiede all'app musicale del telefono di riprodurre un brano, artista, album o playlist. Se nessuna app risponde, apri l'app musicale con open_app e usa gli strumenti dello schermo."
    override val properties = mapOf("query" to prop("string", "Cosa riprodurre, es. 'Lucio Dalla' o 'musica rilassante'"))
    override val required = listOf("query")
    override suspend fun execute(input: ToolInput): ToolResult {
        val q = input.requireString("query")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, q)
        }
        return if (ctx.launch(intent)) ToolResult.ok("Richiesta inviata all'app musicale: $q.")
        else ToolResult.error("Nessuna app musicale ha accettato la richiesta. Apri un'app musicale con open_app e cerca dal suo schermo.")
    }
}

class OpenCameraTool(private val ctx: Context) : MemyTool {
    override val name = "open_camera"
    override val description = "Apre la fotocamera in modalità foto o video."
    override val properties = mapOf("mode" to enumProp("Modalità", listOf("photo", "video")))
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val video = input.string("mode") == "video"
        val action = if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return if (ctx.launch(Intent(action))) ToolResult.ok("Fotocamera aperta in modalità ${if (video) "video" else "foto"}.")
        else ToolResult.error("Impossibile aprire la fotocamera.")
    }
}

class ComposeEmailTool(private val ctx: Context) : MemyTool {
    override val name = "compose_email"
    override val description = "Apre l'app di posta con una nuova email già compilata; l'utente (o tu, con gli strumenti dello schermo) preme invia."
    override val properties = mapOf(
        "to" to prop("string", "Indirizzo del destinatario"),
        "subject" to prop("string", "Oggetto"),
        "body" to prop("string", "Testo dell'email"),
    )
    override val required = listOf("subject", "body")
    override suspend fun execute(input: ToolInput): ToolResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(input.string("to").orEmpty()))).apply {
            putExtra(Intent.EXTRA_SUBJECT, input.requireString("subject"))
            putExtra(Intent.EXTRA_TEXT, input.requireString("body"))
        }
        return if (ctx.launch(intent)) ToolResult.ok("Aperta l'app di posta con la bozza pronta.")
        else ToolResult.error("Nessuna app di posta configurata.")
    }
}

class ShareTextTool(private val ctx: Context) : MemyTool {
    override val name = "share_text"
    override val description = "Apre il menu di condivisione di Android con un testo, per mandarlo a qualsiasi app."
    override val properties = mapOf("text" to prop("string", "Testo da condividere"))
    override val required = listOf("text")
    override suspend fun execute(input: ToolInput): ToolResult {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, input.requireString("text"))
        return if (ctx.launch(Intent.createChooser(send, "Condividi con"))) ToolResult.ok("Menu di condivisione aperto.")
        else ToolResult.error("Impossibile aprire la condivisione.")
    }
}
