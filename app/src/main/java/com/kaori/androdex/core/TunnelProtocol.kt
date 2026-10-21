package com.kaori.androdex.core

import com.google.gson.JsonElement
import java.math.BigInteger

object TunnelProtocol {
    private val tunnelPattern = Regex("^tunnel_(?:[a-z0-9]{32}|[a-z0-9]{4}_[a-z0-9]{32})$")

    fun isValidTunnelId(value: String): Boolean = tunnelPattern.matches(value)

    /** Strictly parses the documented control-plane duration grammar; malformed values fail open. */
    fun responseTimeoutNanos(value: JsonElement?): Long? {
        if (value == null || value.isJsonNull || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        val text = value.asString
        if (text.length !in 2..64) return null
        val match = Regex("^([0-9]+)(ns|us|ms|s|m|h)$").matchEntire(text) ?: return null
        val magnitude = runCatching { BigInteger(match.groupValues[1]) }.getOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "ns" -> BigInteger.ONE
            "us" -> BigInteger.valueOf(1_000)
            "ms" -> BigInteger.valueOf(1_000_000)
            "s" -> BigInteger.valueOf(1_000_000_000)
            "m" -> BigInteger.valueOf(60_000_000_000)
            "h" -> BigInteger.valueOf(3_600_000_000_000)
            else -> return null
        }
        val nanos = magnitude.multiply(multiplier)
        if (nanos > BigInteger.valueOf(Long.MAX_VALUE)) return null
        return nanos.toLong()
    }
}
