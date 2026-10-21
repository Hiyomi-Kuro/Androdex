package com.kaori.androdex.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.JsonObject
import com.kaori.androdex.data.DiagnosticsManager
import com.kaori.androdex.data.SettingsRepository
import com.kaori.androdex.data.TunnelConnectionState
import com.kaori.androdex.mcp.McpRequestHandler
import com.kaori.androdex.mcp.McpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import java.util.concurrent.TimeUnit

class TunnelConnectionManager(
    context: Context,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsManager,
    private val tunnelClient: TunnelClient,
    private val mcpHandler: McpRequestHandler,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(TunnelConnectionState.STOPPED)
    val state: StateFlow<TunnelConnectionState> = mutableState.asStateFlow()

    @Volatile
    private var running = false
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var pollJob: Job? = null

    fun start() {
        synchronized(this) {
            if (pollJob?.isActive == true) return
            running = true
            lastError = null
            mutableState.value = TunnelConnectionState.STARTING
            pollJob = scope.launch { runLoop() }
        }
        diagnostics.log("服务", "用户启动 Secure MCP Tunnel 服务；正在检查连接")
    }

    fun stop() {
        synchronized(this) {
            running = false
            pollJob?.cancel()
            pollJob = null
            mutableState.value = TunnelConnectionState.STOPPED
        }
        tunnelClient.cancelInFlightRequests()
        diagnostics.log("连接断开", "用户停止 Tunnel 服务")
    }

    fun notifyNetworkChanged() {
        wakeups.trySend(Unit)
    }

    fun isServiceActive(): Boolean = running

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("service_active", running)
        addProperty("tunnel_state", mutableState.value.name.lowercase())
        addProperty("mcp_transport", "OpenAI Secure MCP Tunnel control-plane polling")
        addProperty("mcp_server", "Android in-process stateless MCP handler")
        addProperty("last_error", diagnostics.sanitize(lastError ?: ""))
        addProperty("chatgpt_connector_status", "unknown_requires_chatgpt_ui_check")
    }

    private suspend fun runLoop() {
        var backoffSeconds = 2L
        var wasConnected = false
        try {
            while (running && currentCoroutineContext().isActive) {
                if (!hasNetwork()) {
                    mutableState.value = TunnelConnectionState.DISCONNECTED
                    if (wasConnected) diagnostics.log("连接断开", "网络当前不可用")
                    wasConnected = false
                    if (!settings.settings().autoReconnect) break
                    waitForWakeOrDelay(15_000)
                    continue
                }
                try {
                    if (!wasConnected) mutableState.value = TunnelConnectionState.STARTING
                    val batch = withContext(Dispatchers.IO) { tunnelClient.poll() }
                    if (!wasConnected) diagnostics.log("连接成功", "已收到 OpenAI Secure MCP Tunnel 轮询响应")
                    wasConnected = true
                    lastError = null
                    mutableState.value = TunnelConnectionState.CONNECTED
                    backoffSeconds = 2L
                    processAll(batch.commands)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!running) break
                    wasConnected = false
                    val safe = when (error) {
                        is TunnelApiException -> error.message ?: "Tunnel 请求失败"
                        is IllegalArgumentException -> error.message ?: "配置无效"
                        else -> "Tunnel 连接失败（${error.javaClass.simpleName}）"
                    }
                    lastError = diagnostics.sanitize(safe)
                    diagnostics.log("错误", lastError ?: "Tunnel 连接失败", error = true)
                    val authError = error is TunnelApiException && !error.retryable && error.httpStatus in setOf(401, 403, 404)
                    mutableState.value = if (authError || error is IllegalArgumentException) TunnelConnectionState.AUTH_ERROR
                    else TunnelConnectionState.DISCONNECTED
                    val retryable = if (error is TunnelApiException) error.retryable else TunnelErrorPolicy.retryableTransportFailure(error)
                    if (!settings.settings().autoReconnect || authError || !retryable) break
                    val delayMillis = backoffSeconds * 1_000
                    waitForWakeOrDelay(delayMillis)
                    backoffSeconds = min(backoffSeconds * 2, MAX_BACKOFF_SECONDS)
                }
            }
        } catch (_: CancellationException) {
            // Explicit service stop cancels polling, in-flight file handling, and response delivery.
        } finally {
            if (!running) mutableState.value = TunnelConnectionState.STOPPED
        }
    }

    private suspend fun processAll(commands: List<TunnelCommand>) {
        if (commands.isEmpty()) return
        val semaphore = Semaphore(MAX_PARALLEL_COMMANDS)
        coroutineScope {
            commands.map { command ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { process(command) }
                }
            }
                .awaitAll()
        }
    }

    private suspend fun process(command: TunnelCommand) {
        if (!running) return
        val timeoutNanos = command.responseTimeoutNanos
        val deadline = timeoutNanos?.let { command.receivedAtNanos + it }
        if (isExpired(command)) {
            diagnostics.log("MCP 请求", "丢弃已过期的 Tunnel 命令 request_id=${command.requestId}")
            return
        }
        val configuredMillis = settings.settings().networkTimeoutSeconds * 1_000L
        val remainingMillis = timeoutNanos?.let { remainingMillis(command) }
        val commandBudget = if (remainingMillis == null) configuredMillis else min(configuredMillis, remainingMillis)
        if (commandBudget <= 0) return
        val startedAt = System.nanoTime()
        try {
            val response = withTimeout(commandBudget.coerceAtLeast(1)) {
                withContext(Dispatchers.IO) {
                    if (command.commandType == "session_termination") {
                        McpResponse(null, 204, "session_termination_response", emptyMap())
                    } else {
                        val rpc = command.jsonrpc ?: throw IllegalArgumentException("Tunnel 命令缺少 JSON-RPC 请求")
                        mcpHandler.handle(rpc, command.headers)
                    }
                }
            }
            if (!running || isExpired(command)) {
                diagnostics.log("MCP 请求", "MCP 处理完成但已停止或超过响应期限，未发送 response")
                return
            }
            tunnelClient.post(command, response, deadline)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val bytes = response.body?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong()
            diagnostics.log("MCP 请求", "已完成 ${command.commandType} 命令，HTTP ${response.statusCode}", elapsed, bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!running || isExpired(command)) {
                diagnostics.log("MCP 请求", "命令取消或超时，未发送延迟响应")
                return
            }
            diagnostics.log("错误", "Tunnel 命令处理或响应提交失败（${error.javaClass.simpleName}）", error = true)
        }
    }

    private fun isExpired(command: TunnelCommand): Boolean {
        val timeout = command.responseTimeoutNanos ?: return false
        return timeout - (System.nanoTime() - command.receivedAtNanos) <= 0
    }

    private fun remainingMillis(command: TunnelCommand): Long {
        val timeout = command.responseTimeoutNanos ?: return Long.MAX_VALUE
        val nanos = timeout - (System.nanoTime() - command.receivedAtNanos)
        if (nanos <= 0) return 0
        return (nanos + 999_999) / 1_000_000
    }

    private fun hasNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun waitForWakeOrDelay(milliseconds: Long) {
        kotlinx.coroutines.withTimeoutOrNull(milliseconds) { wakeups.receive() }
    }

    companion object {
        private const val MAX_PARALLEL_COMMANDS = 3
        private const val MAX_BACKOFF_SECONDS = 60L
    }
}
