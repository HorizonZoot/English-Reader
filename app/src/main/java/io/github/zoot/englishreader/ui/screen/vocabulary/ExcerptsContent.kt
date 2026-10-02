package io.github.zoot.englishreader.ui.screen.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.zoot.englishreader.viewmodel.ExcerptPlaybackState
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExcerptsContent(
    excerpts: List<ExcerptEntity>,
    onOpenSource: (ExcerptEntity) -> Unit,
    onDelete: (ExcerptEntity) -> Unit,
    playback: ExcerptPlaybackState? = null,
    onTogglePlayback: (ExcerptEntity) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    if (excerpts.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.excerpts_empty))
        }
    } else {
        LazyColumn(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("excerpts-list"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(excerpts, key = { it.id }) { excerpt ->
                var expanded by rememberSaveable(excerpt.id) { mutableStateOf(false) }
                val expandLabel = stringResource(
                    if (expanded) R.string.excerpt_collapse else R.string.excerpt_expand
                )
                Column(Modifier.fillMaxWidth().testTag("excerpt-${excerpt.id}")) {
                    Text(
                        text = excerpt.text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(onClickLabel = expandLabel) { expanded = !expanded }
                    )
                    val active = playback?.takeIf { it.excerptId == excerpt.id }
                    val playbackDescription = stringResource(
                        if (active?.loading == true) R.string.excerpt_audio_loading
                        else if (active != null) R.string.excerpt_audio_playing
                        else R.string.excerpt_play
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(expandLabel)
                        }
                        TextButton(
                            onClick = { onTogglePlayback(excerpt) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("excerpt-play-${excerpt.id}")
                                .semantics { stateDescription = playbackDescription }
                        ) {
                            if (active?.loading == true) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(if (active != null) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (active != null) R.string.excerpt_stop else R.string.excerpt_play))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = excerpt.sourceTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dateFormat.format(Date(excerpt.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (excerpt.articleId == null) {
                        Text(
                            text = stringResource(R.string.excerpt_source_deleted),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(excerpt.text)) }) {
                            Text(stringResource(R.string.excerpt_copy))
                        }
                        TextButton(onClick = { onDelete(excerpt) }) {
                            Text(stringResource(R.string.excerpt_delete))
                        }
                        TextButton(
                            onClick = { onOpenSource(excerpt) },
                            enabled = excerpt.articleId != null
                        ) {
                            Text(stringResource(R.string.vocabulary_open_article))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
