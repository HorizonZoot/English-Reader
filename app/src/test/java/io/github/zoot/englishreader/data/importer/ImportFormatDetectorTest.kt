package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportFormatDetectorTest {

    private val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    private val textHeader = "Hello world".toByteArray()

    @Test
    fun detect_zipSignature_takesPriorityOverNameAndMime() {
        listOf(
            "book.txt" to "text/plain",
            "book.epub" to "application/pdf"
        ).forEach { (name, mime) ->
            assertEquals("$name $mime", ImportFormat.EPUB, ImportFormatDetector.detect(zipHeader, name, mime))
        }
    }

    @Test
    fun detect_textContent_usesMarkdownMetadataOrPlainTextFallback() {
        // 扩展名和 MIME 不能把普通文本变成 EPUB；未知文本扩展名仍应放行。
        listOf(
            Triple("fake.epub", "application/epub+zip", ImportFormat.PLAIN_TEXT),
            Triple("notes.md", "application/octet-stream", ImportFormat.MARKDOWN),
            Triple("notes.MARKDOWN", null, ImportFormat.MARKDOWN),
            Triple("notes", "text/markdown", ImportFormat.MARKDOWN),
            Triple(null, "text/markdown; charset=utf-8", ImportFormat.MARKDOWN),
            Triple("article.txt", "text/plain", ImportFormat.PLAIN_TEXT),
            Triple("README", "text/plain", ImportFormat.PLAIN_TEXT),
            Triple("config.ini", "text/plain", ImportFormat.PLAIN_TEXT),
            Triple("server.log", "text/plain", ImportFormat.PLAIN_TEXT),
            Triple("data", "application/octet-stream", ImportFormat.PLAIN_TEXT)
        ).forEach { (name, mime, expected) ->
            assertEquals("$name $mime", expected, ImportFormatDetector.detect(textHeader, name, mime))
        }
    }

    @Test
    fun detect_emptyHead_doesNotCrash() {
        assertEquals(
            ImportFormat.PLAIN_TEXT,
            ImportFormatDetector.detect(ByteArray(0), null, null)
        )
    }

    @Test
    fun pickerMimeTypes_includeOctetStreamFallback() {
        // 若 provider 把 .md 上报为 octet-stream 而数组里没有它，
        // 文件在选择器里已被 MIME 过滤隐藏——扩展名兜底救不了可见性
        assertTrue(
            ImportFormatDetector.PICKER_MIME_TYPES.contains("application/octet-stream")
        )
        assertTrue(ImportFormatDetector.PICKER_MIME_TYPES.contains("application/epub+zip"))
        assertTrue(ImportFormatDetector.PICKER_MIME_TYPES.contains("text/markdown"))
    }

    @Test
    fun detect_knownBinarySignature_isRejected() {
        val cases = listOf(
            Triple("doc.pdf", "application/pdf", "%PDF-1.7\n".toByteArray()),
            Triple(
                "image.png",
                "image/png",
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) + listOf(0xE0, 0xE1, 0xE2).map { marker ->
            // JPEG JFIF / Exif / ICC：第四字节分别是 E0 / E1 / E2。
            Triple(
                "photo.jpg",
                "image/jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), marker.toByte())
            )
        }
        cases.forEach { (name, mime, header) ->
            val case = "$name $mime " + header.contentToString()
            val error = assertThrows(case, ImportException::class.java) {
                ImportFormatDetector.detect(header, name, mime)
            }
            assertEquals(case, ImportFailure.UnsupportedFormat, error.failure)
        }
    }

    @Test
    fun detect_nonTextMime_isRejectedEvenWhenContentIsText() {
        // image/* 包含可解码的 SVG；PDF 元数据可解码也不等于可导入。
        listOf(
            Triple("image.svg", "image/svg+xml", "Some text"),
            Triple("icon.webp", "image/webp", "Some text"),
            Triple("doc.pdf", "application/pdf", "Some text metadata")
        ).forEach { (name, mime, content) ->
            val error = assertThrows("$name $mime", ImportException::class.java) {
                ImportFormatDetector.detect(content.toByteArray(), name, mime)
            }
            assertEquals("$name $mime", ImportFailure.UnsupportedFormat, error.failure)
        }
    }
}
