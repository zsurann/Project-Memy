package com.nexa.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nexa.assistant.ui.HomeScreen
import com.nexa.assistant.ui.MemyTheme
import com.nexa.assistant.ui.SettingsScreen
import com.nexa.assistant.update.UpdateManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as MemyApp
    private var listenWhenGranted = false
    /** Richiesta di ascolto (riquadro, tasto assistente) da gestire appena l'app è visibile. */
    private var pendingLaunch: Intent? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && listenWhenGranted) app.controller.startListening()
        if (granted) MemyWakeService.sync(this)
        listenWhenGranted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MemyTheme {
                var showSettings by rememberSaveable { mutableStateOf(!app.settings.state.value.hasApiKey) }
                AnimatedContent(
                    targetState = showSettings,
                    transitionSpec = {
                        if (targetState) {
                            (slideInHorizontally(tween(350)) { it / 3 } + fadeIn(tween(350))) togetherWith fadeOut(tween(200))
                        } else {
                            fadeIn(tween(350)) togetherWith (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(250)))
                        }
                    },
                    label = "screen",
                ) { settingsVisible ->
                    if (settingsVisible) {
                        SettingsScreen(app = app, onBack = { showSettings = false })
                    } else {
                        HomeScreen(app = app, onOrb = ::onOrb, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
        // Si gestisce in onResume: qui l'app non è ancora "visibile" e il microfono non partirebbe.
        if (savedInstanceState == null) pendingLaunch = intent
    }

    override fun onStart() {
        super.onStart()
        app.controller.inForeground = true
        // Con l'app visibile Android permette di (ri)avviare l'ascolto di "Ehi Memy".
        MemyWakeService.sync(this)
    }

    override fun onStop() {
        app.controller.inForeground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        pendingLaunch?.let { pendingLaunch = null; handleLaunch(it) }
        // Nello scope dell'aggiornamento, non della schermata: chiudere l'app non lo interrompe a metà.
        app.updates.scope.launch {
            val st = app.updates.state.value
            if (st is UpdateManager.State.NeedsPermission && packageManager.canRequestPackageInstalls()) {
                app.updates.downloadAndInstall(st.release)
            } else {
                app.updates.checkInBackground()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLaunch = intent
    }

    /** Avviata dal riquadro rapido, dal tasto assistente o da un comando vocale: inizia subito ad ascoltare. */
    private fun handleLaunch(intent: Intent?) {
        val wantsListen = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND
        if (wantsListen && app.settings.state.value.hasApiKey) {
            intent?.removeExtra(EXTRA_LISTEN)
            if (app.controller.state.value.phase == Phase.IDLE) onOrb()
        }
    }

    private fun onOrb() {
        if (app.controller.hasMicPermission()) {
            app.controller.onOrbPressed()
        } else {
            listenWhenGranted = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val EXTRA_LISTEN = "com.nexa.assistant.LISTEN"
    }
}
