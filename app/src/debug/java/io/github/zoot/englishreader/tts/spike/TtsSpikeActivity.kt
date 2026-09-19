package io.github.zoot.englishreader.tts.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme

@AndroidEntryPoint
class TtsSpikeActivity : ComponentActivity() {
    private val viewModel: TtsSpikeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val view = LocalView.current
            DisposableEffect(state.running) {
                view.keepScreenOn = state.running
                onDispose { view.keepScreenOn = false }
            }
            EnglishReaderTheme {
                TtsSpikeScreen(state, viewModel::selectSpeaker, viewModel::start, viewModel::stop)
            }
        }
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsSpikeScreen(
    state: TtsSpikeState,
    onSpeaker: (Int) -> Unit,
    onStart: (Boolean) -> Unit,
    onStop: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.tts_spike_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.tts_spike_description))
            Text(stringResource(R.string.tts_spike_sentence), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsSpikeViewModel.SPEAKERS.forEach { speaker ->
                    FilterChip(
                        selected = state.speakerId == speaker,
                        enabled = !state.running,
                        onClick = { onSpeaker(speaker) },
                        label = { Text(stringResource(R.string.tts_spike_speaker, speaker)) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                    )
                }
            }
            Button(onClick = { onStart(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tts_spike_single))
            }
            Button(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tts_spike_repeat))
            }
            OutlinedButton(onClick = onStop, enabled = state.running, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tts_spike_stop))
            }
            Text(stringResource(statusMessage(state)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tts_spike_progress, state.completedUtterances, state.elapsedMillis / 1_000))
            state.modelLoadMillis?.let { Text(stringResource(R.string.tts_spike_load_time, it)) }
            state.requestToFirstBlockMillis?.let { Text(stringResource(R.string.tts_spike_request_time, it)) }
            state.lastFirstBlockMillis?.let {
                Text(stringResource(R.string.tts_spike_block_time, it))
                Text(stringResource(R.string.tts_spike_max_block_time, state.maxFirstBlockMillis))
            }
            state.lastRtf?.let {
                Text(stringResource(R.string.tts_spike_rtf, it))
                Text(stringResource(R.string.tts_spike_max_rtf, state.maxRtf))
            }
            state.stopCallMillis?.let { Text(stringResource(R.string.tts_spike_stop_time, it)) }
            state.peakThermalStatus?.let { Text(stringResource(R.string.tts_spike_thermal, it)) }
            Text(stringResource(R.string.tts_spike_measurement_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.tts_spike_licenses), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusMessage(state: TtsSpikeState): Int = when (state.failure) {
    TtsSpikeFailure.MODEL_MISSING -> R.string.tts_spike_model_missing
    TtsSpikeFailure.UNSUPPORTED_ABI -> R.string.tts_spike_unsupported
    TtsSpikeFailure.MODEL_LOAD -> R.string.tts_spike_model_failed
    TtsSpikeFailure.SYNTHESIS -> R.string.tts_spike_synthesis_failed
    TtsSpikeFailure.AUDIO -> R.string.tts_spike_audio_failed
    null -> when (state.phase) {
        TtsSpikePhase.IDLE -> R.string.tts_spike_idle
        TtsSpikePhase.LOADING -> R.string.tts_spike_loading
        TtsSpikePhase.SYNTHESIZING -> R.string.tts_spike_synthesizing
        TtsSpikePhase.PLAYING -> R.string.tts_spike_playing
        TtsSpikePhase.FINISHED -> R.string.tts_spike_finished
        TtsSpikePhase.STOPPED -> R.string.tts_spike_stopped
        TtsSpikePhase.FAILED -> R.string.tts_spike_synthesis_failed
    }
}
