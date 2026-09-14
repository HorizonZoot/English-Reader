package io.github.zoot.englishreader.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.importer.ImportFormatDetector
import io.github.zoot.englishreader.ui.dialog.ImportDialog
import io.github.zoot.englishreader.ui.dialog.PasteTextDialog
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.util.ErrorMessageMapper
import io.github.zoot.englishreader.viewmodel.ArticleListUiEvent
import io.github.zoot.englishreader.viewmodel.ArticleListViewModel
import io.github.zoot.englishreader.viewmodel.LibraryItem

/**
 * 文章列表界面
 */
@Composable
fun ArticleListScreen(
    onArticleClick: (Long) -> Unit,
    onBookClick: (Long) -> Unit,
    viewModel: ArticleListViewModel = hiltViewModel()
) = ArticleUiTheme {
    val libraryItems by viewModel.libraryItems.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    var articleToDelete by remember { mutableStateOf<ArticleEntity?>(null) }
    var bookToDelete by remember { mutableStateOf<BookEntity?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
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
    LaunchedEffect(Unit) {
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
                onImport = { if (!isImporting) showImportDialog = true }
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
                // 空态带一行格式说明：整本 EPUB 只覆盖约三分之一的真实公版书
                // （见 ADR-013），单靠失败提示要用户连试几本才明白，而那时候
                // 他们更可能认为是 app 坏了而不是版本不合。
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
                        text = stringResource(R.string.articles_empty),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.articles_empty_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp)
                ) {
                    item(key = "library-section") {
                        Text(
                            text = stringResource(R.string.library_section_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 8.dp)
                                .semantics { heading() }
                        )
                    }
                    // 两种实体的数字 id 会撞号，条目状态必须跟随带类型前缀的 listKey。
                    items(libraryItems, key = { it.listKey }) { item ->
                        when (item) {
                            is LibraryItem.Article -> LibraryRow(
                                title = item.article.title,
                                subtitle = null,
                                isBook = false,
                                onClick = { onArticleClick(item.article.id) },
                                onDelete = { articleToDelete = item.article }
                            )

                            is LibraryItem.Book -> LibraryRow(
                                title = item.book.title,
                                subtitle = stringResource(
                                    R.string.book_chapters_count,
                                    item.book.chapterCount
                                ),
                                isBook = true,
                                onClick = { onBookClick(item.book.id) },
                                onDelete = { bookToDelete = item.book }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(isImporting: Boolean, onImport: () -> Unit) {
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
                .padding(horizontal = 24.dp, vertical = 12.dp)
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
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            }
            Text(
                text = stringResource(R.string.nav_articles),
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 8.dp)
                    .semantics { heading() }
            )
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String?,
    isBook: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val typeLabel = stringResource(
        if (isBook) R.string.library_item_book else R.string.library_item_article
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 112.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LibraryThumbnail(title = title, typeLabel = typeLabel, isBook = isBook)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isBook) {
                                Icons.AutoMirrored.Outlined.MenuBook
                            } else {
                                Icons.Outlined.Description
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.library_more_actions, title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    if (isBook) R.string.book_delete_title else R.string.article_delete_title
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LibraryThumbnail(title: String, typeLabel: String, isBook: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (isBook) colors.tertiary else colors.tertiaryContainer,
        contentColor = if (isBook) colors.onTertiary else colors.onTertiaryContainer,
        shape = RoundedCornerShape(topStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 3.dp),
        modifier = Modifier
            .size(width = 64.dp, height = 92.dp)
            .clearAndSetSemantics { }
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = typeLabel, fontSize = 8.sp, maxLines = 1)
            Text(
                text = title,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(
                modifier = Modifier.width(18.dp),
                color = LocalContentColor.current.copy(alpha = 0.45f)
            )
        }
    }
}
