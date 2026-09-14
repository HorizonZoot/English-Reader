package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.zoot.englishreader.data.entity.DictionaryEntry

/**
 * 离线词典 DAO
 */
@Dao
interface DictionaryDao {

    /**
     * 按单词精确查询。
     *
     * ## 契约：调用方必须传入已 lowercase 的词
     *
     * 两侧都已归一化，所以比较不需要 `COLLATE NOCASE`：
     *  - 入库：`DictionaryRepository.parseAssetTsv` 与 `DictionaryPackInstaller.parseCsvLine`
     *    都写 `trim().lowercase()`
     *  - 查询：`DictionaryRepository.lookupOffline` 的 `cleanWord`，以及 `WordLemmatizer`
     *    产出的候选（其入口同样 `trim().lowercase()`）
     *
     * ## 为什么去掉 COLLATE NOCASE
     *
     * `dictionary.word` 是 `TEXT PRIMARY KEY`，其隐式唯一索引的 collation 是 BINARY。
     * SQLite 只在比较 collation 与索引 collation 一致时才用该索引，所以 `COLLATE NOCASE`
     * 让这个查询退化成全表扫描。
     *
     * 这段注释此前就描述了问题，还写着「移除前需实跑 `EXPLAIN QUERY PLAN` 确认」——
     * 外部审计把那个待办真的跑了，我也复现了：
     *
     * ```text
     * NOCASE   -> SCAN dictionary
     * BINARY   -> SEARCH dictionary USING INDEX sqlite_autoindex_dictionary_1 (word=?)
     * ```
     *
     * 单次未命中查询的中位数（宿主内存 SQLite，**不是**设备数据）：
     *
     * ```text
     * 词条数     NOCASE       BINARY
     *   7,005    0.163 ms     0.0014 ms
     * 760,000   21.599 ms     0.0014 ms
     * ```
     *
     * 「7,000 词时影响有限」这个判断本来成立，但扩展词库把差距放大到约 15,000 倍，
     * 而单次长按会触发 4-6 次查询（原词 + 词形还原候选）。
     *
     * `DictionaryQueryPlanTest` 断言查询计划走索引 —— 加回 `COLLATE NOCASE` 会让它红。
     */
    @Query("SELECT * FROM dictionary WHERE word = :word LIMIT 1")
    suspend fun lookup(word: String): DictionaryEntry?

    /**
     * 批量插入词条（用于初始化）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    /**
     * 获取词典总数（用于检查是否已初始化）
     */
    @Query("SELECT COUNT(*) FROM dictionary")
    suspend fun getCount(): Int

    /**
     * 清空词典（用于重新导入）
     */
    @Query("DELETE FROM dictionary")
    suspend fun deleteAll()

    /**
     * 原子性初始化：先清空，再批量插入
     * 使用 @Transaction 保证要么全部成功，要么全部回滚
     */
    @Transaction
    suspend fun replaceAll(entries: List<DictionaryEntry>) {
        deleteAll()
        // 分批只限制单条 INSERT 的绑定参数量，**不能**降低峰值内存：调用方
        // DictionaryRepository.parseAssetTsv 已用 toList() 把全部词条物化成 List 传进来。
        // 真要压低峰值需改成按 Sequence 流式传入 —— 见 replaceAllStreaming。
        entries.chunked(500).forEach { batch ->
            insertAll(batch)
        }
    }

    /**
     * 原子性替换，但由调用方**流式**提供词条。
     *
     * [replaceAll] 要求调用方先把全部词条物化成 `List`。7,000 词无所谓，但 ECDICT 扩展词库
     * 有 76 万条，物化会是几百 MB 的峰值内存。这里把插入动作交给调用方按批调用，峰值只有
     * 一批的大小。
     *
     * 原子性不变：`@Transaction` 覆盖整个 [block]，中途抛异常则 `deleteAll` 与已插入的批次
     * 一起回滚 —— 失败后用户仍保有原来的词库，而不是一张空表。
     *
     * @param block 接收一个插入函数；每次调用插入一批。**不要**在 block 里做网络 IO，
     *   事务持有数据库写锁，长时间占用会阻塞查词。
     */
    @Transaction
    suspend fun replaceAllStreaming(block: suspend (insert: suspend (List<DictionaryEntry>) -> Unit) -> Unit) {
        deleteAll()
        block { batch -> insertAll(batch) }
    }

    /**
     * 就地解码已入库释义里的字面换行转义，返回改动行数。
     *
     * char(92) 避免 Kotlin 与 SQL 双重转义；CRLF 必须先处理，防止留下孤立的回车转义。
     * 仅更新含转义的行，保留主键、音标和 NULL，不重建已安装的扩展词库。
     */
    @Query(
        """
        UPDATE dictionary
           SET chinese = replace(replace(replace(chinese, char(92)||'r'||char(92)||'n', char(10)), char(92)||'n', char(10)), char(92)||'r', char(10)),
               english = replace(replace(replace(english, char(92)||'r'||char(92)||'n', char(10)), char(92)||'n', char(10)), char(92)||'r', char(10))
         WHERE instr(chinese, char(92)||'n') > 0 OR instr(chinese, char(92)||'r') > 0
            OR instr(english, char(92)||'n') > 0 OR instr(english, char(92)||'r') > 0
        """
    )
    suspend fun decodeLiteralEscapes(): Int
}
