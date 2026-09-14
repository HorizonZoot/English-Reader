package io.github.zoot.englishreader.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.util.Log
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 可选的 ECDICT 扩展词库：下载、解析、安装。
 *
 * ## 为什么是可选下载而不是打包进 APK
 *
 * 内置 `assets/dict_base.tsv` 只有约 7,000 词，真实英文小说的漏词率很高 —— 用户报的
 * `Handwritings` 查词失败就是这个（实测该词不在内置词库里，而 ECDICT 收录了它，
 * 连复数形式一起）。但 ECDICT 全量 CSV 是 63 MB 原始 / 21.6 MB gzip 传输，
 * 打进 APK 会让安装包从 9.5 MB 涨到 30 MB 以上，对不需要生僻词的用户是纯负担。
 *
 * 所以做成可选下载，且**必须由用户显式触发** —— 60 MB 级流量不能替用户决定。
 *
 * ## 为什么不复用 `DictionaryRepository.ensureInitialized`
 *
 * 那套是为 assets 设计的同步初始化：读文件 → `replaceAll` → 写版本号。下载有它没有的东西：
 * 进度、可取消、可失败重试、以及「失败后必须保住内置词库」。但两者共享**同一个版本号机制**
 * （`DICT_PREFS` / `KEY_DICT_VERSION`），这是刻意的：安装扩展词库后写入
 * [PACK_DICT_VERSION]，于是 `ensureInitialized` 会认为词库已是最新而跳过 assets 重建，
 * 不会把 76 万词覆盖回 7,000 词。
 *
 * ## 失败安全
 *
 * 安装走 `DictionaryDao.replaceAll`（`@Transaction`）。中途失败整体回滚，且版本号只在事务
 * 成功后才写 —— 所以失败的净效果是「扩展词库没装上」，而不是「词库被清空」。下次启动
 * `ensureInitialized` 看到版本号仍是 assets 的值，会重新从 assets 初始化。
 */
