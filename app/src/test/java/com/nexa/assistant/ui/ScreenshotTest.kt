package com.nexa.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nexa.assistant.Phase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Genera immagini dei componenti grafici (solo per controllo visivo: `./gradlew testDebugUnitTest -Pscreenshots`).
 * Le immagini finiscono in app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { NexaTheme { content() } }
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun orbStates() = shot("orb") {
        AuroraBackground {
            androidx.compose.foundation.layout.Column {
                Phase.entries.forEach { p -> NexaOrb(p, if (p == Phase.LISTENING) 0.7f else 0.3f, androidx.compose.ui.unit.Dp(140f)) }
            }
        }
    }

    private val app get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<com.nexa.assistant.NexaApp>()

    @Test fun homeEmpty() {
        app.settings.setUserName("Luca")
        shot("home") { HomeScreen(app, {}, {}) }
    }

    @Test fun homeChat() {
        app.controller.setPreviewState(
            com.nexa.assistant.UiState(
                phase = Phase.SPEAKING,
                toolInUse = null,
                lines = listOf(
                    com.nexa.assistant.ChatLine(1, true, "Ciao Nexa, come stai?"),
                    com.nexa.assistant.ChatLine(2, false, "Ciao Luca! Sto benissimo, grazie. È una bella giornata per fare due chiacchiere: tu come stai?"),
                    com.nexa.assistant.ChatLine(3, true, "Che tempo fa domani a Milano?"),
                    com.nexa.assistant.ChatLine(4, false, "", streaming = true),
                ),
            ),
            0.6f,
        )
        shot("chat") { HomeScreen(app, {}, {}) }
    }

    @Test fun settings() {
        shot("settings") { SettingsScreen(app) {} }
    }
}
