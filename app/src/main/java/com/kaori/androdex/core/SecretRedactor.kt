package com.kaori.androdex.core

class SecretRedactor {
    private val known = linkedSetOf<String>()

    @Synchronized
    fun add(secret: String?) {
        if (!secret.isNullOrBlank()) known += secret
    }

    @Synchronized
    fun redact(input: String): String {
        var value = input
        known.forEach { secret -> value = value.replace(secret, "[已隐藏]", ignoreCase = false) }
        value = value.replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [已隐藏]")
        value = value.replace(Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b"), "[已隐藏]")
        value = value.replace(Regex("(?i)(api[_ -]?key|token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[已隐藏]")
        return value.take(800)
    }
}