@Singleton
class DictionaryPackInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictionaryDao: DictionaryDao
) {

    /**
     * 下载地址。生产走 [PACK_URL]，测试可覆盖成 MockWebServer 的地址。
     *
     * ## 为什么需要这个接缝
     *
     * 审计要求「用真实 Room 验证不足阈值、重复主键都保留旧词库」，而那条路径必须经
     * `install()` 才能同时覆盖下载、解析、事务与状态。URL 写死成 `private const val` 时
     * 测试无法驱动它 —— 只能测 `parseAndInstall`，那样就绕过了状态机与失败分类。
     *
     * 用 `internal` 可写属性而非构造参数：Hilt 的 `@Inject constructor` 不能有默认值，
     * 加参数会连带改 DI 与所有调用点，而这个接缝只服务测试。
     */
    internal var packUrl: String = PACK_URL

    private val _state = MutableStateFlow<DictionaryPackState>(DictionaryPackState.NotInstalled)

    /** 在 [stateLock] 内读写；查询期间的任何新发布都会使旧库存快照失效。 */
    private var stateSequence: Long = 0

    /** 序号比较和状态写入共享临界区，防止安装进度插入检查与提交之间。 */
    private val stateLock = Any()
    private val operationMutex = Mutex()
    val state: StateFlow<DictionaryPackState> = _state.asStateFlow()

    /**
     * 用当前数据库条数刷新状态。
     *
     * 在设置页出现时调用。判据是**条数**而不是版本号：版本号说明「装过」，条数说明
     * 「现在真的有」—— 若用户清了应用数据，版本号可能还在而表已空。
     */
    suspend fun refreshState() {
        val seenSequence = synchronized(stateLock) { stateSequence }

        val count = withContext(Dispatchers.IO) {
            try {
                dictionaryDao.getCount()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }

        synchronized(stateLock) {
            if (stateSequence != seenSequence) return

            // 安装可能在查询之前就已开始，此时序号相等，库存快照仍没有状态所有权。
            when (_state.value) {
                is DictionaryPackState.Downloading,
                is DictionaryPackState.Installing,
                DictionaryPackState.Removing -> return
                else -> Unit
            }

            publish(
                when {
                    count == null -> DictionaryPackState.NotInstalled
                    count >= INSTALLED_THRESHOLD -> DictionaryPackState.Installed(count)
                    else -> DictionaryPackState.NotInstalled
                }
            )
        }
    }

    /** 每次状态写入都递增序号，包括 refresh 提交；锁可由条件提交重入。 */
    private fun publish(next: DictionaryPackState) {
        synchronized(stateLock) {
            stateSequence++
            _state.value = next
        }
    }

    /**
     * 把状态置成「下载中」，只供测试构造竞态。
     *
     * 生产路径由 [install] 自己发布状态。测试需要在 `refreshState` 的 DAO 查询挂起期间
     * 模拟「用户点了下载」，而真实 `install` 会连带发起网络请求 —— 那让被测的时序变得
     * 不可控。这个 hook 只做状态转换，不触碰网络或数据库。
     */
    internal fun beginDownloadingForTest() {
        publish(DictionaryPackState.Downloading(0, null))
    }

    /** 同 [beginDownloadingForTest]，但停在解析写库阶段。 */
    internal fun beginInstallingForTest(processed: Int) {
        publish(DictionaryPackState.Installing(processed))
    }


    /**
     * 下载并安装扩展词库。调用方必须已取得用户同意。
     *
     * 取消由协程取消驱动：调用方 cancel 后临时文件被删除，数据库因事务回滚保持原样。
     *
     * ## 脆弱点：DictionaryRepository.ensureInitialized 的 count > 0 守卫
     *
     * 本函数的版本号写入（line 198）发生在 `replaceAllStreaming` **之后**。若协程在事务期间
     * 被取消，`deleteAll()` 已执行但插入未完成，事务回滚后表为空，而版本号未落盘。下次启动
     * `DictionaryRepository.ensureInitialized` 看到 `installedVersion (旧值) >= DICT_VERSION
     * && count (0) > 0`——**`count > 0` 守住了这一情况**，让它重新从 assets 初始化。
     *
     * **若有人「优化」时把 `count > 0` 删掉、只信版本号，那么取消会让用户落到一张不可恢复的
     * 空词表。** 这两处的逻辑必须一起改，不能单独动。
     */
    suspend fun install() {
        if (!operationMutex.tryLock()) return
        val temp = File(context.cacheDir, TEMP_FILE_NAME)
        try {
            publish(DictionaryPackState.Downloading(0, null))
            download(temp)
            coroutineContext.ensureActive()
            val entries = parseAndInstall(temp)
            publish(DictionaryPackState.Installed(entries))
        } catch (cancellation: CancellationException) {
            publish(DictionaryPackState.Failed(DictionaryPackFailure.CANCELLED))
            throw cancellation
        } catch (error: Exception) {
            // 一个 catch 处理全部非取消失败，**刻意不分成 IOException / Exception 两支**。
            //
            // 分成两支时 `catch (io: IOException)` 会先接住外层是 IOException 的异常，于是
            // 下面的 cause chain 搜索被绕过 —— 若 `SQLiteFullException` 被包在
            // IOException 里（Room 会包装 DAO 抛出的异常），存储写满会被归成 NETWORK。
            // 审计指出了这个顺序问题；目前没有证据证明 Room 真的这样包装，但顺序本身是错的，
            // 而合并成一支不需要那个证据也能修好。
            //
            // 存储检查放在最前：`SQLiteFullException` 走 RuntimeException 分支而**不是**
            // IOException（继承链 SQLiteFullException -> SQLiteException ->
            // android.database.SQLException -> RuntimeException，SDK 34 的 android.jar 上用
            // javap 确认过）。不单独认它就会落进 UNKNOWN，界面只给「未知错误，请重试」——
            // 而重试必然再次失败，用户得不到「去清空间」这个唯一有用的指引。
            val storageFull = generateSequence(error as Throwable?) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .any { it is SQLiteFullException }
            when {
                storageFull -> {
                    Log.w(TAG, "Dictionary pack install failed: database full")
                    publish(DictionaryPackState.Failed(DictionaryPackFailure.STORAGE))
                }
                error is IOException -> {
                    Log.w(TAG, "Dictionary pack download failed")
                    publish(DictionaryPackState.Failed(classify(error)))
                }
                else -> {
                    Log.e(TAG, "Dictionary pack install failed")
                    publish(DictionaryPackState.Failed(DictionaryPackFailure.UNKNOWN))
                }
            }
        } finally {
            try {
                if (temp.exists() && !temp.delete()) Log.w(TAG, "Failed to delete temp dictionary pack")
            } finally {
                operationMutex.unlock()
            }
        }
    }

    /**
     * 用户确认后恢复内置词库。先完整读取资源，再原子替换词条；不会操作文章或生词表。
     * 进入提交阶段后完成这次有界的本地事务和版本写入，避免离开页面留下不一致的状态。
     */
    suspend fun remove(): DictionaryPackRemovalResult {
        if (!operationMutex.tryLock()) return DictionaryPackRemovalResult.BUSY
        val previous = state.value
        var committed = false
        try {
            if (previous !is DictionaryPackState.Installed) return DictionaryPackRemovalResult.BUSY
            publish(DictionaryPackState.Removing)
            val entries = withContext(Dispatchers.IO) { BuiltInDictionary.read(context) }
            if (entries.isEmpty()) throw IOException("built-in dictionary is empty")
            coroutineContext.ensureActive()
            withContext(Dispatchers.IO + NonCancellable) {
                dictionaryDao.replaceAll(entries)
                committed = true
                context.getSharedPreferences(DICT_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_DICT_VERSION, BuiltInDictionary.VERSION).apply()
                publish(DictionaryPackState.NotInstalled)
            }
            return DictionaryPackRemovalResult.REMOVED
        } catch (cancellation: CancellationException) {
            publish(if (committed) DictionaryPackState.NotInstalled else previous)
            throw cancellation
        } catch (error: Exception) {
            publish(if (committed) DictionaryPackState.NotInstalled else previous)
            Log.w(TAG, "Failed to restore built-in dictionary")
            return DictionaryPackRemovalResult.FAILED
        } finally {
            operationMutex.unlock()
        }
    }

    /**
     * 有界下载到临时文件。
     *
     * 逐块上报进度并检查取消：60 MB 在慢网络上要几分钟，没有检查点的话用户退出设置页后
     * 下载仍会跑完。
     */
    private suspend fun download(target: File) = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            // 读超时按**单次读**计，不是整体下载时长；大文件不能用 callTimeout 卡死。
            .readTimeout(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(packUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val declaredTotal = body.contentLength().takeIf { it > 0 }

            body.byteStream().use { source ->
                target.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    var lastReported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        total += read
                        // 节流上报：逐块更新 StateFlow 会让 Compose 每几 KB 重组一次。
                        if (total - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = total
                            publish(DictionaryPackState.Downloading(total, declaredTotal))
                        }
                        if (total > MAX_PACK_BYTES) {
                            throw IOException("pack exceeds $MAX_PACK_BYTES bytes")
                        }
                    }
                    publish(DictionaryPackState.Downloading(total, declaredTotal))
                    if (total < MIN_PACK_BYTES) {
                        // 拿到的太小，几乎肯定是错误页面而不是词库。
                        throw MalformedPackException("pack too small: $total bytes")
                    }
                }
            }
        }
    }

    /**
     * 解析 CSV 并原子替换词库。
     *
     * ## 已知代价：整个安装期间持有 Room 的事务线程与 SQLite 写锁
     *
     * `withTransaction` 会把执行搬到事务 dispatcher，所以 68 MB 文件的解析与全部批量插入都跑在
     * Room 的事务线程上 —— 默认是 ArchTaskExecutor 那 4 个共享 IO 线程之一，真机上一次 76 万行
     * 安装会持有它数分钟，期间 SQLite 写锁不放，并发查词会被阻塞。
     *
     * 单测里用 `Runnable::run` 看不到这一点（复审指出）。这是刻意的取舍：把解析移出事务就失去了
     * 「校验失败能回滚」这个核心性质。用户在安装期间查词变慢，好过安装失败时丢词库。
     * 若将来要改善，方向是先解析到临时表再原子 swap，而不是把校验移出事务。
     *
     * ## 为什么流式而不是先 `toList()`
     *
     * 现有 `parseAssetTsv` 用 `toList()` 把全部词条物化，这对 7,000 词无所谓，对 76 万词
     * 会是几百 MB 的峰值内存。这里按批解析、按批插入，峰值只有一批的大小。
     * 代价是失去 `replaceAll` 的单事务原子性，所以自己开事务包住整个过程。
     */
    private suspend fun parseAndInstall(source: File): Int = withContext(Dispatchers.IO) {
        var persisted = 0
        dictionaryDao.replaceAllStreaming { insert ->
            source.bufferedReader().useLines { lines ->
                val batch = ArrayList<DictionaryEntry>(INSERT_BATCH_SIZE)
                var parsed = 0
                var isHeader = true
                for (line in lines) {
                    coroutineContext.ensureActive()
                    if (isHeader) {
                        isHeader = false
                        if (line.startsWith("word,")) continue
                    }
                    val entry = parseCsvLine(line) ?: continue
                    batch += entry
                    if (batch.size >= INSERT_BATCH_SIZE) {
                        insert(batch.toList())
                        parsed += batch.size
                        batch.clear()
                        publish(DictionaryPackState.Installing(parsed))
                    }
                }
                if (batch.isNotEmpty()) {
                    insert(batch.toList())
                    parsed += batch.size
                }

                // 校验必须在**事务内**，且用数据库实际行数而非解析行数。
                //
                // 两个独立的坑，审计各给了 SQLite 反例：
                //
                // 1. 原来这个检查在 `replaceAllStreaming` **之后**，也就是事务已提交。异常包
                //    （能解析出 1..49,999 条）会先把旧词库覆盖掉再报失败。而
                //    `DictionaryRepository.ensureInitialized` 的条件是「版本够 && count > 0」——
                //    残缺词库非空，于是重启也不会重建。实测：7,005 条被替换成 1 条后，
                //    原有样本词永久消失。这违反词库 PRD R3「安装失败不得失去现有词库」。
                //
                // 2. 用 `parsed`（解析行数）当判据是错的：`insertAll` 是
                //    `OnConflictStrategy.REPLACE`，重复主键会合并。50,000 行同一个词能满足阈值，
                //    而数据库里只有 1 条。`DictionaryPackState.Installed` 的文档说那个数字是
                //    「数据库里的实际条数」，所以必须查库。
                //
                // 在这里抛异常会让 `deleteAll` 与已插入的批次一起回滚，用户保有原词库。
                persisted = dictionaryDao.getCount()
                if (persisted < INSTALLED_THRESHOLD) {
                    throw MalformedPackException(
                        "pack persisted only $persisted entries (parsed $parsed lines); " +
                            "rolling back to keep the existing dictionary"
                    )
                }
            }
        }
        // 版本号只在事务成功提交后才写。回滚时它保持旧值，`ensureInitialized` 会照常重建。
        context.getSharedPreferences(DICT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DICT_VERSION, PACK_DICT_VERSION)
            .apply()
        Log.d(TAG, "Dictionary pack installed: $persisted entries")
        persisted
    }

    /**
     * 解析一行 ECDICT CSV。
     *
     * 列序（实测自 `ecdict.csv` 表头）：
     * `word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio`
     *
     * 映射到本项目的四列：`translation` → `chinese`，`definition` → `english`。
     * 其余列（词频、词性、变形表）本版不用 —— 用它们需要改 schema，属独立决定。
     *
     * 必须处理带引号的字段：`translation` 里含逗号和 `\n` 的情况很常见，实测
     * `handwriting` 那行的 translation 就是 `"n. 笔迹"`，而 `hand` 的含内嵌换行。
     */
    private fun parseCsvLine(line: String): DictionaryEntry? {
        if (line.isBlank()) return null
        val fields = splitCsv(line)
        if (fields.size < 4) return null
        val word = fields[0].trim().lowercase()
        if (word.isEmpty()) return null
        val chinese = decodeEscapes(fields[3])
        if (chinese.isEmpty()) return null
        return DictionaryEntry(
            word = word,
            phonetic = fields[1].trim().takeIf { it.isNotBlank() },
            chinese = chinese,
            english = decodeEscapes(fields[2]).takeIf { it.isNotBlank() }
        )
    }

    /**
     * 解码 ECDICT 字段里的字面转义序列。
     *
     * ## 为什么必须在导入边界做
     *
     * ECDICT 用反斜杠加 n 表示换行；真实换行会破坏 CSV 行结构。
     * 原来这里原样入库，而全链路没有任何地方解码它：`splitChineseDefinitions` 只按 `；`
     * 拆分，`WordDetailsBottomSheet` 直接显示。结果是用户在释义里看到字面的反斜杠 n。
     *
     * 实测上游前缀（131,072 bytes / 2,013 个完整数据行）有 189 处这样的序列，而且**第一条
     * 数据行就有**：`'hood` 的 translation 形如「n. 罩；风帽；…」+ 转义 +「v. 覆盖；用头巾包…」。
     * 内置的 `dict_base.tsv` 没有这种形式，所以旧 fixture 覆盖不到它 —— 扩展词库是第一次
     * 把这类输入引进来的。
     *
     * 转成真实换行而不是删掉：那个位置在源数据里表示词性分段（n. / v. / [网络]），
     * 换行能保留这个结构，删掉会让两段释义粘在一起。
     *
     * 只处理换行与回车两种转义：实测样本里没有出现制表符或转义引号（后者由 CSV 层的
     * `""` 规则处理），不为没见过的形式写解码分支。
     *
     * ## 两个已知的行为改变（复审指出，判定为可接受）
     *
     * 1. `trim()` 在解码**之后**执行，所以一个只含转义、没有其他内容的 translation 会解码成
     *    换行、再被 trim 成空串，于是 `parseCsvLine` 返回 null 丢掉整行（连音标与英文释义
     *    一起）。此前它会以带字面反斜杠的形式保留。76 万词条里这种行的价值接近零，
     *    而保留一个只有换行的释义对用户没有意义。
     * 2. 源数据里若有**双反斜杠**后跟 n（表示字面反斜杠而非换行），会被误解成换行。
     *    实测取样未见此形式。
     *
     * `phonetic`（`fields[1]`）不解码：音标里不应出现换行，若出现那是源数据错误，
     * 解码它反而会掩盖问题。
     */
    private fun decodeEscapes(raw: String): String =
        raw.replace("""\r\n""", "\n")
            .replace("""\n""", "\n")
            .replace("""\r""", "\n")
            .trim()

    /**
     * 最小 CSV 切分：支持双引号包裹与 `""` 转义。
     *
     * 不引入 CSV 库：只需要前四列，而 ECDICT 的引号规则是标准 RFC 4180 的子集。
     * 跨行字段（引号内含真实换行）在本实现下会被截断成两条，其中一条因缺列被丢弃 ——
     * 这是已知取舍：ECDICT 用字面 `\n` 两字符表示换行而非真实换行，所以实际不触发。
     */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(5)
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
            // 前四列够用，提前退出省掉长行的剩余解析。
            if (out.size >= 4) break
        }
        if (out.size < 4) out += current.toString()
        return out
    }

    /**
     * 词库包本身不可用（内容不是预期的 CSV、体积异常、有效词条不足）。
     *
     * ## 为什么需要一个专门的类型
     *
     * `classify` 原来按**异常文案**判断：`message.contains("corrupt")`。那让「用户看到哪条
     * 提示」耦合在一句英文日志文字上 —— 我把阈值检查挪进事务时顺手改了文案，新句子既不含
     * `corrupt` 也不含 `too small`，于是包损坏被分类成 `NETWORK`，界面提示「请检查网络」，
     * 而真因是包本身有问题、重试同一个源不会好。审计用源码对应的模型确认了这一点。
     *
     * 类型区分之后，改文案不再影响用户看到的提示。
     */
    private class MalformedPackException(message: String) : IOException(message)

    /**
     * 把 [IOException] 归类成用户能采取下一步的失败原因。
     *
     * 优先看**类型**：[MalformedPackException] 一定是 CORRUPT。只有类型无法判断时才退回读
     * 文案（`ENOSPC` 来自平台，不由本类抛出，没有类型可依）。
     */
    private fun classify(error: IOException): DictionaryPackFailure {
        if (error is MalformedPackException) return DictionaryPackFailure.CORRUPT
        val message = error.message.orEmpty()
        return when {
            message.contains("ENOSPC", ignoreCase = true) ||
                message.contains("No space", ignoreCase = true) ->
                DictionaryPackFailure.STORAGE
            else -> DictionaryPackFailure.NETWORK
        }
    }

    companion object {
        private const val TAG = "DictionaryPack"

        /**
         * ECDICT 全量 CSV，MIT 许可（`skywind3000/ECDICT`）。
         *
         * 实测 63 MB 原始 / 21.6 MB gzip 传输，约 76 万词条。选全量而非裁剪版的理由：
         * 仓库里的 `ecdict.mini.csv` 实测只有 52 行 4 KB，是格式样例不是精简版，
         * 所以没有中间尺寸可用。按词频裁剪需要自己托管产物，那是独立决定。
         */
        private const val PACK_URL =
            "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"

        private const val TEMP_FILE_NAME = "ecdict-pack.csv"

        /** 与 `DictionaryRepository` 共用，保证安装后 assets 初始化不会覆盖回 7,000 词。 */
        private const val DICT_PREFS = "dictionary_prefs"
        private const val KEY_DICT_VERSION = "dict_version"

        /**
         * 扩展词库对应的版本号，必须大于 `DictionaryRepository.DICT_VERSION`（当前 2）。
         * 取 100 留出空间给 assets 词库将来的版本递增。
         */
        private const val PACK_DICT_VERSION = 100

        /** 判定「已安装扩展词库」的条数下界。内置词库约 7,005 条，取 5 万足以区分。 */
        private const val INSTALLED_THRESHOLD = 50_000

        /** 小于此值几乎肯定不是词库（比如拿到一个 HTML 错误页）。 */
        private const val MIN_PACK_BYTES = 1_000_000L

        /** 有界下载：远大于实测的 63 MB，防远端换成一个巨大文件把磁盘写满。 */
        private const val MAX_PACK_BYTES = 200L * 1024 * 1024

        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** 进度上报节流，约 0.5 MB 一次 —— 60 MB 下载约 120 次更新。 */
        private const val PROGRESS_STEP_BYTES = 512 * 1024L

        private const val INSERT_BATCH_SIZE = 2_000

        /**
         * cause 链的遍历上限。Room 通常只包一层，取 8 层足够而不至于在异常自引用时死循环。
         */
        private const val MAX_CAUSE_DEPTH = 8


        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
