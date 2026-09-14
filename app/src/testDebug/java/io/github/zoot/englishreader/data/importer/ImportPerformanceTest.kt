package io.github.zoot.englishreader.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 生产导入链路的耗时形状，在真实公版书上测。
 *
 * ## 它测什么
 *
 * `EpubBookParser.parse` 的**全部**成本：`BookArchivePreflight` 四道 ZIP 闸门、加密预检、
 * Readium 打开 publication、逐资源 `XmlBytesDecoder` + `XhtmlTextExtractor`、逐章预算校验、
 * 内容指纹。也就是用户按下「导入」之后到落库之前的一切。
 *
 * 与既有覆盖的区别：
 *
 * ```text
 * ReadiumParsePerformanceSpikeTest  只到裸 Readium，不过生产 parser，不含预算与指纹
 * BookImportBenchmarkAndroidTest    走生产链路且含落库，但需要设备，且用合成 fixture
 * ParagraphAlignerScalingTest       测的是渲染前的对齐成本，不是导入
 * ```
 *
 * ## 它**不能**回答什么
 *
 * **渲染成本。** 抬 [ImportBudget.MAX_CHAPTER_CHARS] 卡的是「进入一个 80k 章节时主线程阻塞
 * 多少毫秒」，那需要 `BasicText` 真的测量文本，只有设备上的
 * `ChapterRenderBenchmarkAndroidTest` 能测。本类测的是导入侧，两者不互相替代。
 *
 * JVM + Robolectric 的绝对毫秒与真机 ART 无关。所以断言只针对**与硬件无关的性质**：
 * 每字符成本不随书变大而恶化（抓平方级退化），以及各阶段占比。绝对值只打印。
 *
 * ## 语料
 *
 * 用 `build/epub-corpus/` 的真书（`python tools/epub-corpus/fetch_corpus.py`）。语料缺失时
 * 跳过而不是假装通过 —— 与 `CorpusImportSurveyTest` 同一理由。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPerformanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser by lazy { EpubBookParser(context) }

    private fun corpusFiles(): List<File> {
        val dir = File(System.getProperty("user.dir"), "../build/epub-corpus")
        return dir.listFiles { f: File -> f.extension == "epub" }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * 最大的书的每字符成本不应显著高于语料里的最低值。
     *
     * ## 为什么不是「最大 vs 最小的书」
     *
     * 那是我的第一版，它**抓不到平方级退化**。最小的书 perChar 很高，因为 Readium 启动与 ZIP
     * 打开这类固定成本摊在很少字符上；那个地板把退化掩盖了。实测：往 `EpubBookParser` 注入
     * 平方级工作后，战争与和平从 290ms 涨到 1086ms、perChar 从 89.1 涨到 333.5ns，而
     * 「最大/最小」比值只从 0.41 变到 1.38 —— 低于当时的阈值 3.0，测试照绿。
     *
     * ## 现在的判据
     *
     * 健康实现下，最大的书因摊销**接近**全语料最低 perChar；平方级下它会成为最大值。实测两态：
     *
     * ```text
     * 健康    最低 69.4ns（se-dracula）    最大书 89.1ns   → 1.28
     * 平方级  最低 96.7ns（gutenberg-98）  最大书 315.4ns  → 3.26
     * ```
     *
     * 阈值 2.0 两边都有余量。这是唯一与硬件无关的判据 —— JVM 的绝对毫秒与真机 ART 无关，
     * 但「最大的书是否反常地贵」是算法性质。
     */
    @Test
    fun importCost_doesNotDegradeWithBookSize() = runBlocking {
        val books = corpusFiles()
        assumeTrue(
            "corpus absent; run: python tools/epub-corpus/fetch_corpus.py",
            books.size >= MIN_BOOKS_FOR_TREND
        )

        val samples = books.mapNotNull { measureImport(it) }
        assumeTrue("no book in the corpus parses successfully", samples.size >= MIN_BOOKS_FOR_TREND)

        val bySize = samples.sortedBy { it.chars }
        bySize.forEach { println(it.report()) }

        val largest = bySize.last()
        val cheapest = samples.minBy { it.nanosPerChar }
        val ratio = largest.nanosPerChar / cheapest.nanosPerChar
        println(
            "IMPORT-PERF largestVsCheapestPerChar=${"%.2f".format(ratio)} " +
                "(${largest.book} ${"%.1f".format(largest.nanosPerChar)}ns vs " +
                "${cheapest.book} ${"%.1f".format(cheapest.nanosPerChar)}ns)"
        )

        assertTrue(
            "the largest book (${largest.book}, ${largest.chars} chars) costs " +
                "${"%.1f".format(largest.nanosPerChar)}ns per char, " +
                "${"%.2f".format(ratio)}x the cheapest observed " +
                "(${cheapest.book}, ${"%.1f".format(cheapest.nanosPerChar)}ns). Under a healthy " +
                "implementation amortisation puts the largest book NEAR the cheapest (~1.3x " +
                "measured); quadratic work makes it the most expensive by a wide margin (~3.3x " +
                "measured with injected quadratic work). Above 2.0 means import degrades with " +
                "book size, which no device benchmark can fix.",
            ratio < MAX_PER_CHAR_RATIO
        )
    }

    /**
     * 打印各阶段耗时占比，供真机基准设计取参考。
     *
     * 不设阈值：占比取决于书的形状（插图多的书 preflight 占比高，纯文本书提取占比高），钉死
     * 任何比例都会在换书时误报。它的价值是让「导入慢在哪一段」有据可查 —— 若将来导入变慢，
     * 这份输出能区分是 ZIP 预检、Readium 打开，还是逐章提取。
     */
    @Test
    fun importCost_reportsStageBreakdown() = runBlocking {
        val books = corpusFiles()
        assumeTrue("corpus absent", books.isNotEmpty())

        // 按 **archive 字节数** 预选三本代表（最小、中位、最大），只对这三本计时。
        //
        // 上一版是先对全部 32 本跑 measureStages、再从结果里挑三本 —— 注释写着「避免 32 本全跑」，
        // 代码却正好相反。用文件大小预选不需要先解析，所以「避免全跑」这句话现在是真的。
        // archive 字节数与解析成本不完全成正比（插图占体积不占解析），但作为选样本的代理足够，
        // 且它是唯一能在解析前拿到的量。
        //
        // 实测会打印**两行**而不是三行：按字节数最大的是 24 MiB 的 gutenberg3-1342，它被
        // MAX_CHAPTER_CHARS 拒，`measureStages` 返回 null 因而落选。这是对的 —— 被拒的书在
        // 拒绝点就停了，耗时不可比。所以样本数是「三本里能解析的那些」，可能少于三。
        val bySize = books.sortedBy { it.length() }
        val samples = listOfNotNull(
            bySize.firstOrNull(),
            bySize.getOrNull(bySize.size / 2),
            bySize.lastOrNull()
        ).distinct()

        val parsed = samples.mapNotNull { measureStages(it) }
        assumeTrue("none of the ${samples.size} sampled books parsed", parsed.isNotEmpty())

        parsed.sortedBy { it.totalNanos }.forEach { println(it.report()) }
    }

    // --- measurement ---

    private data class Sample(
        val book: String,
        val chars: Int,
        val chapters: Int,
        val archiveBytes: Long,
        val nanos: Long
    ) {
        val nanosPerChar: Double get() = nanos.toDouble() / chars

        fun report(): String =
            "IMPORT-PERF book=$book archiveBytes=$archiveBytes chapters=$chapters " +
                "chars=$chars totalMs=${nanos / 1_000_000} perChar=${"%.1f".format(nanosPerChar)}ns"
    }

    /**
     * 测一本书的完整导入耗时。
     *
     * 只统计**能导入**的书：被预算拒绝的书在拒绝点就停了，耗时不可比（一本在第一章就被拒的书
     * 看起来极快，混进趋势判断会掩盖真实退化）。
     */
    private fun measureImport(file: File): Sample? = runBlocking {
        // 预热：Readium 首次打开含类加载与 ICU 初始化；不预热会让第一本书看起来最贵。
        val warm = runCatching { parser.parse(file) }.getOrNull() ?: return@runBlocking null

        var book = warm
        val nanos = (1..MEASURED_RUNS).minOf {
            measureNanoTime { book = parser.parse(file) }
        }
        Sample(
            book = file.nameWithoutExtension,
            chars = book.chapters.sumOf { it.content.length },
            chapters = book.chapters.size,
            archiveBytes = file.length(),
            nanos = nanos
        )
    }

    private data class Stages(
        val book: String,
        val preflightNanos: Long,
        val encryptionNanos: Long,
        val parseNanos: Long
    ) {
        val totalNanos: Long get() = parseNanos

        fun report(): String {
            val preflightPct = preflightNanos * 100.0 / parseNanos
            val encryptionPct = encryptionNanos * 100.0 / parseNanos
            return "IMPORT-STAGES book=$book totalMs=${parseNanos / 1_000_000} " +
                "preflightMs=${preflightNanos / 1_000_000}(${"%.0f".format(preflightPct)}%) " +
                "encryptionCheckMs=${encryptionNanos / 1_000_000}(${"%.0f".format(encryptionPct)}%) " +
                "readiumPlusExtractMs=${(parseNanos - preflightNanos - encryptionNanos) / 1_000_000}"
        }
    }

    /**
     * 分阶段计时。
     *
     * preflight 与加密预检可以独立调用，所以能单独测；Readium 打开与逐章提取在
     * `EpubBookParser.parse` 内部无法拆开，故按差值归为一项。这是刻意的近似，不是遗漏 ——
     * 为了分阶段去改生产代码的可见性，代价大于收益。
     */
    private fun measureStages(file: File): Stages? = runBlocking {
        runCatching { parser.parse(file) }.getOrNull() ?: return@runBlocking null

        val preflightNanos = measureNanoTime { BookArchivePreflight.inspect(file) }
        val encryptionNanos = measureNanoTime {
            EpubTextExtractor(context).requireNotEncrypted(file)
        }
        val parseNanos = measureNanoTime { parser.parse(file) }

        Stages(
            book = file.nameWithoutExtension,
            preflightNanos = preflightNanos,
            encryptionNanos = encryptionNanos,
            parseNanos = parseNanos
        )
    }

    private companion object {
        /** 趋势判断至少需要的样本数。 */
        const val MIN_BOOKS_FOR_TREND = 3

        /** 计时次数，取最小值 —— 最小值受 GC 与调度干扰最少。 */
        const val MEASURED_RUNS = 2

        /**
         * 最大的书的 perChar 相对全语料最低值的上界。
         *
         * 2.0 是量出来的，不是猜的：健康态实测 1.28，注入平方级工作后实测 3.26。两边各有
         * 约 56% 与 63% 的余量。见 [importCost_doesNotDegradeWithBookSize] 的说明。
         */
        const val MAX_PER_CHAR_RATIO = 2.0
    }
}
