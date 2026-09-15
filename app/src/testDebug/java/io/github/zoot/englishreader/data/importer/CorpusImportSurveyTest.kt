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
 * 于是覆盖率这个结论从未被生产代码验证过。本类是它唯一的生产侧证据。
 *
 * 引用覆盖率时只能用本类实测的 **31/32 = 97%**。
 *
 * 本类跑完全部 32 本，把每本的实际 typed failure 与脚本预测逐本比对。**不一致就是发现**：
 * 要么近似有偏差（该改脚本与 ADR），要么 parser 有 bug（该改代码）。两种都比「数字看着差不多」
 * 有价值。
 *
 * ## 章节切分把这些数字整体换掉了（2026-09-15）
 *
 * 改动前：11/32（34%）可导入，19 本死在章节闸门、2 本死在段落闸门。原因是
 * `EpubBookParser` 对超限章节直接从 `parse()` 抛 `ChapterTooLong` —— **一章超限整本被拒**。
 * 现在超限章节按段落边界切开（见 [ChapterSplitter]），每个产物仍在
 * [ImportBudget.MAX_CHAPTER_CHARS] 以内，所以这不需要 ADR-013 要求的真机渲染基线。
 *
 * 实测结果 **31/32**。唯一仍被拒的是 `gutenberg-4300`（*Ulysses*）：Molly Bloom 那段
 * 21,381 字符的独白，ICU 报 `sentences=1` —— 整段就是一个句子，**没有内部边界可切**
 * （不是「没有标点」：ICU 找到了一句，只是那一句就是全段）。切分器于是原样输出，
 * 由段落闸门拒掉它。切到句子以下（按字符硬切）会在正文中间断句 —— 那比拒绝更糟，
 * 用户读到坏文本且无提示。
 *
 * ⚠️ 这一本也是脚本与生产的**真实分歧点**：`measure_corpus.py` 把超长段落按均分建模
 * （它没有分句器，ICU 只在 Android 上），所以它预测 32/32。分歧记在下面的 [predicted] 表里
 * 而不是抹平 —— 脚本看不见「无句子边界」这种输入，这是它的已知盲区，不是 parser 的 bug。
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
     * 每本书的**期望生产判决**。
     *
     * 语义在 2026-09-15 变了：切分之前这张表存的是 `measure_corpus.py` 的预测，用来把脚本近似
     * 与生产代码对账。切分之后脚本对 `gutenberg-4300` 必然预测错（它没有分句器，把超长段落按
     * 均分建模，于是预测 32/32 全过），所以这张表改存**实测的生产判决**，脚本的分歧写在注释里
     * 而不是抹平。理由：那是脚本的已知盲区（看不见「无句子边界」这种输入），不是 parser 的 bug，
     * 而把它塞进表里当成一致会让下一个人以为脚本能预测这种情况。
     *
     * 摘要已由 `corpus.manifest` 钉住，所以同样的字节应当得到同样的判决；比对不上就是回归。
     *
     * ⚠️ **这张表只在 [ImportBudget.MAX_CHAPTER_CHARS] == 40,000 时有效。** 判决是「哪道闸门
     * 先拒」，闸门一动判决就全变。所以 [PINNED_CHAPTER_CEILING] 会先于逐本比对被断言，否则
     * 抬上限的人会先看到一串「判决不一致」，那条消息读起来像代码坏了，而真相是这张表按定义过期了。
     */
    private val predicted = mapOf(
        "gutenberg-1080" to Verdict.PASS,
        "gutenberg-11" to Verdict.PASS,
        "gutenberg-120" to Verdict.PASS,
        "gutenberg-1260" to Verdict.PASS,
        "gutenberg-1342" to Verdict.PASS,
        "gutenberg-158" to Verdict.PASS,
        "gutenberg-16328" to Verdict.PASS,
        "gutenberg-1661" to Verdict.PASS,
        "gutenberg-174" to Verdict.PASS,
        "gutenberg-205" to Verdict.PASS,
        "gutenberg-2542" to Verdict.PASS,
        "gutenberg-2701" to Verdict.PASS,
        "gutenberg-345" to Verdict.PASS,
        // 唯一仍被拒的一本：Molly Bloom 的独白 21,381 字符，ICU 实测报 `sentences=1`
        // —— 整段就是一个句子，没有内部边界可切，ChapterSplitter 按设计原样输出它，
        // 由段落闸门拒绝。
        // 切到句子以下需要按字符硬切，那会在词中间断开正文。
        // `measure_corpus.py` 对这本预测 PASS —— 它没有分句器，把超长段落按均分建模，
        // 看不见「无句子边界」这种输入。这是脚本的已知盲区，不是 parser 的 bug。
        "gutenberg-4300" to Verdict.PARAGRAPH_TOO_LONG,
        "gutenberg-74" to Verdict.PASS,
        "gutenberg-768" to Verdict.PASS,
        "gutenberg-84" to Verdict.PASS,
        "gutenberg-98" to Verdict.PASS,
        "gutenberg3-1342" to Verdict.PASS,
        "gutenberg3-1661" to Verdict.PASS,
        "gutenberg3-2701" to Verdict.PASS,
        "gutenberg3-84" to Verdict.PASS,
        "se-a-tale-of-two-cities" to Verdict.PASS,
        "se-crime-and-punishment" to Verdict.PASS,
        "se-dracula" to Verdict.PASS,
        "se-frankenstein" to Verdict.PASS,
        "se-moby-dick" to Verdict.PASS,
        "se-pride-and-prejudice" to Verdict.PASS,
        "se-the-adventures-of-sherlock-holmes" to Verdict.PASS,
        "se-the-picture-of-dorian-gray" to Verdict.PASS,
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
        // 字面量 31 与 32 是 ADR-013、context.md、prd.md、README.md 共同引用的那两个数。
        // 行为一变，这里先红，而且报的是「文档要一起改」而不是「测试要改」。
        assertEquals("corpus size changed; ADR-013 quotes 32", 32, books.size)
        assertEquals(
            "importable count changed from 31; ADR-013 / context.md / prd.md / README.md all " +
                "quote 31 of 32 = 97%. Update those documents in the same commit as this number. " +
                "A drop back toward 11 means chapter splitting regressed; the one remaining " +
                "rejection is gutenberg-4300 (Ulysses), whose 21,381-char soliloquy has no " +
                "sentence boundary to split on.",
            31,
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
            "importable book count changed from 31. The corpus is complete (asserted above), so " +
                "this is a code change, not a corpus problem. Two likely causes: chapter " +
                "splitting regressed (see ChapterSplitter), or a budget ceiling moved (check " +
                "MAX_CHAPTER_CHARS, currently ${ImportBudget.MAX_CHAPTER_CHARS}). Either way " +
                "ADR-013, context.md, prd.md and README.md quote this number.",
            31,
            checked
        )
        println("CORPUS-SURVEY-USABLE verified=$checked importable book(s)")
    }

    private companion object {
        /**
         * [predicted] 与 31/32 这个覆盖率共同绑定的章节上限。
         *
         * 单独提出来是为了让「这些数字随上限而变」在代码里可见，而不是靠读 KDoc 记住。
         *
         * 切分之后这道上限的含义变了，但**没有失效**：它不再决定「哪本书被拒」，而是决定
         * 「每本书被切成几段」。所以判决表对它的依赖比切分前弱，段落上限的依赖反而更强——
         * 唯一剩下的拒绝就来自段落闸门。
         */
        const val PINNED_CHAPTER_CEILING = 40_000
    }
}
