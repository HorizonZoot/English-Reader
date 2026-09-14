package io.github.zoot.englishreader.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.importer.spike.ReadiumEpubFixtures
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实公版书跑**生产** [EpubBookParser] 的判据。
 *
 * 为什么必须单独有这个类：Phase 0 的 `ReadiumPublicationSpikeTest` 也用这两本书，但它只验到
 * 「Readium 能打开、能读出资源字节」为止，**完全不施加本项目的任何预算**。
 * `EpubBookParserTest` 反过来——预算都验了，但全部用合成 fixture，章节大小是我们自己造的。
 * 于是「真实出版物能否通过生产预算」这个问题在两边都没被回答过。
 *
 * 本类回答它，结论是**两本都导不进来**。这不是本类要修的缺陷，而是要钉住的既有边界：
 * 见 `.trellis/spec/project/decisions.md` 的 ADR-013 与 [realBooks_areRejectedByChapterBudget]。
 *
 * 放在 `src/testDebug` 而不是 `src/test`：fixture 在 `src/testDebug/resources`，只在 debug
 * 变体的单测 classpath 上。放到 `src/test` 会让 `testReleaseUnitTest` 找不到资源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealBookImportBudgetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser by lazy { EpubBookParser(context) }

    /**
     * 仓库里两本真实公版书**都被 [ImportBudget.MAX_CHAPTER_CHARS] 拒绝**。
     *
     * 原因不是书太长，而是**打包粒度**：一个章节 = 一个 linear spine item，而 Gutenberg 把
     * 整本小说塞进极少数 XHTML 文件。实测单资源正文字符数：
     *
     * ```text
     * 1342  (傲慢与偏见, EPUB2)  15 个资源，最大 63,612；15 个里 12 个越过 40,000
     * 78457 (EPUB3)              3 个正文资源：206,782 / 153,528 / 18,144
     * ```
     *
     * 也就是说本功能当前只能导入 spine 切分足够细的 EPUB，而这两本 —— 也是本仓库唯一的两本
     * 真书 —— 都不是。
     *
     * **这条断言是边界记录，不是庆祝失败。** 它的价值在于：
     * - 证伪了一个在文档里流传的推论。Phase 0 记录「实测某公版长篇 74 万字符」被当成
     *   「长书链路已验证」，但 spike 不跑预算，那 74 万字符从未通过生产导入。
     * - 一旦提高单章上限、或引入 spine 内二次切分，本用例会红。届时必须改成成功路径并记录
     *   新的基准，而不是让「真书能不能导入」这件事静默改变。
     * - 用户可见行为是 `ContentTooLong`，提示「某章太长」。真实原因是「这本书的章节文件太大」，
     *   与用户能做的事（换一本）之间存在落差 —— 这是产品侧待决的问题，不在本类范围。
     */
    @Test
    fun realBooks_areRejectedByChapterBudget() = runTest {
        // 数字来自 `tools/epub-corpus/measure_corpus.py`（提交在库的那个分段器），
        // 不是最初那次 PowerShell 一次性测量 —— 两者相差约 0.3%，而 ADR-013 让读者用
        // measure_corpus.py 复现，所以这里必须与它一致，否则「可复现」是假的。
        val cases = listOf(
            "/readium/public/gutenberg-1342.epub" to 63_612,
            "/readium/public/gutenberg-78457-epub3.epub" to 206_782
        )

        cases.forEach { (resourcePath, approxLargestResourceChars) ->
            val file = fixtureFile(resourcePath)

            val error = runCatching { parser.parse(file) }.exceptionOrNull()

            assertTrue("$resourcePath: expected ImportException but was $error", error is ImportException)
            val failure = (error as ImportException).failure
            assertTrue("$resourcePath: got $failure", failure is ImportFailure.ChapterTooLong)
            failure as ImportFailure.ChapterTooLong
            assertEquals(
                "$resourcePath: must report the chapter budget, not the single-article one",
                ImportBudget.MAX_CHAPTER_CHARS,
                failure.limitChars
            )
            assertTrue(
                "$resourcePath: failure must name the offending chapter",
                failure.chapterTitle.isNotBlank()
            )
            assertTrue(
                "$resourcePath: actualChars ${failure.actualChars} must exceed the chapter budget",
                failure.actualChars > ImportBudget.MAX_CHAPTER_CHARS
            )
            // 上限提高到 approx 以上时本行会红——那正是需要重新评估的时刻。
            assertTrue(
                "$resourcePath: chapter budget ${ImportBudget.MAX_CHAPTER_CHARS} now exceeds the " +
                    "largest measured resource ($approxLargestResourceChars); re-measure this book",
                ImportBudget.MAX_CHAPTER_CHARS < approxLargestResourceChars
            )
        }
    }

    /**
     * 拒绝发生在**逐章**校验处，不是先把整本读进内存再判。
     *
     * 判据是失败时报出的 `actualChars` 等于某一个资源的正文长度，而不是全书总量：
     * 78457 全书约 38 万字符，若实现是「先拼全书再判」，这里会看到 38 万而不是单资源的
     * 20.7 万。这条性质保护的是内存 —— 一本超限的书不应该在拒绝之前先被完整装进 heap。
     */
    @Test
    fun realBook78457_rejectionReportsSingleResourceNotWholeBook() = runTest {
        val file = fixtureFile("/readium/public/gutenberg-78457-epub3.epub")

        val error = runCatching { parser.parse(file) }.exceptionOrNull()

        val failure = (error as ImportException).failure as ImportFailure.ChapterTooLong
        // 最大单资源实测 206,782；全书三个正文资源合计 378,454。
        assertTrue(
            "actualChars ${failure.actualChars} looks like a whole-book total, " +
                "which would mean the parser buffered the entire book before rejecting",
            failure.actualChars < 300_000
        )
    }

    /**
     * 把 fixture 落到临时文件，并校验 SHA-256。
     *
     * 摘要与 `ReadiumPublicationSpikeTest.PUBLIC_FIXTURE_SHA256` 同源（记录于
     * `readium/public/SOURCE.md`）。这里独立校验一遍：本类的结论是具体数字，
     * 若 fixture 被换掉而结论照旧宣称，那就是拿另一批字节冒充证据。
     */
    private fun fixtureFile(resourcePath: String): File {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Public EPUB fixture missing: $resourcePath"
        }.use { it.readBytes() }

        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "fixture bytes changed; re-measure before trusting this class's numbers",
            requireNotNull(FIXTURE_SHA256[resourcePath]) { "No recorded SHA-256 for $resourcePath" },
            actual
        )

        return ReadiumEpubFixtures.writeTo(
            temporaryFolder.newFolder(),
            resourcePath.substringAfterLast('/'),
            bytes
        )
    }

    private companion object {
        /** 与 `readium/public/SOURCE.md` 及 Phase 0 spike 记录的摘要一致（2026-08-28 下载）。 */
        val FIXTURE_SHA256 = mapOf(
            "/readium/public/gutenberg-1342.epub" to
                "462be7852d84412c6695851395144a97e9762d45bd3c41b9f356dc7ac047b8a9",
            "/readium/public/gutenberg-78457-epub3.epub" to
                "dfaa55888e8dbf16e255bccb4affd7249a11bddd8592a4e9ab0b08fc84d0453b",
        )
    }
}
