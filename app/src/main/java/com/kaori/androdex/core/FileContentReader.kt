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
    val fileLimitReached: Boolean = false,
)

data class TextChunk(val text: String, val offset: Long, val nextOffset: Long, val hasMore: Boolean)

data class TextCodePointPage(
    val text: String,
    val offset: Long,
    val nextOffset: Long,
    val hasMore: Boolean,
    val startLine: Int,
    val endLine: Int,
    val fileLimitReached: Boolean,
)

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
        maxTotalChars: Int = Int.MAX_VALUE,
    ): TextPage {
        require(startLine >= 1) { "start_line 必须大于等于 1" }
        require(lineCount in 1..2_000) { "line_count 必须在 1 到 2000 之间" }
        require(maxChars in 1..1_000_000) { "字符上限无效" }
        require(maxBytes > 0) { "字节上限无效" }
        require(maxTotalChars > 0) { "单文件累计字符上限无效" }

        val counting = CountingInputStream(input, maxBytes)
        val reader = openTextReader(counting)
        reader.use {
            var currentLine = 0
            var totalChars = 0
            while (currentLine < startLine - 1) {
                val skippedLine = it.readLine() ?: return TextPage("", startLine, null, 0, counting.count)
                currentLine++
                totalChars += skippedLine.codePointCount(0, skippedLine.length) + 1
                if (totalChars >= maxTotalChars) return TextPage("", startLine, null, 0, counting.count, true)
            }
            val output = StringBuilder(minOf(maxChars, 8_192))
            var returned = 0
            var hasMore = false
            var fileLimitReached = false
            while (returned < lineCount && totalChars < maxTotalChars) {
                val line = it.readLine() ?: break
                currentLine++
                val lineChars = line.codePointCount(0, line.length) + 1
                if (totalChars + lineChars > maxTotalChars) {
                    fileLimitReached = true
                    hasMore = true
                    break
                }
                val rendered = if (output.isEmpty()) line else "\n$line"
                if (output.length + rendered.length > maxChars) {
                    val available = maxChars - output.length
                    if (available > 0) output.append(rendered.take(available))
                    hasMore = true
                    break
                }
                output.append(rendered)
                returned++
                totalChars += lineChars
            }
            if (!hasMore && (returned == lineCount || totalChars >= maxTotalChars) && it.readLine() != null) {
                hasMore = true
                fileLimitReached = totalChars >= maxTotalChars
            }
            val next = if (hasMore && returned > 0) startLine + returned else null
            return TextPage(output.toString(), startLine, next, returned, counting.count, fileLimitReached)
        }
    }

    fun readChunk(input: InputStream, offset: Long, length: Int, maxBytes: Long): TextChunk {
        require(offset >= 0) { "offset 不能为负数" }
        require(length in 1..262_144) { "length 必须在 1 到 262144 字节之间" }
        require(offset <= maxBytes - length) { "读取范围超过文件读取上限" }
        val counting = CountingInputStream(input, maxBytes + DETECTION_BYTES)
        val pushback = PushbackInputStream(counting, DETECTION_BYTES)
        val prefix = ByteArray(DETECTION_BYTES)
        val prefixSize = pushback.read(prefix)
        val (charset, skip) = detectCharset(prefix, prefixSize.coerceAtLeast(0))
        if (prefixSize > skip) pushback.unread(prefix, skip, prefixSize - skip)
        skipFully(pushback, offset)
        var actualOffset = offset
        if (charset == Charsets.UTF_8) {
            val alignment = ByteArray(4)
            val read = pushback.read(alignment)
            if (read > 0) {
                var leading = 0
                while (leading < read && alignment[leading].toInt() and 0xC0 == 0x80) leading++
                if (leading > 0) {
                    actualOffset += leading
                    pushback.unread(alignment, leading, read - leading)
                } else {
                    pushback.unread(alignment, 0, read)
                }
            }
        } else if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) {
            if (offset % 2L != 0L) {
                if (pushback.read() >= 0) actualOffset++
            }
            val firstUnit = ByteArray(2)
            val previewSize = pushback.read(firstUnit)
            if (previewSize == 2 && Character.isLowSurrogate(utf16Unit(firstUnit, 0, charset))) {
                actualOffset += 2
            } else if (previewSize > 0) {
                pushback.unread(firstUnit, 0, previewSize)
            }
        }
        val buffer = ByteArray(length + 4)
        var total = 0
        while (total < buffer.size) {
            val read = pushback.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        val hasMore = total > length
        var size = minOf(total, length)
        if (charset == Charsets.UTF_8) {
            size = utf8SafeBoundary(buffer, size)
            if (size == 0 && total > 0) {
                val first = buffer[0].toInt() and 0xFF
                val width = when {
                    first and 0xE0 == 0xC0 -> 2
                    first and 0xF0 == 0xE0 -> 3
                    first and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                if (total >= width) size = width
            }
        }
        if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) {
            size -= size % 2
            if (size >= 2 && total >= size + 2) {
                val unit = utf16Unit(buffer, size - 2, charset)
                val next = utf16Unit(buffer, size, charset)
                if (Character.isHighSurrogate(unit) && Character.isLowSurrogate(next)) size -= 2
            }
            if (size == 0 && total >= 2) {
                size = 2
                if (total >= 4 && Character.isHighSurrogate(utf16Unit(buffer, 0, charset)) && Character.isLowSurrogate(utf16Unit(buffer, 2, charset))) size = 4
            }
        }
        val text = decode(buffer.copyOf(size), charset)
        return TextChunk(text, actualOffset, actualOffset + size, total > size)
    }

    /** Pages by Unicode code point so UTF-8 sequences and UTF-16 surrogate pairs stay intact. */
    fun readCodePointPage(
        input: InputStream,
        offset: Long,
        limit: Int,
        maxOutputChars: Int,
        maxFileChars: Int,
        maxBytes: Long,
    ): TextCodePointPage {
        require(offset >= 0) { "offset 不能为负数" }
        require(limit in 1..2_000_000) { "limit 必须在 1 到 2000000 之间" }
        require(maxOutputChars in 1..1_000_000) { "单次输出字符上限无效" }
        require(maxFileChars > 0) { "单文件累计字符上限无效" }
        require(maxBytes > 0) { "字节上限无效" }
        if (offset > maxFileChars) throw FileReadLimitException("读取偏移超过单文件累计字符上限")

        val reader = openTextReader(CountingInputStream(input, maxBytes))
        reader.use {
            val codePoints = CodePointReader(it)
            var currentOffset = 0L
            var line = 1
            while (currentOffset < offset) {
                val codePoint = codePoints.read() ?: return TextCodePointPage("", offset, offset, false, line, line, false)
                currentOffset++
                if (codePoint == '\n'.code) line++
            }
            val startLine = line
            val output = StringBuilder(minOf(maxOutputChars, 8_192))
            var returned = 0
            var pendingMore = false
            while (returned < limit && currentOffset < maxFileChars) {
                val codePoint = codePoints.read() ?: break
                val charWidth = Character.charCount(codePoint)
                if (output.length + charWidth > maxOutputChars) {
                    codePoints.unread(codePoint)
                    pendingMore = true
                    break
                }
                output.appendCodePoint(codePoint)
                currentOffset++
                returned++
                if (codePoint == '\n'.code) line++
            }

            val next = codePoints.read()
            val atFileLimit = currentOffset >= maxFileChars && next != null
            if (next != null) codePoints.unread(next)
            val hasMore = next != null && !atFileLimit && (pendingMore || returned >= limit || output.length >= maxOutputChars)
            return TextCodePointPage(
                text = output.toString(),
                offset = offset,
                nextOffset = currentOffset,
                hasMore = hasMore,
                startLine = startLine,
                endLine = (line - if (output.isNotEmpty() && output.last() == '\n') 1 else 0).coerceAtLeast(startLine),
                fileLimitReached = atFileLimit,
            )
        }
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

    private fun utf8SafeBoundary(bytes: ByteArray, requested: Int): Int {
        if (requested <= 0) return 0
        var lead = requested - 1
        while (lead > 0 && bytes[lead].toInt() and 0xC0 == 0x80) lead--
        val first = bytes[lead].toInt() and 0xFF
        val expectedWidth = when {
            first and 0x80 == 0 -> 1
            first and 0xE0 == 0xC0 -> 2
            first and 0xF0 == 0xE0 -> 3
            first and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (lead + expectedWidth > requested) lead else requested
    }

    private fun utf16Unit(bytes: ByteArray, offset: Int, charset: Charset): Char {
        val first = bytes[offset].toInt() and 0xFF
        val second = bytes[offset + 1].toInt() and 0xFF
        val value = if (charset == Charsets.UTF_16LE) first or (second shl 8) else (first shl 8) or second
        return value.toChar()
    }

    private class CodePointReader(private val source: BufferedReader) {
        private var pendingCodePoint: Int? = null
        private var pendingChar: Int? = null

        fun read(): Int? {
            pendingCodePoint?.let { pendingCodePoint = null; return it }
            val first = pendingChar?.also { pendingChar = null } ?: source.read().takeIf { it >= 0 } ?: return null
            val firstChar = first.toChar()
            if (!Character.isHighSurrogate(firstChar)) return first
            val second = source.read()
            if (second >= 0 && Character.isLowSurrogate(second.toChar())) {
                return Character.toCodePoint(firstChar, second.toChar())
            }
            if (second >= 0) pendingChar = second
            return first
        }

        fun unread(codePoint: Int) {
            check(pendingCodePoint == null) { "只支持回退一个 Unicode 字符" }
            pendingCodePoint = codePoint
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
