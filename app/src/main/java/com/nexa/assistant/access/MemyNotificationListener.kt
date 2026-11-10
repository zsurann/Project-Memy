package com.nexa.assistant.access

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Permette a Memy di leggere le notifiche, rispondere ai messaggi e sapere cosa è in riproduzione. */
class MemyNotificationListener : NotificationListenerService() {

    data class Item(val key: String, val app: String, val title: String, val text: String, val time: Long, val canReply: Boolean)

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun visible(): List<StatusBarNotification> = runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
        .filter { it.packageName != packageName && !it.isOngoing }
        .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        .sortedByDescending { it.postTime }

    fun list(): List<Item> = visible().map { sbn ->
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        Item(sbn.key, appLabel(sbn.packageName), title, text.take(300), sbn.postTime, replyAction(sbn) != null)
    }.filter { it.title.isNotBlank() || it.text.isNotBlank() }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** Risponde a una notifica di messaggistica (WhatsApp, Telegram, SMS…) senza aprire l'app. */
    fun reply(key: String, message: String): Boolean {
        val sbn = visible().firstOrNull { it.key == key } ?: return false
        val action = replyAction(sbn) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, message) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent); true }.getOrDefault(false)
    }

    fun dismiss(key: String?): Int {
        if (key == null) {
            val n = visible().size
            runCatching { cancelAllNotifications() }
            return n
        }
        return runCatching { cancelNotification(key); 1 }.getOrDefault(0)
    }

    /** Apre l'app/schermata collegata alla notifica. */
    fun open(key: String): Boolean {
        val sbn = visible().firstOrNull { it.key == key } ?: return false
        return runCatching { sbn.notification.contentIntent?.send(); true }.getOrDefault(false)
    }

    /** Titolo e artista del brano in riproduzione, se c'è. */
    fun nowPlaying(): String? {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = runCatching {
            msm.getActiveSessions(ComponentName(this, MemyNotificationListener::class.java))
        }.getOrDefault(emptyList())
        val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            ?: return null
        val md = active.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val playing = active.playbackState?.state == PlaybackState.STATE_PLAYING
        return "\"$title\"${artist?.let { " di $it" } ?: ""} su ${appLabel(active.packageName)} (${if (playing) "in riproduzione" else "in pausa"})"
    }

    companion object {
        @Volatile
        var instance: MemyNotificationListener? = null
            private set

        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) == true
    }
}
