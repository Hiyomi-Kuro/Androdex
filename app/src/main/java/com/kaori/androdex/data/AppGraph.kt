package com.kaori.androdex.data

import android.content.Context
import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kaori.androdex.files.FileAccessManager
import com.kaori.androdex.files.PermissionManager
import com.kaori.androdex.mcp.McpIntrospection
import com.kaori.androdex.mcp.McpRequestHandler
import com.kaori.androdex.mcp.McpCredentialProvider
import com.kaori.androdex.mcp.McpLogSink
import com.kaori.androdex.mcp.McpToolRegistry
import com.kaori.androdex.tunnel.TunnelClient
import com.kaori.androdex.tunnel.TunnelConnectionManager

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val settings = SettingsRepository(appContext)
    val credentials = SecureCredentialStore(appContext)
    val diagnostics = DiagnosticsManager(appContext)
    val permissionManager = PermissionManager(appContext, settings)
    val fileAccessManager = FileAccessManager(appContext, settings, diagnostics)
    val toolRegistry = McpToolRegistry()
    val tunnelClient = TunnelClient(settings, credentials, diagnostics)

    val connectionManager: TunnelConnectionManager
    val mcpRequestHandler: McpRequestHandler

    init {
        lateinit var manager: TunnelConnectionManager
        val introspection = McpIntrospection { method ->
            when (method) {
                "connection_status" -> manager.toJson()
                else -> capabilitiesJson()
            }
        }
        mcpRequestHandler = McpRequestHandler(
            tools = toolRegistry,
            executor = fileAccessManager,
            credentials = McpCredentialProvider { credentials.mcpBearer() },
            diagnostics = McpLogSink { kind, message, error -> diagnostics.log(kind, message, error = error) },
            introspection = introspection,
            serverName = { settings.settings().pluginName },
            maxOutputChars = { settings.settings().maxOutputChars },
        )
        manager = TunnelConnectionManager(appContext, settings, diagnostics, tunnelClient, mcpRequestHandler)
        connectionManager = manager
    }

    fun capabilitiesJson(): JsonObject {
        val roots = settings.roots()
        val formats = JsonArray().apply {
            listOf("TXT", "MD", "JSON", "XML", "YAML", "CSV", "LOG", "HTML", "CSS", "JS", "TS", "PY", "JAVA", "KT", "SQL", "SH", "INI", "TOML", "PDF", "DOCX", "XLSX", "PPTX").forEach(::add)
        }
        return JsonObject().apply {
            addProperty("android_release", Build.VERSION.RELEASE)
            addProperty("android_api", Build.VERSION.SDK_INT)
            addProperty("file_access", when {
                permissionManager.hasAllFilesAccess() -> "SAF + user-enabled shared-storage paths, each root separately allowlisted"
                else -> "SAF persistable folder grants"
            })
            addProperty("all_files_access_granted", permissionManager.hasAllFilesAccess())
            addProperty("authorized_root_count", roots.count { it.enabled })
            addProperty("read_only", true)
            addProperty("images_returned", false)
            add("supported_formats", formats)
            addProperty("scope", "每次访问均受 Androdex 已启用目录列表约束")
            addProperty("chatgpt_registration", "unknown_requires_chatgpt_ui_check")
        }
    }
}
