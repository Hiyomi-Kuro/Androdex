package com.kaori.androdex.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRequestHandlerTest {
    private val logs = mutableListOf<String>()
    private var executed = 0
    private var bearer: String? = null
    private val handler = McpRequestHandler(
        tools = McpToolRegistry(),
        executor = McpToolExecutor { name, arguments ->
            executed++
            McpToolOutput(JsonObject().apply { addProperty("tool", name); add("arguments", arguments.deepCopy()) }, "工具调用已完成")
        },
        credentials = McpCredentialProvider { bearer },
        diagnostics = McpLogSink { _, message, _ -> logs += message },
        introspection = McpIntrospection { JsonObject().apply { addProperty("status", "stopped") } },
    )

    @Test fun toolsListPublishesTenReadOnlyTools() {
        val response = handler.handle(request(1, "tools/list"))
        val result = response.body!!.getAsJsonObject("result")
        assertEquals(200, response.statusCode)
        assertEquals(10, result.getAsJsonArray("tools").size())
        assertTrue(result.getAsJsonArray("tools").all { it.asJsonObject.getAsJsonObject("annotations").get("readOnlyHint").asBoolean })
    }

    @Test fun validatesArgumentsAndDispatchesToolCalls() {
        val invalid = request(2, "tools/call").apply {
            add("params", JsonObject().apply { addProperty("name", "read_text"); add("arguments", JsonObject().apply { addProperty("root_id", "root") }) })
        }
        assertEquals(-32602, handler.handle(invalid).body!!.getAsJsonObject("error").get("code").asInt)
        assertEquals(0, executed)

        val valid = request(3, "tools/call").apply {
            add("params", JsonObject().apply { addProperty("name", "list_roots"); add("arguments", JsonObject()) })
        }
        val result = handler.handle(valid).body!!.getAsJsonObject("result")
        assertEquals(1, executed)
        assertEquals("list_roots", result.getAsJsonObject("structuredContent").get("tool").asString)
    }

    @Test fun requiresConfiguredBearerAndNeverWritesItToLogs() {
        bearer = "very-secret-mcp-value"
        val call = request(4, "tools/list")
        assertEquals(401, handler.handle(call).statusCode)
        val authorized = handler.handle(call, mapOf("Authorization" to listOf("Bearer very-secret-mcp-value")))
        assertEquals(200, authorized.statusCode)
        assertFalse(logs.joinToString(" ").contains("very-secret-mcp-value"))
    }

    @Test fun supportsModernStatelessDiscoveryAndHeaders() {
        val discovery = JsonParser.parseString("""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""").asJsonObject
        val headers = mapOf("Mcp-Protocol-Version" to listOf("2026-07-28"), "Mcp-Method" to listOf("server/discover"))
        val response = handler.handle(discovery, headers)
        assertEquals(200, response.statusCode)
        assertEquals("complete", response.body!!.getAsJsonObject("result").get("resultType").asString)
        assertEquals(202, handler.handle(request(5, "notifications/initialized")).statusCode)
    }

    @Test fun oversizedToolResultsReturnARecoverableSmallerPageHint() {
        val matches = JsonObject()
        val array = com.google.gson.JsonArray()
        repeat(80) { index -> array.add(JsonObject().apply { addProperty("path", "project/$index.txt"); addProperty("line", index + 1); addProperty("text", "x".repeat(180)) }) }
        matches.add("matches", array)
        val args = JsonObject().apply { addProperty("root_id", "root"); addProperty("keyword", "needle"); addProperty("max_results", 80) }
        val output = McpResponseLimiter { 4_096 }.limit("search_file_contents", args, McpToolOutput(matches, "结果很多"))
        assertTrue(output.isError)
        assertEquals("response_too_large", output.structured.get("error_code").asString)
        assertTrue(output.structured.getAsJsonObject("retry_with").get("max_results").asInt < 80)
        assertTrue(output.structured.toString().length + output.text.length < 4_096)
    }

    @Test fun readTextSchemaAcceptsCompatibleLineAndUnicodeOffsetPaging() {
        val definition = McpToolRegistry().find("read_text")!!
        val lineRequest = JsonObject().apply { addProperty("root_id", "root"); addProperty("path", "app/Main.kt"); addProperty("start_line", 3) }
        val offsetRequest = JsonObject().apply { addProperty("root_id", "root"); addProperty("path", "app/Main.kt"); addProperty("offset", 120L); addProperty("limit", 32_768) }
        assertTrue(McpToolRegistry().validate(definition.name, lineRequest) == null)
        assertTrue(McpToolRegistry().validate(definition.name, offsetRequest) == null)
        offsetRequest.addProperty("offset", -1)
        assertTrue(McpToolRegistry().validate(definition.name, offsetRequest)?.contains("不能小于") == true)
    }

    @Test fun oversizedOffsetReadSuggestsRetryAtSameCursor() {
        val args = JsonObject().apply {
            addProperty("root_id", "root")
            addProperty("path", "app/Main.kt")
            addProperty("offset", 12_345L)
            addProperty("limit", 262_144)
        }
        val output = McpResponseLimiter { 4_096 }.limit(
            "read_text",
            args,
            McpToolOutput(JsonObject().apply { addProperty("text", "x".repeat(8_000)) }, "读取文本"),
        )
        val retry = output.structured.getAsJsonObject("retry_with")
        assertEquals(12_345L, retry.get("offset").asLong)
        assertTrue(retry.get("limit").asInt < args.get("limit").asInt)
    }

    private fun request(id: Int, method: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("id", id)
        addProperty("method", method)
        add("params", JsonObject())
    }
}
