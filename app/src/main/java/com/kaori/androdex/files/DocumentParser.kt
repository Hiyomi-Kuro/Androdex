package com.kaori.androdex.files

import android.util.Xml
import com.kaori.androdex.core.FileContentReader
import com.kaori.androdex.core.FileReadLimitException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ParsedDocument(
    val text: String,
    val nextOffset: Int?,
    val totalExtractedChars: Int,
    val parserTruncated: Boolean,
    val format: String,
)

/** Text extraction only: no embedded images, binary payloads, or OCR are returned. */
class DocumentParser {
    fun parse(
        name: String,
        input: InputStream,
        maxFileBytes: Long,
        startOffset: Int,
        maxChars: Int,
    ): ParsedDocument {
        require(startOffset >= 0) { "start_offset 不能为负数" }
        require(maxChars in 1..262_144) { "max_chars 必须在 1 到 262144 之间" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val extracted = when (extension) {
            "pdf" -> parsePdf(input, maxFileBytes)
            "docx" -> parseDocx(input, maxFileBytes)
            "xlsx" -> parseXlsx(input, maxFileBytes)
            "pptx" -> parsePptx(input, maxFileBytes)
            else -> throw UnsupportedDocumentException("不支持解析 .$extension 文档")
        }
        val start = startOffset.coerceAtMost(extracted.text.length)
        val end = minOf(extracted.text.length, start + maxChars)
        val page = extracted.text.substring(start, end)
        val moreInBuffer = end < extracted.text.length
        val next = if (moreInBuffer || extracted.parserTruncated) end.takeIf { it > start } else null
        return ParsedDocument(
            text = page,
            nextOffset = next,
            totalExtractedChars = extracted.text.length,
            parserTruncated = extracted.parserTruncated,
            format = extension.uppercase(),
        )
    }

    private fun parsePdf(input: InputStream, maxBytes: Long): Extraction {
        val bounded = readBounded(input, maxBytes)
        return try {
            PDDocument.load(ByteArrayInputStream(bounded)).use { document ->
                val output = StringBuilder()
                val stripper = PDFTextStripper()
                var truncated = false
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(document)
                    val section = "[PDF 第 ${page} 页]\n$pageText\n"
                    if (output.length + section.length > MAX_EXTRACTED_CHARS) {
                        output.append(section.take(MAX_EXTRACTED_CHARS - output.length))
                        truncated = true
                        break
                    }
                    output.append(section)
                }
                Extraction(output.toString(), truncated)
            }
        } catch (e: Exception) {
            throw DocumentParseException("PDF 解析失败：文件可能已损坏、加密或超过解析能力", e)
        }
    }

