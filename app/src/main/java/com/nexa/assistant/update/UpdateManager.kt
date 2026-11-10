package com.nexa.assistant.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nexa.assistant.BuildConfig
import com.nexa.assistant.data.MemySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Aggiornamenti automatici da GitHub: legge `version.json` dalla release "ultima-versione",
 * scarica il nuovo APK e lo installa con il PackageInstaller di Android.
 */
class UpdateManager(private val context: Context, private val settings: MemySettings) {

    data class Release(val versionCode: Long, val versionName: String, val notes: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data object Installing : State
        data class NeedsPermission(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /** Controllo "silenzioso" all'avvio: al massimo ogni 30 minuti. */
    suspend fun checkInBackground() {
        if (System.currentTimeMillis() - settings.lastUpdateCheck < 30 * 60_000L) return
        val rel = check() ?: return
        // In automatico scarica solo con Wi-Fi (l'APK pesa circa 45 MB); con i dati mobili resta l'avviso con il pulsante.
        if (settings.state.value.autoUpdate && !isMetered()) downloadAndInstall(rel)
    }

    private fun isMetered(): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        var lastError = "nessuna risposta"
        for (base in BuildConfig.UPDATE_BASE_URLS.split(',')) {
            try {
                val req = Request.Builder().url("$base/version.json?t=${System.currentTimeMillis()}").build()
                val json = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    JSONObject(resp.body!!.string())
                }
                settings.lastUpdateCheck = System.currentTimeMillis()
                val rel = Release(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.optString("versionName"),
                    notes = json.optString("notes"),
                    apkUrl = json.optString("apkUrl").ifBlank { "$base/Memy.apk" },
                )
                return@withContext if (rel.versionCode > currentVersionCode) {
                    _state.value = State.Available(rel); rel
                } else {
                    _state.value = State.UpToDate; null
                }
            } catch (e: Exception) {
                lastError = e.message ?: lastError
            }
        }
        _state.value = State.Failed("Controllo aggiornamenti non riuscito: $lastError")
        null
    }

    suspend fun downloadAndInstall(rel: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(rel)
            return
        }
        val apk = withContext(Dispatchers.IO) {
            try {
                download(rel)
            } catch (e: Exception) {
                _state.value = State.Failed("Download non riuscito: ${e.message}")
                null
            }
        } ?: return
        withContext(Dispatchers.IO) {
            try {
                install(apk)
                _state.value = State.Installing
            } catch (e: Exception) {
                _state.value = State.Failed("Installazione non riuscita: ${e.message}")
            }
        }
    }

    private fun download(rel: Release): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Memy-${rel.versionCode}.apk")
        http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: 45_000_000L
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        done += read
                        _state.value = State.Downloading(rel, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        return file
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Dopo il primo aggiornamento fatto da Memy stessa, i successivi non chiedono più conferma.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("memy.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    internal fun reportFailure(message: String) {
        _state.value = State.Failed(message)
    }

    fun dismiss() {
        _state.value = State.Idle
    }
}
