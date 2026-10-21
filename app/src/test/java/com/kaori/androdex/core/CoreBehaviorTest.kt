package com.kaori.androdex.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset

class CoreBehaviorTest {
    @Test fun normalizesSafeRelativePathsAndRejectsTraversal() {
        assertEquals("notes/today.md", PathNormalizer.normalizeRelative("./notes//today.md"))
        listOf("../secret.txt", "notes/../../secret", "/absolute", "C:/private", "%2e%2e/secret", "a\\b").forEach { path ->
            assertRejected { PathNormalizer.normalizeRelative(path) }
        }
    }

    @Test fun canonicalResolutionCannotEscapeAnAuthorizedRoot() {
        val root = createTempDir(prefix = "androdex-root-")
        val child = File(root, "notes.md").apply { writeText("local content") }
        assertEquals(child.canonicalFile, PathNormalizer.resolveInside(root, "notes.md"))
        assertRejected { PathNormalizer.resolveInside(root, "../outside.txt") }
        assertRejected { ReadAccessPolicy.requireEnabledRoot(false) }
        root.deleteRecursively()
    }

    @Test fun revocationAndMissingFilesFailClosed() {
        assertRejected { ReadAccessPolicy.requireCurrentPermission(false, "SAF 目录读取权限") }
        assertRejected { ReadAccessPolicy.requireExistingFile(false) }
        ReadAccessPolicy.requireCurrentPermission(true, "SAF 目录读取权限")
        ReadAccessPolicy.requireExistingFile(true)
    }

    @Test fun readsUtf8AndChineseLegacyText() {
        val utf8 = FileContentReader.readPage(ByteArrayInputStream("第一行\n第二行".toByteArray()), 1, 2, 200, 4_096)
        assertEquals("第一行\n第二行", utf8.text)
        val chinese = "中文编码检测".toByteArray(Charset.forName("GB18030"))
        assertEquals("中文编码检测", FileContentReader.decodeBounded(ByteArrayInputStream(chinese), 4_096))
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BOM文本".toByteArray()
        assertEquals("BOM文本", FileContentReader.decodeBounded(ByteArrayInputStream(withBom), 4_096))
    }

    @Test fun readsBoundedPagesAndChunks() {
        val source = (1..20).joinToString("\n") { "line-$it" }
        val page = FileContentReader.readPage(ByteArrayInputStream(source.toByteArray()), 5, 3, 100, 4_096)
        assertEquals("line-5\nline-6\nline-7", page.text)
        assertEquals(8, page.nextLine)
        val chunk = FileContentReader.readChunk(ByteArrayInputStream("abcdefghij".toByteArray()), 3, 4, 4_096)
        assertEquals("defg", chunk.text)
        assertEquals(7L, chunk.nextOffset)
        assertTrue(chunk.hasMore)
        assertRejected { FileContentReader.readChunk(ByteArrayInputStream(byteArrayOf(1)), 4_097, 1, 4_096) }
    }

    @Test fun textChunksKeepUtf8CodePointsAndSurrogatePairsWhole() {
        val source = "中🙂文x".toByteArray(Charsets.UTF_8)
        val first = FileContentReader.readChunk(ByteArrayInputStream(source), 0, 1, 4_096)
        assertEquals("中", first.text)
        assertEquals(3L, first.nextOffset)
        assertTrue(first.hasMore)

        val second = FileContentReader.readChunk(ByteArrayInputStream(source), first.nextOffset, 1, 4_096)
        assertEquals("🙂", second.text)
        assertEquals(7L, second.nextOffset)
        assertTrue(second.hasMore)

        val utf16 = "A🙂Z".toByteArray(Charsets.UTF_16LE)
        val firstUtf16 = FileContentReader.readChunk(ByteArrayInputStream(utf16), 0, 2, 4_096)
        assertEquals("A", firstUtf16.text)
        assertEquals(2L, firstUtf16.nextOffset)
        val emojiUtf16 = FileContentReader.readChunk(ByteArrayInputStream(utf16), firstUtf16.nextOffset, 2, 4_096)
        assertEquals("🙂", emojiUtf16.text)
        assertEquals(6L, emojiUtf16.nextOffset)
        val afterEmoji = FileContentReader.readChunk(ByteArrayInputStream(utf16), 4, 2, 4_096)
        assertEquals("Z", afterEmoji.text)
        assertEquals(6L, afterEmoji.offset)
    }

