package com.kaori.androdex.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kaori.androdex.core.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class DiagnosticsManager(context: Context) {
    private val prefs = context.getSharedPreferences("androdex_diagnostics", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val redactor = SecretRedactor()
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val mutableEntries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<ActivityLogEntry>> = mutableEntries.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun registerSecret(value: String?) = redactor.add(value)

    @Synchronized
    fun sanitize(value: String): String = redactor.redact(value)

    @Synchronized
    fun log(kind: String, message: String, durationMillis: Long? = null, bytes: Long? = null, error: Boolean = false) {
        val entry = ActivityLogEntry(
            id = nextId.incrementAndGet(),
            timeMillis = System.currentTimeMillis(),
            kind = kind.take(48),
            message = redactor.redact(message),
            durationMillis = durationMillis,
            bytes = bytes,
        )
        if (error) lastError = entry.message
        val next = (mutableEntries.value + entry).takeLast(MAX_ENTRIES)
        mutableEntries.value = next
        prefs.edit().putString(KEY_LOGS, gson.toJson(next)).apply()
    }

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
        lastError = null
        prefs.edit().remove(KEY_LOGS).apply()
    }

    private fun loadEntries(): List<ActivityLogEntry> {
        val raw = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        val type = object : TypeToken<List<ActivityLogEntry>>() {}.type
        return runCatching { gson.fromJson<List<ActivityLogEntry>>(raw, type) ?: emptyList() }
            .getOrDefault(emptyList())
            .takeLast(MAX_ENTRIES)
            .also { entries -> lastError = entries.lastOrNull { it.kind == "错误" }?.message }
    }

    private companion object {
        const val KEY_LOGS = "recent_events"
        const val MAX_ENTRIES = 300
    }
}
