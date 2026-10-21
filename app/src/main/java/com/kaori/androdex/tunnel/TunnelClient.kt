package com.kaori.androdex.tunnel

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kaori.androdex.core.TunnelProtocol
import com.kaori.androdex.data.DiagnosticsManager
import com.kaori.androdex.data.SecureCredentialStore
import com.kaori.androdex.data.SettingsRepository
import com.kaori.androdex.mcp.McpRequestHandler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TunnelCommand(
    val requestId: String,
    val shardToken: String,
    val commandType: String,
    val channel: String?,
    val headers: Map<String, List<String>>,
    val jsonrpc: JsonObject?,
    val responseTimeoutNanos: Long?,
    val receivedAtNanos: Long,
)

data class PollBatch(val receivedAtNanos: Long, val commands: List<TunnelCommand>)

class TunnelApiException(
    val httpStatus: Int,
    val apiCode: String?,
    val retryable: Boolean,
    message: String,
) : IOException(message)

class TunnelClient(
    private val settings: SettingsRepository,
    private val credentials: SecureCredentialStore,
    private val diagnostics: DiagnosticsManager,
) {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun validateConfiguration(): Pair<String, String> {
        val current = settings.settings()
        if (!TunnelProtocol.isValidTunnelId(current.tunnelId)) throw IllegalArgumentException("Tunnel ID 格式不正确")
        val key = credentials.runtimeApiKey()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Runtime API Key 尚未配置")
        diagnostics.registerSecret(key)
        return current.tunnelId to key
    }

    fun testConfiguration(): String {
        val (tunnelId, _) = validateConfiguration()
        val request = controlRequest("/v1/tunnels/$tunnelId", method = "GET")
        val response = execute(request, settings.settings().networkTimeoutSeconds)
        response.use {
            if (!it.isSuccessful) throw apiException(it.code, readErrorCode(it), "Tunnel 元数据检查失败")
            readBounded(it.body?.byteStream(), MAX_CONTROL_BODY_BYTES)
        }
        return "Tunnel 元数据请求成功；Runtime API Key 有效且此隧道可访问。ChatGPT 注册状态仍需在 ChatGPT 页面检查。"
    }

    fun poll(): PollBatch {
        val (tunnelId, _) = validateConfiguration()
        val pollTimeout = minOf(settings.settings().networkTimeoutSeconds, MAX_NETWORK_TIMEOUT_SECONDS)
        val url = controlUrl("/v1/tunnels/$tunnelId/poll")
            .newBuilder()
            .addQueryParameter("limit", "25")
            .addQueryParameter("timeout_ms", POLL_WAIT_MS.toString())
            .build()
        val request = controlRequest(url, "GET")
        val response = execute(request, pollTimeout + POLL_WAIT_SECONDS + 10)
        val receivedAt = System.nanoTime()
        response.use {
            if (it.code == 204) return PollBatch(receivedAt, emptyList())
            if (!it.isSuccessful) throw apiException(it.code, readErrorCode(it), "Tunnel 长轮询失败")
            val raw = readBounded(it.body?.byteStream(), MAX_POLL_BODY_BYTES)
            val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
                throw TunnelApiException(502, "invalid_poll_json", true, "Tunnel 返回了无法解析的轮询数据")
            }
            val commands = root.get("commands")?.takeIf { element -> element.isJsonArray }?.asJsonArray ?: JsonArray()
            return PollBatch(receivedAt, commands.mapNotNull { item -> parseCommand(item, receivedAt) })
        }
    }

    fun post(command: TunnelCommand, response: com.kaori.androdex.mcp.McpResponse, deadlineNanos: Long? = null) {
        if (command.requestId.isBlank() || command.shardToken.isBlank()) {
            throw TunnelApiException(400, "missing_correlation", false, "Tunnel 命令缺少 request_id 或 shard_token")
        }
        val (tunnelId, _) = validateConfiguration()
        val body = JsonObject().apply {
            addProperty("request_id", command.requestId)
            command.channel?.let { addProperty("channel", it) }
            response.body?.let { add("resp_json", it.deepCopy()) }
            if (response.headers.isNotEmpty()) {
                add("resp_headers", JsonObject().apply {
                    response.headers.forEach { (name, values) -> add(name, JsonArray().apply { values.forEach(::add) }) }
                })
            }
            addProperty("resp_code", response.statusCode)
            addProperty("resp_type", response.responseType)
        }
        val request = controlRequest("/v1/tunnels/$tunnelId/response", method = "POST")
            .newBuilder()
            .header("X-Tunnel-Shard-Token", command.shardToken)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val result = execute(request, settings.settings().networkTimeoutSeconds, deadlineNanos)
        result.use {
            if (!it.isSuccessful && it.code != 204) throw apiException(it.code, readErrorCode(it), "Tunnel 响应提交失败")
            readBounded(it.body?.byteStream(), MAX_RESPONSE_BODY_BYTES)
        }
    }

    fun cancelInFlightRequests() {
        baseClient.dispatcher.cancelAll()
    }

    private fun parseCommand(element: JsonElement, receivedAt: Long): TunnelCommand? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val type = string(obj, "command_type") ?: ""
        val rawId = string(obj, "request_id")
        val shard = string(obj, "shard_token")
        if (type !in setOf("jsonrpc", "session_termination")) {
            diagnostics.log("MCP 请求", "忽略未支持的 Tunnel 命令类型")
            return null
        }
        if (rawId.isNullOrBlank() || shard.isNullOrBlank()) {
            diagnostics.log("MCP 请求", "忽略缺少关联标识的 Tunnel 命令", error = true)
            return null
        }
        val incoming = obj.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject
        val headers = linkedMapOf<String, List<String>>()
        incoming?.entrySet()?.forEach { (name, value) ->
            val values = when {
                value.isJsonArray -> value.asJsonArray.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString }
                value.isJsonPrimitive -> listOf(value.asString)
                else -> emptyList()
            }
            if (values.isNotEmpty()) headers[name] = values
        }
        val rpc = obj.get("jsonrpc")?.takeIf { it.isJsonObject }?.asJsonObject
        if (type == "jsonrpc" && rpc == null) {
            diagnostics.log("MCP 请求", "忽略缺少 JSON-RPC 正文的 Tunnel 命令", error = true)
            return null
        }
        return TunnelCommand(
            requestId = rawId,
            shardToken = shard,
            commandType = type,
            channel = string(obj, "channel") ?: "main",
            headers = headers,
            jsonrpc = rpc,
            responseTimeoutNanos = TunnelProtocol.responseTimeoutNanos(obj.get("response_timeout")),
            receivedAtNanos = receivedAt,
        )
    }

    private fun controlRequest(path: String, method: String): Request = controlRequest(controlUrl(path), method)

    private fun controlRequest(url: okhttp3.HttpUrl, method: String): Request {
        val (_, apiKey) = validateConfiguration()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("X-Tunnel-Client-Name", CLIENT_NAME)
            .header("X-Tunnel-Client-Version", CLIENT_VERSION)
            .header("X-Tunnel-Client-Wire-Protocol-Version", TUNNEL_WIRE_VERSION)
            .header("X-Tunnel-Client-Instance-Id", settings.tunnelClientInstanceId())
            .header("X-Tunnel-MCP-Server-Info", MCP_SERVER_INFO)
            .method(method, if (method == "POST") ByteArray(0).toRequestBody(JSON_MEDIA_TYPE) else null)
            .build()
    }

    private fun controlUrl(path: String) = (CONTROL_PLANE + path).toHttpUrl()

    private fun execute(request: Request, timeoutSeconds: Int, deadlineNanos: Long? = null): okhttp3.Response {
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds.coerceIn(10, MAX_NETWORK_TIMEOUT_SECONDS).toLong(), TimeUnit.SECONDS)
            .readTimeout((timeoutSeconds + 15).coerceAtMost(150).toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.coerceIn(10, MAX_NETWORK_TIMEOUT_SECONDS).toLong(), TimeUnit.SECONDS)
            .build()
        return try {
            val call = client.newCall(request)
            call.timeout().timeout(timeoutSeconds.coerceIn(10, MAX_NETWORK_TIMEOUT_SECONDS).toLong(), TimeUnit.SECONDS)
            deadlineNanos?.let { call.timeout().deadlineNanoTime(it) }
            call.execute()
        } catch (e: IOException) {
            if (e.message?.contains("Canceled", ignoreCase = true) == true) throw IOException("Tunnel 请求已停止")
            throw IOException("无法连接 Secure MCP Tunnel（${e.javaClass.simpleName}）", e)
        }
    }

    private fun readErrorCode(response: okhttp3.Response): String? {
        val raw = runCatching { readBounded(response.body?.byteStream(), 64 * 1024) }.getOrNull() ?: return null
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.getAsJsonObject("error")?.get("code")?.asString
        }.getOrNull()
    }

    private fun apiException(status: Int, code: String?, prefix: String): TunnelApiException {
        val message = when {
            status == 401 -> "Runtime API Key 认证失败，请检查密钥"
            status == 403 -> "Runtime API Key 缺少此 Tunnel 的 Tunnels Read + Use 权限"
            status == 404 -> "Tunnel ID 不存在或当前账号无法访问"
            status == 429 -> "Tunnel 请求被限流，请稍后重试"
            status >= 500 -> "OpenAI Secure MCP Tunnel 暂时不可用"
            else -> "$prefix（HTTP $status${code?.let { ", $it" } ?: ""}）"
        }
        val retryable = TunnelErrorPolicy.retryableHttpStatus(status)
        return TunnelApiException(status, code, retryable, message)
    }

    private fun readBounded(stream: java.io.InputStream?, maximum: Long): String {
        if (stream == null) return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        stream.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maximum) throw TunnelApiException(502, "response_too_large", false, "Tunnel 响应超过安全大小限制")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun string(obj: JsonObject, key: String): String? = obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        private const val CONTROL_PLANE = "https://api.openai.com"
        private const val CLIENT_NAME = "androdex-android"
        private const val CLIENT_VERSION = "1.0.0"
        private const val TUNNEL_WIRE_VERSION = "2026-08-25"
        private const val MCP_SERVER_INFO = "{\"version\":2,\"channels\":[{\"name\":\"main\",\"stateless\":true}]}"
        private const val POLL_WAIT_MS = 15_000
        private const val POLL_WAIT_SECONDS = 15
        private const val MAX_NETWORK_TIMEOUT_SECONDS = 120
        private const val MAX_CONTROL_BODY_BYTES = 256L * 1024
        private const val MAX_POLL_BODY_BYTES = 2L * 1024 * 1024
        private const val MAX_RESPONSE_BODY_BYTES = 64L * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
