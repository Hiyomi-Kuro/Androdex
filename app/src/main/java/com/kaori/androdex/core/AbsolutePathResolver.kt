package com.kaori.androdex.core

import java.io.File

/** Converts only paths that can be proven to stay within the selected, authorized root. */
object AbsolutePathResolver {
    fun fromPathRoot(rootPath: String, relativePath: String): String? = runCatching {
        val root = File(rootPath).canonicalFile
        PathNormalizer.resolveInside(root, relativePath).canonicalPath
    }.getOrNull()

    /** Android's primary external-storage provider maps primary:<path> to shared internal storage. */
    fun fromPrimarySafDocumentIds(
        authority: String?,
        rootDocumentId: String,
        documentId: String,
        relativePath: String,
    ): String? = runCatching {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        if (!rootDocumentId.startsWith(PRIMARY_PREFIX) || !documentId.startsWith(PRIMARY_PREFIX)) return null

        val normalizedRelative = PathNormalizer.normalizeRelative(relativePath)
        val rootRelative = PathNormalizer.normalizeRelative(rootDocumentId.removePrefix(PRIMARY_PREFIX))
        val documentRelative = PathNormalizer.normalizeRelative(documentId.removePrefix(PRIMARY_PREFIX))
        val expected = listOf(rootRelative, normalizedRelative)
            .filter(String::isNotEmpty)
            .joinToString("/")
        if (documentRelative != expected) return null

        val clean = documentRelative
        if (clean.isEmpty()) PRIMARY_STORAGE_ROOT else "$PRIMARY_STORAGE_ROOT/$clean"
    }.getOrNull()

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_PREFIX = "primary:"
    private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
}
