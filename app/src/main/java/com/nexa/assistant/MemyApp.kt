package com.nexa.assistant

import android.app.Application
import com.nexa.assistant.data.MemoryStore
import com.nexa.assistant.data.MemySettings
import com.nexa.assistant.reminders.ReminderStore
import com.nexa.assistant.update.UpdateManager

class MemyApp : Application() {

    lateinit var settings: MemySettings
        private set
    lateinit var updates: UpdateManager
        private set
    lateinit var memory: MemoryStore
        private set
    lateinit var reminders: ReminderStore
        private set

    /** Unico "cervello" dell'app: vive finché vive il processo, così la conversazione non si perde. */
    val controller: MemyController by lazy { MemyController(this) }

    override fun onCreate() {
        super.onCreate()
        settings = MemySettings(this)
        updates = UpdateManager(this, settings)
        memory = MemoryStore(this)
        reminders = ReminderStore(this).also { it.rescheduleAll() }
    }
}
