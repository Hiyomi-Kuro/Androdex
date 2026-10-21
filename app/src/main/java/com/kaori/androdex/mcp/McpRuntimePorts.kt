package com.kaori.androdex.mcp

fun interface McpCredentialProvider {
    fun mcpBearer(): String?
}

fun interface McpLogSink {
    fun log(kind: String, message: String, error: Boolean)
}

fun McpLogSink.log(kind: String, message: String) = log(kind, message, false)
