package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.audio.PronunciationCacheStats
import io.github.zoot.englishreader.data.dictionary.DictionaryPackFailure
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.viewmodel.ProfileActionResult
import java.text.NumberFormat

@Composable
internal fun SettingsCacheManagementContent(
    onRetryStorage: () -> Unit,
    onClearCredentials: () -> Unit,
    onClearCache: () -> Unit,
    pronunciationCacheStats: PronunciationCacheStats = PronunciationCacheStats(0, 0L),
    onClearPronunciationCache: () -> Unit = {}
) {
    var showClearCredentialsConfirmation by remember { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.settings_cache_cleanup)) {
        PronunciationCacheRow(stats = pronunciationCacheStats, onClear = onClearPronunciationCache)
        SettingsDivider()
        SettingsCacheClearAction(onClearCache)
    }
    SettingsGroup(title = stringResource(R.string.settings_credential_maintenance)) {
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
            title = stringResource(R.string.settings_profile_retry_storage),
            onClick = onRetryStorage
        )
        SettingsDivider()
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = stringResource(R.string.settings_profile_clear_credentials),
            titleColor = MaterialTheme.colorScheme.error,
            onClick = { showClearCredentialsConfirmation = true }
        )
    }

    ConfirmationDialogs(
        showClearCredentialsConfirmation = showClearCredentialsConfirmation,
        onDismissClearCredentials = { showClearCredentialsConfirmation = false },
        onConfirmClearCredentials = {
            showClearCredentialsConfirmation = false
            onClearCredentials()
        }
    )
}

/**
 * 发音缓存的一行：占用显示 + 清除。
 *
 * ## 为什么清除也要确认对话框
 *
 * 缓存是可重建的数据，清掉不丢任何用户内容。但清完之后再查那些词会重新联网，
 * 也就是用户能感知到的退步（那正是这个缓存要消除的等待）。与「清除 AI 缓存」同一逻辑：
 * 那个也是可重建数据却有确认框。
 *
 * ## 空缓存时不可点
 *
 * 没有东西可清时点它没有意义，而弹一个「确认清除 0 个词」的对话框比不可点更困惑。
 */
@Composable
private fun PronunciationCacheRow(
    stats: PronunciationCacheStats,
    onClear: () -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }
    val isEmpty = stats.wordCount == 0 && stats.totalBytes == 0L

    val supporting = if (isEmpty) {
        stringResource(R.string.settings_pron_cache_empty)
    } else {
        stringResource(
            R.string.settings_pron_cache_usage,
            stats.wordCount,
            formatCacheSize(stats.totalBytes)
        )
    }

    SettingsActionRow(
        icon = {
            Icon(
                Icons.Outlined.VolumeUp,
                contentDescription = stringResource(R.string.settings_pron_cache_title)
            )
        },
        title = stringResource(R.string.settings_pron_cache_title),
        supporting = supporting,
        titleColor = if (isEmpty) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
        onClick = if (isEmpty) null else ({ showConfirmation = true }),
        testTag = "settings-pron-cache"
    )

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.settings_pron_cache_clear_title)) },
            text = { Text(stringResource(R.string.settings_pron_cache_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        onClear()
                    },
                    modifier = Modifier.testTag("settings-pron-cache-confirm")
                ) {
                    Text(stringResource(R.string.settings_pron_cache_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(R.string.settings_pron_cache_clear_cancel))
                }
            }
        )
    }
}

