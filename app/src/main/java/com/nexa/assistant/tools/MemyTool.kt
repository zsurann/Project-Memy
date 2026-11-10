package com.nexa.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nexa.assistant.access.MemyAccessibilityService
import java.text.Normalizer
import java.util.Locale

/**
 * Uno strumento che Claude può usare sul telefono.
 * Lo schema è un normale JSON Schema (come Map) che descrive i parametri.
 */
interface MemyTool {
    val name: String
    val description: String
    val properties: Map<String, Map<String, Any>>
    val required: List<String>

    /** Se non null, l'azione è delicata: Memy legge questa frase e aspetta un "sì". */
    suspend fun confirmationPrompt(input: ToolInput): String? = null

    suspend fun execute(input: ToolInput): ToolResult
}

class ToolResult(val text: String, val isError: Boolean = false, val imageJpeg: ByteArray? = null) {
    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** Accesso comodo ai parametri arrivati da Claude. */
class ToolInput(val raw: Map<String, Any?>) {
    fun string(key: String): String? = raw[key]?.toString()?.takeIf { it.isNotBlank() }
    fun requireString(key: String): String = string(key) ?: throw IllegalArgumentException("Manca il parametro '$key'")
    fun int(key: String): Int? = when (val v = raw[key]) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt()
        else -> null
    }
    fun bool(key: String): Boolean? = when (val v = raw[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }
    @Suppress("UNCHECKED_CAST")
    fun intList(key: String): List<Int> = (raw[key] as? List<Any?>).orEmpty().mapNotNull {
        when (it) {
            is Number -> it.toInt()
            is String -> it.toIntOrNull()
            else -> null
        }
    }
}

internal fun prop(type: String, description: String, extra: Map<String, Any> = emptyMap()): Map<String, Any> =
    mapOf("type" to type, "description" to description) + extra

internal fun enumProp(description: String, values: List<String>) = prop("string", description, mapOf("enum" to values))

internal fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/**
 * Apre una schermata. Se il servizio di accessibilità è attivo si passa da lì:
 * Android gli permette di aprire app anche quando Memy non è in primo piano.
 */
internal fun Context.launch(intent: Intent): Boolean = try {
    (MemyAccessibilityService.instance ?: this).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

internal fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal const val NEED_ACCESSIBILITY =
    "Il controllo dello schermo non è attivo. Di' all'utente di attivarlo in Memy: Impostazioni → Poteri → Controllo dello schermo."
internal const val NEED_NOTIFICATIONS =
    "L'accesso alle notifiche non è attivo. Di' all'utente di attivarlo in Memy: Impostazioni → Poteri → Notifiche."
