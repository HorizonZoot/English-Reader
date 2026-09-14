package io.github.zoot.englishreader.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel

/**
 * 生词本界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(
    viewModel: VocabularyViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val groupType by viewModel.groupType.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_vocabulary)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 分组方式切换标签
            GroupTypeTabs(
                selectedType = groupType,
                onTypeSelected = { viewModel.switchGroupType(it) }
            )

            // 分组列表
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.vocabulary_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEach { group ->
                        item(key = group.id) {
                            GroupHeader(
                                title = vocabularyGroupTitle(group.id),
                                count = group.words.size,
                                isExpanded = group.isExpanded,
                                onClick = { viewModel.toggleGroup(group.id) }
                            )
                        }

                        if (group.isExpanded) {
                            items(group.words, key = { it.id }) { word ->
                                VocabularyCard(
                                    word = word,
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

/**
 * 分组类型切换标签
 */
@Composable
fun GroupTypeTabs(
    selectedType: GroupType,
    onTypeSelected: (GroupType) -> Unit
) {
    TabRow(
        selectedTabIndex = when (selectedType) {
            GroupType.ByTime -> 0
            GroupType.ByAlphabet -> 1
            // ByArticle 已从 UI 中移除，若触发则默认为 ByTime
            GroupType.ByArticle -> 0
        }
    ) {
        Tab(
            selected = selectedType == GroupType.ByTime,
            onClick = { onTypeSelected(GroupType.ByTime) },
            text = { Text(stringResource(R.string.vocabulary_group_by_time)) }
        )
        Tab(
            selected = selectedType == GroupType.ByAlphabet,
            onClick = { onTypeSelected(GroupType.ByAlphabet) },
            text = { Text(stringResource(R.string.vocabulary_group_by_alphabet)) }
        )
        // ByArticle tab 已移除，因为功能未实现
    }
}

/**
 * 分组头部
 */
@Composable
fun GroupHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.vocabulary_group_count, title, count),
                style = MaterialTheme.typography.titleMedium
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (isExpanded) R.string.vocabulary_group_collapse
                    else R.string.vocabulary_group_expand
                ),
                modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
            )
        }
    }
}

/**
 * 单词卡片
 */
@Composable
fun VocabularyCard(
    word: VocabularyEntity,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = word.word,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_vocabulary)
                )
            }
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
