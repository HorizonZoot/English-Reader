package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.audio.PronunciationCacheStats
import io.github.zoot.englishreader.data.dictionary.DictionaryPackFailure
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.dictionary.DictionaryPackRemovalResult
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.ui.component.messageRes
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.viewmodel.ProfileActionResult
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import java.text.NumberFormat

private val SettingsBlue = Color(0xFF007AFF)
private val SettingsDarkBlue = Color(0xFF0A84FF)
private val SettingsCardShape = RoundedCornerShape(16.dp)
private val SettingsButtonShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAiProfile: () -> Unit = {},
    onOpenCacheManagement: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val fontSize by viewModel.fontSizeOption.collectAsStateWithLifecycle()
    val theme by viewModel.themeOption.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val dictionaryPackState by viewModel.dictionaryPackState.collectAsStateWithLifecycle()
    val ttsCapability by viewModel.ttsCapability.collectAsStateWithLifecycle()
    val allowNetworkTts by viewModel.allowNetworkTts.collectAsStateWithLifecycle()
    val savingTtsPreference by viewModel.savingTtsPreference.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshTtsCapability()
                Lifecycle.Event.ON_STOP -> viewModel.releaseTts()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releaseTts()
        }
    }

    // 进入设置页刷新一次扩展词库状态：判据是数据库实际条数，用户清过应用数据时
    // 「装过」的记录仍在但表已空，只看记录会显示成已安装。
    LaunchedEffect(Unit) {
        viewModel.refreshDictionaryPackState()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.ttsPreferenceErrors.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.tts_preference_save_failed))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.dictionaryPackRemovalEvents.collect { result ->
            val message = when (result) {
                DictionaryPackRemovalResult.REMOVED -> R.string.settings_dict_pack_removed
                DictionaryPackRemovalResult.FAILED -> R.string.settings_dict_pack_remove_failed
                DictionaryPackRemovalResult.BUSY -> return@collect
            }
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) { padding ->
        SettingsPage(modifier = Modifier.padding(padding)) {
            SettingsOverviewContent(
                fontSize = fontSize,
                onFontSizeChange = viewModel::setFontSizeOption,
                theme = theme,
                onThemeChange = viewModel::setThemeOption,
                profiles = profiles,
                activeProfileId = activeProfileId,
                onOpenAiProfile = onOpenAiProfile,
                onOpenCacheManagement = onOpenCacheManagement,
                dictionaryPackState = dictionaryPackState,
                onInstallDictionaryPack = viewModel::installDictionaryPack,
                onCancelDictionaryPack = viewModel::cancelDictionaryPackInstall,
                onRemoveDictionaryPack = viewModel::removeDictionaryPack
            )
            TtsSettingsSection(
                capability = ttsCapability,
                allowNetwork = allowNetworkTts,
                saving = savingTtsPreference,
                onAllowNetworkChange = viewModel::setAllowNetworkTts,
                onRefresh = viewModel::refreshTtsCapability,
                onSystemAction = viewModel::openTtsSystemAction
            )
        }
    }
}

@Composable
internal fun TtsSettingsSection(
    capability: TtsCapability,
    allowNetwork: Boolean,
    saving: Boolean,
    onAllowNetworkChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSystemAction: (TtsSystemAction) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.settings_tts_title)) {
        Text(
            stringResource(capability.messageRes()),
            modifier = Modifier.testTag("settings-tts-status"),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("settings-network-tts")
                .toggleable(
                    value = allowNetwork, enabled = !saving, role = Role.Switch,
                    onValueChange = onAllowNetworkChange
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_allow_network_tts), modifier = Modifier.weight(1f))
            Switch(checked = allowNetwork, onCheckedChange = null, enabled = !saving)
        }
        Text(
            stringResource(R.string.tts_network_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsDivider()
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
            title = stringResource(R.string.tts_recheck), onClick = onRefresh,
            testTag = "settings-tts-refresh"
        )
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            title = stringResource(R.string.tts_system_settings),
            onClick = { onSystemAction(TtsSystemAction.OPEN_SETTINGS) }, testTag = "settings-tts-system"
        )
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            title = stringResource(R.string.tts_install_data),
            onClick = { onSystemAction(TtsSystemAction.INSTALL_DATA) }, testTag = "settings-tts-install"
        )
    }
}

