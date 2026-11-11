package com.nexa.assistant.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nexa.assistant.MainActivity
import com.nexa.assistant.R
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(val id: Int, val text: String, val timeMs: Long)

/** Promemoria di Memy: salvati sul telefono e riprogrammati dopo un riavvio. */
class ReminderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("memy_reminders", Context.MODE_PRIVATE)

    fun all(): List<Reminder> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Reminder(it.getInt("id"), it.getString("text"), it.getLong("time")) }
    }.getOrDefault(emptyList()).sortedBy { it.timeMs }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.timeMs)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(text: String, timeMs: Long): Reminder {
        val list = all()
        val reminder = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, text, timeMs)
        save(list + reminder)
        schedule(reminder)
        return reminder
    }

    fun remove(id: Int): Boolean {
        val list = all()
        val target = list.firstOrNull { it.id == id } ?: return false
        save(list - target)
        context.getSystemService(AlarmManager::class.java).cancel(pending(target))
        return true
    }

    /** Da chiamare all'avvio dell'app e dopo un riavvio del telefono. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val (future, past) = all().partition { it.timeMs > now }
        if (past.isNotEmpty()) save(future)
        future.forEach(::schedule)
    }

    private fun pending(r: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context, r.id,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_TEXT, r.text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun schedule(r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.timeMs, pending(r))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.timeMs, pending(r))
        }.onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.timeMs, pending(r)) }
    }

    companion object {
        private const val KEY = "items"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
        const val CHANNEL = "promemoria"
    }
}

/** Dopo un riavvio del telefono o un aggiornamento di Memy: riprogramma i promemoria e riattiva "Ehi Memy". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ReminderStore(context).rescheduleAll()
        // Riattiva "Ehi Memy"; se Android non lo permette da qui, compare una notifica per farlo con un tocco.
        com.nexa.assistant.MemyWakeService.sync(context)
    }
}

/** Mostra la notifica del promemoria all'ora stabilita. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ReminderStore(context)
        val id = intent.getIntExtra(ReminderStore.EXTRA_ID, 0)
        val text = intent.getStringExtra(ReminderStore.EXTRA_TEXT) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ReminderStore.CHANNEL, "Promemoria", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderStore.CHANNEL)
            .setSmallIcon(R.drawable.ic_memy_tile)
            .setContentTitle("Promemoria di Memy")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(1000 + id, notification) }
        store.remove(id)
    }
}
