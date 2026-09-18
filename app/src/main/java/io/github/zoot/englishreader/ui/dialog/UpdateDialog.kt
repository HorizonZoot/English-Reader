package io.github.zoot.englishreader.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.update.ReleaseNotesFormatter

/**
 * 发现新版本时的确认弹窗。
 *
 * 只做三件事：显示版本号、显示更新概要、给出两个选择。**不下载、不安装**——
 * 「立即更新」只是打开 Release 页面，后续由用户在浏览器里决定。
 *
 * @param onUpdate 打开 Release 页面。调用方负责真正的跳转与失败反馈。
 */
@Composable
fun UpdateDialog(
    versionName: String,
    releaseNotes: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    // 清理只依赖入参，重组时不必重做。
    val notes = remember(releaseNotes) { ReleaseNotesFormatter.format(releaseNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    // Release Notes 长度无上限。不封顶 + 不滚动的话，长内容会把两个按钮
                    // 推出可视区——用户既点不到「立即更新」也点不到「稍后」，弹窗变成死锁。
                    .heightIn(max = NOTES_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = versionName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = notes.ifEmpty { stringResource(R.string.update_dialog_no_notes) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onUpdate,
                modifier = Modifier.testTag(UPDATE_DIALOG_CONFIRM_TAG)
            ) {
                Text(stringResource(R.string.update_dialog_action_update))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UPDATE_DIALOG_DISMISS_TAG)
            ) {
                Text(stringResource(R.string.update_dialog_action_later))
            }
        }
    )
}

const val UPDATE_DIALOG_CONFIRM_TAG = "update_dialog_confirm"
const val UPDATE_DIALOG_DISMISS_TAG = "update_dialog_dismiss"

/** 够放约十来行说明，再长就滚动；留足空间给标题与按钮。 */
private val NOTES_MAX_HEIGHT = 280.dp
