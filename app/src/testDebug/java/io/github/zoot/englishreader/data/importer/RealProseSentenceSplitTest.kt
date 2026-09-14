package io.github.zoot.englishreader.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.util.SentenceSplitter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SentenceSplitter] 在**真实书籍正文**上的行为。
 *
 * ## 空白在哪
 *
 * `SentenceSplitterAndroidTest` 有 11 个用例，全是手写的合成句子（缩写、感叹号、`ms.` 歧义）。
 * `ParagraphAlignerRealSplitterTest` 用的也是手写的棘手字符串，且验的是对齐不变量。
 * 于是「真书正文分词是否正确」从未被测过 —— 而真书有合成 fixture 想不到的形状。
 *
 * 语料实测（32 本，`build/epub-corpus/`）里出现的形状及出现段数：
 *
 * ```text
 * 18200  分号从句           "grievance; and therefore"
 *  7213  Mr./Dr. 句中       "by Dr. Jonathan Swift"
 *  7034  感叹号后接大写     "Oh dear! I shall be late!"
 *  5347  em-dash 句中       "the previous one—the old editions"
 *  5124  em-dash 后接大写   "a mouse—O mouse!"
 *  1556  引号后句号加大写   "to begin.” For, you see"
 *  1006  省略号后接大写     "like … Let us ride on"
 *   274  U.S. 式缩写        "U.S. copyright law"
 *   248  首字母缩写         "J. T."
 *   109  三点省略号         "path.... The only way"
 *    36  i.e./e.g.          "to them, i.e. , to my"
 * ```
 *
 * ## 两类断言，性质不同
 *
 * **不变量**（[realChapters_splitPreservesEveryOffsetAndIndex]）与 ICU 版本无关，必须永远成立：
 * 偏移精确、索引连续、句子文本真的落在它声称的位置。这是 `InteractiveText` 逐句 append 拼
 * `AnnotatedString` 所依赖的东西，破了会让点击高亮错位。
 *
 * **特征固化**（[realProseShapes_pinCurrentIcuBehaviour]）记录 ICU 当前**怎么做**，不主张它
 * 应当怎么做。`SentenceSplitter` 的 KDoc 只承诺处理 `Mr|Mrs|Ms|Dr|Prof`，其余（`U.S.`、
 * `e.g.`）明确「完全取决于 ICU 的 UAX#29 基础规则」。所以这些用例的价值是让 ICU 升级或分词
 * 规则改动**可见**，而不是宣称当前行为正确。若某条变红，先判断新行为是否更好，再改期望值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealProseSentenceSplitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser by lazy { EpubBookParser(context) }

    private fun corpusFiles(): List<File> {
        val dir = File(System.getProperty("user.dir"), "../build/epub-corpus")
        return dir.listFiles { f: File -> f.extension == "epub" }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * 真书章节的分词必须满足三条不变量。
     *
     * 这些是 `InteractiveText` 的前提条件，不是分词质量的度量 —— 分得对不对是产品判断，
     * 但偏移错了就是数据损坏：点击第 3 句会高亮到第 4 句的位置。
     */
    @Test
    fun realChapters_splitPreservesEveryOffsetAndIndex() = runBlocking {
        val books = corpusFiles()
        assumeTrue(
            "corpus absent; run: python tools/epub-corpus/fetch_corpus.py",
            books.isNotEmpty()
        )

        var chaptersChecked = 0
        var sentencesChecked = 0

        // 只取能导入的书：被预算拒绝的书没有章节可查。
        books.forEach { file ->
            val book = runCatching { parser.parse(file) }.getOrNull() ?: return@forEach
            book.chapters.forEach { chapter ->
                val sentences = SentenceSplitter.split(chapter.content)
                assertTrue(
                    "${file.nameWithoutExtension} ch${chapter.chapterIndex}: " +
                        "an imported chapter must produce sentences",
                    sentences.isNotEmpty()
                )
                chaptersChecked++
                sentencesChecked += sentences.size

                sentences.forEachIndexed { position, sentence ->
                    // 1. 索引从 0 起连续 —— InteractiveText 用 index + offset 作全局句子身份。
                    assertEquals(
                        "${file.nameWithoutExtension} ch${chapter.chapterIndex}: " +
                            "sentence index must be sequential from 0",
                        position,
                        sentence.index
                    )
                    // 2. 句子文本真的落在它声称的偏移上 —— 这条破了点击高亮就会错位。
                    assertTrue(
                        "${file.nameWithoutExtension} ch${chapter.chapterIndex} sentence $position: " +
                            "text does not occur at declared offset ${sentence.startOffset}",
                        chapter.content.startsWith(sentence.text, sentence.startOffset)
                    )
                    // 3. 偏移区间自洽。
                    assertEquals(
                        "${file.nameWithoutExtension} ch${chapter.chapterIndex} sentence $position: " +
                            "endOffset must equal startOffset + text.length",
                        sentence.startOffset + sentence.text.length,
                        sentence.endOffset
                    )
                }

                // 4. 句子按偏移单调递增且不重叠。重叠会让同一段文字被两句认领，
                //    AnnotatedString 拼出来的正文就会出现重复片段。
                sentences.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "${file.nameWithoutExtension} ch${chapter.chapterIndex}: " +
                            "sentence ${a.index} [${a.startOffset},${a.endOffset}) overlaps " +
                            "${b.index} [${b.startOffset},${b.endOffset})",
                        a.endOffset <= b.startOffset
                    )
                }
            }
        }

        println("REAL-PROSE-SPLIT chapters=$chaptersChecked sentences=$sentencesChecked")
        assumeTrue("no importable book in the corpus", chaptersChecked > 0)
        assertTrue("expected many sentences across real chapters", sentencesChecked > 1_000)
    }

    /**
     * 固化 ICU 在语料实测形状上的当前行为。
     *
     * 每条的文本都是从真书里摘的（书名标在注释里），不是我编的。期望值是**实测所得**，
     * 不是我认为应该如何 —— 见类 KDoc 里关于两类断言的说明。
     */
    @Test
    fun realProseShapes_pinCurrentIcuBehaviour() {
        // "Mr|Mrs|Ms|Dr|Prof" 由 SentenceSplitter 的正则兜底合并，这是它唯一承诺的。
        assertSentenceCount("A Modest Proposal by Dr. Jonathan Swift is short.", 1, "Dr. mid-sentence")

        // em-dash 不是句边界，即便后面是大写。gutenberg-11（爱丽丝）实际文本。
        assertSentenceCount("“A mouse—of a mouse—to a mouse—a mouse—O mouse!”", 1, "em-dash before capital")

        // 引号内句号 + 引号后大写：ICU 在此切分。gutenberg-11 实际文本。
        assertSentenceCount(
            "I think I could, if I only knew how to begin.” For, you see, so many things had happened.",
            2,
            "curly quote after period"
        )

        // U.S. —— SentenceSplitter 不处理，行为取决于 ICU。gutenberg-1080 实际文本。
        assertSentenceCount(
            "Works from print editions not protected by U.S. copyright law means that no one owns it.",
            1,
            "U.S. acronym"
        )

        // 首字母缩写 J. T. —— **ICU 在这里切成两句**，`[The letter was signed J.]` +
        // `[T. and nothing more.]`。这是实测，不是期望：我原本以为不切，写了 1，红了才知道。
        //
        // 与紧邻的 `U.S.` 形成不对称：`U.S.` 两点之间无空格，ICU 不切；`J. T.` 有空格，ICU 切。
        // 语料里 248 段含此形状（`gutenberg-120` 的 "J. T." 等），所以真书里确实会出现
        // 「点一句只选到人名一半」。这是已知的分词质量缺口，不是本类要修的东西 ——
        // `SentenceSplitter` 的 KDoc 只承诺 `Mr|Mrs|Ms|Dr|Prof`，扩展缩写抑制列表是独立决定。
        assertSentenceCount("The letter was signed J. T. and nothing more.", 2, "initials")

        // i.e. —— KDoc 明确点名不处理。gutenberg-1260 实际文本。
        assertSentenceCount("To encourage a stranger; to them, i.e. , to my own people.", 1, "i.e.")

        // 三点省略号后接大写。gutenberg-158（爱玛）实际文本。
        assertSentenceCount(
            "To mind exactly the present line of the path.... The only way of proving it will be to try.",
            2,
            "three dots then capital"
        )

        // 感叹号 / 问号后接大写：正常句边界。
        assertSentenceCount("Oh dear! I shall be late!", 2, "exclamation then capital")
        assertSentenceCount("Do cats eat bats? Do bats eat cats?", 2, "question then capital")

        // 分号不是句边界。gutenberg-1080 实际文本。
        assertSentenceCount(
            "A very great additional grievance; and therefore whoever could find out a fair remedy.",
            1,
            "semicolon clause"
        )
    }

    private fun assertSentenceCount(text: String, expected: Int, label: String) {
        val actual = SentenceSplitter.split(text)
        assertEquals(
            "$label: expected $expected sentence(s), got ${actual.size}. " +
                "This pins current ICU behaviour, not a correctness claim -- if ICU changed, " +
                "decide whether the new split is better before editing this number. " +
                "Split: ${actual.joinToString(" | ") { "[${it.text.trim()}]" }}",
            expected,
            actual.size
        )
        // 固化行为的同时，偏移不变量也必须成立。
        actual.forEach { sentence: SentenceRange ->
            assertTrue(
                "$label: sentence text not at declared offset",
                text.startsWith(sentence.text, sentence.startOffset)
            )
        }
    }
}