@Composable
internal fun SettingsOverviewContent(
    fontSize: FontSizeOption,
    onFontSizeChange: (FontSizeOption) -> Unit,
    theme: ThemeOption,
    onThemeChange: (ThemeOption) -> Unit,
    profiles: List<AiProviderProfile>,
    activeProfileId: String?,
    onOpenAiProfile: () -> Unit,
    onOpenCacheManagement: () -> Unit = {},
    dictionaryPackState: DictionaryPackState = DictionaryPackState.NotInstalled,
    onInstallDictionaryPack: () -> Unit = {},
    onCancelDictionaryPack: () -> Unit = {},
    onRemoveDictionaryPack: () -> Unit = {}
) {
    AppearanceSection(
        fontSize = fontSize,
        onFontSizeChange = onFontSizeChange,
        theme = theme,
        onThemeChange = onThemeChange
    )
    AiServiceSummarySection(
        profiles = profiles,
        activeProfileId = activeProfileId,
        onOpenAiProfile = onOpenAiProfile
    )
    SettingsGroup(title = stringResource(R.string.settings_data_and_cache)) {
        DictionaryPackRow(
            state = dictionaryPackState,
            onInstall = onInstallDictionaryPack,
            onCancel = onCancelDictionaryPack,
            onRemove = onRemoveDictionaryPack
        )
        SettingsDivider()
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Cached, contentDescription = null) },
            title = stringResource(R.string.settings_cache_management),
            supporting = stringResource(R.string.settings_cache_management_summary),
            onClick = onOpenCacheManagement,
            testTag = "settings-cache-management"
        )
    }
}

@Composable
private fun AppearanceSection(
    fontSize: FontSizeOption,
    onFontSizeChange: (FontSizeOption) -> Unit,
    theme: ThemeOption,
    onThemeChange: (ThemeOption) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.settings_appearance)) {
        AdaptiveChoiceRow(label = stringResource(R.string.settings_font_size)) {
            SegmentedRow(
                options = FontSizeOption.entries,
                selected = fontSize,
                label = { option -> stringResource(option.fontSizeLabelRes()) },
                onSelect = onFontSizeChange
            )
        }
        SettingsDivider()
        AdaptiveChoiceRow(label = stringResource(R.string.settings_theme)) {
            SegmentedRow(
                options = ThemeOption.entries,
                selected = theme,
                label = { option -> stringResource(option.themeLabelRes()) },
                onSelect = onThemeChange
            )
        }
    }
}

@Composable
private fun AiServiceSummarySection(
    profiles: List<AiProviderProfile>,
    activeProfileId: String?,
    onOpenAiProfile: () -> Unit
) {
    val activeProfile = profiles.firstOrNull { it.profileId == activeProfileId }
    SettingsGroup(title = stringResource(R.string.settings_ai)) {
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            title = stringResource(R.string.settings_ai_profile_entry),
            supporting = activeProfile?.displayName
                ?: stringResource(R.string.settings_ai_profile_unconfigured),
            onClick = onOpenAiProfile,
            testTag = "settings_ai_profile_entry"
        )
        SettingsDivider()
        AiProfileDisclosure()
    }
}

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
                    Text(stringResource(R.string.settings_pron_cache_clear_confirm))
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
private fun DictionaryPackRow(
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
                progress = { fraction },
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
                    Text(stringResource(R.string.settings_ai_cache_clear_confirm))
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
                    Text(stringResource(R.string.settings_profile_clear_credentials_confirm))
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

@Composable
internal fun SettingsPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = SettingsCardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

@Composable
private fun SettingsActionRow(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    testTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .semantics(mergeDescendants = true) {}
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdaptiveChoiceRow(label: String, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 480.dp) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(0.35f), style = MaterialTheme.typography.bodyLarge)
                Box(modifier = Modifier.weight(0.65f)) { content() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, style = MaterialTheme.typography.bodyLarge); content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        options.forEach { option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.widthIn(min = 82.dp),
                shape = SettingsButtonShape,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = settingsAccentColor().copy(alpha = 0.12f),
                    activeContentColor = settingsAccentColor(),
                    activeBorderColor = settingsAccentColor(),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                )
            ) {
                Text(label(option), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun settingsAccentColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SettingsDarkBlue else SettingsBlue

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

private fun FontSizeOption.fontSizeLabelRes(): Int = when (this) {
    FontSizeOption.SMALL -> R.string.settings_font_size_small
    FontSizeOption.MEDIUM -> R.string.settings_font_size_medium
    FontSizeOption.LARGE -> R.string.settings_font_size_large
}

private fun ThemeOption.themeLabelRes(): Int = when (this) {
    ThemeOption.SYSTEM -> R.string.settings_theme_system
    ThemeOption.LIGHT -> R.string.settings_theme_light
    ThemeOption.DARK -> R.string.settings_theme_dark
}
