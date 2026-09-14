package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.viewmodel.AlternateDefinition

/**
 * 单词详情 BottomSheet（双语版）
 *
 * 显示单词的详细信息：音标、中文释义、英文释义
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailsBottomSheet(
    word: String,
    phonetic: String?,
    chineseDefinitions: List<String>,
    englishDefinitions: List<String>,
    onDismiss: () -> Unit,
    // 传入要收藏的原形（主原形 word 或备选 alternate.word），支持歧义词各自独立收藏
    onAddToVocabulary: (String) -> Unit,
    onPlayAudio: () -> Unit,
    isLoadingAudio: Boolean = false,
    // 按原形判定是否已收藏：主原形与备选原形（live / life）各自独立判断
    isInVocabulary: (String) -> Boolean = { false },
    // 非空表示 word（原形）是从该变形词还原来的，标题下方标注"xxx 的原形"
    inflectedForm: String? = null,
    // 歧义变形词（lives/leaves）的其余合法原形，在主释义下方并列展示
    alternates: List<AlternateDefinition> = emptyList(),
    /** 上报展开动画结束后的面板顶边屏幕坐标，供阅读列表计算实际遮挡。 */
    onVisibleTopChanged: (Float) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            val dialogView = LocalView.current
            val location = remember(dialogView) { IntArray(2) }
            var visibleTop by remember { mutableStateOf<Float?>(null) }
            val reportVisibleTop by rememberUpdatedState(onVisibleTopChanged)
            LaunchedEffect(visibleTop, sheetState.currentValue, sheetState.targetValue) {
                if (sheetState.isVisible && sheetState.currentValue == sheetState.targetValue) {
                    withFrameNanos { }
                    visibleTop?.let(reportVisibleTop)
                }
            }
            // The handle starts at the surface top, after the sheet's animated offset.
            Box(Modifier.onGloballyPositioned { coordinates ->
                dialogView.getLocationOnScreen(location)
                visibleTop = coordinates.positionInRoot().y + location[1]
            }) {
                BottomSheetDefaults.DragHandle()
            }
        }
    ) {
        WordDetailsContent(
            word = word,
            phonetic = phonetic,
            chineseDefinitions = chineseDefinitions,
            englishDefinitions = englishDefinitions,
            onDismiss = onDismiss,
            onAddToVocabulary = onAddToVocabulary,
            onPlayAudio = onPlayAudio,
            isLoadingAudio = isLoadingAudio,
            isInVocabulary = isInVocabulary,
            inflectedForm = inflectedForm,
            alternates = alternates
        )
    }
}

@Composable
internal fun WordDetailsContent(
    word: String,
    phonetic: String?,
    chineseDefinitions: List<String>,
    englishDefinitions: List<String>,
    onDismiss: () -> Unit,
    onAddToVocabulary: (String) -> Unit,
    onPlayAudio: () -> Unit,
    modifier: Modifier = Modifier,
    isLoadingAudio: Boolean = false,
    isInVocabulary: (String) -> Boolean = { false },
    inflectedForm: String? = null,
    alternates: List<AlternateDefinition> = emptyList()
) {
    val loadingDescription = stringResource(R.string.loading_pronunciation)

    Column(
        modifier = modifier
            .testTag("word-details-sheet")
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.headlineLarge,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 36.sp,
                        lineHeight = 42.sp,
                        modifier = Modifier.semantics { heading() }
                    )
                    inflectedForm?.let {
                        Text(
                            text = stringResource(R.string.word_inflected_note, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    phonetic?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.reading_sentence_popup_close)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onPlayAudio,
                        enabled = !isLoadingAudio,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        if (isLoadingAudio) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .semantics {
                                        contentDescription = loadingDescription
                                    },
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.play_pronunciation)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BilingualDefinitions(chineseDefinitions, englishDefinitions)

            alternates.forEachIndexed { index, alternate ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = alternate.word,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.semantics { heading() }
                    )
                    alternate.phonetic?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BilingualDefinitions(
                        alternate.chineseDefinitions,
                        alternate.englishDefinitions
                    )
                    AddToVocabularyButton(
                        word = alternate.word,
                        isSaved = isInVocabulary(alternate.word),
                        onAddToVocabulary = onAddToVocabulary,
                        modifier = Modifier.testTag("word-details-add-alternate-$index"),
                        primary = false
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (alternates.isNotEmpty()) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AddToVocabularyButton(
                word = word,
                isSaved = isInVocabulary(word),
                onAddToVocabulary = onAddToVocabulary,
                modifier = Modifier.testTag("word-details-add-main"),
                primary = true
            )
        }
    }
}

@Composable
private fun AddToVocabularyButton(
    word: String,
    isSaved: Boolean,
    onAddToVocabulary: (String) -> Unit,
    modifier: Modifier,
    primary: Boolean
) {
    val description = stringResource(
        if (isSaved) R.string.word_in_vocabulary_content_description
        else R.string.word_add_to_vocabulary_content_description,
        word
    )
    Button(
        onClick = { onAddToVocabulary(word) },
        enabled = !isSaved,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics { contentDescription = description },
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (primary) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            imageVector = if (isSaved) Icons.Default.Check else Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (isSaved) R.string.already_in_vocabulary else R.string.add_to_vocabulary
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun BilingualDefinitions(
    chineseDefinitions: List<String>,
    englishDefinitions: List<String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (chineseDefinitions.isNotEmpty()) {
            DefinitionSection(
                title = stringResource(R.string.definition_chinese),
                definitions = chineseDefinitions,
                fontFamily = FontFamily.Default
            )
        }
        if (englishDefinitions.isNotEmpty()) {
            DefinitionSection(
                title = stringResource(R.string.definition_english),
                definitions = englishDefinitions,
                fontFamily = FontFamily.Serif
            )
        }
        if (chineseDefinitions.isEmpty() && englishDefinitions.isEmpty()) {
            Text(
                text = stringResource(R.string.no_definitions_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DefinitionSection(
    title: String,
    definitions: List<String>,
    fontFamily: FontFamily
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        definitions.forEachIndexed { index, definition ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = stringResource(R.string.definition_list_number, index + 1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 24.dp)
                )
                Text(
                    text = definition,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = fontFamily,
                    fontSize = 18.sp,
                    lineHeight = 28.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
