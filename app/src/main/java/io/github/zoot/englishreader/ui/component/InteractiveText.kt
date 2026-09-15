package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.ui.theme.LocalReaderHighlightColors
import io.github.zoot.englishreader.util.SentenceSplitter
import io.github.zoot.englishreader.util.WordBoundaryDetector
import java.util.Locale

/**
 * 交互式文本组件
 *
 * 核心功能：
 * - 点击句子 → 高亮整句
 * - 长按单词 → 提取单词
 *
 * 从 PoC 项目迁移，使用 BasicText + TextLayoutResult 实现精确交互
 */
@Composable
fun InteractiveText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    highlightedSentenceIndex: Int? = null,
    selectedWord: String? = null,
    sentenceIndexOffset: Int = 0,
    precomputedSentences: List<SentenceRange>? = null,
    onSentenceClick: (Int, SentenceRange) -> Unit,
    onWordLongPress: (String) -> Unit,
    onClearSelection: () -> Unit = {},
    onSentencePlay: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    onSentenceTranslate: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    onWordPressStart: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    onWordPressRelease: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    onSentenceTapTarget: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    selectedWordStartOffset: Int? = null,
    selectedWordEndOffset: Int? = null,
    fontFamily: FontFamily? = null,
    onSentenceExplain: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    selectedSentenceTarget: InteractiveTextLongPressTarget? = null,
    onSentenceTargetLayoutChanged: ((InteractiveTextLongPressTarget) -> Unit)? = null,
    visibleViewport: ReadingTextViewport? = null,
    enabled: Boolean = true,
    /**
     * 整段高亮的强度（0 = 不高亮，1 = 与句子选中同强度）。
     *
     * 用强度而不是布尔：调用方要把它接到 `animateFloatAsState` 上做渐隐，否则
     * 生词本跳过来点亮的那一段到期熄灭时是硬切。颜色仍由本组件内部决定，
     * 调用方不需要知道高亮色是什么。
     */
    paragraphHighlight: Float = 0f,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    if (text.isEmpty()) {
        BasicText(text = "", style = TextStyle(fontSize = fontSize))
        return
    }

    // 回调快照：始终指向最新的 lambda，但不参与 remember/pointerInput 的 key，
    // 避免 ReadingScreen 每次重组新建 lambda 导致手势检测器和无障碍动作被反复重建。
    val currentOnTextLayout by rememberUpdatedState(onTextLayout)
    val currentOnSentenceClick by rememberUpdatedState(onSentenceClick)
    val currentOnWordLongPress by rememberUpdatedState(onWordLongPress)
    val currentOnClearSelection by rememberUpdatedState(onClearSelection)
    val currentOnSentencePlay by rememberUpdatedState(onSentencePlay)
    val currentOnSentenceTranslate by rememberUpdatedState(onSentenceTranslate)
    val currentOnSentenceExplain by rememberUpdatedState(onSentenceExplain)
    val currentOnWordPressStart by rememberUpdatedState(onWordPressStart)
    val currentOnWordPressRelease by rememberUpdatedState(onWordPressRelease)
    val currentOnSentenceTapTarget by rememberUpdatedState(onSentenceTapTarget)
    val currentOnSentenceTargetLayoutChanged by rememberUpdatedState(onSentenceTargetLayoutChanged)

    // 正文色随主题（暗色下不再是硬编码黑）；高亮色由主题下发以保证暗色对比度。
    val textColor = MaterialTheme.colorScheme.onSurface
    // 句子选中与单词选中共用同一高亮色。
    val highlightColor = LocalReaderHighlightColors.current.word
    val underlineColor = MaterialTheme.colorScheme.primary
    // 整段高亮走同一套观感：贴字形的底纹 + 虚线框，和「选中」完全一致，
    // 而不是给整段套一个色块——那看起来像卡片，不像选中。
    //
    // 底纹画在**绘制阶段**而不是写成 AnnotatedString 的 SpanStyle：强度由
    // animateFloatAsState 驱动，每帧都是一个新颜色值，若参与 annotatedText 的
    // remember key，渐隐的每一帧都会重建整段 AnnotatedString 并触发重新布局
    // （长段落上是阅读页热路径）。绘制阶段只重绘，不重排。
    val paragraphHighlightColor = if (paragraphHighlight > 0f) {
        highlightColor.copy(alpha = highlightColor.alpha * paragraphHighlight)
    } else {
        Color.Transparent
    }

    // 句子来源：调用方已在对齐阶段分好句时直接复用，避免同一段文本被分句两次
    // （对齐层算 sentenceOffset 一次 + 渲染层再一次）。
    // 传入的 offset 必须是相对 [text] 的段落局部坐标；ParagraphAligner.AlignedParagraph
    // 在构造时已校验该配对，故此处不再重复校验。
    // 空列表视为「未预计算」而非「此段无句」：否则非空 text 会静默渲染为空白。
    val sentences = remember(text, precomputedSentences) {
        precomputedSentences?.takeIf { it.isNotEmpty() } ?: SentenceSplitter.split(text)
    }

    // TextLayoutResult 用于指针命中和 TalkBack 句子动作的响应式锚点。
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val visibleStart = visibleViewport?.startOffset ?: 0
    val visibleEnd = visibleViewport?.endOffset ?: text.length
    val visibleSentences = remember(sentences, visibleStart, visibleEnd) {
        sentences.filter { it.startOffset < visibleEnd && it.endOffset > visibleStart }
    }

    LaunchedEffect(textLayoutResult, selectedSentenceTarget, visibleViewport) {
        val target = selectedSentenceTarget ?: return@LaunchedEffect
        val layout = textLayoutResult ?: return@LaunchedEffect
        if (layout.layoutInput.text.text != text) return@LaunchedEffect
        val sentence = visibleSentences.firstOrNull {
            it.index + sentenceIndexOffset == target.sentenceIndex && it == target.sentenceRange
        } ?: return@LaunchedEffect
        val glyphOffset = target.glyphOffset?.takeIf {
            it in maxOf(sentence.startOffset, visibleStart) until minOf(sentence.endOffset, visibleEnd) &&
                !text[it].isWhitespace()
        } ?: (maxOf(sentence.startOffset, visibleStart) until minOf(sentence.endOffset, visibleEnd))
            .firstOrNull { !text[it].isWhitespace() }
            ?: return@LaunchedEffect
        val measured = target.copy(
            anchorBounds = layout.getBoundingBox(glyphOffset).inViewport(visibleViewport),
            sentenceBounds = layout.sentenceBounds(sentence, visibleViewport),
            glyphOffset = glyphOffset
        )
        if (measured != target) currentOnSentenceTargetLayoutChanged?.invoke(measured)
    }

    // 限制可访问词汇数量，防止超过 MaxAccessibilityWordActions 导致系统截断
    val accessibilityWords = remember(text, visibleStart, visibleEnd) {
        extractAccessibilityWords(text, startOffset = visibleStart, endOffset = visibleEnd)
    }

    val accessibilityContentDescription = stringResource(
        R.string.interactive_text_content_description
    )
    val highlightSentenceActionPrefix = stringResource(
        R.string.accessibility_highlight_sentence_action_prefix
    )
    val extractWordActionPrefix = stringResource(
        R.string.accessibility_extract_word_action_prefix
    )
    val playSentenceActionLabel = stringResource(R.string.accessibility_play_sentence_action)
    val translateSentenceActionLabel = stringResource(R.string.accessibility_translate_sentence_action)
    val explainSentenceActionLabel = stringResource(R.string.accessibility_explain_sentence_action)

    // 句子动作与单词动作各自独立限额（互不挤占），合计有上限避免超过系统对 CustomAccessibilityAction
    // 数量的限制被静默丢弃。key 只用稳定值（回调经 rememberUpdatedState 快照，不再作为 key）。
    val accessibilityActions = remember(
        visibleSentences, accessibilityWords, sentenceIndexOffset, visibleViewport, enabled,
        highlightedSentenceIndex,
        highlightSentenceActionPrefix, extractWordActionPrefix,
        playSentenceActionLabel, translateSentenceActionLabel, explainSentenceActionLabel,
        onSentencePlay != null, onSentenceTranslate != null, onSentenceExplain != null
    ) {
        val selectedAccessibilitySentence = visibleSentences.firstOrNull { sentence ->
            sentence.index + sentenceIndexOffset == highlightedSentenceIndex
        }
        val extraActionCount = if (selectedAccessibilitySentence == null) {
            0
        } else {
            listOf(onSentencePlay, onSentenceTranslate, onSentenceExplain).count { it != null }
        }
        val reservedPerCategory = (extraActionCount + 1) / 2
        val sentenceActions = visibleSentences
            .take(MaxAccessibilitySentenceActions - reservedPerCategory)
            .map { sentence ->
            CustomAccessibilityAction(
                label = "$highlightSentenceActionPrefix ${sentence.index + sentenceIndexOffset + 1}",
                action = {
                    currentOnSentenceClick(sentence.index + sentenceIndexOffset, sentence)
                    true
                },
            )
        }
        val wordActions = accessibilityWords
            .take(MaxAccessibilityWordActions - extraActionCount / 2)
            .map { word ->
            CustomAccessibilityAction(
                label = "$extractWordActionPrefix $word",
                action = {
                    currentOnWordLongPress(word)
                    true
                },
            )
        }
        buildList {
            if (!enabled) return@buildList
            addAll(sentenceActions)
            addAll(wordActions)
            if (selectedAccessibilitySentence != null && currentOnSentencePlay != null) {
                add(CustomAccessibilityAction(playSentenceActionLabel) {
                    val target = createAccessibilityTarget(
                        text = text,
                        sentence = selectedAccessibilitySentence,
                        sentenceIndexOffset = sentenceIndexOffset,
                        layoutResult = textLayoutResult,
                        viewport = visibleViewport
                    )
                    if (target == null) {
                        false
                    } else {
                        currentOnSentencePlay?.invoke(target)
                        true
                    }
                })
            }
            if (selectedAccessibilitySentence != null && currentOnSentenceTranslate != null) {
                add(CustomAccessibilityAction(translateSentenceActionLabel) {
                    val target = createAccessibilityTarget(
                        text = text,
                        sentence = selectedAccessibilitySentence,
                        sentenceIndexOffset = sentenceIndexOffset,
                        layoutResult = textLayoutResult,
                        viewport = visibleViewport
                    )
                    if (target == null) {
                        false
                    } else {
                        currentOnSentenceTranslate?.invoke(target)
                        true
                    }
                })
            }
            if (selectedAccessibilitySentence != null && currentOnSentenceExplain != null) {
                add(CustomAccessibilityAction(explainSentenceActionLabel) {
                    val target = createAccessibilityTarget(
                        text = text,
                        sentence = selectedAccessibilitySentence,
                        sentenceIndexOffset = sentenceIndexOffset,
                        layoutResult = textLayoutResult,
                        viewport = visibleViewport
                    )
                    if (target == null) {
                        false
                    } else {
                        currentOnSentenceExplain?.invoke(target)
                        true
                    }
                })
            }
        }
    }

    // 构建带高亮的 AnnotatedString
    // sentences 必须入 key：它不再是 text 的纯函数（precomputedSentences 可让同一 text
    // 配不同列表），只键 text 会用旧列表拼出与当前句子不一致的 annotatedText。
    val annotatedText = remember(
        text, sentences, highlightedSentenceIndex, selectedWord, selectedWordStartOffset,
        selectedWordEndOffset, sentenceIndexOffset, highlightColor
    ) {
        buildAnnotatedString {
            append(text)
            sentences.forEach { sentence ->
                if (sentence.index + sentenceIndexOffset == highlightedSentenceIndex) {
                    addStyle(SpanStyle(background = highlightColor), sentence.startOffset, sentence.endOffset)
                }
            }
            selectedWord?.let { word ->
                var from = 0
                while (word.isNotEmpty() && from < text.length) {
                    val start = text.indexOf(word, from, ignoreCase = true)
                    if (start < 0) break
                    val end = start + word.length
                    val wordBoundary = (start == 0 || !WordBoundaryDetector.isWordCharacter(text[start - 1])) &&
                        (end == text.length || !WordBoundaryDetector.isWordCharacter(text[end]))
                    val exact = selectedWordStartOffset == null ||
                        (selectedWordStartOffset == start && selectedWordEndOffset == end)
                    if (wordBoundary && exact) addStyle(SpanStyle(background = highlightColor), start, end)
                    from = start + 1
                }
            }
        }
    }

    val accessibilityModifier = if (visibleViewport == null) {
        Modifier.semantics {
            contentDescription = accessibilityContentDescription
            customActions = accessibilityActions
        }
    } else {
        Modifier.clearAndSetSemantics {
            contentDescription = accessibilityContentDescription
            this.text = AnnotatedString(text.substring(visibleStart, visibleEnd))
            customActions = accessibilityActions
            getTextLayoutResult { results ->
                textLayoutResult?.let { results.add(it) } ?: false
            }
        }
    }

    BasicText(
        text = annotatedText,
        modifier = modifier
            .then(accessibilityModifier)
            .readingTextViewport(visibleViewport)
            .drawWithContent {
                // 整段底纹画在文字**之前**：它是背景，盖在字上会把正文糊掉。
                // 句子/单词的高亮仍是 AnnotatedString 的 SpanStyle，由 drawContent
                // 连同文字一起画出，因此天然叠在这层之上。
                if (paragraphHighlightColor != Color.Transparent) {
                    drawTextRangeBackground(
                        layout = textLayoutResult,
                        startOffset = 0,
                        endOffset = text.length,
                        color = paragraphHighlightColor
                    )
                }
                drawContent()
                // 整段的虚线框画在最底层：它标的是「这一段」，句子/单词的框叠在其上。
                if (paragraphHighlightColor != Color.Transparent) {
                    drawDashedTextRange(
                        layout = textLayoutResult,
                        startOffset = 0,
                        endOffset = text.length,
                        color = underlineColor.copy(alpha = paragraphHighlight)
                    )
                }
                val selectedSentence = sentences.firstOrNull { sentence ->
                    sentence.index + sentenceIndexOffset == highlightedSentenceIndex
                }
                selectedSentence?.let { sentence ->
                    drawDashedTextRange(
                        layout = textLayoutResult,
                        startOffset = sentence.startOffset,
                        endOffset = sentence.endOffset,
                        color = underlineColor
                    )
                }

                val start = selectedWordStartOffset
                val end = selectedWordEndOffset
                val layout = textLayoutResult
                if (start != null && end != null && layout != null && end > start) {
                    drawDashedTextRange(
                        layout = layout,
                        startOffset = start,
                        endOffset = end,
                        color = underlineColor
                    )
                }
            }
            .pointerInput(text, sentences, sentenceIndexOffset, visibleViewport, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    var released: PointerInputChange? = null
                    var cancelled = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed ||
                                event.changes.any { it.id != down.id && it.pressed } ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                            if (!change.pressed) {
                                released = change
                                return@withTimeoutOrNull
                            }
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.any { it.id == down.id && it.isConsumed }) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                        }
                    }
                    if (cancelled) return@awaitEachGesture
                    val layout = textLayoutResult
                    val charOffset = findGlyphOffsetAtPosition(down.position, layout)
                        ?.takeIf { it in visibleStart until visibleEnd }
                    val sentence = charOffset?.let { offset ->
                        sentences.firstOrNull { offset in it.startOffset until it.endOffset }
                    }
                    if (released != null) {
                        if (layout != null && charOffset != null && sentence != null) {
                            currentOnSentenceTapTarget?.invoke(
                                InteractiveTextLongPressTarget(
                                    sentenceIndex = sentence.index + sentenceIndexOffset,
                                    sentenceRange = sentence,
                                    word = null,
                                    anchorBounds = layout.getBoundingBox(charOffset).inViewport(visibleViewport),
                                    sentenceBounds = layout.sentenceBounds(sentence, visibleViewport),
                                    glyphOffset = charOffset
                                )
                            )
                            currentOnSentenceClick(sentence.index + sentenceIndexOffset, sentence)
                        } else {
                            currentOnClearSelection()
                        }
                        return@awaitEachGesture
                    }
                    if (layout == null || charOffset == null || sentence == null) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    val word = WordBoundaryDetector.getWordAtOffset(text, charOffset)
                    if (word == null) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    val target = InteractiveTextLongPressTarget(
                        sentenceIndex = sentence.index + sentenceIndexOffset,
                        sentenceRange = sentence,
                        word = word,
                        anchorBounds = layout.getBoundingBox(charOffset).inViewport(visibleViewport),
                        sentenceBounds = layout.sentenceBounds(sentence, visibleViewport),
                        wordStartOffset = findWordStart(text, charOffset),
                        wordEndOffset = findWordEnd(text, charOffset),
                        glyphOffset = charOffset
                    )
                    currentEvent.changes.firstOrNull { it.id == down.id }?.consume()
                    currentOnWordPressStart?.invoke(target)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed ||
                            event.changes.any { it.id != down.id && it.pressed } ||
                            change.position.x !in 0f..layout.size.width.toFloat() ||
                            change.position.y !in (visibleViewport?.top ?: 0).toFloat()..
                                (visibleViewport?.let { it.top + it.height } ?: layout.size.height).toFloat()
                        ) {
                            currentOnClearSelection()
                            break
                        }
                        change.consume()
                        if (!change.pressed) {
                            val onRelease = currentOnWordPressRelease
                            if (onRelease != null) onRelease(target) else currentOnWordLongPress(word)
                            break
                        }
                    }
                }
            },
        style = readingOriginalTextStyle(fontSize, fontFamily, textColor),
        onTextLayout = {
            textLayoutResult = it
            currentOnTextLayout(it)
        }
    )
}

