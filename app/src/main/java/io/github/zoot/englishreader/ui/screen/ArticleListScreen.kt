package io.github.zoot.englishreader.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ArticleSummary
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.importer.ImportFormatDetector
import io.github.zoot.englishreader.ui.component.ImportStatusOverlay
import io.github.zoot.englishreader.ui.dialog.ImportDialog
import io.github.zoot.englishreader.ui.dialog.PasteTextDialog
import io.github.zoot.englishreader.ui.theme.LibraryUiTheme
import io.github.zoot.englishreader.ui.component.SortMenuButton
import io.github.zoot.englishreader.model.LibrarySort
import io.github.zoot.englishreader.model.LibrarySortField
import io.github.zoot.englishreader.model.LibrarySortDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import io.github.zoot.englishreader.util.ErrorMessageMapper
import io.github.zoot.englishreader.viewmodel.ArticleListUiEvent
import io.github.zoot.englishreader.viewmodel.ArticleListViewModel
import io.github.zoot.englishreader.viewmodel.ImportState
import io.github.zoot.englishreader.viewmodel.LibraryItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

internal const val LIBRARY_SEARCH_TAG = "library-search"

/**
 * 文章列表界面
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleListScreen(
    onArticleClick: (Long) -> Unit,
    onBookClick: (Long) -> Unit,
    viewModel: ArticleListViewModel = hiltViewModel(),
    onEditArticle: ((Long) -> Unit)? = null
) = LibraryUiTheme {
    val sort by viewModel.librarySort.collectAsStateWithLifecycle()
    val sortMenuExpanded by viewModel.sortMenuExpanded.collectAsStateWithLifecycle()
    val libraryItems by viewModel.libraryItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 只在关键词真正变化时回到顶部；跳过首次发射，否则从阅读页返回或旋屏时
    // 会覆盖 rememberLazyListState 已恢复的滚动位置。
    LaunchedEffect(viewModel, listState) {
        viewModel.searchQuery
            .map { it.trim() }
            .distinctUntilChanged()
            .drop(1)
            .collect { listState.scrollToItem(0) }
    }
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val isImporting = importState is ImportState.Running
    var articleToDelete by remember(viewModel) { mutableStateOf<ArticleSummary?>(null) }
    var bookToDelete by remember(viewModel) { mutableStateOf<BookEntity?>(null) }
    var showImportDialog by remember(viewModel) { mutableStateOf(false) }
    var showPasteDialog by remember(viewModel) { mutableStateOf(false) }
    val snackbarHostState = remember(viewModel) { SnackbarHostState() }
    val context = LocalContext.current

    // 文件选择器：用 OpenDocument 而非 GetContent——前者接受 MIME 数组、明确走系统文档
    // 选择器，适合多格式；GetContent 在部分 ROM 上不走 SAF，可能路由到图库。
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importFromFile(it)
        }
    }

    // 单一事件流：不再为「导入成功」加第三个 collector（会放大 Snackbar 顺序竞争）
    LaunchedEffect(viewModel, snackbarHostState, context) {
        viewModel.uiEvent.collect { event ->
            val message = when (event) {
                is ArticleListUiEvent.ImportSucceeded -> ErrorMessageMapper.mapImportSuccess(
                    context,
                    event.title,
                    event.exceedsFullExplanationLimit
                )

                is ArticleListUiEvent.BookImportSucceeded -> context.getString(
                    R.string.book_import_succeeded,
                    event.title,
                    event.chapterCount
                )

                is ArticleListUiEvent.ImportFailed ->
                    ErrorMessageMapper.mapImportFailure(context, event.failure)

                is ArticleListUiEvent.DeleteFailed ->
                    context.getString(R.string.error_delete_article_failed)

                ArticleListUiEvent.SortPreferencesFailed ->
                    context.getString(R.string.library_sort_save_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // 导入方式选择对话框
    if (showImportDialog) {
        ImportDialog(
            onDismiss = { showImportDialog = false },
            onImportFile = {
                filePickerLauncher.launch(ImportFormatDetector.PICKER_MIME_TYPES)
            },
            onImportPaste = {
                showPasteDialog = true
            }
        )
    }

    // 粘贴文本对话框
    if (showPasteDialog) {
        PasteTextDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { title, content ->
                viewModel.importFromPaste(title, content)
            }
        )
    }

    articleToDelete?.let { article ->
        AlertDialog(
            onDismissRequest = { articleToDelete = null },
            title = { Text(stringResource(R.string.article_delete_title)) },
            text = {
                Text(stringResource(R.string.article_delete_confirmation, article.title))
            },
            confirmButton = {
                TextButton(onClick = {
                    articleToDelete?.let { pendingArticle ->
                        articleToDelete = null
                        viewModel.deleteArticle(pendingArticle)
                    }
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { articleToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text(stringResource(R.string.book_delete_title)) },
            text = {
                // 明确告知生词会保留：删一本 500 章的书会连带删掉 500 篇章节正文，
                // 用户有理由担心自己在书里查过的词跟着消失。deleteBookCascade 会
                // 先解绑再删，所以这个承诺是真的。
                Text(stringResource(R.string.book_delete_confirmation, book.title))
            },
            confirmButton = {
                TextButton(onClick = {
                    bookToDelete?.let { pendingBook ->
                        bookToDelete = null
                        viewModel.deleteBook(pendingBook)
                    }
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LibraryHeader(
                isImporting = isImporting,
                onImport = { if (!isImporting) showImportDialog = true },
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::updateSearchQuery,
                count = libraryItems.size,
                sort = sort,
                sortMenuExpanded = sortMenuExpanded,
                onSortMenuExpandedChange = viewModel::setSortMenuExpanded,
                onSortField = viewModel::setSortField,
                onSortDirection = viewModel::setSortDirection
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
            if (libraryItems.isEmpty()) {
                // 空态带一行格式说明：让用户一眼知道支持哪些格式，否则挑错文件时
                // 更可能认为是 app 坏了而不是格式不合。
                //
                // 这里原先写着「整本 EPUB 只覆盖约三分之一的真实公版书」——那是按段落
                // 边界切分之前的数字。现在 32 本真实语料里 31 本可导入（97%，见 ADR-013），
                // 唯一失败的《尤利西斯》是因为那段 21,381 字符的独白没有任何句子边界可切。
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp)
                        .padding(horizontal = 32.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = stringResource(
                            if (searchQuery.isBlank()) R.string.articles_empty
                            else R.string.library_search_empty
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(
                            if (searchQuery.isBlank()) R.string.articles_empty_supporting
                            else R.string.library_search_empty_supporting
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)
                ) {
                    // Separate lazy items form one rounded group without eagerly composing the library.
                    itemsIndexed(libraryItems, key = { _, item -> item.listKey }) { index, item ->
                        val first = index == 0
                        val last = index == libraryItems.lastIndex
                        val shape = RoundedCornerShape(
                            topStart = if (first) 12.dp else 0.dp,
                            topEnd = if (first) 12.dp else 0.dp,
                            bottomStart = if (last) 12.dp else 0.dp,
                            bottomEnd = if (last) 12.dp else 0.dp
                        )
                        Surface(
                            shape = shape,
                            modifier = Modifier.animateItemPlacement().clip(shape),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column {
                                when (item) {
                                    is LibraryItem.Article -> LibraryRow(
                                        title = item.title,
                                        subtitle = librarySubtitle(item),
                                        isBook = false,
                                        onClick = { onArticleClick(item.article.id) },
                                        onDelete = { articleToDelete = item.article },
                                        onEdit = onEditArticle?.let { edit -> { edit(item.article.id) } }
                                    )
                                    is LibraryItem.Book -> LibraryRow(
                                        title = item.title,
                                        subtitle = librarySubtitle(item),
                                        isBook = true,
                                        onClick = { onBookClick(item.book.id) },
                                        onDelete = { bookToDelete = item.book }
                                    )
                                }
                                if (!last) Divider(
                                    modifier = Modifier.padding(start = 80.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            // 覆盖在内容之上、不拦触摸：导入期间用户仍可滚动书库或进入已有书。
            // 放在这个 Box 的最后一个子节点，所以画在列表之上；Center 对齐与本 Box
            // 的 TopCenter 默认值无关。
            key(viewModel) {
                ImportStatusOverlay(
                    state = importState,
                    onFinishedShown = viewModel::acknowledgeImport,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    isImporting: Boolean,
    onImport: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    count: Int,
    sort: LibrarySort,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortField: (LibrarySortField) -> Unit,
    onSortDirection: (LibrarySortDirection) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val searchDescription = stringResource(R.string.library_search_hint)
    val importDescription = stringResource(
        if (isImporting) R.string.library_importing else R.string.import_article
    )

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = onImport,
                    enabled = !isImporting,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        disabledContentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = importDescription }
                ) {
                    // 导入中不在按钮里再放一个 spinner：屏幕中央的 ImportStatusOverlay 已经
                    // 在表达「正在工作」，两处同时转正是那种廉价的加载感。按钮仍然禁用，
                    // 且 contentDescription 会切成「正在导入」，无障碍信息不丢。
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
            Text(
                text = stringResource(R.string.nav_articles),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 8.dp)
                    .semantics { heading() }
            )
            TextField(
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                // 无障碍名称由 placeholder 提供；再设 contentDescription 会在有输入时重复朗读提示。
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LIBRARY_SEARCH_TAG),
                placeholder = { Text(searchDescription) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.library_search_clear)
                            )
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
            val options = listOf(
                LibrarySortField.LAST_READ to stringResource(R.string.library_sort_last_read),
                LibrarySortField.ADDED to stringResource(R.string.library_sort_created),
                LibrarySortField.TITLE to stringResource(R.string.library_sort_title),
                LibrarySortField.PROGRESS to stringResource(R.string.library_sort_progress)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.library_reading_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                SortMenuButton(
                    label = stringResource(R.string.library_sort_by, options.first { it.first == sort.field }.second),
                    options = options,
                    selectedField = sort.field,
                    ascending = sort.direction == LibrarySortDirection.ASCENDING,
                    expanded = sortMenuExpanded,
                    onExpandedChange = onSortMenuExpandedChange,
                    onFieldSelected = onSortField,
                    preferBelowAnchor = true,
                    onAscendingSelected = { ascending ->
                        onSortDirection(if (ascending) LibrarySortDirection.ASCENDING else LibrarySortDirection.DESCENDING)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    isBook: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val moreActions = stringResource(R.string.library_more_actions, title)
    Box {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = moreActions,
                    onLongClick = { showMenu = true }
                ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { LibraryThumbnail(isBook) },
            headlineContent = {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (!isBook && onEdit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit_article)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { showMenu = false; onEdit() }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(if (isBook) R.string.book_delete_title else R.string.article_delete_title),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { showMenu = false; onDelete() }
            )
        }
    }
}

@Composable
private fun LibraryThumbnail(isBook: Boolean) {
    // Importers do not persist cover images. Keep an honest placeholder rather than a fake cover.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.size(width = 48.dp, height = 66.dp).clearAndSetSemantics { }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (isBook) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun librarySubtitle(item: LibraryItem): String {
    val timestamp = (item.lastReadAt ?: item.createdAt).coerceAtLeast(0L)
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, LocalDate.now()).coerceAtLeast(0L)
    val relative = when (days) {
        0L -> stringResource(R.string.library_today)
        1L -> stringResource(R.string.library_yesterday)
        else -> stringResource(R.string.library_days_ago, days)
    }
    val progress = if (item.lastReadAt == null) stringResource(R.string.library_unread)
        else stringResource(R.string.library_read_percent, (item.progress * 100).roundToInt())
    return stringResource(R.string.library_reading_metadata, relative, progress)
}
