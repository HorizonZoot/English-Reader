package io.github.zoot.englishreader.ui.screen.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalLayoutApi::class)
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
    onToggleGroup: (VocabularyGroupId) -> Unit = {}
) {
    var deleting by remember { mutableStateOf<StudyWord?>(null) }
    LazyColumn(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("context-card-list"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when {
            library.loading -> item { CircularProgressIndicator() }
            library.failed -> item {
                Text(stringResource(R.string.context_load_failed))
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            else -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.context_due_count, library.dueCount), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.study_round_hint), style = MaterialTheme.typography.bodyMedium)
                        if (library.canResume) Button(
                            onClick = onReview,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-start-review")
                        ) { Text(stringResource(R.string.context_review_resume)) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onLearn,
                                enabled = library.words.isNotEmpty(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("study-learn")
                            ) { Text(stringResource(R.string.study_learn)) }
                            OutlinedButton(
                                onClick = onReview,
                                enabled = library.dueCount > 0,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("study-review-due")
                            ) { Text(stringResource(R.string.context_review_start)) }
                        }
                        if (library.canResume) Text(stringResource(R.string.study_resume_first), style = MaterialTheme.typography.bodySmall)
                        if (library.dueCount == 0 && !library.canResume) Text(stringResource(R.string.study_no_due))
                    }
                }
                if (library.words.isEmpty()) item { Text(stringResource(R.string.study_words_empty)) }
                if (groups == null) {
                    items(library.words, key = { it.key }, contentType = { "study-word" }) { word ->
                        StudyWordCard(word, details[word.key], loadingAudioKey, onPlayAudio, { deleting = it }, onOpenSource)
                    }
                } else {
                    groups.forEach { group ->
                        item(key = group.id.listKey, contentType = "group-header") {
                            GroupHeader(vocabularyGroupTitle(group.id), group.words.size, group.id == VocabularyGroupId.Today, group.isExpanded) {
                                onToggleGroup(group.id)
                            }
                        }
                        if (group.isExpanded) items(group.words, key = { it.key }, contentType = { "study-word" }) { word ->
                            StudyWordCard(word, details[word.key], loadingAudioKey, onPlayAudio, { deleting = it }, onOpenSource)
                        }
                    }
                }
            }
        }
    }
    deleting?.let { word ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.study_delete_title)) },
            text = { Text(stringResource(R.string.study_delete_confirmation, word.word)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(word) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyWordCard(
    word: StudyWord,
    detail: VocabularyWordDetail?,
    loadingAudioKey: String?,
    onPlayAudio: (StudyWord) -> Unit,
    onDelete: (StudyWord) -> Unit,
    onOpenSource: ((StudyWord) -> Unit)?
) {
    Card(Modifier.fillMaxWidth().testTag("study-word-${word.key}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onPlayAudio(word) },
                    modifier = Modifier.size(48.dp).testTag("study-audio-${word.key}")
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.word_play_pronunciation, word.word))
                }
                if (loadingAudioKey == word.key) CircularProgressIndicator(Modifier.size(16.dp))
            }
            val phonetic = detail?.phonetic ?: (word as? StudyWord.Legacy)?.vocabulary?.phonetic
            if (!phonetic.isNullOrBlank()) Text(phonetic, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
            detail?.headword?.let {
                Text(stringResource(R.string.vocabulary_word_headword, it), style = MaterialTheme.typography.bodySmall)
            }
            val meaning = word.meaning.ifBlank { detail?.chinese.orEmpty() }
            Text(meaning.ifBlank { stringResource(R.string.study_answer_missing) }, style = MaterialTheme.typography.bodyMedium)
            if (word is StudyWord.Context) ContextSentence(word.card.sentence, word.card.targetStartOffset, word.card.targetEndOffset)
            Text(
                if (word.lastReviewedAt == null) stringResource(R.string.study_unlearned)
                else stringResource(R.string.context_due_date, LocalDate.ofEpochDay(word.dueDay).toString()),
                style = MaterialTheme.typography.bodySmall
            )
            word.sourceTitle?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (word.articleId == null) Text(stringResource(R.string.study_source_missing), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenSource != null && word.articleId != null) TextButton(onClick = { onOpenSource(word) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.vocabulary_open_article))
                }
                TextButton(onClick = { onDelete(word) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}
