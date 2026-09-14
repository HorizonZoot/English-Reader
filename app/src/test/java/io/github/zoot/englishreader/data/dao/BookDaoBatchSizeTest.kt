package io.github.zoot.englishreader.data.dao

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [BookDao.VOCABULARY_BATCH] 的参数展开安全边界。
 *
 * 这个约束不在 SQL 里，也不在类型系统里，靠的是一个常量取值恰好落在
 * SQLite 的变量上限之内。删书路径的去重 SQL 把 `:articleIds` 绑定**两次**，
 * 所以真正的约束是 2N ≤ 999（API 24–30 的 `SQLITE_MAX_VARIABLE_NUMBER`）。
 *
 * 没有这个测试，未来任何人把 batch 调大到 500、或给那条 SQL 再加一处
 * `:articleIds`，都会在 API 24–30 上把「删一本满章节的书」变成永久失败——
 * 而在 API 31+ 的模拟器上跑测试完全看不出来（新 SQLite 上限是 32766）。
 */
class BookDaoBatchSizeTest {

    /** 去重 SQL 里 `:articleIds` 出现的次数。改 SQL 时必须同步改这里。 */
    private val bindingsPerBatch = 2

    /** API 24–30 的 SQLite 变量上限。minSdk = 24，所以这是必须满足的那一个。 */
    private val legacySqliteVariableLimit = 999

    @Test
    fun vocabularyBatch_staysWithinLegacySqliteVariableLimit() {
        val worstCaseParameters = BookDao.VOCABULARY_BATCH * bindingsPerBatch

        assertTrue(
            "batch ${BookDao.VOCABULARY_BATCH} x $bindingsPerBatch bindings = " +
                "$worstCaseParameters parameters, exceeds the API 24-30 limit of " +
                "$legacySqliteVariableLimit",
            worstCaseParameters <= legacySqliteVariableLimit
        )
    }
}
