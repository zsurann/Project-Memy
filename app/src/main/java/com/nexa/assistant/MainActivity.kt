package com.nexa.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.nexa.assistant.ui.HomeScreen
import com.nexa.assistant.ui.NexaTheme
import com.nexa.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val app get() = application as NexaApp
    private var listenWhenGranted = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && listenWhenGranted) app.controller.startListening()
        listenWhenGranted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexaTheme {
                var showSettings by rememberSaveable { mutableStateOf(!app.settings.state.value.hasApiKey) }
                if (showSettings) {
                    SettingsScreen(app = app, onBack = { showSettings = false })
                } else {
                    HomeScreen(
                        controller = app.controller,
                        onMic = ::onMic,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
        handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    /** Avviata dal riquadro rapido, dal tasto assistente o da un comando vocale: inizia subito ad ascoltare. */
    private fun handleLaunch(intent: Intent?) {
        val wantsListen = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND
        if (wantsListen && app.settings.state.value.hasApiKey) {
            intent?.removeExtra(EXTRA_LISTEN)
            if (app.controller.state.value.phase == Phase.IDLE) onMic()
        }
    }

    private fun onMic() {
        if (app.controller.hasMicPermission()) {
            app.controller.onMicPressed()
        } else {
            listenWhenGranted = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val EXTRA_LISTEN = "com.nexa.assistant.LISTEN"
    }
}
