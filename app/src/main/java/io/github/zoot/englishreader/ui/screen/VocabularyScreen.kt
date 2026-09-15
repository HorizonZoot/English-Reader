package io.github.zoot.englishreader.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import io.github.zoot.englishreader.ui.screen.vocabulary.listKey
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.LocalSegmentedControlColors
import io.github.zoot.englishreader.ui.theme.NeutralIconGray
import io.github.zoot.englishreader.ui.theme.SegmentedControlColors
import io.github.zoot.englishreader.viewmodel.VocabularyUiEvent
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel

// 分段控件尺寸：整体 38dp 高、19dp 圆角（正圆端），滑块比轨道内缩 2dp。
private val SegmentHeight = 38.dp
private val SegmentRadius = 19.dp
private val SegmentThumbRadius = 17.dp
private val SegmentInset = 2.dp

/**
 * 生词本界面。
 *
 * 视觉上只做四件事：标题收小、留白放大、边框减到几乎没有、字体层级拉开。
 * 不加卡片、不加渐变、不加装饰。
 */
@Composable
fun VocabularyScreen(
    onOpenArticle: (articleId: Long, word: String) -> Unit,
    viewModel: VocabularyViewModel = hiltViewModel()
) = ArticleUiTheme {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val groupType by viewModel.groupType.collectAsStateWithLifecycle()
    val wordCount by viewModel.vocabulary.collectAsStateWithLifecycle()
    val loadingAudioWordId by viewModel.loadingAudioWordId.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 单一 collector 顺序消费所有一次性事件：showSnackbar 挂起到消息消失，
    // 因此连续事件天然串行，不会互相抢占 Snackbar 宿主。
    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                // 「已删除 + 撤销」只在删除**确实落库后**才弹（事件由 ViewModel 在写库
                // 返回后发出）。左滑是易误触的手势，而误删一条生词没有别的找回途径。
                is VocabularyUiEvent.Deleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(
                            R.string.vocabulary_deleted,
                            event.vocabulary.word
                        ),
                        actionLabel = context.getString(R.string.vocabulary_undo)
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreVocabulary(event.vocabulary)
                    }
                }

                VocabularyUiEvent.DeleteFailed -> snackbarHostState.showSnackbar(
                    context.getString(R.string.vocabulary_delete_failed)
                )

                VocabularyUiEvent.RestoreFailed -> snackbarHostState.showSnackbar(
                    context.getString(R.string.vocabulary_restore_failed)
                )

                // 真人音与缓存都拿不到时回落系统 TTS；TTS 也没有引擎才算真失败，
                // 那必须说出来——用户是主动点了一下，静默无声会被当成「功能没做」。
                VocabularyUiEvent.AudioUnavailable -> snackbarHostState.showSnackbar(
                    context.getString(R.string.tts_unavailable)
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VocabularyHeader(
                wordCount = wordCount.size,
                groupType = groupType,
                onTypeSelected = viewModel::switchGroupType
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (groups.isEmpty()) {
                VocabularyEmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)
                ) {
                    groups.forEach { group ->
                        item(key = group.id.listKey, contentType = "group-header") {
                            GroupHeader(
                                title = vocabularyGroupTitle(group.id),
                                wordCount = group.words.size,
                                isToday = group.id == VocabularyGroupId.Today,
                                isExpanded = group.isExpanded,
                                onClick = { viewModel.toggleGroup(group.id) }
                            )
                        }

                        if (group.isExpanded) {
                            itemsIndexed(group.words, key = { _, word -> word.id }) { index, word ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // 分隔线画在可滑动内容之外：跟着手指滑走会很别扭。
                                    // 从内容区左边缘起（列表已有 24dp 内边距），不贯穿屏幕。
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                    SwipeToDeleteWordRow(
                                        word = word,
                                        detail = details[word.id],
                                        isPreparingAudio = loadingAudioWordId == word.id,
                                        onPlayAudio = { viewModel.playWordAudio(word) },
                                        onOpenArticle = onOpenArticle,
                                        onDelete = { viewModel.deleteVocabulary(word) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部标题区：标题、总词数、分组方式分段控件。
 *
 * 标题 32sp/Semibold 而不是更大更粗——Apple 的大标题感来自留白，不是字重。
 * 词数为 0 时不显示分段控件：没有分组可切换，摆在那里只是噪音。
 */
@Composable
private fun VocabularyHeader(
    wordCount: Int,
    groupType: GroupType,
    onTypeSelected: (GroupType) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.nav_vocabulary),
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.semantics { heading() }
            )
            if (wordCount > 0) {
                Text(
                    text = stringResource(R.string.vocabulary_total_count, wordCount),
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                GroupTypeSegments(
                    selectedType = groupType,
                    onTypeSelected = onTypeSelected,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 分组方式切换，iOS 风格分段控件。
 *
 * 不用 Material 3 的 `SegmentedButton`：它自带描边和选中打勾图标，视觉重量远超这里
 * 需要的「浅灰轨道 + 白色滑块」；那圈描边正是要弱化的东西。
 *
 * 语义上用 [selectableGroup] + `Role.RadioButton`，读屏会念出「已选中」，而不是
 * 把两个按钮念成两个独立动作。
 */
@Composable
private fun GroupTypeSegments(
    selectedType: GroupType,
    onTypeSelected: (GroupType) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalSegmentedControlColors.current

    Row(
        modifier = modifier
            .height(SegmentHeight)
            .clip(RoundedCornerShape(SegmentRadius))
            .background(colors.track)
            .padding(SegmentInset)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SegmentInset)
    ) {
        SegmentButton(
            label = stringResource(R.string.vocabulary_group_by_time),
            selected = selectedType == GroupType.ByTime,
            onClick = { onTypeSelected(GroupType.ByTime) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        SegmentButton(
            label = stringResource(R.string.vocabulary_group_by_alphabet),
            selected = selectedType == GroupType.ByAlphabet,
            onClick = { onTypeSelected(GroupType.ByAlphabet) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: SegmentedControlColors,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(SegmentThumbRadius)

    Box(
        modifier = modifier
            .fillMaxHeight()
            // 选中项只有极轻的一层投影，用来把它从轨道上「浮」起来，不制造边框。
            .then(if (selected) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(if (selected) colors.thumb else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Normal,
            // 蓝色只出现在文字上，不做选中色块。
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1
        )
    }
}

/**
 * 分组头：标题一行、词数一行、右侧展开箭头。
 *
 * 纯文本，没有背景也没有边框——做成卡片会把「分组」变成视觉主体，
 * 而这里真正的主体是下面的单词。
 */
@Composable
private fun GroupHeader(
    title: String,
    wordCount: Int,
    isToday: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 180f,
        label = "group-chevron"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(
                    if (isToday) R.string.vocabulary_group_words_new
                    else R.string.vocabulary_group_words,
                    wordCount
                ),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(
                if (isExpanded) R.string.vocabulary_group_collapse
                else R.string.vocabulary_group_expand
            ),
            tint = NeutralIconGray,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation)
        )
    }
}

/**
 * 左滑删除的生词行。
 *
 * 删除是**破坏性且不可逆**的操作，所以：
 *  - 只允许从右往左滑（[SwipeToDismissBox] 的 StartToEnd 关掉），避免方向上的误触；
 *  - `confirmValueChange` 派发删除后返回 **false**，行滑回原位，由列表数据回流把它
 *    移除。返回 true 会让行停在 dismissed 锚点上：删除万一没落库，Flow 不回流、
 *    行也滑不回来（StartToEnd 已关、`Settled` 又被拒），那一行就永久卡在屏幕外——
 *    `swipeLeft_rowSlidesBackAndWaitsForTheListToDropIt` 盯的就是这个；
 *  - 重复派发的去重在 ViewModel（按 id），不在这里：它跨重组存活，也可单测；
 *  - 滑动过程中**不画任何东西**。早先在右侧放了一个垃圾桶，随进度由灰变红；它虽然在
 *    静止时被行内容盖住，却是整屏最抢眼的元素，而行本身跟手滑动已经说明了这个手势
 *    要做什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteWordRow(
    word: VocabularyEntity,
    detail: VocabularyWordDetail?,
    isPreparingAudio: Boolean,
    onPlayAudio: () -> Unit,
    onOpenArticle: (articleId: Long, word: String) -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            // 一律不接受新状态：行滑回原位，等列表把它移除。
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.testTag("vocabulary-word-${word.id}"),
        backgroundContent = {}
    ) {
        WordRow(
            word = word,
            detail = detail,
            isPreparingAudio = isPreparingAudio,
            onPlayAudio = onPlayAudio,
            onOpenArticle = onOpenArticle,
            onDelete = onDelete
        )
    }
}

/**
 * 生词行本体。
 *
 * 字体体系是刻意分开的：英文单词与音标用 Serif（和阅读页、文章列表的标题一致），
 * 中文释义与来源用系统字体（苹方）——一套字体打天下会让所有信息看起来一样重。
 *
 * 颜色层级由深到浅：单词 `onSurface` > 释义 `onSurface` > 音标 `onSurfaceVariant`
 * > 来源 `onSurfaceVariant` 再降透明度；「查看原文」是唯一的系统蓝。
 *
 * 释义最多两行：ECDICT 的长词条（如 "a"）展开有几百字，不截断会把整屏撑爆。
 */
@Composable
private fun WordRow(
    word: VocabularyEntity,
    detail: VocabularyWordDetail?,
    isPreparingAudio: Boolean,
    onPlayAudio: () -> Unit,
    onOpenArticle: (articleId: Long, word: String) -> Unit,
    onDelete: () -> Unit
) {
    // clickable 会合并子节点语义，读屏只会念出单词本身「noticing，按钮」——听不出按下去
    // 会做什么。用 contentDescription 覆盖成动作描述。
    val playDescription = if (isPreparingAudio) {
        stringResource(R.string.loading_pronunciation)
    } else {
        stringResource(R.string.word_play_pronunciation, word.word)
    }
    val deleteActionLabel = stringResource(R.string.delete_vocabulary)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 单词本身就是播放按钮：喇叭图标只是可点区域的提示，不是独立的第二次点击目标。
        //
        // 删除的自定义无障碍操作必须挂在**这个**节点上：它因 clickable 而合并子节点、
        // 有 contentDescription，是读屏真正会聚焦的元素，自定义操作也只出现在当前
        // 获得焦点的节点的局部菜单里。挂在外层 SwipeToDismissBox 上不行——那个节点
        // 既不合并子节点也没有文本，读屏遍历会直接跳过它，删除入口等于不存在。
        // 左滑手势对读屏用户不可达，这是他们唯一的删除入口。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onPlayAudio)
                .semantics {
                    contentDescription = playDescription
                    customActions = listOf(
                        CustomAccessibilityAction(deleteActionLabel) {
                            onDelete()
                            true
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = word.word,
                fontFamily = FontFamily.Serif,
                fontSize = 22.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (isPreparingAudio) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = NeutralIconGray
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = NeutralIconGray,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        if (detail?.hasGloss == true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                detail.phonetic?.let {
                    Text(
                        text = it,
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = detail.chinese.orEmpty(),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        detail?.headword?.let {
            Text(
                text = stringResource(R.string.vocabulary_headword_note, it),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val articleId = word.articleId
        val sourceTitle = detail?.sourceTitle
        if (articleId != null && sourceTitle != null) {
            SourceArticleRow(
                sourceTitle = sourceTitle,
                onClick = { onOpenArticle(articleId, word.word) }
            )
        }
    }
}

/**
 * 来源文章行：整行可点，跳回该文章的阅读页。
 *
 * 「查看原文」是纯文本链接——没有背景、没有圆角、不加粗，只有系统蓝。
 *
 * `articleId` 非空即代表文章仍存在——vocabulary 对 articles 是 CASCADE，
 * 文章被删时关联生词会一起消失（删书场景则由 `deleteBookCascade` 先把
 * `articleId` 置 NULL 保住生词，那种情况下这里不显示来源行）。
 */
@Composable
private fun SourceArticleRow(
    sourceTitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = NeutralIconGray,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = stringResource(R.string.vocabulary_source_article, sourceTitle),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = stringResource(R.string.vocabulary_open_article),
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun VocabularyEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = NeutralIconGray,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = stringResource(R.string.vocabulary_empty),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.vocabulary_empty_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun vocabularyGroupTitle(id: VocabularyGroupId): String = when (id) {
    VocabularyGroupId.Today -> stringResource(R.string.vocabulary_group_today)
    VocabularyGroupId.Yesterday -> stringResource(R.string.vocabulary_group_yesterday)
    VocabularyGroupId.ThisWeek -> stringResource(R.string.vocabulary_group_this_week)
    is VocabularyGroupId.OlderDate -> id.value
    is VocabularyGroupId.Alphabet -> id.value
}
