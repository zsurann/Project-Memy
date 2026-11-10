package com.nexa.assistant.tools

/**
 * Uno strumento che Claude può usare sul telefono.
 * Lo schema è un normale JSON Schema (come Map) che descrive i parametri.
 */
interface NexaTool {
    val name: String
    val description: String
    val properties: Map<String, Map<String, Any>>
    val required: List<String>

    /** Se non null, l'azione è delicata: Nexa legge questa frase e aspetta un "sì". */
    fun confirmationPrompt(input: ToolInput): String? = null

    suspend fun execute(input: ToolInput): ToolResult
}

data class ToolResult(val text: String, val isError: Boolean = false) {
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
        is String -> v.toIntOrNull()
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