    private fun parseDocx(input: InputStream, maxBytes: Long): Extraction {
        val entries = openXmlEntries(input, maxBytes)
        val body = entries["word/document.xml"] ?: throw DocumentParseException("DOCX 缺少 word/document.xml")
        val parser = xml(body)
        val result = StringBuilder()
        var paragraphOpen = false
        var inText = false
        var truncated = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> paragraphOpen = true
                    "t" -> inText = true
                    "tab" -> if (result.length < MAX_EXTRACTED_CHARS) result.append('\t')
                    "br" -> if (result.length < MAX_EXTRACTED_CHARS) result.append('\n')
                }
                XmlPullParser.TEXT -> if (inText && result.length < MAX_EXTRACTED_CHARS) result.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> if (paragraphOpen) {
                        if (result.length >= MAX_EXTRACTED_CHARS) truncated = true else result.append('\n')
                        paragraphOpen = false
                    }
                }
            }
            if (result.length >= MAX_EXTRACTED_CHARS) {
                truncated = parser.eventType != XmlPullParser.END_DOCUMENT
                break
            }
            parser.next()
        }
        return Extraction(result.toString(), truncated)
    }

    private fun parseXlsx(input: InputStream, maxBytes: Long): Extraction {
        val entries = openXmlEntries(input, maxBytes)
        val workbook = entries["xl/workbook.xml"] ?: throw DocumentParseException("XLSX 缺少 xl/workbook.xml")
        val sheetNames = mutableListOf<String>()
        val workbookParser = xml(workbook)
        while (workbookParser.eventType != XmlPullParser.END_DOCUMENT) {
            if (workbookParser.eventType == XmlPullParser.START_TAG && workbookParser.name == "sheet") {
                sheetNames += workbookParser.getAttributeValue(null, "name") ?: "工作表 ${sheetNames.size + 1}"
            }
            workbookParser.next()
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheetEntries = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.substringAfter("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        val output = StringBuilder()
        var truncated = false
        sheetEntries.forEachIndexed { index, entryName ->
            if (output.length >= MAX_EXTRACTED_CHARS) return@forEachIndexed
            val sheetName = sheetNames.getOrNull(index) ?: "工作表 ${index + 1}"
            output.append("[工作表：").append(sheetName).append("]\n")
            val parser = xml(entries.getValue(entryName))
            var cellRef = ""
            var cellType = ""
            var value = ""
            var inValue = false
            var row = 0
            while (parser.eventType != XmlPullParser.END_DOCUMENT && output.length < MAX_EXTRACTED_CHARS) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> row = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: row + 1
                        "c" -> {
                            cellRef = parser.getAttributeValue(null, "r") ?: ""
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                        }
                        "v", "t" -> inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) value += parser.text
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val rendered = if (cellType == "s") sharedStrings.getOrNull(value.toIntOrNull() ?: -1) ?: value else value
                            if (rendered.isNotEmpty()) output.append("第 ").append(row).append(" 行，").append(cellRef).append("：").append(rendered).append('\n')
                            cellRef = ""
                            cellType = ""
                            value = ""
                        }
                    }
                }
                parser.next()
            }
            if (output.length >= MAX_EXTRACTED_CHARS) truncated = true
        }
        return Extraction(output.take(MAX_EXTRACTED_CHARS).toString(), truncated)
    }

    private fun parsePptx(input: InputStream, maxBytes: Long): Extraction {
        val entries = openXmlEntries(input, maxBytes)
        val slideEntries = entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfter("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        val result = StringBuilder()
        var truncated = false
        for ((index, entry) in slideEntries.withIndex()) {
            if (result.length >= MAX_EXTRACTED_CHARS) {
                truncated = true
                break
            }
            result.append("[幻灯片 ${index + 1}]\n")
            val parser = xml(entries.getValue(entry))
            var inText = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT && result.length < MAX_EXTRACTED_CHARS) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> if (parser.name == "t") inText = true
                    XmlPullParser.TEXT -> if (inText) result.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name == "t") {
                        inText = false
                        result.append('\n')
                    }
                }
                parser.next()
            }
            if (result.length >= MAX_EXTRACTED_CHARS) truncated = true
        }
        return Extraction(result.take(MAX_EXTRACTED_CHARS).toString(), truncated)
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val parser = xml(xmlBytes)
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        current.setLength(0)
                        inString = true
                    }
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inString && inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        strings += current.toString()
                        inString = false
                    }
                }
            }
            parser.next()
        }
        return strings
    }

    private fun xml(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        setInput(ByteArrayInputStream(bytes), "UTF-8")
        next()
    }

    private fun openXmlEntries(input: InputStream, maxBytes: Long): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        var compressedRead = 0L
        val counted = CountingInputStream(input, maxBytes)
        ZipInputStream(counted).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && isRelevantOpenXmlEntry(entry.name)) {
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8_192)
                    while (true) {
                        val count = zip.read(chunk)
                        if (count < 0) break
                        total += count
                        if (total > MAX_UNCOMPRESSED_BYTES) throw DocumentParseException("文档展开后超过解析资源上限")
                        if (buffer.size().toLong() + count > MAX_ENTRY_BYTES) throw DocumentParseException("文档中的 XML 部件过大")
                        buffer.write(chunk, 0, count)
                    }
                    result[entry.name] = buffer.toByteArray()
                }
                zip.closeEntry()
                compressedRead = counted.count
                if (compressedRead > maxBytes) throw FileReadLimitException("文档压缩文件超过读取上限")
            }
        }
        if (result.isEmpty()) throw DocumentParseException("未找到可解析的 Office Open XML 部件")
        return result
    }

    private fun isRelevantOpenXmlEntry(name: String): Boolean = when {
        name == "word/document.xml" -> true
        name == "xl/workbook.xml" || name == "xl/sharedStrings.xml" -> true
        name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) -> true
        name.matches(Regex("ppt/slides/slide\\d+\\.xml")) -> true
        else -> false
    }

    private fun readBounded(input: InputStream, maximum: Long): ByteArray {
        if (maximum <= 0 || maximum > 16L * 1024 * 1024) throw FileReadLimitException("PDF 读取上限无效")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var countTotal = 0L
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                countTotal += count
                if (countTotal > maximum) throw FileReadLimitException("PDF 超过当前文件大小上限")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private class CountingInputStream(private val source: InputStream, private val maximum: Long) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) increment(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = source.read(buffer, offset, length)
            if (value > 0) increment(value.toLong())
            return value
        }

        override fun close() = source.close()

        private fun increment(bytes: Long) {
            count += bytes
            if (count > maximum) throw FileReadLimitException("Office 文档读取超过大小上限")
        }
    }

    private data class Extraction(val text: String, val parserTruncated: Boolean)

    companion object {
        private const val MAX_EXTRACTED_CHARS = 1_000_000
        private const val MAX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 12L * 1024 * 1024
    }
}

class UnsupportedDocumentException(message: String) : Exception(message)
class DocumentParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
