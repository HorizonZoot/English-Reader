package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R

@Composable
fun ContextSentence(sentence: String, start: Int, end: Int, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val text = remember(sentence, start, end, background, foreground) {
        buildAnnotatedString {
            append(sentence)
            if (start >= 0 && end <= sentence.length && start < end) {
                addStyle(SpanStyle(color = foreground, background = background, fontWeight = FontWeight.SemiBold), start, end)
            }
        }
    }
    Text(text, modifier, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun ContextMeaningDialog(
    word: String,
    sentence: String,
    targetStart: Int,
    targetEnd: Int,
    choices: List<String>,
    value: String,
    saving: Boolean,
    editing: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (editing) R.string.context_edit_meaning else R.string.context_confirm_meaning)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.context_target_word, word), style = MaterialTheme.typography.titleMedium)
                ContextSentence(sentence, targetStart, targetEnd)
                Text(stringResource(if (editing) R.string.context_meaning_reset_hint else R.string.context_meaning_hint))
                choices.forEach { choice ->
                    TextButton(
                        onClick = { onValueChange(choice) },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(choice, Modifier.fillMaxWidth()) }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.context_meaning_label)) },
                    enabled = !saving,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("context-meaning-input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !saving && value.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("context-meaning-confirm")
            ) { Text(stringResource(if (saving) R.string.context_saving else R.string.context_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