    @Test fun codePointPagesContinueAtExactOffsetsAndRespectFileCharacterCap() {
        val source = "第一行\n🙂第二行\n结束"
        val first = FileContentReader.readCodePointPage(ByteArrayInputStream(source.toByteArray()), 0, 4, 16, 100, 4_096)
        assertEquals("第一行\n", first.text)
        assertEquals(4L, first.nextOffset)
        assertEquals(1, first.startLine)
        assertEquals(1, first.endLine)
        assertTrue(first.hasMore)

        val second = FileContentReader.readCodePointPage(ByteArrayInputStream(source.toByteArray()), first.nextOffset, 100, 100, 8, 4_096)
        assertTrue(second.text.startsWith("🙂第二行"))
        assertEquals(8L, second.nextOffset)
        assertTrue(second.fileLimitReached)
        assertFalse(second.hasMore)
    }

    @Test fun streamsTenMegabyteTextAtBeginningAndNearEnd() {
        val source = ByteArray(10_000_000) { 'x'.code.toByte() }
        val first = FileContentReader.readCodePointPage(ByteArrayInputStream(source), 0, 16, 32, 10_000_000, 64L * 1024 * 1024)
        assertEquals(16L, first.nextOffset)
        assertTrue(first.hasMore)

        val lastOffset = 9_999_984L
        val last = FileContentReader.readCodePointPage(ByteArrayInputStream(source), lastOffset, 32, 32, 10_000_000, 64L * 1024 * 1024)
        assertEquals(16, last.text.length)
        assertEquals(10_000_000L, last.nextOffset)
        assertFalse(last.hasMore)
    }

    @Test fun absolutePathCopyOnlyMapsVerifiedPaths() {
        val root = createTempDir(prefix = "androdex-path-root-")
        val nested = File(root, "模块 # 1/主 Activity.kt").apply {
            parentFile?.mkdirs()
            writeText("class Main")
        }
        assertEquals(nested.canonicalPath, AbsolutePathResolver.fromPathRoot(root.path, "模块 # 1/主 Activity.kt"))
        assertEquals(null, AbsolutePathResolver.fromPathRoot(root.path, "../outside.kt"))
        root.deleteRecursively()

        assertEquals(
            "/storage/emulated/0/AndroidIDEProjects/代码/Main.kt",
            AbsolutePathResolver.fromPrimarySafDocumentIds(
                AbsolutePathResolver.EXTERNAL_STORAGE_AUTHORITY,
                "primary:AndroidIDEProjects",
                "primary:AndroidIDEProjects/代码/Main.kt",
                "代码/Main.kt",
            ),
        )
        assertEquals(null, AbsolutePathResolver.fromPrimarySafDocumentIds(
            AbsolutePathResolver.EXTERNAL_STORAGE_AUTHORITY, "ABCD-1234:Projects", "ABCD-1234:Projects/Main.kt", "Main.kt",
        ))
        assertEquals(null, AbsolutePathResolver.fromPrimarySafDocumentIds(
            AbsolutePathResolver.EXTERNAL_STORAGE_AUTHORITY, "primary:Projects", "primary:Other/Main.kt", "Main.kt",
        ))
    }

    @Test fun tunnelIdsAndDeadlineDurationsAreStrictlyParsed() {
        assertTrue(TunnelProtocol.isValidTunnelId("tunnel_0123456789abcdef0123456789abcdef"))
        assertFalse(TunnelProtocol.isValidTunnelId("https://api.openai.com/tunnel_0123456789abcdef0123456789abcdef"))
        assertEquals(1_500_000_000L, TunnelProtocol.responseTimeoutNanos(JsonParser.parseString("\"1500ms\"")))
        assertEquals(null, TunnelProtocol.responseTimeoutNanos(JsonParser.parseString("\"1.5s\"")))
    }

    @Test fun registeredCredentialsAreRedactedBeforeLogging() {
        val redactor = SecretRedactor().apply { add("runtime-secret-value") }
        val safe = redactor.redact("Authorization: Bearer runtime-secret-value and sk-12345678abcdef")
        assertFalse(safe.contains("runtime-secret-value"))
        assertFalse(safe.contains("12345678abcdef"))
        assertTrue(safe.contains("[已隐藏]"))
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Expected the access rule to reject this input") } catch (_: IllegalArgumentException) { }
        catch (_: SecurityException) { }
        catch (_: java.io.FileNotFoundException) { }
    }
}
