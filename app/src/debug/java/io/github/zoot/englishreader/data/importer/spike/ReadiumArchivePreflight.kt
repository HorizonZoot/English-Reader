package io.github.zoot.englishreader.data.importer.spike

import android.util.Xml
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.OcfPathNormalizer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * SPIKE-ONLY safety preflight executed before Readium sees an EPUB archive.
 *
 * Readium's `Resource.read()` materializes a complete resource and does not enforce this app's ZIP
 * budgets. This pass validates every XML/HTML publication resource with the same entry-count,
 * per-entry, cumulative-inflated and linear-spine limits used by the production importer.
 */
internal object ReadiumArchivePreflight {

    data class Report(
        val archiveEntries: Int,
        val inspectedTextEntries: Int,
        val inflatedTextBytes: Long,
        val linearSpineItems: Int,
    )

    fun inspect(file: File): Report {
        val zip = try {
            ZipFile(file)
        } catch (_: Exception) {
            throw ImportException(ImportFailure.InvalidEpub)
        }

        zip.use { archive ->
            if (archive.size() > ImportBudget.MAX_ZIP_ENTRIES) invalidEpub()

            val entries = linkedMapOf<String, ZipEntry>()
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                val normalized = try {
                    normalizeEntryName(entry.name)
                } catch (_: ImportException) {
                    invalidEpub()
                }
                if (entries.put(normalized, entry) != null) invalidEpub()
            }

            val budget = InflatedBudget(archive)
            val container = entries[CONTAINER_PATH] ?: invalidEpub()
            val opfPath = parseRootfilePath(budget.read(container))
            val opfEntry = entries[opfPath] ?: invalidEpub()
            val linearSpineItems = countLinearSpineItems(budget.read(opfEntry))

            entries.forEach { (path, entry) ->
                if (path.isPublicationTextResource()) budget.read(entry)
            }

            return Report(
                archiveEntries = archive.size(),
                inspectedTextEntries = budget.inspectedEntries,
                inflatedTextBytes = budget.inflatedBytes,
                linearSpineItems = linearSpineItems,
            )
        }
    }

    private class InflatedBudget(private val zip: ZipFile) {
        private val inspected = mutableSetOf<String>()
        var inflatedBytes: Long = 0
            private set
        val inspectedEntries: Int
            get() = inspected.size

        fun read(entry: ZipEntry): ByteArray {
            if (!inspected.add(entry.name)) return ByteArray(0)

            val remaining = ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES - inflatedBytes
            if (remaining <= 0) invalidEpub()
            val limit = minOf(ImportBudget.MAX_XML_ENTRY_BYTES.toLong(), remaining).toInt()
            val output = ByteArray(limit + 1)
            var filled = 0

            try {
                zip.getInputStream(entry).use { input ->
                    while (filled < output.size) {
                        val read = input.read(output, filled, output.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                }
            } catch (e: ImportException) {
                throw e
            } catch (_: Exception) {
                invalidEpub()
            }

            if (filled > limit) invalidEpub()
            inflatedBytes += filled
            return output.copyOf(filled)
        }
    }

    private fun parseRootfilePath(bytes: ByteArray): String {
        val parser = parser(bytes)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val mediaType = parser.getAttributeValue(null, "media-type")
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (mediaType == OPF_MEDIA_TYPE && fullPath != null) {
                        return try {
                            OcfPathNormalizer.normalize(fullPath)
                        } catch (_: ImportException) {
                            invalidEpub()
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            invalidEpub()
        }
        invalidEpub()
    }

    private fun countLinearSpineItems(bytes: ByteArray): Int {
        val parser = parser(bytes)
        var inSpine = false
        var count = 0
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "spine" -> inSpine = true
                    event == XmlPullParser.END_TAG && parser.name == "spine" -> inSpine = false
                    event == XmlPullParser.START_TAG && inSpine && parser.name == "itemref" -> {
                        if (parser.getAttributeValue(null, "idref") == null) invalidEpub()
                        if (parser.getAttributeValue(null, "linear") != "no") {
                            count++
                            if (count > ImportBudget.MAX_SPINE_ITEMS) invalidEpub()
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            invalidEpub()
        }
        if (count == 0) invalidEpub()
        return count
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(bytes.inputStream(), null)
        }

    /**
     * 归一化 ZIP entry 名并复用生产的逃逸判据，但**不做 percent-decoding**。
     *
     * [OcfPathNormalizer.normalize] 的输入契约是 **URI 引用**（container.xml / OPF 里的
     * href），所以它第一步就 percent-decode。ZIP entry 名不是 URI，而是解码后的字面值：
     * 字面叫 `a%20b.xhtml` 的 entry，清单里会写成 `a%2520b.xhtml`。直接把 entry 名交给
     * `normalize` 等于多解一次码——map key 变成 `a b.xhtml`，而 opfPath 查的是
     * `a%20b.xhtml`，合法书会被误判损坏；`a b.xhtml` 与 `a%20b.xhtml` 共存时还会撞进
     * 重复键分支。
     *
     * 这里先把 `%` 转义成 `%25`，让 `normalize` 内部的解码步骤成为恒等变换，从而只借用它的
     * 反斜杠统一、`.` 消解、`..` 弹栈与 NUL 拒绝——逃逸校验语义与生产完全一致。
     */
    private fun normalizeEntryName(name: String): String =
        OcfPathNormalizer.normalize(name.replace("%", "%25"))

    private fun String.isPublicationTextResource(): Boolean {
        val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in TEXT_EXTENSIONS || this == CONTAINER_PATH || this == ENCRYPTION_PATH
    }

    private fun invalidEpub(): Nothing = throw ImportException(ImportFailure.InvalidEpub)

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
    private val TEXT_EXTENSIONS = setOf("xml", "opf", "ncx", "xhtml", "html", "htm")
}