private fun createAccessibilityTarget(
    text: String,
    sentence: SentenceRange,
    sentenceIndexOffset: Int,
    layoutResult: TextLayoutResult?,
    viewport: ReadingTextViewport? = null
): InteractiveTextLongPressTarget? {
    layoutResult ?: return null
    val glyphOffset = (maxOf(sentence.startOffset, viewport?.startOffset ?: 0) until
        minOf(sentence.endOffset, viewport?.endOffset ?: text.length))
        .firstOrNull { !text[it].isWhitespace() }
        ?: return null
    return InteractiveTextLongPressTarget(
        sentenceIndex = sentence.index + sentenceIndexOffset,
        sentenceRange = sentence,
        word = null,
        anchorBounds = layoutResult.getBoundingBox(glyphOffset).inViewport(viewport),
        sentenceBounds = layoutResult.sentenceBounds(sentence, viewport),
        glyphOffset = glyphOffset
    )
}

// 空串必须提前返回：`coerceIn(0, text.lastIndex)` 在空串上是 coerceIn(0, -1)，区间非法会抛
// IllegalArgumentException。当前调用点只在 word != null 时才进来，空串到不了这里，但 helper
// 自身不该依赖调用方的前置条件。
private fun findWordStart(text: String, offset: Int): Int {
    if (text.isEmpty()) return 0
    var start = offset.coerceIn(0, text.lastIndex)
    while (start > 0 && WordBoundaryDetector.isWordCharacter(text[start - 1])) start--
    return start
}

