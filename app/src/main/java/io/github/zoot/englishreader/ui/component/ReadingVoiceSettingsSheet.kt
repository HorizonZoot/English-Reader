package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.model.ReadingVoiceSettingsState
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsVoiceMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingVoiceSettingsSheet(
    state: ReadingVoiceSettingsState,
    onDismiss: () -> Unit,
    onVoiceChange: (String?) -> Unit,
    onRateChange: (Float) -> Long?,
    onNetworkAllowedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onReset: () -> Unit,
    onRecheck: () -> Unit,
    onSystemAction: (TtsSystemAction) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ReadingVoiceSettingsContent(
            state = state,
            onDismiss = onDismiss,
            onVoiceChange = onVoiceChange,
            onRateChange = onRateChange,
            onNetworkAllowedChange = onNetworkAllowedChange,
            onPreview = onPreview,
            onStopPreview = onStopPreview,
            onReset = onReset,
            onRecheck = onRecheck,
            onSystemAction = onSystemAction
        )
    }
}

/** Reading-only voice preferences. Word pronunciation keeps its own fixed voice and rate. */
@Composable
internal fun ReadingVoiceSettingsContent(
    state: ReadingVoiceSettingsState,
    onDismiss: () -> Unit,
    onVoiceChange: (String?) -> Unit,
    onRateChange: (Float) -> Long?,
    onNetworkAllowedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onReset: () -> Unit,
    onRecheck: () -> Unit,
    onSystemAction: (TtsSystemAction) -> Unit
) {
    val draft = remember { ReadingSpeechRateDraft() }
    val latestOnRateChange = rememberUpdatedState(onRateChange)
    // Material3 1.2.0 用结束回调作为 SliderState 的 key，重组时替换它会中断拖动。
    val finishRateChange: () -> Unit = remember(draft) {
        { draft.submit(latestOnRateChange.value) }
    }
    val rate = draft.value ?: state.pendingSpeechRate ?: state.settings.speechRate
    LaunchedEffect(state.rateChangeId, draft.requestId) {
        draft.acknowledge(state.rateChangeId)
    }
    val rateLabel = stringResource(R.string.reading_voice_rate)

    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag("reading-voice-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.reading_voice_settings),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.reading_sentence_popup_close)
                        )
                    }
                }
                Text(
                    stringResource(R.string.reading_voice_scope),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                stringResource(state.snapshot.capability.messageRes()),
                modifier = Modifier.testTag("reading-voice-status")
            )
            Text(
                stringResource(R.string.reading_voice_rate_value, rate),
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = rate,
                onValueChange = draft::drag,
                onValueChangeFinished = finishRateChange,
                valueRange = TtsReadingSettings.MIN_RATE..TtsReadingSettings.MAX_RATE,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = rateLabel }
                    .testTag("reading-voice-rate")
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = if (state.previewing) onStopPreview else onPreview,
                    enabled = state.snapshot.capability is TtsCapability.Ready || state.previewing,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("reading-voice-preview")
                ) {
                    Text(
                        stringResource(
                            if (state.previewing) R.string.reading_voice_stop_preview
                            else R.string.reading_voice_preview
                        )
                    )
                }
                TextButton(
                    onClick = onReset,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("reading-voice-reset")
                ) {
                    Text(stringResource(R.string.reading_voice_reset))
                }
            }
            state.previewFailure?.let {
                Text(
                    stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("reading-voice-preview-failure")
                )
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_allow_network_tts),
                        modifier = Modifier.weight(1f)
                    )
                    val consentLabel = stringResource(R.string.settings_allow_network_tts)
                    Switch(
                        checked = state.allowNetwork,
                        onCheckedChange = onNetworkAllowedChange,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = consentLabel }
                            .testTag("reading-voice-network")
                    )
                }
                Text(
                    stringResource(R.string.tts_network_disclosure),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            VoiceChoice(
                title = stringResource(R.string.reading_voice_auto),
                subtitle = stringResource(R.string.reading_voice_auto_hint),
                selected = state.settings.voiceId == null,
                enabled = true,
                onClick = { onVoiceChange(null) },
                modifier = Modifier.testTag("reading-voice-auto")
            )
            state.snapshot.voices.forEachIndexed { index, voice ->
                val mode = stringResource(
                    when (voice.mode) {
                        TtsVoiceMode.LOCAL -> R.string.reading_voice_local
                        TtsVoiceMode.NETWORK -> R.string.reading_voice_network
                        TtsVoiceMode.LOCAL_MODEL -> R.string.tts_model_offline
                    }
                )
                val qualitySummary = stringResource(R.string.reading_voice_quality, mode, voice.quality)
                VoiceChoice(
                    title = voice.nameRes?.let { stringResource(it) } ?: stringResource(
                        R.string.reading_voice_option,
                        index + 1,
                        Locale.forLanguageTag(voice.localeTag).getDisplayName(Locale.getDefault())
                    ),
                    subtitle = if (voice.mode == TtsVoiceMode.NETWORK && !state.allowNetwork) {
                        stringResource(R.string.reading_voice_needs_consent)
                    } else {
                        qualitySummary
                    },
                    selected = state.settings.voiceId == voice.id,
                    enabled = voice.mode != TtsVoiceMode.NETWORK || state.allowNetwork,
                    onClick = { onVoiceChange(voice.id) },
                    modifier = Modifier.testTag("reading-voice-option-$index")
                )
            }
            if (
                state.snapshot.catalogLoaded &&
                state.snapshot.voices.none { it.mode == TtsVoiceMode.NETWORK }
            ) {
                Text(
                    stringResource(R.string.reading_voice_no_network),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(
                onClick = onRecheck,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("reading-voice-recheck")
            ) {
                Text(stringResource(R.string.tts_recheck))
            }
            TextButton(
                onClick = { onSystemAction(TtsSystemAction.OPEN_SETTINGS) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("reading-voice-system-settings")
            ) {
                Text(stringResource(R.string.tts_system_settings))
            }
            TextButton(
                onClick = { onSystemAction(TtsSystemAction.INSTALL_DATA) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("reading-voice-install-data")
            ) {
                Text(stringResource(R.string.tts_install_data))
            }
        }
    }
}

/** 手指拖动不随旧存储回流重置；提交后等同一请求的乐观状态接管，再交还给 ViewModel。 */
internal class ReadingSpeechRateDraft {
    var value by mutableStateOf<Float?>(null)
        private set
    var requestId by mutableStateOf<Long?>(null)
        private set
    private var dragging = false

    fun drag(rate: Float) {
        dragging = true
        requestId = null
        value = if (rate.isFinite()) rate.coerceIn(TtsReadingSettings.MIN_RATE, TtsReadingSettings.MAX_RATE)
        else TtsReadingSettings.DEFAULT_RATE
    }

    fun submit(commit: (Float) -> Long?) {
        val rate = value?.let { TtsReadingSettings.normalizeRate(it) } ?: return
        dragging = false
        value = rate
        requestId = commit(rate)
        if (requestId == null) value = null
    }

    fun acknowledge(acceptedRequestId: Long) {
        val submitted = requestId ?: return
        if (!dragging && acceptedRequestId >= submitted) {
            value = null
            requestId = null
        }
    }
}

@Composable
private fun VoiceChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                title,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
