package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.viewmodel.BookTocViewModel

/**
 * 书籍目录页。
 *
 * 章节数可达 [io.github.zoot.englishreader.data.importer.ImportBudget.MAX_BOOK_CHAPTERS]（500），
 * 所以必须 LazyColumn。首次进入时滚动到上次读过的章节：一本 500 章的书如果每次都从
 * 第一章开始滚，用户永远找不到自己读到哪。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTocScreen(
    onBack: () -> Unit,
    onChapterClick: (Long) -> Unit,
    viewModel: BookTocViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 只自动滚动一次：用户手动滚开后再触发会把他拽回去。
    var hasScrolledToProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.lastReadArticleId, state.chapters.size) {
        if (hasScrolledToProgress) return@LaunchedEffect
        val target = state.lastReadArticleId ?: return@LaunchedEffect
        val index = state.chapters.indexOfFirst { it.articleId == target }
        if (index >= 0) {
            hasScrolledToProgress = true
            listState.scrollToItem(index)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.book?.title ?: stringResource(R.string.book_toc_title),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.chapters.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.resumeTargetArticleId()?.let(onChapterClick) },
                    icon = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                    },
                    text = { Text(stringResource(R.string.book_continue_reading)) }
                )
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.chapters.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.book_toc_empty))
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.chapters, key = { it.id }) { chapter ->
                    val isLastRead = chapter.articleId == state.lastReadArticleId
                    ListItem(
                        headlineContent = {
                            Text(
                                // navigationTitle 为 null 时用序号兜底：无 NAV/NCX 的书
                                // 所有章节都没有标题，此时「第 N 章」比空白有用。
                                text = chapter.navigationTitle
                                    ?: stringResource(
                                        R.string.chapter_position,
                                        chapter.chapterIndex + 1,
                                        state.chapters.size
                                    ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    R.string.chapter_position,
                                    chapter.chapterIndex + 1,
                                    state.chapters.size
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            // 高亮上次读到的章节。背景色而非选中状态：ListItem 的
                            // selected 语义会让 TalkBack 播报「已选中」，而这里不是选择。
                            .background(
                                if (isLastRead) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                            .clickable { onChapterClick(chapter.articleId) }
                    )
                }
            }
        }
    }
}
