package com.nexa.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** La memoria a lungo termine di Memy: brevi fatti che l'utente ha chiesto di ricordare. */
class MemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("memy_memory", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    private fun load(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY, JSONArray(list).toString()).apply()
        _items.value = list
    }

    fun add(fact: String): Boolean {
        val clean = fact.trim()
        if (clean.isEmpty() || _items.value.any { it.equals(clean, ignoreCase = true) }) return false
        save((_items.value + clean).takeLast(MAX_ITEMS))
        return true
    }

    /** Sostituisce i ricordi superati (che contengono [old]) con il fatto nuovo. */
    fun replace(old: String, fact: String): Boolean {
        val q = old.trim()
        val kept = if (q.isEmpty()) _items.value else _items.value.filterNot { it.contains(q, ignoreCase = true) }
        val clean = fact.trim()
        if (clean.isEmpty()) return false
        save((kept.filterNot { it.equals(clean, ignoreCase = true) } + clean).takeLast(MAX_ITEMS))
        return true
    }

    /** Rimuove i ricordi che contengono il testo dato. Restituisce quanti ne ha tolti. */
    fun removeMatching(query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        val kept = _items.value.filterNot { it.contains(q, ignoreCase = true) }
        val removed = _items.value.size - kept.size
        if (removed > 0) save(kept)
        return removed
    }

    fun remove(item: String) = save(_items.value - item)

    private companion object {
        const val KEY = "items"
        const val MAX_ITEMS = 100
    }
}
