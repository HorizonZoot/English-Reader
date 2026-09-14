package io.github.zoot.englishreader.ui.dialog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme

@Composable
fun ImportDialog(
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit
) {
    var dispatched by remember { mutableStateOf(false) }
    fun choose(action: () -> Unit) {
        if (dispatched) return
        dispatched = true
        onDismiss()
        action()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ArticleUiTheme {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                        .heightIn(max = maxHeight).testTag("import-dialog"),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.import_article),
                                modifier = Modifier.weight(1f).semantics { heading() },
                                fontSize = 28.sp,
                                lineHeight = 36.sp,
                                letterSpacing = (-0.3).sp,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                                Box(
                                    modifier = Modifier.size(30.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.action_cancel),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Text(
                                stringResource(R.string.import_choose_method),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 21.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                ImportChoiceRow(
                                    title = stringResource(R.string.import_from_file),
                                    subtitle = stringResource(R.string.import_file_formats),
                                    icon = Icons.Outlined.FolderOpen,
                                    enabled = !dispatched,
                                    onClick = { choose(onImportFile) },
                                    modifier = Modifier.testTag("import-choice-file")
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 66.dp, end = 12.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                ImportChoiceRow(
                                    title = stringResource(R.string.import_paste_text),
                                    subtitle = stringResource(R.string.import_paste_description),
                                    icon = Icons.Outlined.ContentPaste,
                                    enabled = !dispatched,
                                    onClick = { choose(onImportPaste) },
                                    modifier = Modifier.testTag("import-choice-paste")
                                )
                            }
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.import_epub_support_note),
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportChoiceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val active = hovered || pressed || focused
    val background by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = if (pressed) 0.11f else 0.06f)
        else Color.Transparent,
        label = "import-choice-background"
    )
    val arrowOffset by animateDpAsState(if (active) 3.dp else 0.dp, label = "import-choice-arrow")
    val iconColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(13.dp)

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 80.dp)
            .shadow(if (hovered) 1.dp else 0.dp, shape)
            .clip(shape)
            .background(background)
            .hoverable(interactions, enabled)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(width = 40.dp, height = 44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp).offset(x = arrowOffset),
            tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