/**
 * 字节数转人读大小。
 *
 * 缓存上限是 20 MB，单个音频约 19 KB，所以只需要 KB 与 MB 两档 —— 不引入通用的
 * 单位换算工具。低于 1 MB 显示 KB（否则「0.0 MB」看起来像空的，而实际可能有几百 KB）。
 *
 * ## 为什么用 NumberFormat 而不是裸 format
 *
 * **不是**因为裸 `"%.1f MB".format(...)` 有 locale 缺陷 —— 我一度以为有，实测证伪：
 * Kotlin 的 `String.format` 走 `Locale.getDefault()`，与 `NumberFormat` 行为一致，
 * 德语环境下两者都给 `1,5 MB`（Robolectric 探针实测）。为此写的负向对照两次都不红，
 * 因为没有缺陷可抓。
 *
 * 保留 `NumberFormat` 的理由只有一条：与同一页面 `DictionaryPackRow` 的数字格式化方式
 * 统一。两种写法功能等价，这是风格选择而非缺陷修复。
 *
 * [SettingsPronunciationCacheTest.cacheSize_followsDeviceLocaleForDecimalSeparator]
 * 钉住「跟随设备 locale」这个性质 —— 两种实现都满足它，但若有人改成硬编码
 * `Locale.US`，那条会红。
 */
private fun formatCacheSize(bytes: Long): String = if (bytes >= BYTES_PER_MB) {
    val mb = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(bytes.toDouble() / BYTES_PER_MB)
    "$mb MB"
} else {
    "${NumberFormat.getIntegerInstance().format(bytes / BYTES_PER_KB)} KB"
}

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L

/**
 * 扩展词库的一行：状态 + 动作。
 *
 * ## 为什么下载必须经确认对话框
 *
 * 这是 22 MB 传输 / 63 MB 解压的下载。用户在设置页误触一下就开始跑几十 MB 流量是不可接受的，
 * 尤其在移动数据下。所以点这一行只打开对话框，对话框里写清体量与收益，用户按「开始下载」
 * 才真正发起。
 *
 * 已安装时点击进入移除确认；移除会恢复内置词库，保留文章与生词。
 */
