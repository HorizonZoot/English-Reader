package io.github.zoot.englishreader.ui.screen

import android.os.Build
import android.os.Debug
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.ui.component.InteractiveText
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.util.SentenceSplitter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 8 章节渲染基准：抬 [ImportBudget.MAX_CHAPTER_CHARS] 所缺的唯一一项数据。
 *
 * ## 为什么需要它
 *
 * 语料实测（ADR-013），可导入书数占比：
 *
 * ```text
 *  40,000（当前）  11/32 = 34%
 *  60,000          22/32 = 69%
 *  80,000          25/32 = 78%
 * 100,000          25/32 = 78%   ← 与 80,000 持平
 * 200,000          26/32 = 81%   ← 放宽到荒谬也只多一本
 * ```
 *
 * 所以抬章节上限的收益在 80,000 附近就基本用完了，此后每一次拒绝都归段落闸门。
 *
 * **不要引用 41%/62%/78%/91%/94%/100%。** 那是「只看章节闸门」那一列，不含段落闸门；
 * 两列在各候选上限处相差 6 到 13 个百分点（40k 差 6，60k 差 9，80k 差 13）。
 * 本文档初版引用了错的一列。要拿数字去定上限的人看到的必须是全闸门那一列，否则会高估收益。
 *
 * 两个 40,000 上限都仍待真机基准校准；章节与单篇文章的预算已分别声明。
 *
 * ## 它测什么，不测什么
 *
 * 阻塞主线程的是 `ReadingScreen` 的 `remember { ParagraphAligner.align(...) }` —— 同步、
 * 对全章每一段跑 ICU 分句、**不管那段是否可见**。LazyColumn 虚拟化渲染，虚拟化不了这一步。
 * 所以「章节多大」直接决定进入章节时主线程一次性付多少钱，而这正是 [alignCostByChapterSize]
 * 测的东西。
 *
 * [renderCostPerParagraph] 记录单段状态派发到 Compose idle 的耗时，包含调度、测试同步、
 * composition 和 layout，不能当作纯渲染 CPU 时间或帧时间。它使用对应语料长度和句子形状的
 * 合成文本，为 [ImportBudget.MAX_PARAGRAPH_CHARS] 的真机评估提供参考。
 *
 * JVM 侧已证成本曲线线性甚至次线性（`ParagraphAlignerScalingTest`，perChar 从 85ns 降到
 * 43ns），所以这里要的**只是绝对值**：真机 ART 上 80k 章节进入时阻塞是 50ms 还是 300ms。
 *
 * ## 怎么读结果
 *
 * 输出前缀 `CHAPTER-BENCH` / `PARAGRAPH-BENCH`，`adb logcat` 或 Gradle 输出里都能捞。
 * 判据检查实际工作量与完整文本布局，不断言任何时间阈值 —— 阈值需要跨设备数据，单台设备上钉死
 * 会让这个类变成换机即红的噪声源。**结论由人读数字得出，不由断言得出。**
 *
 * 滚动是否掉帧仍需单独的真机帧指标，不能由 state-to-idle 耗时直接判断。
 */
class ChapterRenderBenchmarkAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 进入章节的一次性成本，按字符数扫描。
     *
     * 60,000 与 80,000 是候选上限（可导入覆盖率 **69% / 78%**，不是章节闸门那列的 78%/91%）；
     * 160,000 是语料实测最大单章（164,354）的量级，用来看有没有悬崖 —— JVM 侧说没有，
     * 这里在真机上复核。
     */
    @Test
    fun alignCostByChapterSize() {
        println(
            "=== CHAPTER-BENCH model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "stat=min/median/max runs=$RUNS ==="
        )

        // 40,000 是当前上限，必须在列，否则没有基线可比。
        listOf(40_000, 60_000, 80_000, 100_000, 160_000).forEach { chars ->
            val content = proseOfLength(chars)

            // 预热：ICU BreakIterator 首次取实例含类加载与 locale 数据初始化，
            // 混进计时会让第一个样本显得最贵。
            ParagraphAligner.align(content, null, SentenceSplitter::split)

            val timings = mutableListOf<Long>()
            var paragraphs = 0
            var sentences = 0
            repeat(RUNS) {
                val start = System.nanoTime()
                val aligned = ParagraphAligner.align(content, null, SentenceSplitter::split)
                timings += (System.nanoTime() - start) / 1_000_000
                paragraphs = aligned.size
                sentences = aligned.sumOf { it.sentences.size }
            }

            // 内存单独采一次，不放进计时循环：getMemoryInfo 自身毫秒级。
            // 采样点必须是「对齐结果仍被持有」的时刻 —— 全章 AlignedParagraph 加每段
            // SentenceRange 常驻内存，这是异步化搬不走的那笔成本。
            //
            // ⚠️ 这两个读数是**含垃圾的上界**，不是保留内存。计时循环刚跑完 RUNS 次
            // align，每次的产物都已可回收但未必已被回收，所以 heap 里混着它们。
            // 要拿真正的保留量得先 GC，而那会引入不确定的等待；这里选择记下偏向，
            // 不假装数字更精确。读数时看**相对趋势**（章节翻倍时是否也翻倍），
            // 不要把绝对值当作「一个 80k 章节占多少内存」。
            val held = ParagraphAligner.align(content, null, SentenceSplitter::split)
            val runtime = Runtime.getRuntime()
            val heap = runtime.totalMemory() - runtime.freeMemory()
            val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
            // 保活：不读 held 的话 JIT 可让它在采样点前就可回收，读数会系统性偏低。
            // 这里**不**对 heap/pss 断言 —— `totalMemory - freeMemory > 0` 在任何运行中的
            // JVM 上恒真，而 pss 读数为 0 只说明 /proc 读取失败，与对齐内存无关。
            // 两者都是观测量，不是判据；判据是下面「对齐真的产出了工作量」。
            val heldSentences = held.sumOf { it.sentences.size }

            // 先输出，再断言；后续失败仍保留已完成 case 的数据和设备信息。
            println(
                "CHAPTER-BENCH requestedChars=$chars chars=${content.length} " +
                    "paragraphs=$paragraphs sentences=$sentences " +
                    "keepAliveSentences=$heldSentences " +
                    "alignMs=${stat(timings)} heapBytesUpperBound=$heap pssKbUpperBound=$pss"
            )

            assertTrue("no paragraphs produced at $chars chars", paragraphs > 0)
            assertTrue("alignment produced no sentences at $chars chars", sentences > 0)
            assertTrue(
                "paragraph count $paragraphs at $chars chars exceeded MAX_IMPORT_PARAGRAPHS; " +
                    "the fixture shape is wrong, not the budget",
                paragraphs <= ImportBudget.MAX_IMPORT_PARAGRAPHS
            )
        }
    }

    /**
     * 同字符数、不同段落形状的一次性成本。
     *
     * 语料里 40,000 字符的章节段数中位 35、最大 541 —— 同字符数下差一个数量级。
     * 段落数是与字符数正交的成本维度，只扫字符数会漏掉它。
     */
    @Test
    fun alignCostByParagraphShape() {
        val chars = 80_000
        println(
            "=== CHAPTER-BENCH-SHAPE model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "requestedChars=$chars stat=min/median/max runs=$RUNS ==="
        )

        // 每段句数：2 句 ≈ 语料里「多短段」的形状，60 句 ≈「少长段」。
        listOf(2, 8, 30, 60).forEach { sentencesPerParagraph ->
            val content = proseOfLength(chars, sentencesPerParagraph)
            ParagraphAligner.align(content, null, SentenceSplitter::split)

            val timings = mutableListOf<Long>()
            var paragraphs = 0
            repeat(RUNS) {
                val start = System.nanoTime()
                val aligned = ParagraphAligner.align(content, null, SentenceSplitter::split)
                timings += (System.nanoTime() - start) / 1_000_000
                paragraphs = aligned.size
            }

            println(
                "CHAPTER-BENCH-SHAPE sentencesPerPara=$sentencesPerParagraph " +
                    "chars=${content.length} paragraphs=$paragraphs alignMs=${stat(timings)}"
            )
            assertTrue("no paragraphs at shape $sentencesPerParagraph", paragraphs > 0)
        }
    }

    /**
     * 单段状态派发到 idle 的耗时，按段落字符数扫描。
     *
     * 8,000 是当前上限；14,053 对应达西的信的长度；21,381 对应莫莉独白的一段。
     * 前两例合成多句散文，后一例无终结标点。实际句数由本次 fixture 分句结果报告。
     *
     * setContent 和分句在计时外。每轮计时从状态派发前到 idle，包含调度与测试同步开销；
     * 全文语义及布局断言也在计时外，不要求整段同时位于 viewport 中。
     */
    @Test
    fun renderCostPerParagraph() {
        println(
            "=== PARAGRAPH-BENCH model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "metric=state-dispatch-to-idle stat=min/median/max runs=$RUNS ==="
        )

        val cases = listOf(
            "at-budget" to proseOfLength(ImportBudget.MAX_PARAGRAPH_CHARS, Int.MAX_VALUE),
            "darcy-letter" to proseOfLength(14_053, Int.MAX_VALUE),
            "molly-soliloquy" to unpunctuatedProse(21_381)
        )
        val prepared = cases.map { (label, text) -> Triple(label, text, SentenceSplitter.split(text)) }

        // setContent 每个测试只调用一次，后续由 state 驱动。
        var current by mutableStateOf(prepared.first())
        composeRule.setContent {
            val (_, text, sentences) = current
            ParagraphBenchmarkViewport {
                InteractiveText(
                    text = text,
                    fontSize = 16.sp,
                    precomputedSentences = sentences,
                    modifier = Modifier.fillMaxWidth().testTag(PARAGRAPH_BENCHMARK_TAG),
                    onSentenceClick = { _, _ -> },
                    onWordLongPress = {}
                )
            }
        }
        composeRule.waitForIdle()

        // 空段落用作切换间的中继：不经过它，连续两次设置同一 case 不会触发重组，
        // 计时会退化成「什么都没做」。
        val blank = Triple("blank", "", emptyList<SentenceRange>())

        prepared.forEach { case ->
            val (label, paragraph, sentences) = case
            val timings = mutableListOf<Long>()
            var verifiedRuns = 0

            try {
                repeat(RUNS) { iteration ->
                    composeRule.runOnUiThread { current = blank }
                    composeRule.waitForIdle()

                    val start = System.nanoTime()
                    composeRule.runOnUiThread { current = case }
                    composeRule.waitForIdle()
                    timings += (System.nanoTime() - start) / 1_000_000

                    composeRule.assertCompleteParagraph(paragraph, "$label run=${iteration + 1}")
                    verifiedRuns++
                }
                assertTrue("no sentences split for $label", sentences.isNotEmpty())
            } finally {
                println(
                    "PARAGRAPH-BENCH case=$label chars=${paragraph.length} " +
                        "sentences=${sentences.size} measuredRuns=${timings.size} " +
                        "verifiedRuns=$verifiedRuns stateToIdleMs=${stat(timings)}"
                )
            }
        }
    }

    // ---- fixtures ----

    /**
     * 有真实句子结构的散文。
     *
     * 不用 `"word ".repeat(n)`：整段没有终结标点会让 ICU 当成一个巨型句子，测出的是退化
     * 路径而非小说。[sentencesPerParagraph] 传 [Int.MAX_VALUE] 得到单段文本。
     */
    private fun proseOfLength(targetChars: Int, sentencesPerParagraph: Int = 8): String {
        val sentence = "The evening light fell across the narrow street and the shutters were closed. "
        if (sentencesPerParagraph == Int.MAX_VALUE) {
            val builder = StringBuilder(targetChars + sentence.length)
            while (builder.length < targetChars) builder.append(sentence)
            return builder.substring(0, targetChars)
        }
        val paragraph = sentence.repeat(sentencesPerParagraph).trimEnd()
        val builder = StringBuilder(targetChars + paragraph.length)
        while (builder.length < targetChars) builder.append(paragraph).append("\n\n")
        return builder.toString().trim()
    }

    /**
     * 无终结标点的连续散文 —— 莫莉独白的形状。
     *
     * ICU 会把整段当作一个句子，于是 AnnotatedString 只有一个 span 但极长。这是与
     * 「多句长段」不同的成本形态，必须单独测。
     */
    private fun unpunctuatedProse(targetChars: Int): String {
        val clause = "and then the light came through the window and I remembered the sea "
        val builder = StringBuilder(targetChars + clause.length)
        while (builder.length < targetChars) builder.append(clause)
        return builder.substring(0, targetChars)
    }

    private fun stat(values: List<Long>): String {
        if (values.isEmpty()) return "n/a"
        val sorted = values.sorted()
        return "${sorted.first()}/${sorted[sorted.size / 2]}/${sorted.last()}"
    }

    private companion object {
        const val RUNS = 5
    }
}
