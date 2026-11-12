package com.nexa.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.nexa.assistant.access.MemyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Strumenti di base: app, orologio, telefono, SMS, torcia, volume, musica, web, mappe. */
fun basicTools(context: Context): List<MemyTool> {
    val ctx = context.applicationContext
    return listOf(
        DeviceStatusTool(ctx),
        OpenAppTool(ctx),
        SetAlarmTool(ctx),
        SetTimerTool(ctx),
        FindContactTool(ctx),
        CallTool(ctx),
        SendSmsTool(ctx),
        FlashlightTool(ctx),
        VolumeTool(ctx),
        MediaControlTool(ctx),
        OpenSettingsTool(ctx),
        OpenWebTool(ctx),
        NavigateTool(ctx),
    )
}

// ---------------------------------------------------------------------------

class DeviceStatusTool(private val ctx: Context) : MemyTool {
    override val name = "get_device_status"
    override val description =
        "Restituisce data e ora attuali, livello della batteria, stato di carica e connessione di rete del telefono."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()

    override suspend fun execute(input: ToolInput): ToolResult {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN))
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "nessuna connessione"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "rete mobile"
            else -> "connesso"
        }
        return ToolResult.ok(
            "Data e ora: $date (fuso ${ZoneId.systemDefault()}). Batteria: $level%" +
                (if (charging) " in carica" else "") + ". Rete: $net."
        )
    }
}

class OpenAppTool(private val ctx: Context) : MemyTool {
    override val name = "open_app"
    override val description =
        "Apre un'app installata sul telefono cercandola per nome (es. 'WhatsApp', 'Fotocamera', 'Impostazioni'). " +
            "Se il controllo dello schermo è attivo restituisce anche lo schermo dell'app. Se non la trova elenca le app installate."
    override val properties = mapOf("app_name" to prop("string", "Nome dell'app da aprire"))
    override val required = listOf("app_name")

    override suspend fun execute(input: ToolInput): ToolResult {
        val query = normalize(input.requireString("app_name"))
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }.distinctBy { it.second }

        val scored = apps.map { (label, pkg) ->
            val l = normalize(label)
            val score = when {
                l == query -> 100
                l.startsWith(query) -> 80
                l.contains(query) -> 60
                query.contains(l) && l.length > 2 -> 50
                normalize(pkg).contains(query.replace(" ", "")) -> 40
                else -> 0
            }
            Triple(label, pkg, score)
        }.sortedByDescending { it.third }

        val best = scored.firstOrNull()
        if (best == null || best.third == 0) {
            val names = apps.map { it.first }.sorted().joinToString(", ")
            return ToolResult.error("Nessuna app trovata per '$query'. App installate: $names")
        }
        val intent = pm.getLaunchIntentForPackage(best.second)
            ?: return ToolResult.error("L'app ${best.first} non può essere aperta.")
        if (!ctx.launch(intent)) return ToolResult.error("Impossibile aprire ${best.first}.")
        // Se Memy può vedere lo schermo, restituisce subito quello dell'app appena aperta.
        val svc = MemyAccessibilityService.instance ?: return ToolResult.ok("Aperta l'app ${best.first}.")
        delay(1300)
        return ToolResult.ok("Aperta l'app ${best.first}.\nSchermo:\n" + withContext(Dispatchers.Main) { svc.readScreen() })
    }
}

class SetAlarmTool(private val ctx: Context) : MemyTool {
    override val name = "set_alarm"
    override val description = "Imposta una sveglia nell'app Orologio."
    override val properties = mapOf(
        "hour" to prop("integer", "Ora (0-23)"),
        "minute" to prop("integer", "Minuti (0-59)"),
        "label" to prop("string", "Etichetta facoltativa della sveglia"),
        "days" to prop(
            "array",
            "Giorni di ripetizione facoltativi: 1=domenica, 2=lunedì, 3=martedì, 4=mercoledì, 5=giovedì, 6=venerdì, 7=sabato",
            mapOf("items" to mapOf("type" to "integer")),
        ),
    )
    override val required = listOf("hour", "minute")

