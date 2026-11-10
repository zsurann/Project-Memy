package com.nexa.assistant

import android.app.Application
import com.nexa.assistant.data.NexaSettings

class NexaApp : Application() {

    lateinit var settings: NexaSettings
        private set

    /** Unico "cervello" dell'app: vive finché vive il processo, così la conversazione non si perde. */
    val controller: NexaController by lazy { NexaController(this) }

    override fun onCreate() {
        super.onCreate()
        settings = NexaSettings(this)
    }
}
