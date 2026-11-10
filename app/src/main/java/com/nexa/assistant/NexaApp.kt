package com.nexa.assistant

import android.app.Application
import com.nexa.assistant.data.NexaSettings
import com.nexa.assistant.update.UpdateManager

class NexaApp : Application() {

    lateinit var settings: NexaSettings
        private set

    lateinit var updates: UpdateManager
        private set

    /** Unico "cervello" dell'app: vive finché vive il processo, così la conversazione non si perde. */
    val controller: NexaController by lazy { NexaController(this) }

    override fun onCreate() {
        super.onCreate()
        settings = NexaSettings(this)
        updates = UpdateManager(this, settings)
    }
}
