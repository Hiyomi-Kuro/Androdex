package com.kaori.androdex.data

enum class RootKind { SAF, PATH }

data class AuthorizedRoot(
    val id: String,
    val kind: RootKind,
    val name: String,
    val displayPath: String,
    val uriString: String? = null,
    val filePath: String? = null,
    val enabled: Boolean = true,
)

data class UserSettings(
    val pluginName: String = "Androdex",
    val tunnelId: String = "",
    val networkTimeoutSeconds: Int = 30,
    val autoReconnect: Boolean = true,
    val maxReadBytes: Long = 5L * 1024 * 1024,
    val maxOutputChars: Int = 65_536,
    val debugLogging: Boolean = false,
    val autoStart: Boolean = false,
)

enum class TunnelConnectionState { STOPPED, STARTING, CONNECTED, DISCONNECTED, AUTH_ERROR, SERVICE_ERROR }

data class ActivityLogEntry(
    val id: Long,
    val timeMillis: Long,
    val kind: String,
    val message: String,
    val durationMillis: Long? = null,
    val bytes: Long? = null,
)

data class RuntimeStats(
    val serviceActive: Boolean = false,
    val lastToolCallMillis: Long? = null,
    val filesRead: Long = 0,
    val lastError: String? = null,
)
