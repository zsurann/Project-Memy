package com.nexa.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Servizio "in primo piano" attivo durante una conversazione con Memy.
 *
 * Android permette di usare il microfono a un'app non visibile solo se ha un servizio di questo tipo
 * avviato mentre l'app era aperta. Così Memy continua a sentirti anche quando lavora dentro un'altra app
 * (per esempio per rispondere "sì" a una conferma). Si ferma da solo quando la conversazione finisce.
 */
class MemySessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as? MemyApp)?.controller?.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Memy è in ascolto"
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(this, text),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.isSuccess
        running = started
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sessione"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.nexa.assistant.STOP_SESSION"
        private const val EXTRA_TEXT = "text"

        /** true mentre il servizio è attivo, quindi il microfono funziona anche fuori dall'app. */
        @Volatile
        var running = false
            private set

        private fun notification(context: Context, text: String): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Conversazione in corso", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mostrata mentre Memy ti ascolta o lavora per te"
                    setShowBadge(false)
                }
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, MemySessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_memy_tile)
                .setContentTitle("Memy")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(0, "Ferma", stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /** Avvia (o aggiorna) il servizio. Va chiamato mentre Memy è visibile: dopo, Android potrebbe non permetterlo. */
        fun start(context: Context, text: String): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, MemySessionService::class.java).putExtra(EXTRA_TEXT, text),
                )
                true
            }.getOrDefault(false)
        }

        /** Aggiorna solo il testo della notifica, se il servizio è già attivo. */
        fun update(context: Context, text: String) {
            if (!running) return
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, text)) }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, MemySessionService::class.java))
        }
    }
}
