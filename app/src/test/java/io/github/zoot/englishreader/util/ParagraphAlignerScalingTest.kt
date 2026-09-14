package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.data.importer.ImportBudget
import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ParagraphAligner.align` × 真实 `SentenceSplitter` 的**成本曲线形状**。
 *
 * 为什么这个类存在，以及它不是什么
 * -------------------------------
 * 抬高 [ImportBudget.MAX_CHAPTER_CHARS] 的风险集中在一处：`ReadingScreen` 在
 * `remember(content, translation)` 里同步调用 `ParagraphAligner.align`，而 align 会对
 * **每一段**跑一次分句 —— 不管那段是否可见。LazyColumn 只虚拟化渲染，虚拟化不了这一步。
 * 所以「章节多大」直接决定进入章节时主线程一次性付多少钱。
 *
 * 这里测的是**形状**（线性还是超线性），不是绝对耗时。JVM + Robolectric 的 ICU 与真机
 * ART 的性能无关，任何毫秒数都不能用来定上限；但「翻倍字符是否只翻倍成本」这个性质与
 * 硬件无关，若它超线性，抬上限就是危险的，无论设备多快。
 *
 * 断言刻意宽松（比例上界 3.0×，远高于线性的 2.0×）：单次运行会受 GC 与 JIT 影响，
 * 严格的阈值会让这个类变成随机失败源。它要抓的是数量级退化，例如 O(n²)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParagraphAlignerScalingTest {

    /**
     * 构造一段有真实句子结构的文本。
     *
     * 不用 `"word ".repeat(n)`：那样整段没有终结标点，ICU 会当成一个巨型句子，
     * 测出来的是退化路径而非真实小说的成本。
     */
    private fun proseOfLength(targetChars: Int, sentencesPerParagraph: Int = 8): String {
        val sentence = "The evening light fell across the narrow street and the shutters were closed. "
        val paragraph = sentence.repeat(sentencesPerParagraph)
        val builder = StringBuilder(targetChars + paragraph.length)
        while (builder.length < targetChars) {
            builder.append(paragraph.trimEnd()).append("\n\n")
        }
        return builder.toString().trim()
    }

    /**
     * 取多次测量的**最小值**：最小值是受干扰最少的那次，比中位数更能代表算法本身的成本。
     *
     * 预热跑 3 次而非 1 次：ICU `BreakIterator` 首次取实例含类加载与 locale 数据初始化，
     * 而 JIT 编译在 C1/C2 分层下需要更多次调用才稳定。实测 1 次预热时首档（20,000 字符）
     * 的 perChar 会是后续档的 2–4 倍，那不是规模效应而是残留的一次性成本。
     */
    private fun alignNanos(content: String): Long {
        repeat(WARMUP_RUNS) { ParagraphAligner.align(content, null, SentenceSplitter::split) }
        return (1..MEASURED_RUNS).minOf {
            measureNanoTime { ParagraphAligner.align(content, null, SentenceSplitter::split) }
        }
    }

    /**
     * 断言的是**每字符成本的趋势**，不是成对比值。
     *
     * 成对比值（「翻倍字符耗时不超过 3 倍」）是最噪声敏感的量：单次测量受 GC 与 JIT 影响，
     * 相邻两档之间的抖动直接进比值。实测它在被压住的机器上跑出 3.02x 擦阈值翻车 —— 而那次
     * 的 perChar 序列仍然是下降的，也就是说被测性质本身没问题，是断言的形式不对。
     *
     * perChar 抗噪得多，且与要抓的东西直接对应：
     *
     * ```text
     * 线性      perChar 持平
     * 次线性    perChar 下降   （实测是这一档：~85ns → ~43ns）
     * O(n^2)    perChar 随规模线性上升 —— 8 倍字符会让它涨 8 倍
     * ```
     *
     * 阈值取 2.0（最大档的 perChar 不得超过最小档的两倍）：对 O(n^2) 有 4 倍余量，
     * 对观测到的噪声有充足余量，且不会像 3.0x 那样在负载下擦边。
     */
    @Test
    fun align_costGrowsAboutLinearlyWithChapterSize() {
        val sizes = listOf(20_000, 40_000, 80_000, 160_000)
        val timings = sizes.associateWith { alignNanos(proseOfLength(it)) }
        val perChar = sizes.associateWith { timings.getValue(it).toDouble() / it }

        sizes.forEach { chars ->
            println(
                "ALIGN-SCALING chars=$chars nanos=${timings.getValue(chars)} " +
                    "perChar=${"%.1f".format(perChar.getValue(chars))}"
            )
        }

        val smallest = perChar.getValue(sizes.first())
        val largest = perChar.getValue(sizes.last())
        val growth = largest / smallest
        println("ALIGN-SCALING perCharGrowth=${"%.2f".format(growth)} (linear<=1.0, O(n^2)~8.0)")

        assertTrue(
            "per-char align cost grew ${"%.2f".format(growth)}x from ${sizes.first()} to " +
                "${sizes.last()} chars (${"%.1f".format(smallest)}ns -> ${"%.1f".format(largest)}ns). " +
                "Linear or better keeps this at or below 1.0; an 8x size increase under O(n^2) " +
                "would show ~8.0. Above 2.0 means raising MAX_CHAPTER_CHARS is unsafe on any device.",
            growth < 2.0
        )
    }

    /**
     * 同字符数、不同段落结构的成本对比。只断言段数不同，**不断言耗时关系**。
     *
     * ⚠️ 打印的 `ratio` 在本机连续两次运行给出 0.75 与 1.14 —— 也就是说在这个量级上
     * （毫秒级、JVM、单次测量）段落结构的影响被 GC 与 JIT 噪声完全淹没。**不要引用这个
     * 比值下任何结论**，尤其不要用它论证「段落数不影响成本」：`MAX_IMPORT_PARAGRAPHS`
     * 防的是渲染期每段独立构造 AnnotatedString 与测量文本，那部分成本不在 align 里，
     * 本用例根本没测到。
     *
     * 保留它的唯一理由是让「段落数是与字符数正交的成本维度」在设计真机基准时不被忘掉：
     * 真机基准必须同时扫字符数与段落数两个轴，不能只扫字符数。
     */
    @Test
    fun align_sameCharsDifferentParagraphShapes_reportsBothCosts() {
        val chars = 80_000
        val fewLongParagraphs = alignNanos(proseOfLength(chars, sentencesPerParagraph = 60))
        val manyShortParagraphs = alignNanos(proseOfLength(chars, sentencesPerParagraph = 2))

        val fewCount = ParagraphAligner
            .align(proseOfLength(chars, 60), null, SentenceSplitter::split).size
        val manyCount = ParagraphAligner
            .align(proseOfLength(chars, 2), null, SentenceSplitter::split).size

        println(
            "ALIGN-SHAPE chars=$chars " +
                "fewLong=${fewCount}paras/${fewLongParagraphs}ns " +
                "manyShort=${manyCount}paras/${manyShortParagraphs}ns " +
                "ratio=${"%.2f".format(manyShortParagraphs.toDouble() / fewLongParagraphs)} " +
                "(NOISY: observed 0.75 and 1.14 on consecutive runs -- do not draw conclusions)"
        )

        assertTrue("expected the many-paragraph shape to have more paragraphs", manyCount > fewCount)
    }

    /**
     * 每字符成本不应随**单段长度**上升。
     *
     * [align_costGrowsAboutLinearlyWithChapterSize] 扫的是总字符数，而它固定了每段句数，
     * 于是增长的只有**段落数**、段长不变。审查指出这留了个盲区：若分句在单段内部是超线性的
     * （例如 ICU 对长文本退化，或将来有人在段内加了嵌套扫描），那条扫描看不见 —— 它的每段
     * 始终是 8 句。
     *
     * 本用例把总字符数固定在 80,000，只改每段句数（4 → 256），于是段数下降、段长上升。
     * 判据同样是 perChar 趋势：段内线性时 perChar 与段长无关（总工作量恒定），
     * 段内超线性时 perChar 随段长上升。
     *
     * 阈值取 2.5：比总量那条的 2.0 松一点，因为段数变化会改变每段的固定开销
     * （每段一次 `getSentenceInstance`），少而长的段落反而省掉了这部分，两个方向的效应叠加，
     * 噪声比单纯扫总量更大。
     *
     * ## 灵敏度是量出来的
     *
     * 健康态实测 1.05（段长跨 64 倍而 perChar 几乎不动）。注入段内二次项后 7.64 → 红。
     *
     * 中间还试过两次更轻的破坏，都没红，而**那两次是破坏错了不是测试错了**：
     * 第一次注入 O(段长)，总工作量 = 各段长之和 = 总字符数，跨形状恒定，perChar 本该持平；
     * 第二次注入 `n²/2000`，它成了小的附加项而非主导项（1.22）。
     * 真实的段内 O(n²) 会是什么量级可以直接算：段长 20,000 vs 312，单段成本比
     * `(20000/312)² ≈ 4100`，乘上段数 5 vs 256，总量比约 80 倍 —— 远在 2.5 之上。
     * 所以这个阈值对真实退化足够，只是抓不到「和基线同量级的轻微超线性」。
     */
    @Test
    fun align_costDoesNotDegradeWithParagraphLength() {
        val totalChars = 80_000
        val shapes = listOf(4, 16, 64, 256)

        val perChar = shapes.associateWith { sentencesPerParagraph ->
            val content = proseOfLength(totalChars, sentencesPerParagraph)
            val paragraphs = ParagraphAligner.align(content, null, SentenceSplitter::split)
            val nanos = alignNanos(content)
            Triple(nanos.toDouble() / content.length, paragraphs.size, content.length)
        }

        shapes.forEach { s ->
            val (pc, paras, len) = perChar.getValue(s)
            println(
                "ALIGN-PARA-LEN sentencesPerPara=$s paragraphs=$paras chars=$len " +
                    "perChar=${"%.1f".format(pc)}"
            )
        }

        val shortest = perChar.getValue(shapes.first()).first
        val longest = perChar.getValue(shapes.last()).first
        val growth = longest / shortest
        println("ALIGN-PARA-LEN perCharGrowth=${"%.2f".format(growth)} (flat means intra-paragraph linear)")

        assertTrue(
            "per-char align cost grew ${"%.2f".format(growth)}x when paragraph length went from " +
                "${shapes.first()} to ${shapes.last()} sentences at a fixed $totalChars total chars " +
                "(${"%.1f".format(shortest)}ns -> ${"%.1f".format(longest)}ns). Intra-paragraph " +
                "linear behaviour keeps this flat, because total work is unchanged. A rise means " +
                "splitting degrades with paragraph length -- invisible to the total-size sweep, " +
                "which holds paragraph length constant.",
            growth < MAX_PARAGRAPH_LENGTH_GROWTH
        )
    }

    /**
     * 当前上限下的段落数与分句总数，供基准设计取参考点。
     *
     * 语料实测：40,000 字符的章节最多 541 段；放宽到 60,000 时出现 977 段的章节
     * （`MAX_IMPORT_PARAGRAPHS` 是 1,200）。这里打印的是合成文本的对应值。
     */
    @Test
    fun align_atCurrentChapterCeiling_reportsWorkUnits() {
        val content = proseOfLength(ImportBudget.MAX_CHAPTER_CHARS)
        val paragraphs = ParagraphAligner.align(content, null, SentenceSplitter::split)
        val sentences = paragraphs.sumOf { it.sentences.size }

        println(
            "ALIGN-AT-CEILING chars=${content.length} " +
                "paragraphs=${paragraphs.size} sentences=$sentences " +
                "nanos=${alignNanos(content)}"
        )

        assertTrue("ceiling text must produce paragraphs", paragraphs.isNotEmpty())
        assertTrue(
            "paragraph count ${paragraphs.size} must stay inside MAX_IMPORT_PARAGRAPHS",
            paragraphs.size <= ImportBudget.MAX_IMPORT_PARAGRAPHS
        )
    }

    private companion object {
        /**
         * 预热次数。3 次而非 1 次：见 [alignNanos]。1 次时首档 perChar 会带着未摊销的
         * 类加载与 JIT 成本，让「小规模更贵」这个假象进入趋势判断。
         */
        const val WARMUP_RUNS = 3

        /** 计时次数，取最小值。 */
        const val MEASURED_RUNS = 3

        /**
         * 段长维度的 perChar 增长上界。
         *
         * 比总量维度的 2.0 松：改变每段句数会同时改变每段的固定开销
         * （每段一次 `getSentenceInstance`），少而长的段落省掉这部分，两个方向的效应叠加，
         * 噪声比单纯扫总量更大。见 [align_costDoesNotDegradeWithParagraphLength]。
         */
        const val MAX_PARAGRAPH_LENGTH_GROWTH = 2.5
    }
}
