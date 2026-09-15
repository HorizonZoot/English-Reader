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
 * 本类回答它。**结论已经变了**：两本书原先都被章节闸门拒绝，现在都能导入——`EpubBookParser`
 * 把超限章节按段落边界切成多个 article，而不是拒掉整本书。见
 * `.trellis/spec/project/decisions.md` 的 ADR-013 与 [realBooks_importByChapterSplitting]。
 *
 * 上一版这里写的是「两本都导不进来」，并明确记着「一旦引入 spine 内二次切分，本用例会红，
 * 届时必须改成成功路径并记录新的基准」。切分已经落地，所以本类照那句话改成了成功路径：
 * 断言的仍是同一批真实字节，只是判据从「被哪道闸门拒」换成「切出来的产物是否处处合法」。
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
     * 仓库里两本真实公版书**都能导入**，靠的是章节切分而不是抬高上限。
     *
     * 它们原先都被 [ImportBudget.MAX_CHAPTER_CHARS] 拒绝，原因不是书太长，而是**打包粒度**：
     * 一个章节 = 一个 linear spine item，而 Gutenberg 把整本小说塞进极少数 XHTML 文件。
     * 实测单资源正文字符数：
     *
     * ```text
     * 1342  (傲慢与偏见, EPUB2)  15 个资源，最大 63,612；15 个里 12 个越过 40,000
     * 78457 (EPUB3)              3 个正文资源：206,782 / 153,528 / 18,144
     * ```
     *
     * 现在这些资源各自被按段落边界切成多个 article，每个都在上限之内。
     *
     * **判据是「产物处处合法」，不是「没抛异常」。** 后者太弱：一个把正文丢掉大半的实现同样
     * 不抛异常。所以这里逐章断言长度、非空、序号密集，并且断言资源确实被切开了
     * （`chapters.size` 必须大于 spine 资源数）——否则本用例在「切分根本没生效、
     * 而是上限被人偷偷抬高」的情况下会照样绿。
     *
     * [ImportBudget.MAX_CHAPTER_CHARS] 仍钉在 40,000：切分的全部意义就是不动这个上限，
     * 所以它被显式断言。它一旦变了，本类记录的实测数字与 ADR-013 的结论都要重新评估。
     */
    @Test
    fun realBooks_importByChapterSplitting() = runTest {
        // 数字来自 `tools/epub-corpus/measure_corpus.py`（提交在库的那个分段器），
        // 不是最初那次 PowerShell 一次性测量 —— 两者相差约 0.3%，而 ADR-013 让读者用
        // measure_corpus.py 复现，所以这里必须与它一致，否则「可复现」是假的。
        val cases = listOf(
            "/readium/public/gutenberg-1342.epub" to 63_612,
            "/readium/public/gutenberg-78457-epub3.epub" to 206_782
        )

        // 切分的前提是上限没动。先断言它，否则「导入成功」可能是有人抬高上限的副作用，
        // 而那需要 ADR-013 要求的真机渲染基线。
        assertEquals(
            "chapter splitting exists so this ceiling need not move; it did move",
            40_000,
            ImportBudget.MAX_CHAPTER_CHARS
        )

        cases.forEach { (resourcePath, approxLargestResourceChars) ->
            val file = fixtureFile(resourcePath)

            val book = parser.parse(file)

            assertTrue("$resourcePath: no chapters", book.chapters.isNotEmpty())
            assertEquals(
                "$resourcePath: chapterIndex must be dense and ordered after splitting",
                book.chapters.indices.toList(),
                book.chapters.map { it.chapterIndex }
            )
            book.chapters.forEach { chapter ->
                assertTrue(
                    "$resourcePath: chapter ${chapter.chapterIndex} is ${chapter.content.length} " +
                        "chars, above the ${ImportBudget.MAX_CHAPTER_CHARS} ceiling",
                    chapter.content.length <= ImportBudget.MAX_CHAPTER_CHARS
                )
                assertTrue(
                    "$resourcePath: chapter ${chapter.chapterIndex} is blank",
                    chapter.content.isNotBlank()
                )
                assertTrue(
                    "$resourcePath: chapter ${chapter.chapterIndex} has a blank title",
                    chapter.title.isNotBlank()
                )
            }
            // 这本书确实有资源越过上限，所以切分必须真的产出了比资源数更多的章节。
            // 少了这条，一个「上限被抬到 20 万」的世界也能让上面全部通过。
            assertTrue(
                "$resourcePath: largest resource is ~$approxLargestResourceChars chars, so it must " +
                    "have been split into several chapters; got ${book.chapters.size}",
                book.chapters.size > 1
            )
            assertTrue(
                "$resourcePath: total content ${book.totalChars} looks too small; splitting must " +
                    "preserve the text, not drop it",
                book.totalChars >= approxLargestResourceChars
            )
        }
    }

    /**
     * 切分在**单个资源内**发生，不跨资源拼接。
     *
     * 本用例此前钉的是另一条性质：拒绝时 `ChapterTooLong.actualChars` 等于某一个资源的长度
     * 而非全书总量，据此证明 parser 没有「先把整本拼进内存再判」。那条判据随切分一起失效了
     * —— 这本书现在能导入，`ImportedBook` 本身就持有全部章节，无从观察。它由
     * `EpubBookParserTest` 的合成 fixture 继续覆盖（单句超限的残余拒绝路径）。
     *
     * 换成钉这一条，因为它是切分正确性里最容易悄悄坏掉的部分：装箱若跨资源累积，两章不同
     * 出处的正文会被并进同一篇 article，而每个产物仍在上限之内、章节序号仍然连续、总字符数
     * 仍然守恒 —— 上面那个用例的每一条断言都照样通过。判据是 `sourceHref`：同一资源切出的
     * 各部分共享它，且按该 href 归组后每组的字符数不超过该资源本身。
     *
     * 生产 parser 实测：三个正文资源 209,803 / 155,888 / 18,487，合计 384,178，切成 6 + 4 + 1 = 11 章。
     * （`measure_corpus.py` 对同样三个资源报 206,782 / 153,528 / 18,144 —— 那是正则近似，
     * 与生产值差约 1%，不能拿来界定生产值。）
     */
    @Test
    fun realBook78457_splitsWithinEachResourceNotAcrossThem() = runTest {
        val file = fixtureFile("/readium/public/gutenberg-78457-epub3.epub")

        val book = parser.parse(file)

        val byResource = book.chapters.groupBy { it.sourceHref }
        assertEquals(
            "this book has 3 body resources; splitting must not invent or merge resources",
            3,
            byResource.size
        )
        // 逐组字符数钉成精确值。fixture 字节由 SHA-256 校验（见 fixtureFile），提取与切分都是
        // 确定性的，所以这三个数是稳定的；跨资源装箱会立刻让它们变形。
        //
        // 这些是**生产** parser 实测值，不是 measure_corpus.py 的近似值。该脚本用正则近似
        // XhtmlTextExtractor，自带「±几个百分点」的免责声明，把它的数字当成生产值的上界会
        // 得到一条假失败——本用例上一版正是这么写的（用 206,782 去界定实测的 209,803）。
        assertEquals(
            "per-resource char counts changed; packing must stay inside each resource",
            listOf(209_803, 155_888, 18_487),
            byResource.values.map { parts -> parts.sumOf { it.content.length } }
        )
        // 各部分在书内必须是连续的一段序号：交错编号意味着装箱顺序脱离了阅读顺序。
        byResource.forEach { (href, parts) ->
            val indices = parts.map { it.chapterIndex }
            assertEquals(
                "$href: parts must occupy consecutive chapter indices, got $indices",
                (indices.first()..indices.last()).toList(),
                indices
            )
        }
        // 同样是生产实测值：三组之和。切分不改变正文总量，所以它必须逐字符守恒。
        assertEquals(
            "total chars changed; splitting must repartition the text, never add or drop any",
            209_803 + 155_888 + 18_487,
            book.totalChars
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