private fun findWordEnd(text: String, offset: Int): Int {
    if (text.isEmpty()) return 0
    var end = offset.coerceIn(0, text.lastIndex)
    while (end + 1 < text.length && WordBoundaryDetector.isWordCharacter(text[end + 1])) end++
    return end + 1
}

/**
 * 返回覆盖句子范围所触及的全部布局行的局部矩形。
 * 水平锚点仍由被按下的字形单独提供；此矩形只用于弹层的纵向避让，
 * 因此刻意包含完整的行高。
 */
private fun Rect.inViewport(viewport: ReadingTextViewport?): Rect = viewport?.toVisibleBounds(this) ?: this

private fun TextLayoutResult.sentenceBounds(sentence: SentenceRange, viewport: ReadingTextViewport? = null): Rect {
    val textLength = layoutInput.text.length
    if (textLength == 0 || sentence.startOffset >= sentence.endOffset) {
        return Rect.Zero
    }

    val rangeStart = maxOf(sentence.startOffset, viewport?.startOffset ?: 0).coerceIn(0, textLength)
    val rangeEnd = minOf(sentence.endOffset, viewport?.endOffset ?: textLength).coerceIn(rangeStart, textLength)
    val firstOffset = (rangeStart until rangeEnd)
        .firstOrNull { !layoutInput.text[it].isWhitespace() }
        ?: return Rect.Zero
    val lastOffset = (rangeEnd - 1 downTo rangeStart)
        .firstOrNull { !layoutInput.text[it].isWhitespace() }
        ?: return Rect.Zero
    val firstLine = getLineForOffset(firstOffset)
    val lastLine = getLineForOffset(lastOffset)
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY

    for (line in firstLine..lastLine) {
        left = minOf(left, minOf(getLineLeft(line), getLineRight(line)))
        right = maxOf(right, maxOf(getLineLeft(line), getLineRight(line)))
        top = minOf(top, getLineTop(line))
        bottom = maxOf(bottom, getLineBottom(line))
    }

    return if (left.isFinite() && top.isFinite() && right >= left && bottom >= top) {
        Rect(left, top, right, bottom).inViewport(viewport)
    } else {
        Rect.Zero
    }
}

