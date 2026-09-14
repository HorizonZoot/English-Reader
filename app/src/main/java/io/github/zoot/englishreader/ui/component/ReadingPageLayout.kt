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
import io.github.zoot.englishreader.util.ParagraphAligner
import kotlinx.coroutines.yield
import kotlin.math.ceil
import kotlin.math.floor

internal data class ReadingTextBlock(
    val paragraphIndex: Int,
    val kind: ReadingTextKind,
    val text: String,
    val style: TextStyle,
    val gapBeforeDp: Int
)

@Composable
internal fun rememberReadingTextBlocks(
    article: ArticleEntity,
    paragraphs: List<ParagraphAligner.AlignedParagraph>,
    fontSize: FontSizeOption,
    showTranslation: Boolean
): List<ReadingTextBlock> {
    val typography = MaterialTheme.typography
    return remember(article.title, article.source, paragraphs, fontSize, showTranslation, typography) {
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
            paragraphs.forEachIndexed { index, paragraph ->
                add(
                    ReadingTextBlock(
                        index, ReadingTextKind.ORIGINAL, paragraph.english,
                        readingOriginalTextStyle(fontSize.sizeSp.sp, FontFamily.Serif, Color.Unspecified),
                        if (index == 0) 32 else 24
                    )
                )
                if (showTranslation && !paragraph.chinese.isNullOrBlank()) {
                    val size = (fontSize.sizeSp - 3).sp
                    add(
                        ReadingTextBlock(
                            index, ReadingTextKind.TRANSLATION, paragraph.chinese,
                            typography.bodyLarge.copy(fontSize = size, lineHeight = size * 1.6f), 8
                        )
                    )
                }
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
