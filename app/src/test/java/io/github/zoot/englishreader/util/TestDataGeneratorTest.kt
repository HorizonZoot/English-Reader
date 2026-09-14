package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样本数据必须非空，且每个来源都能被刷新白名单完整定位。 */
class TestDataGeneratorTest {

    @Test
    fun sampleArticles_haveCompleteRefreshableSources() {
        val articles = TestDataGenerator.getSampleArticles()
        assertTrue("refreshSampleArticles requires non-empty samples", articles.isNotEmpty())
        articles.forEach { article ->
            assertTrue("样本文章「${article.title}」的 source 不能为空", !article.source.isNullOrBlank())
        }
        assertEquals(
            "SAMPLE_SOURCES 必须完整覆盖样本来源，否则刷新会留下重复文章",
            articles.mapNotNull { it.source }.toSet(),
            TestDataGenerator.SAMPLE_SOURCES.toSet()
        )
    }
}
