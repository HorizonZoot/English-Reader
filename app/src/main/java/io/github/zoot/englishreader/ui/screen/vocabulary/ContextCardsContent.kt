package io.github.zoot.englishreader.ui.screen.vocabulary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.ui.component.ContextSentence
import io.github.zoot.englishreader.ui.screen.GroupHeader
import io.github.zoot.englishreader.ui.screen.vocabularyGroupTitle
import io.github.zoot.englishreader.viewmodel.ContextLibraryState
import java.time.LocalDate

@Composable
fun ContextCardsContent(
    library: ContextLibraryState,
    onReview: () -> Unit,
    onDelete: (StudyWord) -> Unit,
    onRetry: () -> Unit,
    onLearn: () -> Unit,
    onPlayAudio: (StudyWord) -> Unit,
    onOpenSource: ((StudyWord) -> Unit)? = null,
    details: Map<String, VocabularyWordDetail> = emptyMap(),
    loadingAudioKey: String? = null,
    groups: List<StudyWordGroup>? = null,
    onToggleGroup: (VocabularyGroupId) -> Unit = {},
    onShowInfo: () -> Unit = {},
    sortBar: @Composable () -> Unit = {},
    deleting: StudyWord? = null,
    onDeleteConfirmation: (StudyWord?) -> Unit = {},
    menuKey: String? = null,
    onMenuChange: (String?) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("context-card-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
    ) {
        when {
            library.loading -> item { CircularProgressIndicator() }
            library.failed -> item {
                Text(stringResource(R.string.context_load_failed))
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            else -> {
                item(key = "summary") {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.favorites_due_today), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(library.dueCount.toString(), style = MaterialTheme.typography.displaySmall)
                                        Text(stringResource(R.string.favorites_due_unit), Modifier.padding(bottom = 6.dp),
                                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = onShowInfo, modifier = Modifier.align(Alignment.Top).size(48.dp)) {
                                    Icon(Icons.Outlined.Info, stringResource(R.string.favorites_study_info), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onLearn, enabled = library.words.isNotEmpty(), shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("study-learn")) {
                                    Text(stringResource(R.string.study_learn))
                                }
                                Button(onClick = onReview, enabled = library.dueCount > 0, shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("study-review-due")) {
                                    Text(stringResource(R.string.context_review_start))
                                }
                            }
                            if (library.canResume) TextButton(onClick = onReview,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-start-review")) {
                                Text(stringResource(R.string.context_review_resume))
                            }
                        }
                    }
                }
                item(key = "sort") { sortBar() }
                if (library.words.isEmpty()) item { Text(stringResource(R.string.study_words_empty)) }
                if (groups == null) {
                    itemsIndexed(library.words, key = { _, word -> word.key }, contentType = { _, _ -> "study-word" }) { index, word ->
                        StudyWordCard(word, details[word.key], loadingAudioKey, onPlayAudio, onDeleteConfirmation, onOpenSource,
                            index == 0, index == library.words.lastIndex, menuKey == word.key, onMenuChange)
                    }
                } else groups.forEach { group ->
                    item(key = group.id.listKey, contentType = "group-header") {
                        GroupHeader(vocabularyGroupTitle(group.id), group.words.size, group.id == VocabularyGroupId.Today, group.isExpanded) {
                            onToggleGroup(group.id)
                        }
                    }
                    if (group.isExpanded) itemsIndexed(group.words, key = { _, word -> word.key }, contentType = { _, _ -> "study-word" }) { index, word ->
                        StudyWordCard(word, details[word.key], loadingAudioKey, onPlayAudio, onDeleteConfirmation, onOpenSource,
                            index == 0, index == group.words.lastIndex, menuKey == word.key, onMenuChange)
                    }
                }
            }
        }
    }
    deleting?.let { word ->
        AlertDialog(
            onDismissRequest = { onDeleteConfirmation(null) },
            title = { Text(stringResource(R.string.study_delete_title)) },
            text = { Text(stringResource(R.string.study_delete_confirmation, word.word)) },
            confirmButton = { TextButton(onClick = { onDeleteConfirmation(null); onDelete(word) }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { onDeleteConfirmation(null) }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun StudyWordCard(
    word: StudyWord,
    detail: VocabularyWordDetail?,
    loadingAudioKey: String?,
    onPlayAudio: (StudyWord) -> Unit,
    onDelete: (StudyWord) -> Unit,
    onOpenSource: ((StudyWord) -> Unit)?,
    first: Boolean,
    last: Boolean,
    menuExpanded: Boolean,
    onMenuChange: (String?) -> Unit
) {
    val shape = RoundedCornerShape(topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
        bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp)
    Surface(Modifier.fillMaxWidth().clip(shape).testTag("study-word-${word.key}"), shape = shape) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(word.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { onPlayAudio(word) }, modifier = Modifier.size(48.dp).testTag("study-audio-${word.key}")) {
                            if (loadingAudioKey == word.key) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.word_play_pronunciation, word.word),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { onMenuChange(word.key) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.favorites_word_actions, word.word),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuChange(null) }) {
                                Text(if (word.lastReviewedAt == null) stringResource(R.string.study_unlearned)
                                    else stringResource(R.string.context_due_date, LocalDate.ofEpochDay(word.dueDay).toString()),
                                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                                if (onOpenSource != null) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vocabulary_open_article)) }, enabled = word.articleId != null,
                                    onClick = { onMenuChange(null); onOpenSource(word) }
                                )
                                if (word.articleId == null) Text(stringResource(R.string.study_source_missing),
                                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = { onMenuChange(null); onDelete(word) })
                            }
                        }
                    }
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val phonetic = detail?.phonetic ?: (word as? StudyWord.Legacy)?.vocabulary?.phonetic
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!phonetic.isNullOrBlank()) Text(phonetic, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            detail?.headword?.let {
                                Text(stringResource(R.string.vocabulary_word_headword, it), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(word.meaning.ifBlank { detail?.chinese.orEmpty() }.ifBlank { stringResource(R.string.study_answer_missing) },
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        if (word is StudyWord.Context) ContextSentence(word.card.sentence, word.card.targetStartOffset, word.card.targetEndOffset)
                    }
                }
            )
            if (!last) Divider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
}