@Composable
internal fun DictionaryPackRow(
    state: DictionaryPackState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }
    var showRemoveConfirmation by remember { mutableStateOf(false) }
    val entryFormat = remember { NumberFormat.getIntegerInstance() }

    val supporting = when (state) {
        is DictionaryPackState.NotInstalled ->
            stringResource(R.string.settings_dict_pack_not_installed)

        is DictionaryPackState.Downloading -> state.fraction?.let {
            stringResource(
                R.string.settings_dict_pack_downloading,
                NumberFormat.getPercentInstance().format(it)
            )
        } ?: stringResource(R.string.settings_dict_pack_downloading_unknown)

        is DictionaryPackState.Installing -> stringResource(
            R.string.settings_dict_pack_installing,
            entryFormat.format(state.processedEntries)
        )

        is DictionaryPackState.Installed -> stringResource(
            R.string.settings_dict_pack_installed,
            entryFormat.format(state.entryCount)
        )

        DictionaryPackState.Removing -> stringResource(R.string.settings_dict_pack_removing)

        is DictionaryPackState.Failed -> stringResource(
            when (state.reason) {
                DictionaryPackFailure.NETWORK -> R.string.settings_dict_pack_error_network
                DictionaryPackFailure.STORAGE -> R.string.settings_dict_pack_error_storage
                DictionaryPackFailure.CORRUPT -> R.string.settings_dict_pack_error_corrupt
                DictionaryPackFailure.CANCELLED -> R.string.settings_dict_pack_error_cancelled
                DictionaryPackFailure.UNKNOWN -> R.string.settings_dict_pack_error_unknown
            }
        )
    }

    val installing = state is DictionaryPackState.Downloading || state is DictionaryPackState.Installing
    val inFlight = installing || state is DictionaryPackState.Removing

    SettingsActionRow(
        icon = { Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.settings_dict_pack_title)) },
        title = stringResource(R.string.settings_dict_pack_title),
        titleColor = if (state is DictionaryPackState.Installed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        supporting = supporting,
        onClick = when {
            inFlight -> null
            state is DictionaryPackState.Installed -> ({ showRemoveConfirmation = true })
            else -> ({ showConfirmation = true })
        },
        testTag = "settings-dict-pack"
    )

    if (state is DictionaryPackState.Downloading) {
        val fraction = state.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        } else {
            // 总量未知时用不定态：假装知道百分比会让进度条卡在某个值上，比不定态更像坏了。
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }

    if (installing) {
        TextButton(onClick = onCancel, modifier = Modifier.testTag("settings-dict-pack-cancel")) {
            Text(stringResource(R.string.settings_dict_pack_cancel_download))
        }
    }

    if (state is DictionaryPackState.Failed && state.reason != DictionaryPackFailure.CANCELLED) {
        // 失败后明确告诉用户内置词库还能用 —— 否则「安装失败」读起来像查词坏了。
        Text(
            text = stringResource(R.string.settings_dict_pack_kept_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.settings_dict_pack_confirm_title)) },
            text = { Text(stringResource(R.string.settings_dict_pack_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        onInstall()
                    },
                    modifier = Modifier.testTag("settings-dict-pack-confirm")
                ) {
                    Text(stringResource(R.string.settings_dict_pack_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(R.string.settings_dict_pack_cancel))
                }
            }
        )
    }
    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            title = { Text(stringResource(R.string.settings_dict_pack_remove_title)) },
            text = { Text(stringResource(R.string.settings_dict_pack_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (showRemoveConfirmation) {
                            showRemoveConfirmation = false
                            if (state is DictionaryPackState.Installed) onRemove()
                        }
                    },
                    modifier = Modifier.testTag("settings-dict-pack-remove-confirm")
                ) {
                    Text(stringResource(R.string.settings_dict_pack_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

}

@Composable
internal fun SettingsCacheClearAction(
    onClearCache: () -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }

    SettingsActionRow(
        icon = { Icon(Icons.Outlined.Cached, contentDescription = null) },
        title = stringResource(R.string.settings_ai_cache_clear),
        titleColor = MaterialTheme.colorScheme.error,
        onClick = { showConfirmation = true }
    )

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.settings_ai_cache_clear_title)) },
            text = { Text(stringResource(R.string.settings_ai_cache_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    onClearCache()
                }) {
                    Text(stringResource(R.string.settings_ai_cache_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(R.string.settings_ai_cache_clear_cancel))
                }
            }
        )
    }
}

@Composable
private fun ConfirmationDialogs(
    showClearCredentialsConfirmation: Boolean,
    onDismissClearCredentials: () -> Unit,
    onConfirmClearCredentials: () -> Unit
) {
    if (showClearCredentialsConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissClearCredentials,
            title = { Text(stringResource(R.string.settings_profile_clear_credentials_title)) },
            text = { Text(stringResource(R.string.settings_profile_clear_credentials_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmClearCredentials) {
                    Text(stringResource(R.string.settings_profile_clear_credentials_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClearCredentials) {
                    Text(stringResource(R.string.settings_profile_clear_credentials_cancel))
                }
            }
        )
    }
}

internal fun ProfileActionResult.settingsMessageRes(): Int = when (this) {
    ProfileActionResult.CREATED -> R.string.settings_profile_created
    ProfileActionResult.SELECTED -> R.string.settings_profile_selected
    ProfileActionResult.UPDATED -> R.string.settings_profile_updated
    ProfileActionResult.ROTATED -> R.string.settings_profile_key_rotated
    ProfileActionResult.DELETED -> R.string.settings_profile_deleted
    ProfileActionResult.STORAGE_REINITIALIZED -> R.string.settings_profile_storage_reinitialized
    ProfileActionResult.CREDENTIALS_CLEARED -> R.string.settings_profile_credentials_cleared
    ProfileActionResult.CONFIGURATIONS_DELETED -> R.string.settings_profiles_deleted_all
    ProfileActionResult.CACHE_CLEARED -> R.string.settings_ai_cache_cleared
    ProfileActionResult.FAILED -> R.string.settings_profile_failed
}
