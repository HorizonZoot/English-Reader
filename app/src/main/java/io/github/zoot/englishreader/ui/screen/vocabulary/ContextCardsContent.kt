package io.github.zoot.englishreader.ui.screen.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.ui.component.ContextSentence
import io.github.zoot.englishreader.viewmodel.ContextLibraryState
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextCardsContent(
    library: ContextLibraryState,
    onReview: () -> Unit,
    onEdit: (ContextCardEntity) -> Unit,
    onDelete: (ContextCardEntity) -> Unit,
    onRetry: () -> Unit,
    onOpenSource: ((ContextCardEntity) -> Unit)? = null
) {
    var deleting by remember { mutableStateOf<ContextCardEntity?>(null) }
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
                        Text(stringResource(R.string.context_round_hint), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = onReview,
                            enabled = library.canResume || library.dueCount > 0,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("context-start-review")
                        ) { Text(stringResource(if (library.canResume) R.string.context_review_resume else R.string.context_review_start)) }
                        if (library.dueCount == 0 && !library.canResume) Text(stringResource(R.string.context_review_empty))
                    }
                }
                if (library.cards.isEmpty()) item { Text(stringResource(R.string.context_cards_empty)) }
                items(library.cards, key = { it.id }, contentType = { "context-card" }) { card ->
                    Card(Modifier.fillMaxWidth().testTag("context-card-${card.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(card.word, style = MaterialTheme.typography.titleMedium)
                            ContextSentence(card.sentence, card.targetStartOffset, card.targetEndOffset)
                            Text(card.meaning, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.context_due_date, LocalDate.ofEpochDay(card.dueDay).toString()), style = MaterialTheme.typography.bodySmall)
                            Text(card.sourceTitle, style = MaterialTheme.typography.bodySmall)
                            if (card.articleId == null) Text(stringResource(R.string.context_source_deleted), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onEdit(card) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.context_edit_meaning))
                                }
                                if (onOpenSource != null) TextButton(onClick = { onOpenSource(card) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.vocabulary_open_article))
                                }
                                TextButton(onClick = { deleting = card }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.context_delete_title)) },
            text = { Text(stringResource(R.string.context_delete_confirmation, card.word)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(card) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
