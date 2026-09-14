package io.github.zoot.englishreader.util

import android.icu.text.BreakIterator
import io.github.zoot.englishreader.core.SentenceRange
import java.util.Locale

/**
 * 句子拆分工具
 *
 * 使用 Android ICU BreakIterator 进行句子边界检测。
 *
 * 注意 ICU **不**处理英文缩写：`Locale.US` 不带 `ss=standard` 关键字，不启用 CLDR 缩写抑制
 * 列表，ICU 会在 `Mr. ` 后直接断句。称谓合并靠本文件的 [nonTerminalTitleAtEnd] 正则兜底，
 * 删掉它就会退化。该正则只覆盖 `Mr|Mrs|Ms|Dr|Prof`，其余缩写（`U.S.`、`e.g.`）的表现完全
 * 取决于 ICU 的 UAX#29 基础规则加后文首字母大小写，本文件没有为它们做任何处理。
 *
 * 省略号同理没有专门处理：`Wait... I think so.` 仍会按 ICU 规则断成两句。
 *
 * 要求 Android API 24+
 */
object SentenceSplitter {

    /**
     * 句尾的非终结性称谓缩写，命中则与下一句合并。
     *
     * **大小写敏感（刻意不加 `IGNORE_CASE`）**：英文称谓按惯例首字母大写，而小写 `ms.` 是
     * 「毫秒」——`The delay was 20 ms. The next step ran.` 在大小写不敏感下会被误判为
     * 「女士」并把两句强行合并。代价是全大写的 `MS.`/`MR.` 不再命中，但正文里全大写称谓
     * 远比技术文本里的 `ms.` 罕见。
     *
     * 注意本正则只覆盖这 5 个称谓，`U.S.`/`e.g.`/`i.e.` 等缩写不在此列。
     */
    private val nonTerminalTitleAtEnd = Regex("(?:^|\\s)(?:Mr|Mrs|Ms|Dr|Prof)\\.\\s*$")

    /**
     * 将文本拆分为句子列表
     *
     * @param text 完整文本
     * @return 句子列表，每个句子包含原文和精确的 offset
     */
    fun split(text: String): List<SentenceRange> {
        if (text.isEmpty()) return emptyList()

        val sentences = mutableListOf<SentenceRange>()
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(text)

        var start = iterator.first()
        var end = iterator.next()
        var index = 0

        while (end != BreakIterator.DONE) {
            if (shouldMergeWithNextSentence(text, start, end)) {
                end = iterator.next()
                continue
            }

            val sentenceText = text.substring(start, end)

            // 跳过空白句子
            if (sentenceText.trim().isNotEmpty()) {
                sentences.add(
                    SentenceRange(
                        index = index,
                        text = sentenceText,
                        startOffset = start,
                        endOffset = end
                    )
                )
                index++
            }

            start = end
            end = iterator.next()
        }

        return sentences
    }

    private fun shouldMergeWithNextSentence(
        text: String,
        startOffset: Int,
        endOffset: Int
    ): Boolean {
        val hasFollowingText = (endOffset until text.length).any { !text[it].isWhitespace() }
        if (!hasFollowingText) return false

        return nonTerminalTitleAtEnd.containsMatchIn(text.substring(startOffset, endOffset))
    }
}