    override suspend fun execute(input: ToolInput): ToolResult {
        val hour = input.int("hour")?.takeIf { it in 0..23 } ?: return ToolResult.error("Ora mancante o non valida (0-23)")
        val minute = (input.int("minute") ?: 0).takeIf { it in 0..59 } ?: return ToolResult.error("Minuti non validi (0-59)")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            input.string("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            val days = input.intList("days").filter { it in Calendar.SUNDAY..Calendar.SATURDAY }
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        }
        return if (ctx.launch(intent)) ToolResult.ok("Sveglia impostata alle %02d:%02d.".format(hour, minute))
        else ToolResult.error("Nessuna app Orologio compatibile trovata.")
    }
}

class SetTimerTool(private val ctx: Context) : MemyTool {
    override val name = "set_timer"
    override val description = "Avvia un timer (conto alla rovescia)."
    override val properties = mapOf(
        "seconds" to prop("integer", "Durata totale in secondi"),
        "label" to prop("string", "Etichetta facoltativa"),
    )
    override val required = listOf("seconds")

    override suspend fun execute(input: ToolInput): ToolResult {
        val seconds = input.int("seconds")?.takeIf { it in 1..86_400 }
            ?: return ToolResult.error("Durata non valida")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            input.string("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return if (ctx.launch(intent)) ToolResult.ok("Timer avviato per $seconds secondi.")
        else ToolResult.error("Nessuna app Orologio compatibile trovata.")
    }
}

/** Ricerca contatti condivisa dagli strumenti telefono/SMS. */
internal object Contacts {
    data class Entry(val name: String, val number: String)

    fun search(ctx: Context, query: String): List<Entry> {
        if (!ctx.hasPermission(Manifest.permission.READ_CONTACTS)) throw SecurityException("permesso contatti")
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<Entry>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val n = normalize(name)
                if (n == q || n.contains(q) || q.split(" ").all { part -> n.contains(part) }) {
                    out += Entry(name, number)
                }
            }
        }
        return out.distinctBy { it.number.filter(Char::isDigit) }
            .sortedBy { if (normalize(it.name) == q) 0 else 1 }
    }

    /** Se il testo è già un numero lo usa, altrimenti cerca il contatto (deve essere univoco). */
    fun resolve(ctx: Context, who: String): Result<Entry> {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) >= 3 && digits.length >= who.replace(" ", "").length - 1) {
            return Result.success(Entry(who, digits))
        }
        val found = try {
            search(ctx, who)
        } catch (e: SecurityException) {
            return Result.failure(IllegalStateException("Memy non ha il permesso di leggere i contatti. Concedilo nelle impostazioni di Memy."))
        }
        return when {
            found.isEmpty() -> Result.failure(IllegalStateException("Nessun contatto trovato per '$who'."))
            found.size == 1 || normalize(found[0].name) == normalize(who) -> Result.success(found[0])
            else -> Result.failure(
                IllegalStateException(
                    "Più contatti corrispondono a '$who': " +
                        found.take(6).joinToString("; ") { "${it.name} (${it.number})" } +
                        ". Chiedi all'utente quale intende."
                )
            )
        }
    }
}

class FindContactTool(private val ctx: Context) : MemyTool {
    override val name = "find_contact"
    override val description = "Cerca un contatto in rubrica per nome e restituisce i numeri di telefono."
    override val properties = mapOf("name" to prop("string", "Nome (o parte del nome) del contatto"))
    override val required = listOf("name")

    override suspend fun execute(input: ToolInput): ToolResult = try {
        val res = Contacts.search(ctx, input.requireString("name"))
        if (res.isEmpty()) ToolResult.ok("Nessun contatto trovato.")
        else ToolResult.ok(res.take(10).joinToString("\n") { "${it.name}: ${it.number}" })
    } catch (e: SecurityException) {
        ToolResult.error("Permesso contatti non concesso. L'utente deve concederlo nelle impostazioni di Memy.")
    }
}

