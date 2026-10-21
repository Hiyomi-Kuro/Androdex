package com.kaori.androdex.core

import java.io.File
import java.io.IOException

/** Pure path rules shared by the UI, MCP handlers, and tests. */
object PathNormalizer {
    fun normalizeRelative(input: String): String {
        require(input.length <= 4_096) { "路径过长" }
        require(input.none { it == '\u0000' || it.isISOControl() }) { "路径包含控制字符" }
        require(!input.contains('\\')) { "路径必须使用 / 分隔" }
        require(!input.contains('%')) { "路径不能包含百分号编码" }
        require(!input.startsWith('/')) { "这里只接受授权目录内的相对路径" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(input)) { "这里只接受授权目录内的相对路径" }

        val parts = input.split('/')
        require(parts.none { it == ".." }) { "不允许使用 .. 离开授权目录" }
        val safeParts = parts.filter { it.isNotEmpty() && it != "." }
        require(safeParts.none { part -> part.contains(':') || part.any { it.isISOControl() } }) {
            "路径包含无效字符"
        }
        rejectRestrictedSegments(safeParts)
        return safeParts.joinToString("/")
    }

    fun resolveInside(root: File, normalizedRelativePath: String): File {
        val relative = normalizeRelative(normalizedRelativePath)
        val canonicalRoot = root.canonicalFile
        val candidate = if (relative.isEmpty()) canonicalRoot else File(canonicalRoot, relative).canonicalFile
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
        val candidatePath = candidate.path
        require(candidatePath.equals(rootPath, ignoreCase = true) ||
            candidatePath.startsWith(rootPath + File.separator, ignoreCase = true)) {
            "目标路径不在授权目录内"
        }
        rejectRestrictedSegments(candidate.toPath().map { it.toString() })
        return candidate
    }

    fun validateExternalRoot(path: String, externalStoragePath: String): File {
        require(path.isNotBlank() && path.length <= 4_096) { "目录路径不能为空" }
        require(!path.contains('\u0000') && !path.contains('%')) { "目录路径无效" }
        val candidate = try {
            File(path).canonicalFile
        } catch (e: IOException) {
            throw IllegalArgumentException("无法标准化目录路径", e)
        }
        val externalRoot = File(externalStoragePath).canonicalFile.path.trimEnd(File.separatorChar)
        require(candidate.isDirectory) { "所选路径不是可访问的目录" }
        require(candidate.path.startsWith(externalRoot + File.separator, ignoreCase = true) ||
            candidate.path.equals(externalRoot, ignoreCase = true)) {
            "普通路径访问只允许外部共享存储中的目录"
        }
        rejectRestrictedSegments(candidate.toPath().map { it.toString() })
        return candidate
    }

    private fun rejectRestrictedSegments(segments: Iterable<String>) {
        val normalized = segments.map { it.lowercase() }
        val androidIndex = normalized.indexOf("android")
        if (androidIndex >= 0 && normalized.getOrNull(androidIndex + 1) in setOf("data", "obb")) {
            throw IllegalArgumentException("Android/data 与 Android/obb 受 Android 存储沙箱限制")
        }
    }
}
