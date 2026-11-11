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
import com.nexa.assistant.speech.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Ehi Memy" sempre in ascolto, anche con l'app chiusa o lo schermo spento.
 *
 * È un servizio in primo piano di tipo "microfono" con una notifica fissa. Il riconoscimento avviene tutto
 * sul telefono ([WakeWordDetector]): nessun audio esce dal dispositivo finché non dici "Ehi Memy".
 * Mentre Memy ascolta, pensa o parla, il rilevatore si mette in pausa e lascia libero il microfono.
 */
class MemyWakeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector by lazy { WakeWordDetector(this) }
    private var watch: Job? = null
    /** Cambia valore per far ripartire l'ascolto (dopo un errore o un'attivazione a vuoto). */
    private val restart = MutableStateFlow(0)

    private val app get() = application as MemyApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            app.settings.setWakeWord(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!wanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.isSuccess
        if (!started) {
            // Android permette di usare il microfono in background solo se il servizio parte con l'app visibile.
            remind(this)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)
        if (watch == null) watch = scope.launch { watchAndListen() }
        return START_STICKY
    }

    private suspend fun watchAndListen() {
        val controller = app.controller
        combine(
            controller.state.map { it.phase == Phase.IDLE }.distinctUntilChanged(),
            controller.dictating,
            app.settings.state.map { it.wakeSensitivity }.distinctUntilChanged(),
            restart,
        ) { idle, dictating, sensitivity, _ -> sensitivity.takeIf { idle && !dictating } }
            .collectLatest { sensitivity ->
                detector.stop()
                if (sensitivity == null) return@collectLatest
                // Breve attesa: la coda della voce di Memy non deve arrivare al rilevatore.
                delay(RESUME_DELAY_MS)
                detector.start(
                    sensitivity,
                    onDetected = { recorder ->
                        scope.launch {
                            val listening = running && controller.onWakeWord(recorder)
                            if (!running) runCatching { recorder.release() }
                            if (!listening) retryLater()
                        }
                    },
                    onError = { scope.launch { retryLater() } },
                )
            }
    }

    private suspend fun retryLater() {
        delay(RETRY_DELAY_MS)
        restart.value++
    }

    override fun onDestroy() {
        running = false
        detector.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "«Ehi Memy» attiva", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostrata mentre Memy aspetta che tu dica «Ehi Memy»"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this, 2, Intent(this, MemyWakeService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return com.nexa.assistant.MemyNotifications.builder(this, CHANNEL)
            .setContentTitle("Memy è pronta")
            .setContentText("Dì «Ehi Memy» per parlarmi")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Disattiva", off)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "attivazione"
        private const val REMINDER_CHANNEL = "attivazione_avvisi"
        private const val NOTIFICATION_ID = 43
        private const val REMINDER_ID = 44
        private const val ACTION_OFF = "com.nexa.assistant.WAKE_OFF"
        private const val RESUME_DELAY_MS = 400L
        private const val RETRY_DELAY_MS = 3_000L

        /** true mentre il servizio è attivo: il microfono funziona anche fuori dall'app. */
        @Volatile
        var running = false
            private set

        private fun wanted(context: Context): Boolean {
            val app = context.applicationContext as? MemyApp ?: return false
            return app.settings.state.value.wakeWord &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        /** Allinea il servizio all'impostazione: lo avvia se "Ehi Memy" è attiva, altrimenti lo ferma. */
        fun sync(context: Context) {
            val intent = Intent(context, MemyWakeService::class.java)
            if (!wanted(context)) {
                if (running) context.stopService(intent)
                return
            }
            if (running) return
            // startService (e non startForegroundService): se Android rifiuta l'avvio in background
            // si riceve un'eccezione gestibile, invece di un errore dell'app.
            runCatching { context.startService(intent) }.onFailure { remind(context) }
        }

        /** Avviso: "Ehi Memy" non è potuta ripartire da sola, basta aprire Memy. */
        private fun remind(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL, "Riattivare «Ehi Memy»", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = com.nexa.assistant.MemyNotifications.builder(context, REMINDER_CHANNEL)
                .setContentTitle("«Ehi Memy» è in pausa")
                .setContentText("Tocca per riattivare l'ascolto")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching { nm.notify(REMINDER_ID, notification) }
        }
    }
}
