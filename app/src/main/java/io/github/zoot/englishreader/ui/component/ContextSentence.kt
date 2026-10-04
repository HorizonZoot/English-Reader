package io.github.zoot.englishreader.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

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
