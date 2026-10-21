package com.kaori.androdex.mcp

import com.google.gson.JsonObject

data class McpToolOutput(
    val structured: JsonObject,
    val text: String,
    val isError: Boolean = false,
)

fun interface McpToolExecutor {
    fun execute(name: String, arguments: JsonObject): McpToolOutput
}
