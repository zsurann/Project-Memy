package com.nexa.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.nexa.assistant.ChatLine
import com.nexa.assistant.MemyApp
import com.nexa.assistant.Phase
import com.nexa.assistant.UiState
import com.nexa.assistant.speech.ElevenUsage
import com.nexa.assistant.speech.ElevenVoice
import com.nexa.assistant.speech.VoiceCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Genera immagini dell'interfaccia per controllarla a occhio:
 * `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'` → app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<MemyApp>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MemyTheme { content() } }
        compose.mainClock.advanceTimeBy(1800)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Simula chiavi già salvate (il vero salvataggio usa il Keystore di Android, assente nei test). */
    private fun pretendKeys(eleven: Boolean) {
        app.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE).edit()
            .putString("api_key_enc", "x").apply { if (eleven) putString("eleven_key_enc", "x") }.commit()
        app.settings.setVoice("v1", "Sarah")
        app.settings.setUserName("Genny")
    }

    @Test fun orbStates() = shot("orb") {
        AuroraBackground {
            Column { Phase.entries.forEach { p -> MemyOrb(p, if (p == Phase.LISTENING) 0.7f else 0.3f, 140.dp) } }
        }
    }

    @Test fun homeEmpty() {
        pretendKeys(eleven = true)
        shot("home") { HomeScreen(app, {}, {}) }
    }

    @Test fun homeChat() {
        pretendKeys(eleven = true)
        app.controller.setPreviewState(
            UiState(
                phase = Phase.THINKING,
                lines = listOf(
                    ChatLine(1, true, "Svegliami alle 7"),
                    ChatLine(2, false, "Fatto, Genny! Ti ho impostato la sveglia alle sette di domattina, mercoledì 11 novembre. Buon riposo!"),
                    ChatLine(3, true, "Scrivi a Marco su WhatsApp che arrivo tra dieci minuti"),
                    ChatLine(4, false, "Certo, gli scrivo subito.", streaming = true, tool = "tap_element"),
                ),
            ),
            0.4f,
        )
        shot("chat") { HomeScreen(app, {}, {}) }
    }

    @Test fun homeListening() {
        pretendKeys(eleven = true)
        app.controller.setPreviewState(
            UiState(
                phase = Phase.LISTENING, listenerName = "ElevenLabs Scribe", partial = "che tempo fa",
                lines = listOf(ChatLine(1, true, "Ciao Memy, come stai?"), ChatLine(2, false, "Ciao Genny! Sto benissimo, grazie. Tu come stai?")),
            ),
            0.8f,
        )
        shot("listening") { HomeScreen(app, {}, {}) }
    }

    @Test fun settingsCollapsed() {
        pretendKeys(eleven = true)
        previewExpandAllSections = false
        shot("settings") { SettingsScreen(app) {} }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4300dp-xxhdpi")
    fun settingsExpanded() {
        pretendKeys(eleven = true)
        app.memory.add("La sorella di Genny si chiama Giulia")
        app.memory.add("Preferisce il caffè senza zucchero")
        fun v(id: String, name: String, g: String, style: String, accent: String, free: Boolean = true) =
            ElevenVoice(id, name, if (free) "premade" else "cloned", g, accent, "young", style, "https://x/$id.mp3")
        app.controller.voice.setPreviewCatalog(
            VoiceCatalog(
                voices = listOf(
                    v("v1", "Sarah", "female", "professional", "american"),
                    v("v2", "Laura", "female", "sassy", "american"),
                    v("v3", "George", "male", "warm", "british"),
                    v("v4", "Charlie", "male", "hyped", "australian"),
                    v("v5", "La mia voce", "male", "", "italian", free = false),
                ),
                usage = ElevenUsage("free", 3200, 10000),
            ),
            previewingId = "v3",
        )
        previewExpandAllSections = true
        try {
            shot("settings-full") { SettingsScreen(app) {} }
        } finally {
            previewExpandAllSections = false
        }
    }
}
