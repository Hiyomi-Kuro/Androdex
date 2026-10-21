package com.kaori.androdex.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class McpToolDefinition(val name: String, val description: String, val schema: JsonObject)

class McpToolRegistry {
    private val definitions: List<McpToolDefinition> = listOf(
        tool("list_roots", "列出 Androdex 中用户明确授权且当前仍有效的只读目录。", """{"type":"object","properties":{},"additionalProperties":false}"""),
        tool("list_directory", "列出授权目录内的文件与子目录；可用 offset/limit 分页，recursive 仅搜索文件。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"path":{"type":"string"},"recursive":{"type":"boolean"},"limit":{"type":"integer","minimum":1,"maximum":500},"offset":{"type":"integer","minimum":0}},"required":["root_id"],"additionalProperties":false}"""),
        tool("stat_file", "获取授权目录中指定相对路径的元数据，不读取文件正文。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"path":{"type":"string","minLength":1}},"required":["root_id","path"],"additionalProperties":false}"""),
        tool("search_files", "在授权目录中按文件名搜索；默认递归遍历并返回元数据。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"query":{"type":"string","minLength":1},"path":{"type":"string"},"case_sensitive":{"type":"boolean"},"max_results":{"type":"integer","minimum":1,"maximum":500}},"required":["root_id","query"],"additionalProperties":false}"""),
        tool("search_file_contents", "在授权目录的受支持纯文本文件中搜索关键词，返回匹配行及行号；受单次扫描资源上限约束。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"keyword":{"type":"string","minLength":1},"path":{"type":"string"},"case_sensitive":{"type":"boolean"},"max_results":{"type":"integer","minimum":1,"maximum":200}},"required":["root_id","keyword"],"additionalProperties":false}"""),
        tool("read_text", "分页读取授权目录中的纯文本文件，返回行号范围、截断状态及下一页行号。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"path":{"type":"string","minLength":1},"start_line":{"type":"integer","minimum":1},"line_count":{"type":"integer","minimum":1,"maximum":2000},"max_chars":{"type":"integer","minimum":1}},"required":["root_id","path"],"additionalProperties":false}"""),
        tool("read_document", "从 PDF、DOCX、XLSX、PPTX 提取可读文本；通过 start_offset/max_chars 分页。不返回图片或二进制内容。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"path":{"type":"string","minLength":1},"start_offset":{"type":"integer","minimum":0},"max_chars":{"type":"integer","minimum":1}},"required":["root_id","path"],"additionalProperties":false}"""),
        tool("read_file_chunk", "按字节偏移读取受支持文本文件的安全文本片段；不可用于图片或二进制文件。", """{"type":"object","properties":{"root_id":{"type":"string","minLength":1},"path":{"type":"string","minLength":1},"offset":{"type":"integer","minimum":0},"length":{"type":"integer","minimum":1,"maximum":131072}},"required":["root_id","path"],"additionalProperties":false}"""),
        tool("get_connection_status", "查看 Android 服务与 Secure MCP Tunnel 的真实运行状态；不探测 ChatGPT 账号连接器。", """{"type":"object","properties":{},"additionalProperties":false}"""),
        tool("get_capabilities", "查看 Android 版本、文件访问方式、支持格式和当前授权目录范围。", """{"type":"object","properties":{},"additionalProperties":false}"""),
    )

    fun all(): List<McpToolDefinition> = definitions
    fun find(name: String): McpToolDefinition? = definitions.firstOrNull { it.name == name }

    fun toJsonArray(): JsonArray = JsonArray().apply {
        definitions.forEach { definition ->
            add(JsonObject().apply {
                addProperty("name", definition.name)
                addProperty("description", definition.description)
                add("inputSchema", definition.schema.deepCopy())
                add("annotations", JsonObject().apply {
                    addProperty("readOnlyHint", true)
                    addProperty("destructiveHint", false)
                    addProperty("idempotentHint", true)
                    addProperty("openWorldHint", false)
                })
            })
        }
    }

    fun validate(name: String, arguments: JsonObject): String? {
        val definition = find(name) ?: return "未知工具：$name"
        val properties = definition.schema.getAsJsonObject("properties")
        val required = definition.schema.getAsJsonArray("required")?.map { it.asString }.orEmpty()
        required.firstOrNull { !arguments.has(it) || arguments.get(it).isJsonNull }?.let { return "缺少必需参数：$it" }
        if (definition.schema.get("additionalProperties")?.asBoolean == false) {
            val unknown = arguments.keySet().firstOrNull { !properties.has(it) }
            if (unknown != null) return "不支持的参数：$unknown"
        }
        for ((key, value) in arguments.entrySet()) {
            if (!properties.has(key) || value.isJsonNull) continue
            val schema = properties.getAsJsonObject(key)
            val expectedType = schema.get("type")?.asString ?: continue
            if (!matchesType(expectedType, value)) return "参数 $key 类型错误，应为 $expectedType"
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val string = value.asString
                val min = schema.get("minLength")?.asInt
                val max = schema.get("maxLength")?.asInt
                if (min != null && string.length < min) return "参数 $key 长度不能少于 $min"
                if (max != null && string.length > max) return "参数 $key 长度不能超过 $max"
            }
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                val number = runCatching { value.asDouble }.getOrNull() ?: return "参数 $key 数值无效"
                schema.get("minimum")?.asDouble?.let { if (number < it) return "参数 $key 不能小于 $it" }
                schema.get("maximum")?.asDouble?.let { if (number > it) return "参数 $key 不能大于 $it" }
            }
        }
        return null
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value.isJsonObject
        "array" -> value.isJsonArray
        "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
        "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
        "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && runCatching { value.asBigDecimal.stripTrailingZeros().scale() <= 0 }.getOrDefault(false)
        "number" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber
        else -> true
    }

    private fun tool(name: String, description: String, schema: String) =
        McpToolDefinition(name, description, JsonParser.parseString(schema).asJsonObject)
}