class CallTool(private val ctx: Context) : MemyTool {
    override val name = "make_call"
    override val description =
        "Chiama un contatto (per nome) o un numero di telefono. L'app chiederà conferma vocale all'utente prima di chiamare."
    override val properties = mapOf("recipient" to prop("string", "Nome del contatto o numero di telefono"))
    override val required = listOf("recipient")

    private var resolved: Contacts.Entry? = null

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val entry = Contacts.resolve(ctx, input.requireString("recipient")).getOrNull()
        resolved = entry
        return entry?.let { "Vuoi che chiami ${it.name}${if (it.name != it.number) ", al numero ${it.number}" else ""}?" }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val entry = resolved ?: Contacts.resolve(ctx, input.requireString("recipient"))
            .getOrElse { return ToolResult.error(it.message ?: "Contatto non trovato") }
        resolved = null
        val uri = Uri.parse("tel:" + Uri.encode(entry.number))
        val direct = ctx.hasPermission(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        return if (ctx.launch(intent)) {
            ToolResult.ok(if (direct) "Chiamata a ${entry.name} avviata." else "Aperto il compositore con il numero di ${entry.name}: l'utente deve premere chiama (manca il permesso telefono).")
        } else ToolResult.error("Impossibile avviare la chiamata.")
    }
}

class SendSmsTool(private val ctx: Context) : MemyTool {
    override val name = "send_sms"
    override val description =
        "Invia un SMS a un contatto (per nome) o a un numero. L'app leggerà il messaggio all'utente e chiederà conferma prima di inviarlo."
    override val properties = mapOf(
        "recipient" to prop("string", "Nome del contatto o numero di telefono"),
        "message" to prop("string", "Testo del messaggio"),
    )
    override val required = listOf("recipient", "message")

    private var resolved: Contacts.Entry? = null

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val entry = Contacts.resolve(ctx, input.requireString("recipient")).getOrNull()
        resolved = entry
        return entry?.let { "Invio a ${it.name} questo SMS: \"${input.requireString("message")}\". Confermi?" }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val entry = resolved ?: Contacts.resolve(ctx, input.requireString("recipient"))
            .getOrElse { return ToolResult.error(it.message ?: "Contatto non trovato") }
        resolved = null
        val text = input.requireString("message")
        if (!ctx.hasPermission(Manifest.permission.SEND_SMS)) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(entry.number)))
                .putExtra("sms_body", text)
            return if (ctx.launch(intent)) ToolResult.ok("Permesso SMS mancante: ho aperto l'app Messaggi con il testo pronto, l'utente deve premere invia.")
            else ToolResult.error("Impossibile inviare l'SMS.")
        }
        return try {
            val sms = ctx.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(entry.number, null, parts, null, null)
            ToolResult.ok("SMS inviato a ${entry.name}.")
        } catch (e: Exception) {
            ToolResult.error("Invio SMS non riuscito: ${e.message}")
        }
    }
}

class FlashlightTool(private val ctx: Context) : MemyTool {
    override val name = "set_flashlight"
    override val description = "Accende o spegne la torcia del telefono."
    override val properties = mapOf("on" to prop("boolean", "true per accendere, false per spegnere"))
    override val required = listOf("on")

    override suspend fun execute(input: ToolInput): ToolResult {
        val on = input.bool("on") ?: return ToolResult.error("Parametro 'on' mancante")
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult.error("Questo telefono non ha una torcia accessibile.")
        return try {
            cm.setTorchMode(id, on)
            ToolResult.ok(if (on) "Torcia accesa." else "Torcia spenta.")
        } catch (e: Exception) {
            ToolResult.error("Impossibile usare la torcia: ${e.message}")
        }
    }
}

class VolumeTool(private val ctx: Context) : MemyTool {
    override val name = "set_volume"
    override val description =
        "Imposta il volume del telefono in percentuale (0-100), oppure attiva/disattiva il silenzioso."
    override val properties = mapOf(
        "stream" to prop(
            "string", "Quale volume: media (musica/video), ring (suoneria), alarm (sveglia), notification",
            mapOf("enum" to listOf("media", "ring", "alarm", "notification")),
        ),
        "percent" to prop("integer", "Livello da 0 a 100"),
    )
    override val required = listOf("stream", "percent")

