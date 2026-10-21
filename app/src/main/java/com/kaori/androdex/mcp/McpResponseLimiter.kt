package com.kaori.androdex.mcp

import com.google.gson.JsonObject

/** Keeps the complete MCP envelope within the user's output budget and gives a smaller retry page. */
class McpResponseLimiter(private val maxOutputChars: () -> Int) {
    fun limit(name: String, arguments: JsonObject, output: McpToolOutput): McpToolOutput {
        val limit = maxOutputChars().coerceIn(4_096, 262_144)
        val measured = output.structured.toString().length + output.text.length + ENVELOPE_RESERVE_CHARS
        if (measured <= limit) return output

        val retry = retryArguments(name, arguments, limit)
        val result = JsonObject().apply {
            addProperty("error_code", "response_too_large")
            addProperty("max_output_chars", limit)
            addProperty("estimated_output_chars", measured)
            addProperty("message", "本次工具结果超过输出上限，未返回部分文件内容。请按较小页大小重试。")
            if (retry != null) add("retry_with", retry)
            addProperty("truncated", true)
        }
        return McpToolOutput(
            structured = result,
            text = "工具结果超过 $limit 字符上限，未返回部分文件内容。${if (retry != null) "请使用 retry_with 中的参数读取下一小页。" else "请缩小查询范围后重试。"}",
            isError = true,
        )
    }

    private fun retryArguments(name: String, input: JsonObject, outputLimit: Int): JsonObject? {
        val result = JsonObject()
        fun copy(key: String) { input.get(key)?.let { result.add(key, it.deepCopy()) } }
        when (name) {
            "list_directory" -> {
                copy("root_id"); copy("path"); copy("offset")
                result.addProperty("limit", smaller(input.get("limit")?.asInt ?: 100, 100))
            }
            "search_files", "search_file_contents" -> {
                copy("root_id"); copy(if (name == "search_files") "query" else "keyword"); copy("path"); copy("case_sensitive")
                result.addProperty("max_results", smaller(input.get("max_results")?.asInt ?: 50, 50))
            }
            "read_text" -> {
                copy("root_id"); copy("path")
                result.addProperty("start_line", input.get("start_line")?.asInt ?: 1)
                result.addProperty("line_count", smaller(input.get("line_count")?.asInt ?: 200, 100))
                result.addProperty("max_chars", minOf(input.get("max_chars")?.asInt ?: outputLimit / 2, outputLimit / 2).coerceAtLeast(1))
            }
            "read_document" -> {
                copy("root_id"); copy("path")
                result.addProperty("start_offset", input.get("start_offset")?.asInt ?: 0)
                result.addProperty("max_chars", minOf(input.get("max_chars")?.asInt ?: outputLimit / 2, outputLimit / 2).coerceAtLeast(1))
            }
            "read_file_chunk" -> {
                copy("root_id"); copy("path")
                result.addProperty("offset", input.get("offset")?.asLong ?: 0L)
                result.addProperty("length", minOf(input.get("length")?.asInt ?: 32_768, 16_384).coerceAtLeast(1))
            }
            else -> return null
        }
        return result
    }

    private fun smaller(value: Int, ceiling: Int): Int = (value / 2).coerceAtLeast(1).coerceAtMost(ceiling)

    private companion object {
        const val ENVELOPE_RESERVE_CHARS = 2_048
    }
}
