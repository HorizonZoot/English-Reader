package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 查词 SQL 必须命中主键索引，而不是全表扫描。
 *
 * ## 为什么需要这条判据
 *
 * `dictionary.word` 是 `TEXT PRIMARY KEY`，其隐式唯一索引的 collation 是 BINARY。SQLite 只在
 * 比较 collation 与索引 collation 一致时才用索引，所以给查询加 `COLLATE NOCASE` 会静默退化成
 * 全表扫描 —— **不报错、不告警，只是变慢**。
 *
 * `DictionaryDao.lookup` 原来就带 `COLLATE NOCASE`，而它的 KDoc 早就写明了这个代价，还留了
 * 一句「移除前需实跑 `EXPLAIN QUERY PLAN` 确认」。那个待办一直没人做，直到外部审计跑了它：
 *
 * ```text
 * 词条数     NOCASE       BINARY
 *   7,005    0.163 ms     0.0014 ms
 * 760,000   21.599 ms     0.0014 ms
 * ```
 *
 * 7,000 词时「影响有限」的判断本来成立；扩展词库把差距放大到约 15,000 倍。
 *
 * ## 断言的是查询计划，不是耗时
 *
 * 耗时判据在 CI 上不稳定，而且宿主 SQLite 的毫秒数不能当设备验收值。查询计划是确定的：
 * 走索引就是 `SEARCH ... USING INDEX`，退化就是 `SCAN`。加回 `COLLATE NOCASE` 会让
 * [lookupQuery_usesPrimaryKeyIndexNotFullScan] 变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryQueryPlanTest {

    private lateinit var db: EnglishReaderDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 生产查词 SQL 的执行计划必须是索引查找。
     *
     * SQL 字面量与 `DictionaryDao.lookup` 的 `@Query` 保持一致 —— 这是这条测试的已知弱点：
     * 它验的是「这条 SQL 走索引」，不是「DAO 真的用了这条 SQL」。两者若分叉，测试仍绿。
     * 无法直接取到 Room 生成的 SQL，所以由 [lookupQuery_matchesTheDaoAnnotation] 从注解
     * 反向核对字面量一致性来补这个缺口。
     */
    @Test
    fun lookupQuery_usesPrimaryKeyIndexNotFullScan() {
        val plan = explain("SELECT * FROM dictionary WHERE word = ? LIMIT 1")

        assertTrue(
            "lookup must hit the primary-key index; got: $plan. A SCAN here means the query " +
                "degraded to a full table scan -- with the 760k-entry extended pack that is " +
                "about 21 ms per lookup, and a single long-press fires 4-6 of them.",
            plan.contains("USING INDEX") || plan.contains("USING PRIMARY KEY")
        )
        // 措辞必须容错：Room 内置的 SQLite 输出 `SCAN TABLE dictionary`，而命令行 sqlite3
        // 输出 `SCAN dictionary`。我第一版断言 `!contains("SCAN dictionary")`，它在 Room 下
        // **永远为真** —— 是个空转断言，靠下面那条对照才暴露出来。
        assertTrue(
            "plan should not be a full scan; got: $plan",
            !plan.contains("SCAN", ignoreCase = true)
        )
    }

    /**
     * 对照：加上 `COLLATE NOCASE` 确实退化成全表扫描。
     *
     * 这条不测生产代码，它测的是**上一条断言有判别力**。若某天 SQLite 改了行为、让 NOCASE
     * 也能用索引，这条会红，届时上一条就失去了意义 —— 那是需要知道的事。
     */
    @Test
    fun collateNocase_degradesToFullScan_provingTheAssertionHasTeeth() {
        val plan = explain("SELECT * FROM dictionary WHERE word = ? COLLATE NOCASE LIMIT 1")

        assertTrue(
            "COLLATE NOCASE was expected to defeat the BINARY primary-key index; got: $plan. " +
                "If this no longer holds, lookupQuery_usesPrimaryKeyIndexNotFullScan no longer " +
                "guards anything.",
            plan.contains("SCAN", ignoreCase = true) && !plan.contains("USING INDEX")
        )
    }

    /**
     * 上面那条 SQL 字面量必须与 DAO 注解里的一致。
     *
     * 读源码而非字节码：Room 生成的实现类在测试期不可靠地可达，而注解文本是确定的。
     * 这条防的是「测试里的 SQL 和 DAO 里的 SQL 分叉」——那样查询计划测试会守着一条
     * 没人执行的语句。
     */
    @Test
    fun lookupQuery_matchesTheDaoAnnotation() {
        val source = java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/io/github/zoot/englishreader/data/dao/DictionaryDao.kt"
        )
        assertTrue("cannot read ${source.path}", source.isFile)

        val annotation = source.readLines()
            .firstOrNull { it.contains("@Query") && it.contains("FROM dictionary WHERE word") }
            ?: error("lookup @Query not found in DictionaryDao.kt")

        assertTrue(
            "the DAO query must not contain COLLATE NOCASE; found: ${annotation.trim()}",
            !annotation.contains("COLLATE NOCASE")
        )
        assertTrue(
            "the DAO query text drifted from what this test explains; found: ${annotation.trim()}",
            annotation.contains("SELECT * FROM dictionary WHERE word = :word LIMIT 1")
        )
    }

    /** 去掉 NOCASE 之后查词仍然能命中 —— 性能修复不能把功能弄坏。 */
    @Test
    fun lookup_stillFindsLowercasedWords() = runBlocking {
        db.dictionaryDao().replaceAll(
            listOf(
                DictionaryEntry(word = "handwriting", phonetic = null, chinese = "n. 笔迹", english = null),
                DictionaryEntry(word = "lives", phonetic = null, chinese = "n. 生活", english = null)
            )
        )

        assertNotNull("exact lowercase lookup must still work", db.dictionaryDao().lookup("handwriting"))
        assertEquals(2, db.dictionaryDao().getCount())
    }

    /**
     * 大写查询**不再**命中 —— 这是去掉 NOCASE 的行为代价，钉住它。
     *
     * 调用链保证传入的词已 lowercase（`lookupOffline` 的 `cleanWord`、`WordLemmatizer` 的
     * 候选），所以生产上不会走到这里。但若将来有人新增一个不归一化的调用方，这条注释和
     * 判据会告诉他为什么查不到。
     */
    @Test
    fun lookup_isCaseSensitiveByDesign() = runBlocking {
        db.dictionaryDao().replaceAll(
            listOf(DictionaryEntry(word = "handwriting", phonetic = null, chinese = "n. 笔迹", english = null))
        )

        assertEquals(
            "callers must lowercase before calling lookup; the DAO no longer folds case " +
                "because COLLATE NOCASE defeated the primary-key index",
            null,
            db.dictionaryDao().lookup("Handwriting")
        )
    }

    private fun explain(sql: String): String {
        val cursor = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", arrayOf("probe"))
        cursor.use {
            val rows = mutableListOf<String>()
            while (it.moveToNext()) {
                rows += it.getString(it.columnCount - 1)
            }
            return rows.joinToString(" | ")
        }
    }
}