// 句子与单词各自独立配额，合计不超过系统对 CustomAccessibilityAction 数量的软上限（约 32），
// 且互不挤占——句子再多也不会吃掉单词查词动作的槽位（长按查词是核心功能）。
private const val MaxAccessibilitySentenceActions = 16
private const val MaxAccessibilityWordActions = 16

internal fun extractAccessibilityWords(
    text: String,
    maxWords: Int = MaxAccessibilityWordActions,
    startOffset: Int = 0,
    endOffset: Int = text.length,
): List<String> {
    val words = mutableListOf<String>()
    val seenWords = mutableSetOf<String>()
    var offset = startOffset

    while (offset < endOffset && words.size < maxWords) {
        val word = WordBoundaryDetector.getWordAtOffset(text, offset)
        if (word == null) {
            offset++
            continue
        }

        val normalizedWord = word.lowercase(Locale.US)
        if (seenWords.add(normalizedWord)) {
            words += word
        }

        offset = advancePastWordRun(text, offset)
    }

    return words
}

private fun advancePastWordRun(text: String, startOffset: Int): Int {
    var offset = startOffset
    while (offset < text.length && isAccessibilityWordChar(text[offset])) {
        offset++
    }
    return offset.coerceAtLeast(startOffset + 1)
}

private fun isAccessibilityWordChar(char: Char): Boolean {
    return char.isLetterOrDigit() || char == '\'' || char == '-'
}