    override suspend fun execute(input: ToolInput): ToolResult {
        val am = ctx.getSystemService(AudioManager::class.java)
        val stream = when (input.string("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val percent = (input.int("percent") ?: return ToolResult.error("Percentuale mancante")).coerceIn(0, 100)
        val max = am.getStreamMaxVolume(stream)
        return try {
            am.setStreamVolume(stream, (max * percent / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
            ToolResult.ok("Volume ${input.string("stream")} impostato al $percent%.")
        } catch (e: SecurityException) {
            ToolResult.error("Il sistema non permette di cambiare questo volume in modalità Non disturbare.")
        }
    }
}

class MediaControlTool(private val ctx: Context) : MemyTool {
    override val name = "media_control"
    override val description = "Controlla la musica o il video in riproduzione in qualsiasi app."
    override val properties = mapOf(
        "action" to prop(
            "string", "Azione da eseguire",
            mapOf("enum" to listOf("play", "pause", "play_pause", "next", "previous")),
        ),
    )
    override val required = listOf("action")

    override suspend fun execute(input: ToolInput): ToolResult {
        val code = when (input.string("action")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ToolResult.ok("Comando '${input.string("action")}' inviato al lettore multimediale.")
    }
}

class OpenSettingsTool(private val ctx: Context) : MemyTool {
    override val name = "open_settings"
    override val description =
        "Apre una schermata delle impostazioni di sistema. Per accendere o spegnere Wi-Fi e Bluetooth usa prima press_button " +
            "quick_settings e tocca l'interruttore; solo se il controllo dello schermo non è attivo apri il pannello e di' all'utente di toccarlo."
    private val sections = mapOf(
        "wifi" to Settings.Panel.ACTION_WIFI,
        "internet" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "volume" to Settings.Panel.ACTION_VOLUME,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "general" to Settings.ACTION_SETTINGS,
    )
    override val properties = mapOf(
        "section" to prop("string", "Sezione da aprire", mapOf("enum" to sections.keys.toList())),
    )
    override val required = listOf("section")

    override suspend fun execute(input: ToolInput): ToolResult {
        val action = sections[input.string("section")] ?: Settings.ACTION_SETTINGS
        return if (ctx.launch(Intent(action)) || ctx.launch(Intent(Settings.ACTION_SETTINGS))) {
            ToolResult.ok("Aperte le impostazioni: ${input.string("section")}.")
        } else ToolResult.error("Impossibile aprire le impostazioni.")
    }
}

class OpenWebTool(private val ctx: Context) : MemyTool {
    override val name = "open_web"
    override val description =
        "Apre il browser del telefono per MOSTRARE all'utente una ricerca o una pagina. Per rispondere tu a una domanda usa invece la ricerca web."
    override val properties = mapOf(
        "query" to prop("string", "Testo da cercare, oppure un URL completo (https://...)"),
    )
    override val required = listOf("query")

    override suspend fun execute(input: ToolInput): ToolResult {
        val q = input.requireString("query")
        val url = if (q.startsWith("http://") || q.startsWith("https://")) q
        else "https://www.bing.com/search?q=" + Uri.encode(q)
        return if (ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ToolResult.ok("Aperto il browser su: $q")
        else ToolResult.error("Nessun browser disponibile.")
    }
}

class NavigateTool(private val ctx: Context) : MemyTool {
    override val name = "navigate_to"
    override val description = "Apre l'app di mappe installata con la destinazione indicata."
    override val properties = mapOf("destination" to prop("string", "Indirizzo o luogo di destinazione"))
    override val required = listOf("destination")

    override suspend fun execute(input: ToolInput): ToolResult {
        val dest = input.requireString("destination")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
        return if (ctx.launch(intent)) ToolResult.ok("Aperte le mappe per: $dest")
        else ToolResult.error("Nessuna app di mappe installata.")
    }
}
