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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.nexa.assistant.agent.MemyAgent

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
        if (intent?.action == Intent.ACTION_SEND) {
            lifecycleScope.launch {
                val shared = runCatching { readShared(intent) }
                    .getOrElse { Triple(null, "", "non riesco ad aprire il file condiviso (${it.message ?: it.javaClass.simpleName})") }
                app.controller.onShared(shared.first, shared.second, shared.third)
            }
            return
        }
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

    /**
     * Legge ciò che un'altra app ha condiviso con Memy: allegato, come chiamarlo a voce, nome.
     * Allegato null = non leggibile; il nome contiene allora anche il motivo.
     */
    private suspend fun readShared(intent: Intent): Triple<MemyAgent.Attachment?, String, String> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
        // Alcune app mandano testo formattato: getStringExtra restituirebbe null.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uri == null) {
            val link = text?.trim()?.startsWith("http") == true
            return@withContext Triple(text?.let { MemyAgent.Attachment.Text(if (link) "link" else "testo", it) }, if (link) "questo link" else "questo testo", if (link) "Link" else "Testo")
        }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "file"
        val type = contentResolver.getType(uri) ?: intent.type ?: ""
        val zip = type in ZIP_TYPES || name.endsWith(".zip", true)
        val image = type.startsWith("image/")
        val limit = when { zip -> MAX_ZIP_BYTES; image -> MAX_IMAGE_BYTES; else -> MAX_SHARE_BYTES }
        // Si copia a pezzi in un file: gli zip possono pesare fino a 2 GB.
        val dir = if (zip) com.nexa.assistant.tools.Zips.shared(this@MainActivity) else cacheDir
        val file = java.io.File(dir, if (zip) name.replace('/', '_') else "condiviso.tmp")
        val size = try {
            contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { out -> copyCapped(input, out, limit) } }
        } catch (e: Exception) {
            return@withContext Triple(null, "", "$name: non riesco ad aprire il file (${e.message ?: "accesso negato"})")
        }
        if (size > limit) {
            file.delete()
            return@withContext Triple(null, "", "$name: troppo grande (massimo ${limit shr 20} MB)")
        }
        when {
            zip -> {
                val summary = runCatching { com.nexa.assistant.tools.Zips.describe(null, file, 60) }
                    .getOrElse { return@withContext Triple(null, "", "$name: zip non valido (${it.message})") }
                Triple(
                    MemyAgent.Attachment.Text(name, summary + "\nPer leggerlo o estrarlo usa gli strumenti zip con file=\"${file.name}\"."),
                    "questo archivio zip", name,
                )
            }
            image -> {
                // Decodifica già ridotta (le foto da 200 MP non entrano in memoria) e rotazione corretta.
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1568) sample *= 2
                val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@withContext Triple(null, "", "$name: immagine non leggibile")
                val degrees = runCatching {
                    when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }.getOrDefault(0)
                val rotated = if (degrees == 0) bmp else android.graphics.Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true,
                )
                val scale = minOf(1f, 1568f / maxOf(rotated.width, rotated.height))
                val scaled = android.graphics.Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true)
                val jpeg = java.io.ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                Triple(MemyAgent.Attachment.Image(jpeg), "questa foto", name)
            }
            type == "application/pdf" || name.endsWith(".pdf", true) -> Triple(MemyAgent.Attachment.Pdf(file.readBytes()), "questo PDF", name)
            name.endsWith(".docx", true) || type == DOCX_TYPE ->
                Triple(MemyAgent.Attachment.Text(name, com.nexa.assistant.documents.DocxRenderer.extractText(file.readBytes())), "questo documento", name)
            type.startsWith("text/") -> Triple(MemyAgent.Attachment.Text(name, file.readText()), "questo file", name)
            else -> Triple(null, "", "$name: non so leggere questo tipo di file")
        }.also { if (!zip) file.delete() }
    }

    /** Copia fino a [limit] byte (+1 per accorgersi che è troppo grande). */
    private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream, limit: Long): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        while (total <= limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return total
    }

    companion object {
        private const val MAX_SHARE_BYTES = 20L shl 20
        private const val MAX_IMAGE_BYTES = 200L shl 20
        private const val MAX_ZIP_BYTES = 2L shl 30
        private const val DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")
        const val EXTRA_LISTEN = "com.nexa.assistant.LISTEN"
    }
}
