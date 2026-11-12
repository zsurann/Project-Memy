package com.nexa.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.nexa.assistant.data.MemoryStore
import com.nexa.assistant.reminders.ReminderStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/** Strumenti che leggono o scrivono i dati personali: calendario, messaggi, chiamate, posizione, promemoria, memoria. */
fun dataTools(context: Context, memory: MemoryStore, reminders: ReminderStore): List<MemyTool> {
    val ctx = context.applicationContext
    return listOf(
        ReadCalendarTool(ctx), CreateEventTool(ctx), ReadSmsTool(ctx), CallLogTool(ctx), LocationTool(ctx),
        SetReminderTool(ctx, reminders), ListRemindersTool(reminders), DeleteReminderTool(reminders),
        RememberTool(memory), ForgetTool(memory),
    )
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", Locale.ITALIAN)
private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFmt)
private fun parseDateTime(s: String): Long? =
    runCatching { LocalDateTime.parse(s.trim()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

private fun missing(what: String) =
    ToolResult.error("Manca il permesso per $what. Di' all'utente di concederlo in Memy: Impostazioni → Poteri → Permessi.")

private fun contactName(ctx: Context, number: String?): String? {
    if (number.isNullOrBlank() || !ctx.hasPermission(Manifest.permission.READ_CONTACTS)) return null
    return runCatching {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

// --------------------------------------------------------------------------- calendario

class ReadCalendarTool(private val ctx: Context) : MemyTool {
    override val name = "read_calendar"
    override val description = "Legge gli eventi del calendario dei prossimi giorni (a partire da adesso)."
    override val properties = mapOf("days" to prop("integer", "Quanti giorni guardare in avanti (predefinito 7, massimo 60)"))
    override val required = emptyList<String>()

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!ctx.hasPermission(Manifest.permission.READ_CALENDAR)) return missing("leggere il calendario")
        val from = System.currentTimeMillis()
        val to = from + (input.int("days") ?: 7).coerceIn(1, 60) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to); it.build()
        }
        val events = mutableListOf<String>()
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext() && events.size < 40) {
                val allDay = c.getInt(4) == 1
                val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " presso $it" }.orEmpty()
                events += "${c.getString(0) ?: "(senza titolo)"} — ${if (allDay) fmt(c.getLong(1)).substringBefore(",") + " (tutto il giorno)" else fmt(c.getLong(1))}$loc"
            }
        }
        return ToolResult.ok(if (events.isEmpty()) "Nessun evento nel periodo richiesto." else events.joinToString("\n"))
    }
}

class CreateEventTool(private val ctx: Context) : MemyTool {
    override val name = "create_calendar_event"
    override val description = "Crea un evento nel calendario del telefono."
    override val properties = mapOf(
        "title" to prop("string", "Titolo dell'evento"),
        "start" to prop("string", "Inizio in formato ISO locale, es. 2026-11-12T15:30"),
        "duration_minutes" to prop("integer", "Durata in minuti (predefinito 60)"),
        "location" to prop("string", "Luogo (facoltativo)"),
        "reminder_minutes" to prop("integer", "Minuti di preavviso per la notifica (predefinito 15)"),
    )
    override val required = listOf("title", "start")

    override suspend fun execute(input: ToolInput): ToolResult {
        val title = input.requireString("title")
        val startMs = parseDateTime(input.requireString("start"))
            ?: return ToolResult.error("Data di inizio non valida, usa il formato 2026-11-12T15:30")
        val endMs = startMs + (input.int("duration_minutes") ?: 60).coerceAtLeast(5) * 60_000L

        val direct = ctx.hasPermission(Manifest.permission.WRITE_CALENDAR) && ctx.hasPermission(Manifest.permission.READ_CALENDAR)
        val calendarId = if (direct) writableCalendar() else null
        if (calendarId != null) {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                input.string("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            if (uri != null) {
                runCatching {
                    ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                        put(CalendarContract.Reminders.MINUTES, input.int("reminder_minutes") ?: 15)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    })
                }
                return ToolResult.ok("Evento \"$title\" creato per ${fmt(startMs)}.")
            }
        }
        // Ripiego: apre il calendario con l'evento già compilato.
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            input.string("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        return if (ctx.launch(intent)) ToolResult.ok("Ho aperto il calendario con l'evento pronto: va salvato con un tocco (non ho potuto salvarlo direttamente).")
        else ToolResult.error("Nessuna app calendario trovata.")
    }

    private fun writableCalendar(): Long? = runCatching {
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
}

