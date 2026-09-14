package io.github.zoot.englishreader.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 把整个语料灌进**生产** [EpubBookParser]，用实测判决替换脚本近似。
 *
 * ## 它补的缺口
 *
 * ADR-013 的覆盖率数字来自 `tools/epub-corpus/measure_corpus.py` —— 那是正则近似
 * `XhtmlTextExtractor`，不是同一份代码，所以每个数字都带着「±几个百分点」的免责声明。
 * `RealBookImportBudgetTest` 用真实 parser，但只覆盖仓库里原有的 2 本。
 * 于是「32 本里 11 本可导入」这个结论从未被生产代码验证过。本类是它唯一的生产侧证据。
 *
 * 注意是 **11**，不是 13。13 是「只看章节闸门」那一列；另外 2 本过了章节闸门却死在段落闸门上。
 * 引用覆盖率时只能用 11/32 = 34%。
 *
 * 本类跑完全部 32 本，把每本的实际 typed failure 与脚本预测逐本比对。**不一致就是发现**：
 * 要么近似有偏差（该改脚本与 ADR），要么 parser 有 bug（该改代码）。两种都比「数字看着差不多」
 * 有价值。
 *
 * ## 语料**完全**缺失时跳过；数量不符则失败
 *
 * 语料不入库（约 40 MiB 二进制，且上游会重新生成），所以干净 checkout 上它不存在。
 * 那种情况用 [assumeTrue] 显式跳过 —— JUnit 报 skipped 而不是 passed，「没跑」和「跑过了」
 * 在汇总里不会长得一样。这是刻意的：本项目吃过 `tests=0` 被读成成功的亏。
 *
 * 但**部分**存在必须红。只下到一本也全绿的话，34% 就变成了没有判据的断言 —— 而这正是本类
 * 存在的理由。两个用例都各自守一次数量。
 *
 * 准备语料：`python tools/epub-corpus/fetch_corpus.py`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorpusImportSurveyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser by lazy { EpubBookParser(context) }

    /**
     * 脚本对每本书的预测判决。
     *
     * 来自 `measure_corpus.py` 在 2026-08-30 的输出。摘要已由 `corpus.manifest` 钉住，
     * 所以同样的字节应当得到同样的判决；比对不上就说明近似与生产代码在某处分叉。
     *
     * ⚠️ **这张表只在 [ImportBudget.MAX_CHAPTER_CHARS] == 40,000 时有效。** 判决是「哪道闸门
     * 先拒」，闸门一动判决就全变 —— 实测把上限抬到 80,000，32 本里有 16 本判决改变
     * （可导入从 11 变 25）。所以 [PINNED_CHAPTER_CEILING] 会先于逐本比对被断言，否则抬上限的人
     * 会先看到「16 本预测不一致」，那条消息读起来像脚本坏了，而真相是这张表按定义过期了。
     */
    private val predicted = mapOf(
        "gutenberg-1080" to Verdict.PASS,
        "gutenberg-11" to Verdict.PASS,
        "gutenberg-120" to Verdict.PASS,
        "gutenberg-1260" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-1342" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-158" to Verdict.PARAGRAPH_TOO_LONG,
        "gutenberg-16328" to Verdict.PASS,
        "gutenberg-1661" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-174" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-205" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-2542" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-2701" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-345" to Verdict.PASS,
        "gutenberg-4300" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-74" to Verdict.PASS,
        "gutenberg-768" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-84" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg-98" to Verdict.PASS,
        "gutenberg3-1342" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg3-1661" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg3-2701" to Verdict.CHAPTER_TOO_LONG,
        "gutenberg3-84" to Verdict.CHAPTER_TOO_LONG,
        "se-a-tale-of-two-cities" to Verdict.PASS,
        "se-crime-and-punishment" to Verdict.CHAPTER_TOO_LONG,
        "se-dracula" to Verdict.PASS,
        "se-frankenstein" to Verdict.CHAPTER_TOO_LONG,
        "se-moby-dick" to Verdict.CHAPTER_TOO_LONG,
        "se-pride-and-prejudice" to Verdict.PARAGRAPH_TOO_LONG,
        "se-the-adventures-of-sherlock-holmes" to Verdict.CHAPTER_TOO_LONG,
        "se-the-picture-of-dorian-gray" to Verdict.CHAPTER_TOO_LONG,
        "se-the-time-machine" to Verdict.PASS,
        "se-war-and-peace" to Verdict.PASS
    )

    private enum class Verdict {
        PASS, CHAPTER_TOO_LONG, PARAGRAPH_TOO_LONG, TOO_MANY_PARAGRAPHS,
        BOOK_TOO_LONG, BOOK_TOO_MANY_CHAPTERS, OTHER_FAILURE
    }

    private fun verdictOf(failure: ImportFailure?): Verdict = when (failure) {
        null -> Verdict.PASS
        is ImportFailure.ChapterTooLong -> Verdict.CHAPTER_TOO_LONG
        is ImportFailure.ParagraphTooLong -> Verdict.PARAGRAPH_TOO_LONG
        is ImportFailure.TooManyParagraphs -> Verdict.TOO_MANY_PARAGRAPHS
        is ImportFailure.BookTooLong -> Verdict.BOOK_TOO_LONG
        is ImportFailure.BookTooManyChapters -> Verdict.BOOK_TOO_MANY_CHAPTERS
        else -> Verdict.OTHER_FAILURE
    }

    /**
     * 语料目录。
     *
     * `corpus.dir` 是**未接线的**逃生口：Gradle 没有把它传进单测 JVM
     * （`app/build.gradle.kts` 的 `testOptions.unitTests` 里没有 `systemProperty`），
     * 所以它当前永远为 null，落到默认路径。保留是因为接线只需一行，但不要以为它能用。
     *
     * 相对路径按 `user.dir` 的上一级解析：单测的工作目录是 `app/`，语料在仓库根的
     * `build/epub-corpus/`。
     */
    private fun corpusFiles(): List<File> {
        val dir = File(System.getProperty("corpus.dir") ?: "build/epub-corpus")
        val absolute = if (dir.isAbsolute) dir else File(System.getProperty("user.dir"), "../$dir")
        return absolute.listFiles { f: File -> f.extension == "epub" }?.sortedBy { it.name } ?: emptyList()
    }

    @Test
    fun corpus_productionParserAgreesWithScriptPrediction() = runTest {
        val books = corpusFiles()
        // 完全缺失 → 跳过（干净 checkout 上语料不存在，这是正常的）。
        assumeTrue(
            "corpus absent; run: python tools/epub-corpus/fetch_corpus.py",
            books.isNotEmpty()
        )
        // 先断言上限，再断言其他一切。`predicted` 是「哪道闸门先拒」的快照，闸门一动它整张表
        // 就过期；不先拦住这一点，抬上限的人会先撞上 16 条「预测不一致」，而那条消息指向脚本，
        // 真因却是这张表按定义失效了。
        assertEquals(
            "this test's `predicted` table is only valid at MAX_CHAPTER_CHARS=$PINNED_CHAPTER_CEILING. " +
                "It is now ${ImportBudget.MAX_CHAPTER_CHARS}. Raising the ceiling is a real product " +
                "change: re-run `python tools/epub-corpus/measure_corpus.py`, replace the table, and " +
                "update the coverage figures in ADR-013 / context.md / prd.md in the same commit. " +
                "Measured for reference: at 80,000 the importable count becomes 25 of 32 (78%).",
            PINNED_CHAPTER_CEILING,
            ImportBudget.MAX_CHAPTER_CHARS
        )

        // 数量不符必须失败而不是静默测子集。本类是 ADR-013「11/32 = 34%」在生产侧的唯一证据；
        // 只下到一本也全绿的话，那个数字就变成了没有判据的断言。
        // 措辞对两个方向都成立：少了是语料没下完，多了是 manifest 加了书而 predicted 没跟上
        // （后者由下方的 `unknown` 断言给出具体书名）。
        assertEquals(
            "corpus has ${books.size} books, expected ${predicted.size}. Fewer means " +
                "fetch_corpus.py did not finish; more means the manifest gained a book that " +
                "`predicted` does not cover. Either way ADR-013's coverage number is unsupported.",
            predicted.size,
            books.size
        )

        val actual = mutableMapOf<String, Verdict>()
        val details = mutableListOf<String>()
        var passing = 0

        books.forEach { file ->
            val name = file.nameWithoutExtension
            val error = runCatching { parser.parse(file) }.exceptionOrNull()
            val failure = (error as? ImportException)?.failure
            if (error != null && error !is ImportException) {
                details += "$name -> UNEXPECTED ${error::class.simpleName}: ${error.message}"
                actual[name] = Verdict.OTHER_FAILURE
                return@forEach
            }
            val verdict = verdictOf(failure)
            actual[name] = verdict
            if (verdict == Verdict.PASS) passing++

            val extra = when (failure) {
                is ImportFailure.ChapterTooLong ->
                    " chapter='${failure.chapterTitle}' chars=${failure.actualChars}"
                is ImportFailure.ParagraphTooLong -> " paraChars=${failure.actualChars}"
                is ImportFailure.TooManyParagraphs -> " paras=${failure.actualParagraphs}"
                else -> ""
            }
            details += "CORPUS-SURVEY $name -> $verdict$extra"
        }

        details.forEach(::println)
        println("CORPUS-SURVEY-TOTAL importable=$passing of ${books.size}")

        val disagreements = actual.entries
            .filter { (name, verdict) -> predicted[name]?.let { it != verdict } == true }
            .map { (name, verdict) -> "$name: script said ${predicted[name]}, parser said $verdict" }

        assertTrue(
            "script approximation and production parser disagree on ${disagreements.size} book(s):\n" +
                disagreements.joinToString("\n"),
            disagreements.isEmpty()
        )

        // 双向比对。缺书此前完全不会被发现：`disagreements` 用 `predicted[name]?.let`，
        // 语料里没有的书根本不进循环，于是「32 条预测里只核了 1 条」也能全绿。
        val missing = predicted.keys - actual.keys
        assertTrue("predictions with no corresponding book in the corpus: $missing", missing.isEmpty())

        val unknown = actual.keys - predicted.keys
        assertTrue("corpus has books with no recorded prediction: $unknown", unknown.isEmpty())

        // 覆盖率钉成**字面量**，不从 predicted 推导。
        //
        // 上一版写的是 `predicted.values.count { it == Verdict.PASS }`，那是恒真的：
        // 上面三条断言（disagreements / missing / unknown 皆空）已经保证 actual 与
        // predicted 的键集和每个取值都一致，于是 passing 必然等于 predicted 里的 PASS 数，
        // 这条断言不可能独立失败。若有人为了让测试变绿而把某本改成 PASS，期望值会跟着动。
        //
        // 字面量 11 与 32 是 ADR-013、context.md、prd.md 共同引用的那两个数。行为一变，
        // 这里先红，而且报的是「文档要一起改」而不是「测试要改」。
        assertEquals("corpus size changed; ADR-013 quotes 32", 32, books.size)
        assertEquals(
            "importable count changed from 11; ADR-013 / context.md / prd.md all quote " +
                "11 of 32 = 34%. Update those documents in the same commit as this number.",
            11,
            passing
        )
    }

    /**
     * 可导入的书必须真的产出可用章节，不只是「没抛异常」。
     *
     * 一本 0 章或首章空白的书会通过全部预算闸门，然后在书架上变成一个打不开的条目。
     */
    @Test
    fun corpus_importableBooks_produceUsableChapters() = runTest {
        val books = corpusFiles()
        assumeTrue("corpus absent", books.isNotEmpty())
        // 同样需要完整性守卫。没有它，一个只含不可导入书籍的部分语料
        // （例如只下到 gutenberg-1342）会让 `checked == 0`，于是下面那条断言红着报
        // 「没有任何书能导入」—— 把语料问题误诊成 parser 回归。
        assertEquals(
            "corpus is incomplete (${books.size} of 32); this test cannot distinguish " +
                "'parser broke' from 'corpus truncated' on a subset",
            32,
            books.size
        )

        var checked = 0
        books.forEach { file ->
            val book = runCatching { parser.parse(file) }.getOrNull() ?: return@forEach
            checked++
            val name = file.nameWithoutExtension

            assertTrue("$name: no chapters", book.chapters.isNotEmpty())
            assertEquals(
                "$name: chapterIndex must be dense and ordered",
                book.chapters.indices.toList(),
                book.chapters.map { it.chapterIndex }
            )
            assertTrue(
                "$name: first chapter is blank",
                book.chapters.first().content.isNotBlank()
            )
            assertTrue("$name: blank fingerprint", book.metadata.contentFingerprint.isNotBlank())
            assertTrue("$name: blank title", book.metadata.title.isNotBlank())
            book.chapters.forEach { chapter ->
                assertTrue(
                    "$name: chapter ${chapter.chapterIndex} has a blank title",
                    chapter.title.isNotBlank()
                )
            }
        }

        // `assertTrue`，不是 `assumeTrue`。原来用 assume 意味着「32 本全部无法导入」这个
        // 最严重的回归会被报成 **skipped**，而本类的 KDoc 恰恰声称 skip 与 pass 可区分。
        // 与 Phase 5 记的 `tests=0` 陷阱同型：「没跑」和「跑过了」不能长得一样。
        assertEquals(
            "importable book count changed from 11. The corpus is complete (asserted above), so " +
                "this is a code change, not a corpus problem. Two likely causes: a budget ceiling " +
                "moved (check MAX_CHAPTER_CHARS, currently ${ImportBudget.MAX_CHAPTER_CHARS} -- at " +
                "80,000 this count becomes 25), or the parser changed. Either way ADR-013, " +
                "context.md and prd.md quote this number.",
            11,
            checked
        )
        println("CORPUS-SURVEY-USABLE verified=$checked importable book(s)")
    }

    private companion object {
        /**
         * [predicted] 与 11/32 这个覆盖率共同绑定的章节上限。
         *
         * 单独提出来是为了让「这些数字随上限而变」在代码里可见，而不是靠读 KDoc 记住。
         */
        const val PINNED_CHAPTER_CEILING = 40_000
    }
}
