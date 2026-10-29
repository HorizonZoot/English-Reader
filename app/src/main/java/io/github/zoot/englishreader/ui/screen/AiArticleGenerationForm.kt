package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiArticleDifficulty
import io.github.zoot.englishreader.data.ai.AiArticleLength
import io.github.zoot.englishreader.data.ai.AiArticlePromptPolicy
import io.github.zoot.englishreader.ui.theme.AiArticleGenerationStyle
import io.github.zoot.englishreader.viewmodel.AiArticleGenerationUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AiArticleGenerationForm(
    state: AiArticleGenerationUiState,
    onOpenAiProfile: () -> Unit,
    onReloadProfiles: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onDifficultyChange: (AiArticleDifficulty) -> Unit,
    onLengthChange: (AiArticleLength) -> Unit,
    onGenerate: () -> Unit
) {
    val draft = state.session.draft.parameters
    var profileMenuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AiArticleGenerationStyle.SectionGap)) {
        AiGenerationGroup(stringResource(R.string.ai_article_select_profile)) {
            Box {
                TextButton(
                    onClick = { profileMenuOpen = true },
                    enabled = !state.isBusy && state.profilesLoaded && state.profiles.isNotEmpty(),
                    shape = AiArticleGenerationStyle.FieldShape,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = AiArticleGenerationStyle.DisabledContent
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai_article_profile")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        state.selectedProfile?.displayName ?: stringResource(R.string.ai_article_select_profile),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Start
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = profileMenuOpen && !state.isBusy,
                    onDismissRequest = { profileMenuOpen = false }
                ) {
                    state.profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.displayName) },
                            onClick = {
                                profileMenuOpen = false
                                onSelectProfile(profile.profileId)
                            }
                        )
                    }
                }
            }
            when {
                !state.profilesLoaded -> AiGenerationFootnote(stringResource(R.string.ai_article_loading_profiles))
                state.profilesLoadFailed -> {
                    Text(stringResource(R.string.ai_article_profiles_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onReloadProfiles, enabled = !state.isBusy) {
                        Text(stringResource(R.string.ai_article_reload_profiles))
                    }
                }
                state.selectedProfile == null -> Text(
                    stringResource(R.string.ai_article_profile_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            AiGenerationFootnote(stringResource(R.string.ai_article_profile_local))
            TextButton(
                onClick = onOpenAiProfile,
                shape = AiArticleGenerationStyle.FieldShape,
                colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null)
                Text(
                    stringResource(R.string.ai_article_open_settings),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    textAlign = TextAlign.Start
                )
            }
        }
        AiGenerationGroup(stringResource(R.string.ai_article_difficulty)) {
            AiGenerationSegments {
                AiArticleDifficulty.entries.forEach { difficulty ->
                    AiGenerationSegment(
                        label = stringResource(difficulty.labelResource()),
                        selected = draft.difficulty == difficulty,
                        onClick = { onDifficultyChange(difficulty) },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f).testTag("ai_article_difficulty_${difficulty.name}")
                    )
                }
            }
            AiGenerationFootnote(stringResource(draft.difficulty.descriptionResource()))
        }
        AiGenerationGroup(stringResource(R.string.ai_article_length)) {
            AiGenerationSegments {
                AiArticleLength.entries.forEach { length ->
                    AiGenerationSegment(
                        label = stringResource(when (length) {
                            AiArticleLength.SHORT -> R.string.ai_article_short_option
                            AiArticleLength.MEDIUM -> R.string.ai_article_medium_option
                            AiArticleLength.LONG -> R.string.ai_article_long_option
                        }),
                        targetWords = length.targetWords,
                        selected = draft.length == length,
                        onClick = { onLengthChange(length) },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f).testTag("ai_article_length_${length.name}")
                    )
                }
            }
        }
        val topicLabel = stringResource(R.string.ai_article_topic)
        AiGenerationGroup(
            title = topicLabel,
            trailing = {
                val countDescription = stringResource(
                    R.string.ai_article_topic_count, draft.topic.length, AiArticlePromptPolicy.MAX_TOPIC_CHARS
                )
                Text(
                    stringResource(R.string.ai_article_topic_count_compact, draft.topic.length, AiArticlePromptPolicy.MAX_TOPIC_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.topicTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ai_article_topic_count").semantics { contentDescription = countDescription }
                )
            }
        ) {
            OutlinedTextField(
                value = draft.topic,
                onValueChange = onTopicChange,
                enabled = !state.isBusy,
                placeholder = { Text(stringResource(R.string.ai_article_topic_example)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = AiArticleGenerationStyle.FieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                isError = state.topicTooLong,
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("ai_article_topic")
                    .semantics { contentDescription = topicLabel }
            )
            if (state.topicTooLong) {
                Text(stringResource(R.string.ai_article_topic_too_long), color = MaterialTheme.colorScheme.error)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    R.string.ai_article_topic_daily,
                    R.string.ai_article_topic_travel,
                    R.string.ai_article_topic_work,
                    R.string.ai_article_topic_technology,
                    R.string.ai_article_topic_story,
                    R.string.ai_article_clear_topic
                ).forEach { resource ->
                    val label = stringResource(resource)
                    TextButton(
                        onClick = { onTopicChange(if (resource == R.string.ai_article_clear_topic) "" else label) },
                        enabled = !state.isBusy,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(label, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            AiGenerationFootnote(stringResource(R.string.ai_article_topic_hint))
        }
        AiGenerationFootnote(stringResource(R.string.ai_article_cost_notice))
        Button(
            onClick = onGenerate,
            enabled = state.canGenerate,
            shape = AiArticleGenerationStyle.ButtonShape,
            colors = aiGenerationButtonColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai_article_generate")
        ) {
            if (state.session.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).testTag("ai_article_generating_progress"),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(when {
                    state.session.isGenerating -> R.string.ai_article_generating_label
                    state.session.preview != null -> R.string.ai_article_regenerate
                    else -> R.string.ai_article_generate
                }),
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

@Composable
internal fun aiGenerationButtonColors() = ButtonDefaults.buttonColors(
    disabledContainerColor = AiArticleGenerationStyle.DisabledBackground,
    disabledContentColor = AiArticleGenerationStyle.DisabledContent
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiGenerationGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AiArticleGenerationStyle.CardShape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.padding(AiArticleGenerationStyle.CardPadding),
            verticalArrangement = Arrangement.spacedBy(AiArticleGenerationStyle.ContentGap)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
internal fun AiGenerationFootnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AiGenerationSegments(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(AiArticleGenerationStyle.FieldShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .selectableGroup(),
        content = content
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiGenerationSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    targetWords: Int? = null
) {
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> AiArticleGenerationStyle.DisabledContent
    }
    Box(
        modifier.clip(AiArticleGenerationStyle.FieldShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
            if (targetWords != null) {
                // 大字体窄屏时只在「约」后换行，不拆开数字与单位。
                FlowRow(horizontalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.ai_article_word_target_prefix), style = MaterialTheme.typography.bodySmall, color = textColor)
                    Text(
                        stringResource(R.string.ai_article_word_target_words, targetWords),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                        softWrap = false
                    )
                }
            }
        }
    }
}