/**
 * 只有指针确实落在已布局的字形/字符盒内时，才返回字符偏移。
 *
 * `getOffsetForPosition` 返回的是光标偏移，所以指针落在行右侧空白区时会得到 `text.length`。
 * 这里把返回偏移及其前一个字符同时拿去校验可见行范围和 bounding box，
 * 避免这种光标位置误选到行尾最后一个词。
 */
private fun findGlyphOffsetAtPosition(
    position: Offset,
    layoutResult: TextLayoutResult?
): Int? {
    layoutResult ?: return null

    val bounds = layoutResult.size
    if (position.x < 0f || position.y < 0f ||
        position.x > bounds.width || position.y > bounds.height
    ) {
        return null
    }

    val line = layoutResult.getLineForVerticalPosition(position.y)
    val lineTop = layoutResult.getLineTop(line)
    val lineBottom = layoutResult.getLineBottom(line)
    if (position.y < lineTop || position.y >= lineBottom) return null

    val lineStart = layoutResult.getLineStart(line)
    val lineEnd = layoutResult.getLineEnd(line, visibleEnd = true)
    if (lineStart >= lineEnd) return null

    val lineLeft = layoutResult.getLineLeft(line)
    val lineRight = layoutResult.getLineRight(line)
    if (position.x < minOf(lineLeft, lineRight) || position.x > maxOf(lineLeft, lineRight)) {
        return null
    }

    val offset = layoutResult.getOffsetForPosition(position)
    return sequenceOf(offset, offset - 1)
        .distinct()
        .firstOrNull { candidate ->
            candidate in lineStart until lineEnd &&
                !layoutResult.layoutInput.text[candidate].isWhitespace() &&
                layoutResult.getBoundingBox(candidate).contains(position)
        }
}

