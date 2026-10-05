package io.github.zoot.englishreader.ui.screen.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.viewmodel.ExcerptPlaybackState
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExcerptsContent(
    excerpts: List<ExcerptEntity>,
    onOpenSource: (ExcerptEntity) -> Unit,
    onDelete: (ExcerptEntity) -> Unit,
    playback: ExcerptPlaybackState? = null,
    onTogglePlayback: (ExcerptEntity) -> Unit = {},
    expandedIds: Set<Long> = emptySet(),
    onToggleExpanded: (Long) -> Unit = {},
    sortBar: @Composable () -> Unit = {},
    menuId: Long? = null,
    onMenuChange: (Long?) -> Unit = {},
    time: VocabularyGroupingTime = VocabularyGroupingTime.current()
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("excerpts-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "sort") { sortBar() }
        if (excerpts.isEmpty()) item { Text(stringResource(R.string.excerpts_empty)) }
        items(excerpts, key = { it.id }, contentType = { "excerpt" }) { excerpt ->
            val expanded = excerpt.id in expandedIds
            val menuExpanded = menuId == excerpt.id
            val currentDelete by rememberUpdatedState(onDelete)
            val currentExcerpt by rememberUpdatedState(excerpt)
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) currentDelete(currentExcerpt)
                // The Room emission removes the row; failed writes leave it usable.
                false
            })
            val shape = RoundedCornerShape(12.dp)
            SwipeToDismissBox(
                state = dismiss, enableDismissFromStartToEnd = false,
                modifier = Modifier.fillMaxWidth().clip(shape),
                backgroundContent = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(end = 20.dp),
                        contentAlignment = Alignment.CenterEnd) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.excerpt_delete), tint = MaterialTheme.colorScheme.onError)
                    }
                }
            ) {
                Surface(shape = shape, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().testTag("excerpt-${excerpt.id}")) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
                        Text(
                            excerpt.text, style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).heightIn(min = 48.dp)
                                .clickable(onClickLabel = stringResource(if (expanded) R.string.excerpt_collapse else R.string.excerpt_expand)) {
                                    onToggleExpanded(excerpt.id)
                                }
                        )
                        val active = playback?.takeIf { it.excerptId == excerpt.id }
                        val playbackDescription = stringResource(if (active?.loading == true) R.string.excerpt_audio_loading
                            else if (active != null) R.string.excerpt_audio_playing else R.string.excerpt_play)
                        val saved = Instant.ofEpochMilli(excerpt.createdAt).atZone(time.zoneId)
                        val today = Instant.ofEpochMilli(time.nowMillis).atZone(time.zoneId).toLocalDate()
                        val clock = saved.format(DateTimeFormatter.ofPattern("HH:mm"))
                        val relative = when (saved.toLocalDate()) {
                            today -> stringResource(R.string.favorites_time_today, clock)
                            today.minusDays(1) -> stringResource(R.string.favorites_time_yesterday, clock)
                            else -> saved.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    excerpt.sourceTitle, style = MaterialTheme.typography.bodySmall,
                                    color = if (excerpt.articleId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                        .clickable(enabled = excerpt.articleId != null,
                                            onClickLabel = stringResource(R.string.vocabulary_open_article)) { onOpenSource(excerpt) }
                                        .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                                )
                                Text(relative, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = { onTogglePlayback(excerpt) },
                                modifier = Modifier.size(48.dp).testTag("excerpt-play-${excerpt.id}")
                                    .semantics { stateDescription = playbackDescription }) {
                                if (active?.loading == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(if (active != null) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                                    stringResource(if (active != null) R.string.excerpt_stop else R.string.excerpt_play),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { onMenuChange(excerpt.id) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.favorites_excerpt_actions),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuChange(null) }) {
                                    if (excerpt.articleId == null) Text(stringResource(R.string.excerpt_source_deleted),
                                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                                    DropdownMenuItem(text = { Text(stringResource(R.string.excerpt_copy)) }, onClick = {
                                        onMenuChange(null)
                                        clipboard.setText(AnnotatedString(excerpt.text))
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.excerpt_delete), color = MaterialTheme.colorScheme.error) },
                                        onClick = { onMenuChange(null); onDelete(excerpt) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
