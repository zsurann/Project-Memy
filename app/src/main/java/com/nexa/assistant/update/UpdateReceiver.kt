package com.nexa.assistant.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.nexa.assistant.MemyApp

/** Riceve l'esito dell'installazione dell'aggiornamento. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android chiede all'utente di confermare l'aggiornamento.
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm ?: return
                val app = context.applicationContext as? MemyApp
                if (app?.controller?.inForeground == true) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    // Dal background Android non lascia aprire la conferma: la si offre con una notifica.
                    val nm = context.getSystemService(android.app.NotificationManager::class.java)
                    nm.createNotificationChannel(android.app.NotificationChannel("aggiornamenti", "Aggiornamenti", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                    val pi = android.app.PendingIntent.getActivity(
                        context, 7, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    val n = com.nexa.assistant.MemyNotifications.builder(context, "aggiornamenti")
                        .setContentTitle("Aggiornamento di Memy pronto")
                        .setContentText("Tocca per installarlo")
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .build()
                    runCatching { nm.notify(45, n) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // l'app viene riavviata dal sistema
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore sconosciuto"
                (context.applicationContext as? MemyApp)?.updates?.reportFailure("Aggiornamento non installato: $msg")
            }
        }
    }
}