// --------------------------------------------------------------------------- messaggi e chiamate

class ReadSmsTool(private val ctx: Context) : MemyTool {
    override val name = "read_sms"
    override val description = "Legge gli ultimi SMS ricevuti, dal più recente. Si può filtrare per mittente."
    override val properties = mapOf(
        "count" to prop("integer", "Quanti messaggi (predefinito 5, massimo 20)"),
        "from" to prop("string", "Nome o numero del mittente (facoltativo)"),
    )
    override val required = emptyList<String>()

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!ctx.hasPermission(Manifest.permission.READ_SMS)) return missing("leggere gli SMS")
        val count = (input.int("count") ?: 5).coerceIn(1, 20)
        val filter = input.string("from")?.let(::normalize)
        val out = mutableListOf<String>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE + " DESC",
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && out.size < count && scanned++ < 300) {
                val address = c.getString(0).orEmpty()
                val who = contactName(ctx, address) ?: address
                if (filter != null && !normalize(who).contains(filter) && !address.contains(filter)) continue
                out += "$who · ${fmt(c.getLong(2))}${if (c.getInt(3) == 0) " · non letto" else ""}: ${c.getString(1).orEmpty().take(400)}"
            }
        }
        return ToolResult.ok(if (out.isEmpty()) "Nessun SMS trovato." else out.joinToString("\n"))
    }
}

class CallLogTool(private val ctx: Context) : MemyTool {
    override val name = "read_call_log"
    override val description = "Legge le chiamate recenti (ricevute, effettuate, perse)."
    override val properties = mapOf(
        "count" to prop("integer", "Quante chiamate (predefinito 8, massimo 30)"),
        "only_missed" to prop("boolean", "true per vedere solo le chiamate perse"),
    )
    override val required = emptyList<String>()

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!ctx.hasPermission(Manifest.permission.READ_CALL_LOG)) return missing("leggere il registro chiamate")
        val count = (input.int("count") ?: 8).coerceIn(1, 30)
        val onlyMissed = input.bool("only_missed") == true
        val out = mutableListOf<String>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            if (onlyMissed) "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null,
            null, CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < count) {
                val who = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(0).orEmpty().ifBlank { "numero privato" }
                val type = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "ricevuta"
                    CallLog.Calls.OUTGOING_TYPE -> "effettuata"
                    CallLog.Calls.MISSED_TYPE -> "persa"
                    CallLog.Calls.REJECTED_TYPE -> "rifiutata"
                    else -> "chiamata"
                }
                val mins = c.getLong(4) / 60
                out += "$who · $type · ${fmt(c.getLong(3))}${if (mins > 0) " · $mins min" else ""}"
            }
        }
        return ToolResult.ok(if (out.isEmpty()) "Nessuna chiamata trovata." else out.joinToString("\n"))
    }
}

// --------------------------------------------------------------------------- posizione

class LocationTool(private val ctx: Context) : MemyTool {
    override val name = "get_location"
    override val description =
        "Restituisce la posizione attuale del telefono (coordinate e, se disponibile, indirizzo). Utile per meteo, luoghi vicini e indicazioni."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()

    @SuppressLint("MissingPermission")
    override suspend fun execute(input: ToolInput): ToolResult {
        if (!ctx.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) && !ctx.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return missing("conoscere la posizione")
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) return ToolResult.error("La localizzazione del telefono è spenta.")
        var best: Location? = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val stale = best == null || System.currentTimeMillis() - best.time > 10 * 60_000L
        if (stale && Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
                .firstOrNull { it in providers } ?: providers.first()
            val fresh = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    runCatching {
                        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (fresh != null) best = fresh
        }
        val old = best == null || System.currentTimeMillis() - best.time > 10 * 60_000L
        if (old && (ctx.applicationContext as? com.nexa.assistant.MemyApp)?.controller?.inForeground == false) {
            return ToolResult.error("Con Memy in background Android non dà la posizione aggiornata: di' all'utente di aprire Memy e riprovare.")
        }
        val loc = best ?: return ToolResult.error("Posizione non disponibile in questo momento.")
        val address = runCatching {
            if (!Geocoder.isPresent()) null
            else @Suppress("DEPRECATION") Geocoder(ctx, Locale.ITALIAN).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { a -> listOfNotNull(a.thoroughfare, a.locality, a.adminArea, a.countryName).joinToString(", ") }
        }.getOrNull()
        return ToolResult.ok(
            "Latitudine %.5f, longitudine %.5f (precisione circa %d metri).".format(Locale.US, loc.latitude, loc.longitude, loc.accuracy.toInt()) +
                (address?.takeIf { it.isNotBlank() }?.let { " Indirizzo: $it." } ?: " Indirizzo non disponibile: se serve, ricavalo dalle coordinate con la ricerca web.")
        )
    }
}

