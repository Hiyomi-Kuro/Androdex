package com.kaori.androdex.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class SettingsRepository(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("androdex_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun settings(): UserSettings = UserSettings(
        pluginName = preferences.getString(KEY_PLUGIN_NAME, "Androdex") ?: "Androdex",
        tunnelId = preferences.getString(KEY_TUNNEL_ID, "") ?: "",
        networkTimeoutSeconds = preferences.getInt(KEY_NETWORK_TIMEOUT, 30).coerceIn(10, 120),
        autoReconnect = preferences.getBoolean(KEY_AUTO_RECONNECT, true),
        maxReadBytes = preferences.getLong(KEY_MAX_READ_BYTES, 5L * 1024 * 1024).coerceIn(256 * 1024L, 16 * 1024 * 1024L),
        maxOutputChars = preferences.getInt(KEY_MAX_OUTPUT_CHARS, 65_536).coerceIn(4_096, 262_144),
        debugLogging = preferences.getBoolean(KEY_DEBUG, false),
        autoStart = preferences.getBoolean(KEY_AUTO_START, false),
    )

    @Synchronized
    fun saveSettings(value: UserSettings) {
        preferences.edit()
            .putString(KEY_PLUGIN_NAME, value.pluginName.trim().take(48).ifBlank { "Androdex" })
            .putString(KEY_TUNNEL_ID, value.tunnelId.trim())
            .putInt(KEY_NETWORK_TIMEOUT, value.networkTimeoutSeconds.coerceIn(10, 120))
            .putBoolean(KEY_AUTO_RECONNECT, value.autoReconnect)
            .putLong(KEY_MAX_READ_BYTES, value.maxReadBytes.coerceIn(256 * 1024L, 16 * 1024 * 1024L))
            .putInt(KEY_MAX_OUTPUT_CHARS, value.maxOutputChars.coerceIn(4_096, 262_144))
            .putBoolean(KEY_DEBUG, value.debugLogging)
            .putBoolean(KEY_AUTO_START, value.autoStart)
            .apply()
    }

    @Synchronized
    fun tunnelClientInstanceId(): String {
        preferences.getString(KEY_TUNNEL_INSTANCE, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_TUNNEL_INSTANCE, value).apply()
        return value
    }

    @Synchronized
    fun roots(): List<AuthorizedRoot> {
        val raw = preferences.getString(KEY_ROOTS, "[]") ?: "[]"
        return runCatching {
            val type = object : TypeToken<List<AuthorizedRoot>>() {}.type
            gson.fromJson<List<AuthorizedRoot>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveRoot(root: AuthorizedRoot) {
        val updated = roots().filterNot { it.id == root.id } + root
        preferences.edit().putString(KEY_ROOTS, gson.toJson(updated)).apply()
    }

    @Synchronized
    fun updateRoot(id: String, transform: (AuthorizedRoot) -> AuthorizedRoot) {
        val updated = roots().map { if (it.id == id) transform(it) else it }
        preferences.edit().putString(KEY_ROOTS, gson.toJson(updated)).apply()
    }

    @Synchronized
    fun removeRoot(id: String) {
        val updated = roots().filterNot { it.id == id }
        preferences.edit().putString(KEY_ROOTS, gson.toJson(updated)).apply()
    }

    @Synchronized
    fun recordSuccessfulRead() {
        preferences.edit()
            .putLong(KEY_FILES_READ, preferences.getLong(KEY_FILES_READ, 0) + 1)
            .putLong(KEY_LAST_TOOL_CALL, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun recordToolCall() {
        preferences.edit().putLong(KEY_LAST_TOOL_CALL, System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun runtimeStats(serviceActive: Boolean, lastError: String?): RuntimeStats = RuntimeStats(
        serviceActive = serviceActive,
        lastToolCallMillis = preferences.getLong(KEY_LAST_TOOL_CALL, 0).takeIf { it > 0 },
        filesRead = preferences.getLong(KEY_FILES_READ, 0),
        lastError = lastError,
    )

    private companion object {
        const val KEY_PLUGIN_NAME = "plugin_name"
        const val KEY_TUNNEL_ID = "tunnel_id"
        const val KEY_NETWORK_TIMEOUT = "network_timeout"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_MAX_READ_BYTES = "max_read_bytes"
        const val KEY_MAX_OUTPUT_CHARS = "max_output_chars"
        const val KEY_DEBUG = "debug_logging"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_ROOTS = "authorized_roots"
        const val KEY_FILES_READ = "files_read"
        const val KEY_LAST_TOOL_CALL = "last_tool_call"
        const val KEY_TUNNEL_INSTANCE = "tunnel_client_instance_id"
    }
}