/**
 * 计算某段字符区间在每一行上要填充的背景矩形，效果等同于 `SpanStyle(background = color)`。
 *
 * 存在的理由是**动画**：随帧变化的颜色若写进 AnnotatedString，每帧都会重建文本并
 * 触发重新布局；画在绘制阶段则只是重绘。
 *
 * 行高取 `getLineTop/getLineBottom`（与 SpanStyle 的背景一致覆盖整行行高），左右取该行
 * 首末非空白字形的边界，因此仍是贴着字形的底纹，不会在行尾拖出一条到容器右边缘的色块。
 * 拆成纯函数是为了能在 JVM 侧用真实 TextLayoutResult 验证几何——绘制本身测不到，
 * 而「画出来是空的」正是这个改动最容易犯的错。
 */
internal fun textRangeBackgroundRects(
    layout: TextLayoutResult,
    startOffset: Int,
    endOffset: Int
): List<Rect> {
    val textLength = layout.layoutInput.text.length
    if (endOffset <= startOffset || textLength == 0) return emptyList()

    val safeStart = startOffset.coerceIn(0, textLength)
    val safeEnd = endOffset.coerceIn(safeStart, textLength)
    if (safeEnd <= safeStart) return emptyList()

    val rects = mutableListOf<Rect>()
    val firstLine = layout.getLineForOffset(safeStart)
    val lastLine = layout.getLineForOffset(safeEnd - 1)
    for (line in firstLine..lastLine) {
        val lineStart = maxOf(safeStart, layout.getLineStart(line))
        val lineEnd = minOf(safeEnd, layout.getLineEnd(line, visibleEnd = true))
        val firstOffset = (lineStart until lineEnd)
            .firstOrNull { !layout.layoutInput.text[it].isWhitespace() }
        val lastOffset = (lineEnd - 1 downTo lineStart)
            .firstOrNull { !layout.layoutInput.text[it].isWhitespace() }
        if (firstOffset == null || lastOffset == null) continue

        val left = layout.getBoundingBox(firstOffset).left
        val right = layout.getBoundingBox(lastOffset).right
        if (right <= left) continue
        val top = layout.getLineTop(line)
        rects += Rect(left, top, right, layout.getLineBottom(line))
    }
    return rects
}

