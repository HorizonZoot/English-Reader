package io.github.zoot.englishreader.data.repository

import android.content.Context
import android.util.Log
import io.github.zoot.englishreader.data.dictionary.BuiltInDictionary
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.remote.dictionary.DictionaryApiService
import io.github.zoot.englishreader.util.WordLemmatizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线查词结果。
 *
 * @param entry 命中的词典条目（其 word 为原形）
 * @param inflectedForm 若结果是经词形还原命中的，则为用户查询的原始变形词（如 "lives"）；
 *   直接命中（未还原）时为 null。上层据此在界面标注"xxx 的原形"。
 * @param alternateEntries 歧义变形词（lives/leaves）的其余合法原形条目。两种词性解释都成立且
 *   都在词库时并列展示，由用户按上下文判断；非歧义词为空。
 */
data class OfflineLookupResult(
    val entry: DictionaryEntry,
    val inflectedForm: String? = null,
    val alternateEntries: List<DictionaryEntry> = emptyList()
)

/**
 * 词典 Repository
 *
 * 集成离线词典和在线词典 API：
 * 1. 优先查询离线词典（快速、支持中文）
 * 2. 降级到 Free Dictionary API（详细、纯英文）
 */
@Singleton
class DictionaryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictionaryDao: DictionaryDao,
    private val dictionaryApiService: DictionaryApiService
) {

    // 互斥锁：防止并发初始化导致 race condition
    private val initializationMutex = Mutex()

    // 初始化状态标记：用于冷启动时等待初始化完成
    @Volatile
    private var isInitialized = false

    // 空解析失败计数：assets 解析为空且 DB 也空时允许有限次重试（应对瞬时读取失败），
    // 达到上限后放弃标记为已初始化，避免每次查词都重复解析 1.4MB 词库文件空转。
    private var emptyInitAttempts = 0

    /**
     * 查询单词（离线优先）
     *
     * 查询前确保词典已初始化，避免冷启动竞态导致明明有词却查不到。
     * 初始化失败时静默降级（返回 null → 上层走在线 API），不影响查词流程。
     *
     * @return DictionaryEntry 如果离线词典有结果，否则返回 null
     */
    suspend fun lookupOffline(word: String): OfflineLookupResult? {
        // 确保初始化完成（幂等）；失败不阻断查询，降级到在线 API
        try {
            ensureInitialized()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DictionaryRepository", "Ensure init failed before lookup, fallback to online", e)
        }

        return withContext(Dispatchers.IO) {
            try {
                val cleanWord = word.trim().lowercase()

                // 1) 先按原词精确查（覆盖绝大多数原形词，最快路径）
                dictionaryDao.lookup(cleanWord)?.let {
                    return@withContext OfflineLookupResult(it, inflectedForm = null)
                }

                // 2) 原词未命中：词库只存原形（ECDICT 精简版无 exchange 字段），
                //    对变形词（lives/running/children）做词形还原后逐个候选重查，
                //    命中即返回并标注原始变形词，供界面显示"xxx 的原形"。
                //
                //    歧义词（lives→life/live、leaves→leaf/leave）例外：两种词性解释都合法，
                //    规则法无上下文无法判别，故收集所有命中的原形并列返回，交由用户判断。
                val ambiguousBases = WordLemmatizer.ambiguousBases(cleanWord)
                if (ambiguousBases.isNotEmpty()) {
                    val hits = ambiguousBases.mapNotNull { dictionaryDao.lookup(it) }
                    if (hits.isNotEmpty()) {
                        return@withContext OfflineLookupResult(
                            entry = hits.first(),
                            inflectedForm = cleanWord,
                            alternateEntries = hits.drop(1)
                        )
                    }
                }

                for (candidate in WordLemmatizer.candidates(cleanWord)) {
                    dictionaryDao.lookup(candidate)?.let {
                        return@withContext OfflineLookupResult(it, inflectedForm = cleanWord)
                    }
                }

                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DictionaryRepository", "Offline lookup failed", e)
                null
            }
        }
    }

    /**
     * 查询在线词典（Free Dictionary API）。
     *
     * ## 发布门禁（未决）
     *
     * Free Dictionary API 在中国大陆不可访问，而 [ReadingViewModel] 在离线未命中后**无条件**
     * 调用此方法：目标用户实际会等满 10 秒连接超时，再看到「网络错误」。词形还原上线后
     * 离线命中率已大幅提升，剩余未命中多为生僻词/专有名词，在线查也未必有结果。
     *
     * 发布前必须二选一：MVP 默认不访问在线词典；或提供「在线查词」开关且默认关闭。
     * 此项与阶段 6 的 AI 接入无架构关系，不可合并处理。
     */
    suspend fun lookupOnline(word: String) = dictionaryApiService.getWordDefinition(word)

    /**
     * 从 assets 初始化离线词典
     *
     * 在 Application.onCreate() 后台调用做预热。等价于 [ensureInitialized]，
     * 但会重新抛出异常以便启动流程记录初始化失败。
     */
    suspend fun initializeFromAssets() {
        ensureInitialized()
    }

    /**
     * 确保词典已初始化（幂等）。
     *
     * - 首次调用执行初始化；并发调用通过 Mutex 串行化，后到者等待前者完成后走快速路径直接返回。
     * - 使用 [DictionaryDao.replaceAll]（@Transaction）保证 deleteAll + 分批插入的原子性：
     *   进程中途被杀会整体回滚。下次启动会重新初始化，依据是**版本号未落盘**（版本号在
     *   replaceAll 之后才写），而不是 getCount() 为 0——升级场景下回滚保留的是旧词库条数。
     * - **抛异常**的失败路径不标记 isInitialized，允许后续重试（应对瞬时 DB 锁等）。
     *   注意「解析为空」不走这条：它有独立的有限重试策略，达上限或 DB 已有旧数据时
     *   仍会标记已初始化，避免每次查词都空转解析 1.4MB 词库，详见方法内注释。
     */
    suspend fun ensureInitialized() {
        if (isInitialized) return
        initializationMutex.withLock {
            // 双重检查：等待锁期间可能已被其他协程完成
            if (isInitialized) return@withLock
            withContext(Dispatchers.IO) {
                try {
                    // 按版本号判断是否需要（重新）初始化：词库内容升级（如从 20 词扩到 7005 词）后
                    // 即使数据库已有旧数据（getCount>0）也要强制重建，与样本文章的版本化刷新同一思路。
                    val prefs = context.getSharedPreferences(DICT_PREFS, Context.MODE_PRIVATE)
                    val installedVersion = prefs.getInt(KEY_DICT_VERSION, 0)
                    val count = dictionaryDao.getCount()
                    if (installedVersion >= DICT_VERSION && count > 0) {
                        Log.d("DictionaryRepository", "Dictionary up-to-date (v$installedVersion, $count entries)")
                        repairLiteralEscapesOnce(prefs)
                        isInitialized = true
                        return@withContext
                    }

                    Log.d("DictionaryRepository", "Initializing dictionary from assets (installed v$installedVersion -> v$DICT_VERSION)...")

                    // 读取 TSV 文件
                    val entries = parseAssetTsv()

                    if (entries.isEmpty()) {
                        // 解析为空可能是瞬时读取失败（parseAssetTsv 吞异常返回空）或资源缺失。一律不写版本号。
                        // - DB 已有旧词库：标记已初始化，沿用旧数据、避免每次查词重复空解析。
                        // - DB 也空：允许有限次重试（应对瞬时失败），达上限后仍标记已初始化，
                        //   避免每次查词都重复解析 1.4MB 词库文件空转。
                        emptyInitAttempts++
                        val giveUp = count == 0 && emptyInitAttempts >= MAX_EMPTY_INIT_ATTEMPTS
                        Log.w(
                            "DictionaryRepository",
                            "No dictionary data parsed (db=$count, attempt=$emptyInitAttempts); " +
                                if (count > 0 || giveUp) "marking initialized" else "will retry"
                        )
                        if (count > 0 || giveUp) isInitialized = true
                        return@withContext
                    }
                    // 成功解析后重置失败计数
                    emptyInitAttempts = 0

                    // 原子性替换：@Transaction 保证 deleteAll + 分批插入要么全成功要么全回滚
                    dictionaryDao.replaceAll(entries)
                    prefs.edit().putInt(KEY_DICT_VERSION, DICT_VERSION).apply()
                    isInitialized = true  // 成功后才标记
                    Log.d("DictionaryRepository", "Dictionary initialized: ${entries.size} entries (v$DICT_VERSION)")
                } catch (e: Exception) {
                    Log.e("DictionaryRepository", "Failed to initialize dictionary", e)
                    // 不标记 isInitialized，允许重试；重新抛出以便上层感知
                    throw e
                }
            }
        }
    }

    /**
     * 一次性修掉已入库释义里的字面换行转义。
     *
     * 版本已足够的扩展词库也要执行修正；不能提高 DICT_VERSION 强制走 assets 重建，
     * 那会覆盖用户已安装的词库。独立 marker 只在 SQL 成功后写入；SQL 本身幂等。
     * 普通失败保留本次查词能力，下一 Repository 实例根据未写入的 marker 重试。
     */
    private suspend fun repairLiteralEscapesOnce(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_ESCAPES_REPAIRED, false)) return
        try {
            val fixed = dictionaryDao.decodeLiteralEscapes()
            prefs.edit().putBoolean(KEY_ESCAPES_REPAIRED, true).apply()
            if (fixed > 0) {
                Log.d("DictionaryRepository", "Decoded literal escapes in $fixed dictionary entries")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不写标记：下次启动重试。查词不受影响，所以不上抛。
            Log.w("DictionaryRepository", "Literal-escape repair failed; will retry next launch", e)
        }
    }

    /**
     * 解析 TSV 格式词典文件
     *
     * 格式：word\tphonetic\tchinese\tenglish
     * 注意：字段内不应包含 Tab 字符，中文释义用"；"分隔（不支持转义）
     */
    private fun parseAssetTsv(): List<DictionaryEntry> = try {
        BuiltInDictionary.read(context)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.e("DictionaryRepository", "Failed to read built-in dictionary", error)
        emptyList()
    }

    /**
     * 获取词典条目数量（用于调试）
     */
    suspend fun getDictionaryCount(): Int = withContext(Dispatchers.IO) {
        dictionaryDao.getCount()
    }

    companion object {
        private const val DICT_PREFS = "dictionary_prefs"
        private const val KEY_DICT_VERSION = "dict_version"

        /** 与词库版本独立，修正成功后才写入。 */
        private const val KEY_ESCAPES_REPAIRED = "literal_escapes_repaired"

        /**
         * 词库版本号。每次更换/扩充 assets/dict_base.tsv 都应 +1，
         * 触发老用户设备上的词库重建（即使数据库已有旧数据）。
         * v1 = 20 词测试集；v2 = ECDICT COCA 前 8000 常用词（约 7005 词）。
         */
        private const val DICT_VERSION = BuiltInDictionary.VERSION

        /** assets 解析为空且 DB 也空时的最大重试次数，超过后放弃避免每次查词空转解析 */
        private const val MAX_EMPTY_INIT_ATTEMPTS = 3
    }
}
