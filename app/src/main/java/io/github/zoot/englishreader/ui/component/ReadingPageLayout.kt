package io.github.zoot.englishreader.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.ReadingBlockMetrics
import io.github.zoot.englishreader.core.ReadingLine
import io.github.zoot.englishreader.core.ReadingPagination
import io.github.zoot.englishreader.core.paginateReadingBlocks
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.AppliedTranslationLayout
import io.github.zoot.englishreader.model.ReadingProjectedKind
import io.github.zoot.englishreader.model.ReadingTranslationProjection
import io.github.zoot.englishreader.util.ParagraphAligner
import kotlinx.coroutines.yield
import kotlin.math.ceil
import kotlin.math.floor

internal data class ReadingTextBlock(
    val paragraphIndex: Int,
    val kind: ReadingTextKind,
    val text: String,
    val style: TextStyle,
    val gapBeforeDp: Int,
    val textStartOffset: Int = 0,
    val sourceStartOffset: Int = textStartOffset,
    val sourceEndOffset: Int = sourceStartOffset + text.length
) {
    val key: ReadingBlockKey get() = ReadingBlockKey(paragraphIndex, kind, textStartOffset)
    val textEndOffset: Int get() = textStartOffset + text.length
    fun contains(offset: Int): Boolean = offset in textStartOffset until textEndOffset
}

internal fun List<ReadingTextBlock>.blockFor(anchor: ReadingAnchor): ReadingTextBlock? {
    val candidates = filter { it.paragraphIndex == anchor.paragraphIndex && it.kind == anchor.textKind }
    return candidates.firstOrNull { anchor.characterOffset < it.textEndOffset } ?: candidates.lastOrNull()
}

@Composable
internal fun rememberReadingTextBlocks(
    article: ArticleEntity,
    paragraphs: List<ParagraphAligner.AlignedParagraph>,
    fontSize: FontSizeOption,
    showTranslation: Boolean,
    appliedLayout: AppliedTranslationLayout? = null
): List<ReadingTextBlock> {
    val typography = MaterialTheme.typography
    return remember(article.title, article.source, article.translation, paragraphs, fontSize, showTranslation, appliedLayout, typography) {
        buildList {
            article.source?.takeIf { it.isNotBlank() }?.let {
                add(ReadingTextBlock(0, ReadingTextKind.SOURCE, it, typography.labelMedium, 0))
            }
            if (article.title.isNotEmpty()) {
                add(
                    ReadingTextBlock(
                        0, ReadingTextKind.TITLE, article.title,
                        typography.headlineLarge.copy(
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal,
                            fontSize = 36.sp, lineHeight = 40.sp
                        ), 14
                    )
                )
            }
            ReadingTranslationProjection.project(
                paragraphs.map { it.english }, article.translation, appliedLayout, showTranslation
            ).forEachIndexed { index, block ->
                val original = block.kind == ReadingProjectedKind.ORIGINAL
                val size = (fontSize.sizeSp - 3).sp
                add(
                    ReadingTextBlock(
                        paragraphIndex = block.sourceParagraphIndex,
                        kind = if (original) ReadingTextKind.ORIGINAL else ReadingTextKind.TRANSLATION,
                        text = block.text,
                        style = if (original) {
                            readingOriginalTextStyle(fontSize.sizeSp.sp, FontFamily.Serif, Color.Unspecified)
                        } else typography.bodyLarge.copy(fontSize = size, lineHeight = size * 1.6f),
                        gapBeforeDp = if (!original) 8 else if (index == 0) 32 else 24,
                        textStartOffset = block.textStartOffset,
                        sourceStartOffset = block.sourceStartOffset,
                        sourceEndOffset = block.sourceEndOffset
                    )
                )
            }
        }
    }
}

internal data class ReadingPageLayout(
    val blocks: List<ReadingTextBlock>,
    val size: IntSize,
    val density: Float,
    val fontScale: Float,
    val pagination: ReadingPagination
)

@Composable
internal fun rememberReadingPageLayout(
    blocks: List<ReadingTextBlock>,
    size: IntSize
): State<ReadingPageLayout?> {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    return produceState<ReadingPageLayout?>(null, blocks, size, density, measurer) {
        value = null
        if (size.width <= 0 || size.height <= 0) return@produceState
        val metrics = blocks.map { block ->
            yield()
            val layout = measurer.measure(
                text = block.text,
                style = block.style,
                constraints = Constraints.fixedWidth(size.width)
            )
            ReadingBlockMetrics(
                paragraphIndex = block.paragraphIndex,
                textKind = block.kind,
                textStartOffset = block.textStartOffset,
                gapBefore = with(density) { block.gapBeforeDp.dp.roundToPx() },
                lines = List(layout.lineCount) { line ->
                    ReadingLine(
                        startOffset = layout.getLineStart(line),
                        endOffset = if (line == layout.lineCount - 1) block.text.length else layout.getLineStart(line + 1),
                        top = if (line == 0) 0 else floor(layout.getLineTop(line)).toInt(),
                        bottom = if (line == layout.lineCount - 1) layout.size.height else ceil(layout.getLineBottom(line)).toInt()
                    )
                }
            )
        }
        value = ReadingPageLayout(blocks, size, density.density, density.fontScale, paginateReadingBlocks(metrics, size.height))
    }
}