// --------------------------------------------------------------------------- promemoria

class SetReminderTool(private val ctx: Context, private val store: ReminderStore) : MemyTool {
    override val name = "set_reminder"
    override val description = "Crea un promemoria: all'ora indicata il telefono mostra una notifica con il testo."
    override val properties = mapOf(
        "text" to prop("string", "Cosa ricordare"),
        "when" to prop("string", "Data e ora in formato ISO locale, es. 2026-11-12T18:00"),
    )
    override val required = listOf("text", "when")
    override suspend fun execute(input: ToolInput): ToolResult {
        val time = parseDateTime(input.requireString("when"))
            ?: return ToolResult.error("Data non valida, usa il formato 2026-11-12T18:00")
        if (time <= System.currentTimeMillis()) return ToolResult.error("L'orario indicato è già passato.")
        val r = store.add(input.requireString("text"), time)
        val warn = if (Build.VERSION.SDK_INT >= 33 && !ctx.hasPermission(Manifest.permission.POST_NOTIFICATIONS))
            " Attenzione: Memy non ha il permesso di mostrare notifiche, l'utente deve concederlo nelle impostazioni." else ""
        return ToolResult.ok("Promemoria n. ${r.id} impostato per ${fmt(time)}.$warn")
    }
}

class ListRemindersTool(private val store: ReminderStore) : MemyTool {
    override val name = "list_reminders"
    override val description = "Elenca i promemoria attivi."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val list = store.all().filter { it.timeMs > System.currentTimeMillis() }
        return ToolResult.ok(if (list.isEmpty()) "Nessun promemoria attivo." else list.joinToString("\n") { "n. ${it.id}: ${it.text} — ${fmt(it.timeMs)}" })
    }
}

class DeleteReminderTool(private val store: ReminderStore) : MemyTool {
    override val name = "delete_reminder"
    override val description = "Elimina un promemoria usando il suo numero (da list_reminders)."
    override val properties = mapOf("id" to prop("integer", "Numero del promemoria"))
    override val required = listOf("id")
    override suspend fun execute(input: ToolInput): ToolResult =
        if (store.remove(input.int("id") ?: -1)) ToolResult.ok("Promemoria eliminato.") else ToolResult.error("Promemoria non trovato.")
}

// --------------------------------------------------------------------------- memoria

class RememberTool(private val memory: MemoryStore) : MemyTool {
    override val name = "remember"
    override val description =
        "Salva nella memoria a lungo termine un'informazione sull'utente (preferenze, persone, abitudini, indirizzi) da ricordare nelle conversazioni future. " +
            "Usalo quando l'utente dice di ricordare qualcosa o condivide un fatto stabile e utile. Non salvare password o dati di pagamento."
    override val properties = mapOf(
        "fact" to prop("string", "Il fatto da ricordare, in una frase breve e autonoma"),
        "replaces" to prop("string", "Se il fatto aggiorna un ricordo vecchio (es. nuovo indirizzo), una parola o frase contenuta nel ricordo da sostituire"),
    )
    override val required = listOf("fact")
    override suspend fun execute(input: ToolInput): ToolResult {
        val fact = input.requireString("fact")
        val old = input.string("replaces")
        if (old != null) return if (memory.replace(old, fact)) ToolResult.ok("Ricordo aggiornato.") else ToolResult.error("Fatto vuoto.")
        return if (memory.add(fact)) ToolResult.ok("Salvato in memoria.") else ToolResult.ok("Lo ricordavo già.")
    }
}

class ForgetTool(private val memory: MemoryStore) : MemyTool {
    override val name = "forget"
    override val description = "Elimina dalla memoria i ricordi che contengono il testo indicato."
    override val properties = mapOf("about" to prop("string", "Parola o frase contenuta nel ricordo da eliminare"))
    override val required = listOf("about")
    override suspend fun execute(input: ToolInput): ToolResult {
        val n = memory.removeMatching(input.requireString("about"))
        return ToolResult.ok(if (n == 0) "Non ho trovato ricordi su questo." else "Ho dimenticato $n ricord${if (n == 1) "o" else "i"}.")
    }
}
