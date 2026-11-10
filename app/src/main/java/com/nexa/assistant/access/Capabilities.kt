package com.nexa.assistant.access

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings

/** Stato dei "poteri speciali" che l'utente attiva dalle impostazioni di sistema. */
object Capabilities {
    fun accessibilityEnabled(context: Context): Boolean {
        if (MemyAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains("${context.packageName}/") == true
    }

    fun notificationsEnabled(context: Context) = MemyNotificationListener.isEnabled(context)

    fun canWriteSettings(context: Context) = Settings.System.canWrite(context)

    fun canControlDnd(context: Context) =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
}
