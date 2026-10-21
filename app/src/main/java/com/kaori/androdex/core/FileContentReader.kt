package com.kaori.androdex.core

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class TextPage(
    val text: String,
    val startLine: Int,
    val nextLine: Int?,
    val linesReturned: Int,
    val bytesConsumed: Long,
)

data class TextChunk(val text: String, val offset: Long, val nextOffset: Long, val hasMore: Boolean)

class FileReadLimitException(message: String) : Exception(message)

/** Bounded streaming text reads. The whole file is never accumulated for line or chunk access. */
object FileContentReader {
    private val commonTextExtensions = setOf(
        "txt", "md", "json", "xml", "yaml", "yml", "csv", "log", "html", "htm", "css", "js", "ts",
        "py", "java", "kt", "kts", "sql", "sh", "ini", "toml", "properties", "gradle", "gradle.kts",
    )

    fun isSupportedText(name: String): Boolean = extension(name) in commonTextExtensions

    fun readPage(
        input: InputStream,
        startLine: Int,
        lineCount: Int,
        maxChars: Int,
        maxBytes: Long,
    ): TextPage {
        require(startLine >= 1) { "start_line 必须大于等于 1" }
        require(lineCount in 1..2_000) { "line_count 必须在 1 到 2000 之间" }
        require(maxChars in 1..1_000_000) { "字符上限无效" }
        require(maxBytes > 0) { "字节上限无效" }

        val counting = CountingInputStream(input, maxBytes)
        val reader = openTextReader(counting)
        reader.use {
            var currentLine = 0
            while (currentLine < startLine - 1) {
                if (it.readLine() == null) return TextPage("", startLine, null, 0, counting.count)
                currentLine++
            }
            val output = StringBuilder(minOf(maxChars, 8_192))
            var returned = 0
            var hasMore = false
            while (returned < lineCount) {
                val line = it.readLine() ?: break
                currentLine++
                val rendered = if (output.isEmpty()) line else "\n$line"
                if (output.length + rendered.length > maxChars) {
                    val available = maxChars - output.length
                    if (available > 0) output.append(rendered.take(available))
                    hasMore = true
                    break
                }
                output.append(rendered)
                returned++
            }
            if (!hasMore && returned == lineCount && it.readLine() != null) hasMore = true
            val next = if (hasMore) startLine + returned else null
            return TextPage(output.toString(), startLine, next, returned, counting.count)
        }
    }

    fun readChunk(input: InputStream, offset: Long, length: Int, maxBytes: Long): TextChunk {
        require(offset >= 0) { "offset 不能为负数" }
        require(length in 1..262_144) { "length 必须在 1 到 262144 字节之间" }
        require(offset <= maxBytes) { "读取偏移超过文件读取上限" }
        val counting = CountingInputStream(input, maxBytes)
        val pushback = PushbackInputStream(counting, DETECTION_BYTES)
        val prefix = ByteArray(DETECTION_BYTES)
        val prefixSize = pushback.read(prefix)
        val (charset, skip) = detectCharset(prefix, prefixSize.coerceAtLeast(0))
        if (prefixSize > skip) pushback.unread(prefix, skip, prefixSize - skip)
        skipFully(pushback, offset)
        val buffer = ByteArray(length + 4)
        var total = 0
        while (total < buffer.size) {
            val read = pushback.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        val hasMore = total > length
        val size = minOf(total, length)
        val text = decode(buffer.copyOf(size), charset)
        return TextChunk(text, offset, offset + size, hasMore)
    }

    fun search(input: InputStream, keyword: String, caseSensitive: Boolean, maxMatches: Int, maxBytes: Long): List<Pair<Int, String>> {
        require(keyword.isNotEmpty()) { "keyword 不能为空" }
        require(maxMatches in 1..1_000) { "max_results 必须在 1 到 1000 之间" }
        val counting = CountingInputStream(input, maxBytes)
        val reader = openTextReader(counting)
        val needle = if (caseSensitive) keyword else keyword.lowercase()
        val found = mutableListOf<Pair<Int, String>>()
        reader.use {
            var lineNumber = 0
            while (found.size < maxMatches) {
                val line = it.readLine() ?: break
                lineNumber++
                val haystack = if (caseSensitive) line else line.lowercase()
                if (haystack.contains(needle)) found += lineNumber to line.take(2_000)
            }
        }
        return found
    }

    fun decodeBounded(input: InputStream, maxBytes: Long): String {
        val counting = CountingInputStream(input, maxBytes)
        val reader = openTextReader(counting)
        val result = StringBuilder()
        reader.use {
            val buffer = CharArray(8_192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
            }
        }
        return result.toString()
    }

    fun detectCharset(prefix: ByteArray, size: Int): Pair<Charset, Int> {
        if (size >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()) {
            return Charsets.UTF_8 to 3
        }
        if (size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
        if (size >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
        if (size >= 4) {
            val zeroEven = (0 until size step 2).count { prefix[it] == 0.toByte() }
            val zeroOdd = (1 until size step 2).count { prefix[it] == 0.toByte() }
            if (zeroOdd > 0 && zeroEven == 0) return Charsets.UTF_16LE to 0
            if (zeroEven > 0 && zeroOdd == 0) return Charsets.UTF_16BE to 0
        }
        val tailBytesToIgnore = minOf(3, size)
        val hasValidUtf8Prefix = (0..tailBytesToIgnore).any { trim ->
            val candidateSize = size - trim
            candidateSize > 0 && runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(prefix, 0, candidateSize))
            }.isSuccess
        }
        if (size >= 4 && !hasValidUtf8Prefix) return Charset.forName("GB18030") to 0
        return Charsets.UTF_8 to 0
    }

    private fun openTextReader(input: InputStream): BufferedReader {
        val pushback = PushbackInputStream(input, DETECTION_BYTES)
        val prefix = ByteArray(DETECTION_BYTES)
        val size = pushback.read(prefix).coerceAtLeast(0)
        val (charset, skip) = detectCharset(prefix, size)
        if (size > skip) pushback.unread(prefix, skip, size - skip)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return BufferedReader(InputStreamReader(pushback, decoder), 8_192)
    }

    private fun skipFully(input: InputStream, requested: Long) {
        var remaining = requested
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() == -1) return
            else remaining--
        }
    }

    private fun decode(bytes: ByteArray, charset: Charset): String {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset)
        }
    }

    private fun extension(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".gradle.kts") -> "gradle.kts"
            else -> lower.substringAfterLast('.', "")
        }
    }

    private const val DETECTION_BYTES = 4_096

    private class CountingInputStream(private val delegate: InputStream, private val maximum: Long) : InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) add(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = delegate.read(buffer, offset, length)
            if (value > 0) add(value.toLong())
            return value
        }

        override fun skip(n: Long): Long {
            val skipped = delegate.skip(n)
            if (skipped > 0) add(skipped)
            return skipped
        }

        override fun close() = delegate.close()

        private fun add(bytes: Long) {
            count += bytes
            if (count > maximum) throw FileReadLimitException("读取已达到当前文件大小上限")
        }
    }
}
