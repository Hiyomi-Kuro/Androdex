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
