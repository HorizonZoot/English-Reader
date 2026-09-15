package io.github.zoot.englishreader.ui.screen.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分组标识到 LazyColumn item key 的映射（[listKey]）。
 *
 * 纯逻辑，不需要 Compose/Robolectric 运行时——原先挂在 VocabularyScreenTest
 * （@RunWith(RobolectricTestRunner)）里，但它并不组合任何界面。
 *
 * 锁定的是「不同分组必须取得不同 key」：不同维度下存在取值相同的分组，不带前缀就会
 * 撞 key，令列表复用错行的状态。
 *
 * 另一条相关约束——key 必须是能存进 Bundle 的类型（直接用 [VocabularyGroupId] 实例会
 * 让 LazySaveableStateHolder 抛 `IllegalArgumentException: Type of the key Today is not
 * supported`，列表首次测量即崩掉整个生词本）——只有真实渲染才观察得到，由
 * VocabularyScreenTest 覆盖。
 */
class VocabularyGroupIdTest {

    @Test
    fun everyGroupId_mapsToItsOwnKey() {
        val ids = listOf(
            VocabularyGroupId.Today,
            VocabularyGroupId.Yesterday,
            VocabularyGroupId.ThisWeek,
            VocabularyGroupId.OlderDate("2024-07-14"),
            VocabularyGroupId.Alphabet("A")
        )

        assertEquals(ids.size, ids.map { it.listKey }.toSet().size)
    }
}
