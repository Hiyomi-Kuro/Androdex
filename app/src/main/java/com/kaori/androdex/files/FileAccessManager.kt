package com.kaori.androdex.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kaori.androdex.core.FileContentReader
import com.kaori.androdex.core.FileReadLimitException
import com.kaori.androdex.core.PathNormalizer
import com.kaori.androdex.core.ReadAccessPolicy
import com.kaori.androdex.data.AuthorizedRoot
import com.kaori.androdex.data.DiagnosticsManager
import com.kaori.androdex.data.RootKind
import com.kaori.androdex.data.SettingsRepository
import com.kaori.androdex.mcp.McpToolExecutor
import com.kaori.androdex.mcp.McpToolOutput
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class FileAccessManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsManager,
    private val documentParser: DocumentParser = DocumentParser(),
) : McpToolExecutor {
    override fun execute(name: String, arguments: JsonObject): McpToolOutput = try {
        when (name) {
            "list_roots" -> listRoots()
            "list_directory" -> listDirectory(arguments)
            "stat_file" -> statFile(arguments)
            "search_files" -> searchFiles(arguments)
            "search_file_contents" -> searchFileContents(arguments)
            "read_text" -> readText(arguments)
            "read_document" -> readDocument(arguments)
            "read_file_chunk" -> readFileChunk(arguments)
            else -> throw IllegalArgumentException("未知只读文件工具")
        }
    } catch (e: Exception) {
        val code = when (e) {
            is SecurityException -> "permission_revoked"
            is java.io.FileNotFoundException -> "file_not_found"
            is FileReadLimitException -> "file_too_large"
            is UnsupportedDocumentException -> "unsupported_format"
            is DocumentParseException -> "document_parse_failed"
            is FileAccessException -> e.code
            is IllegalArgumentException -> "invalid_path_or_argument"
            else -> "file_access_failed"
        }
        val message = safeReason(e)
        diagnostics.log("请求失败", "工具 $name 失败：$code $message", error = true)
        McpToolOutput(JsonObject().apply {
            addProperty("error_code", code)
            addProperty("error", message)
        }, "$message（错误码：$code）", true)
    }

    fun checkRoot(rootId: String): String {
        val root = getRoot(rootId)
        return when (root.kind) {
            RootKind.SAF -> {
                val uri = Uri.parse(root.uriString ?: throw FileAccessException("目录授权信息不完整"))
                ReadAccessPolicy.requireCurrentPermission(hasPersistedReadPermission(uri), "SAF 目录读取权限")
                ensureAllowedDocumentId(runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(""))
                val doc = DocumentFile.fromTreeUri(context, uri) ?: throw FileAccessException("文档提供方无法访问该目录")
                if (!doc.exists() || !doc.isDirectory || !doc.canRead()) throw FileAccessException("目录当前不可读")
                "目录可读"
            }
            RootKind.PATH -> {
                ReadAccessPolicy.requireCurrentPermission(Environment.isExternalStorageManager(), "所有文件访问权限")
                val file = validatePathRoot(root)
                if (!file.exists() || !file.isDirectory || !file.canRead()) throw FileAccessException("路径目录当前不可读")
                "目录可读"
            }
        }
    }

    fun testRoot(rootId: String): String {
        val message = checkRoot(rootId)
        val directory = resolve(rootId, "")
        val count = listChildren(directory).size
        return "$message，目录项 $count 个"
    }

    private fun listRoots(): McpToolOutput {
        val array = JsonArray()
        settings.roots().forEach { root ->
            val reason = runCatching { checkRoot(root.id); null }.getOrElse { safeReason(it) }
            array.add(JsonObject().apply {
                addProperty("root_id", root.id)
                addProperty("name", root.name)
                addProperty("path", root.displayPath)
                addProperty("kind", root.kind.name.lowercase())
                addProperty("enabled", root.enabled)
                addProperty("accessible", reason == null && root.enabled)
                if (reason != null) addProperty("issue", reason)
            })
        }
        val result = JsonObject().apply {
            add("roots", array)
            addProperty("count", array.size())
            addProperty("scope", "仅限此列表中已启用且当前仍有效的目录")
        }
        return McpToolOutput(result, "已授权目录 ${array.size()} 个。未启用或权限失效的目录不会被读取。")
    }

    private fun listDirectory(args: JsonObject): McpToolOutput {
        val root = requiredString(args, "root_id")
        val path = optionalString(args, "path") ?: ""
        val recursive = optionalBoolean(args, "recursive") ?: false
        val limit = (optionalInt(args, "limit") ?: 100).coerceIn(1, MAX_DIRECTORY_PAGE)
        val offset = (optionalInt(args, "offset") ?: 0).coerceAtLeast(0)
        val base = resolve(root, path)
        if (!base.directory) throw FileAccessException("指定路径不是文件夹")
        val all = if (recursive) collectFiles(base, MAX_TRAVERSED_ENTRIES) else listChildren(base)
        val selected = all.drop(offset).take(limit)
        val array = JsonArray()
        selected.forEach { entry -> array.add(metadata(entry)) }
        val result = JsonObject().apply {
            addProperty("root_id", root)
            addProperty("path", PathNormalizer.normalizeRelative(path))
            add("entries", array)
            addProperty("entry_count", selected.size)
            addProperty("total_entries", all.size)
            if (offset + selected.size < all.size) addProperty("next_offset", offset + selected.size)
            else add("next_offset", com.google.gson.JsonNull.INSTANCE)
            addProperty("recursive", recursive)
        }
        diagnostics.log("文件访问", "列出目录 ${safeDisplay(root, path)}，返回 ${selected.size} 项")
        return McpToolOutput(result, "目录中返回 ${selected.size} 项，共 ${all.size} 项。")
    }

    private fun statFile(args: JsonObject): McpToolOutput {
        val rootId = requiredString(args, "root_id")
        val path = requiredString(args, "path")
        val entry = resolve(rootId, path)
        if (!entry.exists) throw FileAccessException("文件或目录不存在")
        val result = metadata(entry).asJsonObject.apply {
            addProperty("root_id", rootId)
            addProperty("modified_iso", entry.modified.takeIf { it > 0 }?.let { isoDate(it) })
        }
        diagnostics.log("文件访问", "读取元数据 ${safeDisplay(rootId, path)}")
        return McpToolOutput(result, "已读取文件元数据。")
    }

    private fun searchFiles(args: JsonObject): McpToolOutput {
        val rootId = requiredString(args, "root_id")
        val query = requiredString(args, "query").takeIf { it.isNotBlank() } ?: throw FileAccessException("query 不能为空")
        val path = optionalString(args, "path") ?: ""
        val caseSensitive = optionalBoolean(args, "case_sensitive") ?: false
        val maxResults = (optionalInt(args, "max_results") ?: 100).coerceIn(1, MAX_SEARCH_RESULTS)
        val base = resolve(rootId, path)
        if (!base.directory) throw FileAccessException("搜索起点不是文件夹")
        val entries = collectFiles(base, MAX_TRAVERSED_ENTRIES)
        val needle = if (caseSensitive) query else query.lowercase()
        val matches = entries.asSequence()
            .filter { entry ->
                val name = entry.name
                (if (caseSensitive) name else name.lowercase()).contains(needle)
            }
            .take(maxResults)
            .toList()
        val array = JsonArray()
        matches.forEach { array.add(metadata(it)) }
        val result = JsonObject().apply {
            addProperty("root_id", rootId)
            addProperty("query", query)
            add("matches", array)
            addProperty("returned", matches.size)
            addProperty("traversed", entries.size)
            addProperty("truncated", entries.size >= MAX_TRAVERSED_ENTRIES || matches.size >= maxResults)
        }
        diagnostics.log("文件访问", "文件名搜索，遍历 ${entries.size} 项，命中 ${matches.size} 项")
        return McpToolOutput(result, "文件名搜索返回 ${matches.size} 项。")
    }

    private fun searchFileContents(args: JsonObject): McpToolOutput {
        val rootId = requiredString(args, "root_id")
        val keyword = requiredString(args, "keyword").takeIf { it.isNotEmpty() } ?: throw FileAccessException("keyword 不能为空")
        val path = optionalString(args, "path") ?: ""
        val caseSensitive = optionalBoolean(args, "case_sensitive") ?: false
        val maxResults = (optionalInt(args, "max_results") ?: 50).coerceIn(1, 200)
        val base = resolve(rootId, path)
        if (!base.directory) throw FileAccessException("搜索起点不是文件夹")
        val files = collectFiles(base, MAX_SEARCH_FILES).filter { FileContentReader.isSupportedText(it.name) }
        val matches = JsonArray()
        var scanned = 0
        var skippedLarge = 0
        var bytesBudget = MAX_CONTENT_SEARCH_BYTES
        for (file in files) {
            if (matches.size() >= maxResults || bytesBudget <= 0) break
            val fileAllowance = minOf(MAX_PER_SEARCH_FILE_BYTES, bytesBudget, settings.settings().maxReadBytes)
            if (file.size > fileAllowance) {
                skippedLarge++
                continue
            }
            try {
                openStream(file).use { stream ->
                    val hits = FileContentReader.search(stream, keyword, caseSensitive, maxResults - matches.size(), fileAllowance)
                    hits.forEach { (line, text) ->
                        if (matches.size() < maxResults) matches.add(JsonObject().apply {
                            addProperty("path", file.relativePath)
                            addProperty("line", line)
                            addProperty("text", text)
                        })
                    }
                }
                scanned++
                bytesBudget -= fileAllowance
            } catch (e: Exception) {
                diagnostics.log("文件访问", "正文搜索跳过 ${file.relativePath}：${safeReason(e)}")
                skippedLarge++
            }
        }
        val result = JsonObject().apply {
            addProperty("root_id", rootId)
            addProperty("keyword", keyword)
            add("matches", matches)
            addProperty("returned", matches.size())
            addProperty("files_scanned", scanned)
            addProperty("files_skipped", skippedLarge)
            addProperty("truncated", files.size > scanned || matches.size() >= maxResults || bytesBudget <= 0)
        }
        diagnostics.log("文件访问", "正文搜索已扫描 $scanned 个文件，命中 ${matches.size()} 行")
        return McpToolOutput(result, "正文搜索命中 ${matches.size()} 行；已扫描 $scanned 个文本文件，跳过 $skippedLarge 个。")
    }

    private fun readText(args: JsonObject): McpToolOutput {
        val rootId = requiredString(args, "root_id")
        val path = requiredString(args, "path")
        val entry = resolve(rootId, path)
        requireReadableFile(entry)
        if (!FileContentReader.isSupportedText(entry.name)) throw FileAccessException("该格式不是可直接读取的文本文件，请使用 read_document 或查看格式限制")
        val startLine = (optionalInt(args, "start_line") ?: 1).coerceAtLeast(1)
        val lineCount = (optionalInt(args, "line_count") ?: 200).coerceIn(1, 2_000)
        val outputLimit = minOf(optionalInt(args, "max_chars") ?: settings.settings().maxOutputChars, settings.settings().maxOutputChars)
        val started = System.nanoTime()
        val page = openStream(entry).use {
            FileContentReader.readPage(it, startLine, lineCount, outputLimit, settings.settings().maxReadBytes)
        }
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        settings.recordSuccessfulRead()
        diagnostics.log("读取成功", "读取文本 ${safeDisplay(rootId, path)}，${page.linesReturned} 行", duration, page.bytesConsumed)
        val result = JsonObject().apply {
            addProperty("root_id", rootId)
            addProperty("path", entry.relativePath)
            addProperty("start_line", startLine)
            addProperty("lines_returned", page.linesReturned)
            addProperty("text", page.text)
            addProperty("bytes_consumed", page.bytesConsumed)
            if (page.nextLine != null) addProperty("next_start_line", page.nextLine) else add("next_start_line", com.google.gson.JsonNull.INSTANCE)
            addProperty("truncated", page.nextLine != null)
        }
        return McpToolOutput(result, "读取 ${page.linesReturned} 行${page.nextLine?.let { "，下一页从第 $it 行开始" } ?: ""}。")
    }

    private fun readDocument(args: JsonObject): McpToolOutput {
        val rootId = requiredString(args, "root_id")
        val path = requiredString(args, "path")
        val entry = resolve(rootId, path)
        requireReadableFile(entry)
        if (entry.extension !in DOCUMENT_EXTENSIONS) throw FileAccessException("read_document 仅支持 PDF、DOCX、XLSX、PPTX")
        val maxChars = minOf(optionalInt(args, "max_chars") ?: settings.settings().maxOutputChars, settings.settings().maxOutputChars)
        val startOffset = (optionalInt(args, "start_offset") ?: 0).coerceAtLeast(0)
        val started = System.nanoTime()
        val parsed = openStream(entry).use { documentParser.parse(entry.name, it, settings.settings().maxReadBytes, startOffset, maxChars) }
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        settings.recordSuccessfulRead()
        diagnostics.log("读取成功", "解析 ${parsed.format} 文档 ${safeDisplay(rootId, path)}", duration, entry.size)
        val result = JsonObject().apply {
            addProperty("root_id", rootId)
            addProperty("path", entry.relativePath)
            addProperty("format", parsed.format)
            addProperty("text", parsed.text)
            addProperty("start_offset", startOffset)
            addProperty("extracted_chars", parsed.totalExtractedChars)
            if (parsed.nextOffset != null) addProperty("next_offset", parsed.nextOffset) else add("next_offset", com.google.gson.JsonNull.INSTANCE)
            addProperty("parser_truncated", parsed.parserTruncated)
        }
        return McpToolOutput(result, "${parsed.format} 文档文本已提取 ${parsed.text.length} 个字符。${if (parsed.parserTruncated) " 文档超过解析器文本上限。" else ""}")
    }

    private fun readFileChunk(args: JsonObject): McpToolOutput {
        val rootId = requiredString(args, "root_id")
        val path = requiredString(args, "path")
        val entry = resolve(rootId, path)
        requireReadableFile(entry)
        if (!FileContentReader.isSupportedText(entry.name)) throw FileAccessException("分块读取只允许可安全呈现的文本文件；图片及二进制文件不会返回正文")
        val offset = optionalLong(args, "offset") ?: 0L
        val length = (optionalInt(args, "length") ?: 32_768).coerceIn(1, 131_072)
        val started = System.nanoTime()
        val chunk = openStream(entry).use { FileContentReader.readChunk(it, offset, length, settings.settings().maxReadBytes) }
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        settings.recordSuccessfulRead()
        diagnostics.log("读取成功", "分块读取 ${safeDisplay(rootId, path)}，偏移 $offset", duration, chunk.nextOffset - offset)
        val result = JsonObject().apply {
            addProperty("root_id", rootId)
            addProperty("path", entry.relativePath)
            addProperty("offset", offset)
            addProperty("next_offset", chunk.nextOffset)
            addProperty("has_more", chunk.hasMore)
            addProperty("text", chunk.text)
        }
        return McpToolOutput(result, "已读取 ${chunk.nextOffset - offset} 字节${if (chunk.hasMore) "，后面还有内容" else "，文件片段已到末尾"}。")
    }

    private fun requireReadableFile(entry: FileEntry) {
        ReadAccessPolicy.requireExistingFile(entry.exists)
        if (entry.directory) throw FileAccessException("路径指向文件夹，不是文件")
        if (entry.image) throw FileAccessException("图片内容不通过 Androdex MCP 返回；可查看目录元数据")
        if (entry.size > settings.settings().maxReadBytes) throw FileAccessException("文件大小 ${entry.size} 字节超过当前读取上限 ${settings.settings().maxReadBytes} 字节")
    }

    private fun resolve(rootId: String, relativePath: String): FileEntry {
        val root = getRoot(rootId)
        checkRoot(rootId)
        val relative = PathNormalizer.normalizeRelative(relativePath)
        return when (root.kind) {
            RootKind.PATH -> {
                val rootFile = validatePathRoot(root)
                val target = PathNormalizer.resolveInside(rootFile, relative)
                ReadAccessPolicy.requireExistingFile(target.exists())
                val isDirectory = target.isDirectory
                FileEntry(
                    root = root,
                    relativePath = relative,
                    name = if (relative.isEmpty()) root.name else target.name,
                    size = if (isDirectory) 0 else target.length(),
                    modified = target.lastModified(),
                    directory = isDirectory,
                    exists = true,
                    image = isImageName(target.name),
                    extension = extension(target.name),
                    pathFile = target,
                )
            }
            RootKind.SAF -> {
                var current = DocumentFile.fromTreeUri(context, Uri.parse(root.uriString ?: throw FileAccessException("目录授权信息不完整")))
                    ?: throw FileAccessException("文档提供方无法访问该目录")
                if (relative.isNotEmpty()) {
                    for (part in relative.split('/')) {
                        if (!current.isDirectory || !current.canRead()) throw FileAccessException("目录当前不可读")
                        current = current.findFile(part) ?: run {
                            ReadAccessPolicy.requireExistingFile(false)
                            throw FileAccessException("文件或目录不存在")
                        }
                    }
                }
                val id = runCatching { DocumentsContract.getDocumentId(current.uri) }.getOrDefault("")
                ensureAllowedDocumentId(id)
                FileEntry(
                    root = root,
                    relativePath = relative,
                    name = current.name ?: root.name,
                    size = if (current.isDirectory) 0 else current.length().coerceAtLeast(0),
                    modified = current.lastModified().coerceAtLeast(0),
                    directory = current.isDirectory,
                    exists = current.exists() && current.canRead(),
                    image = isImageName(current.name ?: ""),
                    extension = extension(current.name ?: ""),
                    document = current,
                )
            }
        }
    }

    private fun getRoot(rootId: String): AuthorizedRoot {
        val root = settings.roots().firstOrNull { it.id == rootId } ?: throw FileAccessException("授权目录不存在")
        ReadAccessPolicy.requireEnabledRoot(root.enabled)
        return root
    }

    private fun validatePathRoot(root: AuthorizedRoot): File {
        if (!Environment.isExternalStorageManager()) throw FileAccessException("尚未授予所有文件访问权限")
        val raw = root.filePath ?: throw FileAccessException("普通路径授权记录不完整")
        return try {
            PathNormalizer.validateExternalRoot(raw, Environment.getExternalStorageDirectory().absolutePath)
        } catch (e: Exception) {
            throw FileAccessException(e.message ?: "普通路径无法访问")
        }
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

    private fun ensureAllowedDocumentId(id: String) {
        val segments = id.substringAfter(':', id).split('/').filter { it.isNotEmpty() }
        val androidIndex = segments.indexOfFirst { it.equals("Android", true) }
        if (androidIndex >= 0 && segments.getOrNull(androidIndex + 1)?.lowercase() in setOf("data", "obb")) {
            throw FileAccessException("Android/data 与 Android/obb 受 Android 存储沙箱限制")
        }
    }

    private fun listChildren(directory: FileEntry): List<FileEntry> {
        if (directory.root.kind == RootKind.PATH) {
            val folder = directory.pathFile ?: throw FileAccessException("路径目录状态无效")
            verifyPathStillInside(directory.root, folder)
            return (folder.listFiles() ?: throw FileAccessException("目录读取失败或已撤销权限"))
                .take(MAX_TRAVERSED_ENTRIES)
                .map { file ->
                    val relative = listOf(directory.relativePath, file.name).filter { it.isNotEmpty() }.joinToString("/")
                    PathNormalizer.resolveInside(validatePathRoot(directory.root), relative)
                    fileEntry(directory.root, relative, file)
                }
                .sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
        }
        val document = directory.document ?: throw FileAccessException("SAF 目录状态无效")
        if (!document.isDirectory || !document.canRead()) throw FileAccessException("目录读取失败或已撤销权限")
        return document.listFiles().take(MAX_TRAVERSED_ENTRIES).map { child ->
            val relative = listOf(directory.relativePath, child.name ?: "").filter { it.isNotEmpty() }.joinToString("/")
            resolve(directory.root.id, relative)
        }.sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    private fun collectFiles(base: FileEntry, cap: Int): List<FileEntry> {
        val collected = mutableListOf<FileEntry>()
        val queue = ArrayDeque<FileEntry>()
        queue.add(base)
        var visited = 0
        while (queue.isNotEmpty() && visited < cap) {
            val next = queue.removeFirst()
            if (!next.directory) {
                collected += next
                visited++
            } else {
                val children = listChildren(next)
                visited += children.size
                children.forEach { child -> if (child.directory) queue.addLast(child) else collected.add(child) }
            }
        }
        return collected.take(cap)
    }

    private fun fileEntry(root: AuthorizedRoot, relative: String, file: File): FileEntry = FileEntry(
        root = root,
        relativePath = relative,
        name = file.name,
        size = if (file.isDirectory) 0 else file.length(),
        modified = file.lastModified(),
        directory = file.isDirectory,
        exists = file.exists(),
        image = isImageName(file.name),
        extension = extension(file.name),
        pathFile = file,
    )

    private fun metadata(file: FileEntry): JsonObject = JsonObject().apply {
        addProperty("name", file.name)
        addProperty("path", file.relativePath)
        addProperty("type", if (file.directory) "directory" else "file")
        addProperty("size_bytes", file.size)
        addProperty("modified_iso", file.modified.takeIf { it > 0 }?.let { isoDate(it) })
        addProperty("extension", file.extension)
        addProperty("image_metadata_only", file.image)
    }

    private fun openStream(entry: FileEntry): InputStream {
        if (entry.root.kind == RootKind.PATH) {
            val file = entry.pathFile ?: throw FileAccessException("路径读取状态无效")
            verifyPathStillInside(entry.root, file)
            if (!file.isFile || !file.canRead()) throw FileAccessException("文件不可读")
            return file.inputStream()
        }
        val doc = entry.document ?: throw FileAccessException("SAF 文件读取状态无效")
        if (!doc.isFile || !doc.canRead()) throw FileAccessException("SAF 文件不可读")
        return context.contentResolver.openInputStream(doc.uri) ?: throw FileAccessException("文档提供方无法打开文件")
    }

    private fun verifyPathStillInside(root: AuthorizedRoot, file: File) {
        val canonicalRoot = validatePathRoot(root)
        try {
            PathNormalizer.resolveInside(canonicalRoot, file.relativeTo(canonicalRoot).invariantSeparatorsPath)
        } catch (e: Exception) {
            throw FileAccessException("路径标准化检查失败，拒绝访问授权目录之外的数据")
        }
    }

    private fun safeDisplay(rootId: String, relativePath: String): String {
        val rootName = settings.roots().firstOrNull { it.id == rootId }?.displayPath ?: "授权目录"
        return listOf(rootName, relativePath).filter { it.isNotBlank() }.joinToString("/").take(320)
    }

    private fun safeReason(error: Throwable): String = when (error) {
        is FileAccessException -> error.message ?: "权限或路径不可用"
        is SecurityException -> "系统已撤销访问权限"
        else -> error.message?.take(160) ?: "访问检查失败"
    }

    private fun isImageName(name: String): Boolean = extension(name) in IMAGE_EXTENSIONS
    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()
    private fun isoDate(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date(time))

    private fun requiredString(args: JsonObject, name: String): String = optionalString(args, name)
        ?: throw IllegalArgumentException("缺少必需参数：$name")

    private fun optionalString(args: JsonObject, name: String): String? = args.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun optionalBoolean(args: JsonObject, name: String): Boolean? = args.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun optionalInt(args: JsonObject, name: String): Int? = args.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    private fun optionalLong(args: JsonObject, name: String): Long? = args.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private data class FileEntry(
        val root: AuthorizedRoot,
        val relativePath: String,
        val name: String,
        val size: Long,
        val modified: Long,
        val directory: Boolean,
        val exists: Boolean,
        val image: Boolean,
        val extension: String,
        val pathFile: File? = null,
        val document: DocumentFile? = null,
    )

    companion object {
        private const val MAX_DIRECTORY_PAGE = 500
        private const val MAX_SEARCH_RESULTS = 500
        private const val MAX_TRAVERSED_ENTRIES = 2_000
        private const val MAX_SEARCH_FILES = 1_000
        private const val MAX_CONTENT_SEARCH_BYTES = 16L * 1024 * 1024
        private const val MAX_PER_SEARCH_FILE_BYTES = 1L * 1024 * 1024
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
        private val DOCUMENT_EXTENSIONS = setOf("pdf", "docx", "xlsx", "pptx")
    }
}

class FileAccessException(message: String, val code: String = "file_access_error") : Exception(message)
