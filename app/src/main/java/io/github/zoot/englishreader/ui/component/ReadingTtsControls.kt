package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.model.ReadingTtsPhase
import io.github.zoot.englishreader.model.ReadingTtsState

@Composable
fun ReadingTtsControls(
    state: ReadingTtsState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    val active = state.phase == ReadingTtsPhase.PLAYING || state.phase == ReadingTtsPhase.PREPARING
    val status = stringResource(when (state.phase) {
        ReadingTtsPhase.PREPARING -> R.string.reading_tts_preparing
        ReadingTtsPhase.PLAYING -> R.string.reading_tts_playing
        ReadingTtsPhase.PAUSED -> R.string.reading_tts_paused
        ReadingTtsPhase.COMPLETED -> R.string.reading_tts_completed
        ReadingTtsPhase.FAILED -> R.string.reading_tts_failed
        ReadingTtsPhase.IDLE -> R.string.reading_tts_start
    })
    val playLabel = when (state.phase) {
        ReadingTtsPhase.COMPLETED -> R.string.reading_tts_replay
        ReadingTtsPhase.FAILED -> R.string.action_retry
        else -> R.string.reading_tts_resume
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("reading-tts-controls")) {
            Text(
                stringResource(R.string.reading_tts_position, status, state.sentenceIndex + 1, state.sentenceCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                    .testTag("reading-tts-status").semantics { liveRegion = LiveRegionMode.Polite }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onPrevious, enabled = state.sentenceIndex > 0,
                    modifier = Modifier.size(48.dp).testTag("reading-tts-previous")) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.reading_tts_previous))
                }
                IconButton(onClick = if (active) onPause else onResume,
                    modifier = Modifier.size(48.dp).testTag("reading-tts-play-pause")) {
                    Icon(
                        if (active) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (active) R.string.reading_tts_pause else playLabel)
                    )
                }
                IconButton(onClick = onNext, enabled = state.sentenceIndex + 1 < state.sentenceCount,
                    modifier = Modifier.size(48.dp).testTag("reading-tts-next")) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.reading_tts_next))
                }
                IconButton(onClick = onStop, modifier = Modifier.size(48.dp).testTag("reading-tts-stop")) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.reading_tts_stop))
                }
            }
        }
    }
}
