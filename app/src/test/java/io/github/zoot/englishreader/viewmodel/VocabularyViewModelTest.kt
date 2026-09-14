package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * VocabularyViewModel 单元测试
 *
 * 覆盖分组逻辑：
 * - 默认按时间分组
 * - 切换到字母分组后 groups 输出按首字母聚合
 * - 切换分组重置展开状态
 * - toggleGroup 展开/收起
 * - deleteVocabulary 委托 repository
 */
class VocabularyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var vocabularyRepository: VocabularyRepository
    private lateinit var viewModel: VocabularyViewModel

    private fun vocab(word: String, id: Long): VocabularyEntity =
        VocabularyEntity(id = id, word = word, articleId = 1L)

    private fun setupWith(words: List<VocabularyEntity>) {
        vocabularyRepository = mockk(relaxed = true)
        io.mockk.every { vocabularyRepository.getAllVocabulary() } returns flowOf(words)
        viewModel = VocabularyViewModel(vocabularyRepository)
    }

    @Before
    fun setup() {
        setupWith(emptyList())
    }

    @Test
    fun default_groupType_isByTime() = runTest {
        assertEquals(GroupType.ByTime, viewModel.groupType.value)
    }

    @Test
    fun switchToAlphabet_groupsWordsByFirstLetter() = runTest {
        setupWith(
            listOf(
                vocab("apple", 1),
                vocab("banana", 2),
                vocab("avocado", 3)
            )
        )

        viewModel.groups.test {
            // 先跳过时间分组的初始输出
            awaitItem()
            viewModel.switchGroupType(GroupType.ByAlphabet)

            // 等到出现字母分组结果（A、B）
            var groups = awaitItem()
            while (groups.none { it.id == VocabularyGroupId.Alphabet("A") }) {
                groups = awaitItem()
            }

            val a = groups.first { it.id == VocabularyGroupId.Alphabet("A") }
            assertEquals(2, a.words.size) // apple, avocado
            assertEquals(listOf("apple", "avocado"), a.words.map { it.word })
            val b = groups.first { it.id == VocabularyGroupId.Alphabet("B") }
            assertEquals(1, b.words.size) // banana
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun switchGroupType_resetsExpandedState() = runTest {
        assertEquals(setOf(VocabularyGroupId.Today), viewModel.expandedGroups.value)

        viewModel.switchGroupType(GroupType.ByAlphabet)
        // 切到字母分组，展开状态清空
        assertTrue(viewModel.expandedGroups.value.isEmpty())

        viewModel.switchGroupType(GroupType.ByTime)
        assertEquals(setOf(VocabularyGroupId.Today), viewModel.expandedGroups.value)
    }

    @Test
    fun toggleGroup_addsAndRemovesTitle() = runTest {
        viewModel.switchGroupType(GroupType.ByAlphabet) // 展开集合清空

        val groupId = VocabularyGroupId.Alphabet("A")
        viewModel.toggleGroup(groupId)
        assertTrue(groupId in viewModel.expandedGroups.value)

        viewModel.toggleGroup(groupId)
        assertTrue(groupId !in viewModel.expandedGroups.value)
    }

    @Test
    fun deleteVocabulary_delegatesToRepository() = runTest {
        val entity = vocab("delete-me", 99)

        viewModel.deleteVocabulary(entity)

        coVerify(exactly = 1) { vocabularyRepository.deleteVocabulary(entity) }
    }
}