/**
 * 逐行填充某段字符区间的背景。几何由 [textRangeBackgroundRects] 给出。
 */
private fun DrawScope.drawTextRangeBackground(
    layout: TextLayoutResult?,
    startOffset: Int,
    endOffset: Int,
    color: Color
) {
    layout ?: return
    textRangeBackgroundRects(layout, startOffset, endOffset).forEach { rect ->
        drawRect(color = color, topLeft = rect.topLeft, size = rect.size)
    }
}

private fun DrawScope.drawDashedTextRange(
    layout: TextLayoutResult?,
    startOffset: Int,
    endOffset: Int,
    color: Color
) {
    layout ?: return
    if (endOffset <= startOffset || layout.layoutInput.text.isEmpty()) return

    val safeStart = startOffset.coerceIn(0, layout.layoutInput.text.length)
    val safeEnd = endOffset.coerceIn(safeStart, layout.layoutInput.text.length)
    if (safeEnd <= safeStart) return

    val dashEffect = PathEffect.dashPathEffect(
        floatArrayOf(4.dp.toPx(), 3.dp.toPx())
    )
    val firstLine = layout.getLineForOffset(safeStart)
    val lastLine = layout.getLineForOffset(safeEnd - 1)
    for (line in firstLine..lastLine) {
        val lineStart = maxOf(safeStart, layout.getLineStart(line))
        val lineEnd = minOf(safeEnd, layout.getLineEnd(line, visibleEnd = true))
        val firstOffset = (lineStart until lineEnd)
            .firstOrNull { !layout.layoutInput.text[it].isWhitespace() }
        val lastOffset = (lineEnd - 1 downTo lineStart)
            .firstOrNull { !layout.layoutInput.text[it].isWhitespace() }
        if (firstOffset == null || lastOffset == null) continue

        val firstBox = layout.getBoundingBox(firstOffset)
        val lastBox = layout.getBoundingBox(lastOffset)
        val underlineY = layout.getLineBottom(line) - 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(firstBox.left, underlineY),
            end = Offset(lastBox.right, underlineY),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = dashEffect
        )
    }
}
