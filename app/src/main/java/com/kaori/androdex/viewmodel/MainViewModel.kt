package com.kaori.androdex.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.kaori.androdex.AndrodexApplication
import com.kaori.androdex.core.TunnelProtocol
import com.kaori.androdex.data.ActivityLogEntry
import com.kaori.androdex.data.AuthorizedRoot
import com.kaori.androdex.data.RootKind
import com.kaori.androdex.data.TunnelConnectionState
import com.kaori.androdex.data.UserSettings
import com.kaori.androdex.files.FileAccessException
import com.kaori.androdex.mcp.McpRequestHandler
import com.kaori.androdex.service.ConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RootUiItem(val root: AuthorizedRoot, val accessible: Boolean, val issue: String?)
data class BrowserEntry(val name: String, val path: String, val directory: Boolean, val size: Long, val modified: String?, val image: Boolean)

data class MainUiState(
    val settings: UserSettings = UserSettings(),
    val roots: List<RootUiItem> = emptyList(),
    val serviceState: TunnelConnectionState = TunnelConnectionState.STOPPED,
    val serviceActive: Boolean = false,
    val allFilesAccess: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val mcpBearerConfigured: Boolean = false,
    val statsFilesRead: Long = 0,
    val lastToolCall: Long? = null,
    val lastError: String? = null,
    val logs: List<ActivityLogEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val browserRootId: String? = null,
    val browserPath: String = "",
    val browserEntries: List<BrowserEntry> = emptyList(),
    val previewTitle: String? = null,
    val previewText: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as AndrodexApplication).graph
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            graph.connectionManager.state.collect { serviceState ->
                refresh(serviceState = serviceState)
            }
        }
        viewModelScope.launch {
            graph.diagnostics.entries.collect { logs ->
                mutableState.update { it.copy(logs = logs) }
            }
        }
        refresh()
    }

    fun refresh() {
        refresh(serviceState = graph.connectionManager.state.value)
    }

    private fun refresh(serviceState: TunnelConnectionState) {
        val roots = graph.settings.roots().map { root ->
            val check = runCatching { graph.fileAccessManager.checkRoot(root.id) }
            RootUiItem(root, root.enabled && check.isSuccess, if (!root.enabled) "已暂停" else check.exceptionOrNull()?.message)
        }
        val runtime = graph.settings.runtimeStats(graph.connectionManager.isServiceActive(), graph.diagnostics.lastError)
        mutableState.update {
            it.copy(
                settings = graph.settings.settings(),
                roots = roots,
                serviceState = serviceState,
                serviceActive = graph.connectionManager.isServiceActive(),
                allFilesAccess = graph.permissionManager.hasAllFilesAccess(),
                apiKeyConfigured = graph.credentials.hasRuntimeApiKey(),
                mcpBearerConfigured = graph.credentials.hasMcpBearer(),
                statsFilesRead = runtime.filesRead,
                lastToolCall = runtime.lastToolCallMillis,
                lastError = runtime.lastError,
                logs = graph.diagnostics.entries.value,
            )
        }
    }

    fun saveSettings(value: UserSettings) {
        val error = when {
            value.pluginName.isBlank() -> "插件名称不能为空"
            value.pluginName.length > 48 -> "插件名称不能超过 48 个字符"
            value.tunnelId.isNotBlank() && !TunnelProtocol.isValidTunnelId(value.tunnelId.trim()) -> "Tunnel ID 格式不正确"
            else -> null
        }
        if (error != null) {
            showMessage(error)
            return
        }
        graph.settings.saveSettings(value)
        graph.diagnostics.log("配置", "已保存 Androdex 服务配置")
        refresh()
        showMessage("配置已保存。新插件名称不会自动修改 ChatGPT 中已注册的连接器。")
    }

    fun saveRuntimeApiKey(value: String) {
        if (value.isBlank()) {
            showMessage("密钥输入为空，已保留当前密钥；如需删除请点“清除密钥”。")
            return
        }
        graph.credentials.saveRuntimeApiKey(value.trim())
        graph.diagnostics.registerSecret(value.trim())
        graph.diagnostics.log("配置", "Runtime API Key 已加密保存")
        refresh()
        showMessage("Runtime API Key 已安全保存。")
    }

    fun clearRuntimeApiKey() {
        graph.credentials.saveRuntimeApiKey("")
        graph.diagnostics.log("配置", "已清除 Runtime API Key")
        refresh()
    }

    fun saveMcpBearer(value: String) {
        graph.credentials.saveMcpBearer(value.trim())
        graph.diagnostics.registerSecret(value.trim())
        graph.diagnostics.log("配置", if (value.isBlank()) "已清除可选 MCP Bearer 认证" else "可选 MCP Bearer 认证已加密保存")
        refresh()
        showMessage(if (value.isBlank()) "可选 MCP 认证已清除。" else "可选 MCP Bearer 认证已保存；ChatGPT 连接器需使用相同认证值。")
    }

    fun testConfiguration() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            val report = withContext(Dispatchers.IO) {
                val checks = mutableListOf<String>()
                try {
                    checks += "Tunnel ID 格式：${if (TunnelProtocol.isValidTunnelId(graph.settings.settings().tunnelId)) "通过" else "失败"}"
                    val tunnelResult = graph.tunnelClient.testConfiguration()
                    checks += "Tunnel Runtime API Key / 网络：$tunnelResult"
                    val init = graph.mcpRequestHandler.handle(JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        addProperty("id", 1)
                        addProperty("method", "initialize")
                        add("params", JsonObject().apply { addProperty("protocolVersion", McpRequestHandler.LATEST_LEGACY_PROTOCOL) })
                    })
                    val listed = graph.mcpRequestHandler.handle(JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        addProperty("id", 2)
                        addProperty("method", "tools/list")
                        add("params", JsonObject())
                    })
                    val toolCount = listed.body?.getAsJsonObject("result")?.getAsJsonArray("tools")?.size() ?: 0
                    if (init.statusCode == 200 && toolCount == 10) checks += "本地 MCP initialize / tools/list：通过（$toolCount 个工具）"
                    else checks += "本地 MCP initialize / tools/list：失败"
                    val usable = graph.settings.roots().firstOrNull { it.enabled }
                    if (usable == null) checks += "SAF/文件访问：尚未授权目录"
                    else checks += "目录访问权限：${runCatching { graph.fileAccessManager.testRoot(usable.id) }.getOrElse { "失败：${it.message}" }}"
                } catch (e: Exception) {
                    checks += "检查失败：${e.message ?: e.javaClass.simpleName}"
                    graph.diagnostics.log("错误", "配置诊断失败：${e.message ?: e.javaClass.simpleName}", error = true)
                }
                checks += "ChatGPT 连接器注册：Android 无法可靠查询；请在 ChatGPT 网页端手动验证。"
                checks.joinToString("\n")
            }
            mutableState.update { it.copy(busy = false, message = report) }
        }
    }

    fun addSafFolder(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val root = graph.permissionManager.saveSafTree(uri)
                graph.diagnostics.log("权限", "已添加 SAF 授权目录 ${root.displayPath}")
                refresh()
                showMessage("目录已授权：${root.displayPath}")
            } catch (e: Exception) {
                graph.diagnostics.log("错误", "保存 SAF 目录授权失败：${e.message ?: e.javaClass.simpleName}", error = true)
                showMessage("目录授权失败：${e.message ?: "系统未授予持久读取权限"}")
            }
        }
    }

    fun addPathFolder(path: String) {
        viewModelScope.launch {
            val result = runCatching { graph.permissionManager.savePathRoot(path) }
            result.onSuccess { root ->
                graph.diagnostics.log("权限", "已添加普通路径目录 ${root.displayPath}")
                refresh()
                showMessage("普通路径目录已加入授权列表。")
            }.onFailure { error -> showMessage(error.message ?: "目录添加失败") }
        }
    }

    fun setRootEnabled(rootId: String, enabled: Boolean) {
        graph.settings.updateRoot(rootId) { it.copy(enabled = enabled) }
        graph.diagnostics.log("权限", if (enabled) "已启用一个授权目录" else "已暂停一个授权目录")
        refresh()
    }

    fun removeRoot(rootId: String) {
        val root = graph.settings.roots().firstOrNull { it.id == rootId } ?: return
        graph.permissionManager.releaseRoot(root)
        graph.diagnostics.log("权限", "已删除目录授权 ${root.displayPath}")
        refresh()
        showMessage("目录授权已删除。")
    }

    fun testRoot(rootId: String, relativeFilePath: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val directoryResult = graph.fileAccessManager.testRoot(rootId)
                    if (relativeFilePath.isBlank()) directoryResult
                    else {
                        val output = graph.fileAccessManager.execute("read_file_chunk", JsonObject().apply {
                            addProperty("root_id", rootId)
                            addProperty("path", relativeFilePath)
                            addProperty("offset", 0)
                            addProperty("length", 128)
                        })
                        if (output.isError) throw FileAccessException(output.text)
                        "目录可读；测试文件读取成功（仅读取首个文本片段，未将内容写入日志或发送出去）。"
                    }
                } catch (e: Exception) {
                    "读取测试失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
            graph.diagnostics.log(if (result.startsWith("读取测试失败")) "错误" else "权限", result, error = result.startsWith("读取测试失败"))
            showMessage(result)
        }
    }

    fun browse(rootId: String, path: String = "") {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, browserRootId = rootId, browserPath = path) }
            val output = withContext(Dispatchers.IO) {
                graph.fileAccessManager.execute("list_directory", JsonObject().apply {
                    addProperty("root_id", rootId)
                    addProperty("path", path)
                    addProperty("recursive", false)
                    addProperty("limit", 200)
                    addProperty("offset", 0)
                })
            }
            val entries = output.structured.getAsJsonArray("entries")?.mapNotNull { item ->
                runCatching {
                    val json = item.asJsonObject
                    BrowserEntry(
                        name = json.get("name").asString,
                        path = json.get("path").asString,
                        directory = json.get("type").asString == "directory",
                        size = json.get("size_bytes")?.asLong ?: 0,
                        modified = json.get("modified_iso")?.takeIf { !it.isJsonNull }?.asString,
                        image = json.get("image_metadata_only")?.asBoolean ?: false,
                    )
                }.getOrNull()
            }.orEmpty()
            mutableState.update { it.copy(busy = false, browserEntries = entries, message = if (output.isError) output.text else null) }
        }
    }

    fun preview(rootId: String, path: String, document: Boolean) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            val output = withContext(Dispatchers.IO) {
                graph.fileAccessManager.execute(if (document) "read_document" else "read_text", JsonObject().apply {
                    addProperty("root_id", rootId)
                    addProperty("path", path)
                    if (document) addProperty("max_chars", 8_000)
                    else {
                        addProperty("start_line", 1)
                        addProperty("line_count", 200)
                        addProperty("max_chars", 8_000)
                    }
                })
            }
            val text = output.structured.get("text")?.takeIf { !it.isJsonNull }?.asString
            mutableState.update {
                it.copy(
                    busy = false,
                    previewTitle = path,
                    previewText = if (output.isError) output.text else text.orEmpty(),
                )
            }
        }
    }

    fun closePreview() = mutableState.update { it.copy(previewTitle = null, previewText = null) }
    fun clearLogs() { graph.diagnostics.clear(); refresh() }
    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    fun startService() {
        val intent = Intent(getApplication(), ConnectionService::class.java).setAction(ConnectionService.ACTION_START)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun stopService() {
        val intent = Intent(getApplication(), ConnectionService::class.java).setAction(ConnectionService.ACTION_STOP)
        getApplication<Application>().startService(intent)
    }

    fun openAllFilesSettings() {
        runCatching {
            val intent = graph.permissionManager.openAllFilesSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            showMessage("无法打开系统存储权限页面：${it.message ?: it.javaClass.simpleName}")
        }
    }
}
