package io.github.zoot.englishreader.data.dictionary

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteFullException
import java.io.IOException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.dao.DictionaryDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DictionaryPackInstaller] 的安装失败安全性：**坏包不得毁掉用户现有的词库**。
 *
 * ## 为什么这一层必须用真实 Room
 *
 * 这些性质全都发生在事务边界上，mock 掉 DAO 就等于把被测的东西替换掉了：
 * 「回滚是否真的发生」「`getCount()` 读到的是事务内还是提交后的值」「`INSERT OR REPLACE`
 * 是否把重复主键合并」——三条都只有真 SQLite 能回答。
 *
 * 所以这里用 in-memory Room（真 SQLite、真 DAO、真事务），只把网络换成 [MockWebServer]。
 *
 * ## 这个类为什么存在
 *
 * 外部审计发现了一个 P1：阈值检查原本在 `replaceAllStreaming` **之后**，也就是事务已经提交
 * 才验证词条数。于是一个能解析出 1..49,999 条的异常包会**先覆盖旧词库、再报告失败**，而
 * `DictionaryRepository.ensureInitialized` 的条件是「版本够 && count > 0」——残缺词库非空，
 * 重启也不会重建。审计的 SQLite 反例：7,005 条被替换成 1 条后原有样本词永久消失。
 *
 * 修复当时没有配任何测试，审计明确要求补上。本类就是那个判据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DictionaryPackInstallerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: EnglishReaderDatabase
    private lateinit var server: MockWebServer
    private lateinit var installer: DictionaryPackInstaller

    @Before
    fun setUp() {
        // 两个 executor 同步：Room 的 suspend DAO 走它自己的线程池，
        // 默认配置下 runBlocking 里的断言可能早于写入完成。
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        server = MockWebServer().apply { start() }
        installer = DictionaryPackInstaller(context, db.dictionaryDao()).apply {
            packUrl = server.url("/ecdict.csv").toString()
        }
        clearDictVersion()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        clearDictVersion()
    }

    /**
     * 有效词条不足的坏包：必须整体回滚，旧词库一条不少。
     *
     * 这是审计那条 P1 的直接判据。断言分三层，因为它们会因不同的缺陷而红：
     *  - 条数不变 → 事务真的回滚了（原来的缺陷让它变成 1,000）
     *  - 抽样词还在 → 回滚的是全部而非部分
     *  - 版本号未落盘 → 下次启动 `ensureInitialized` 仍会重建
     */
    @Test
    fun install_packBelowThreshold_reportsCorruptAndRollsBackExistingDictionary() = runBlocking {
        seedBuiltInDictionary()
        val before = db.dictionaryDao().getCount()

        server.enqueue(csvResponse(uniqueWords = 1_000))
        installer.install()

        val state = installer.state.value
        assertTrue("expected Failed, got $state", state is DictionaryPackState.Failed)
        assertEquals(DictionaryPackFailure.CORRUPT, (state as DictionaryPackState.Failed).reason)
        assertEquals(
            "a pack with too few valid entries must roll back; the user's dictionary " +
                "must not be replaced by a partial one",
            before,
            db.dictionaryDao().getCount()
        )
        assertNotNull("a sampled pre-existing word disappeared", db.dictionaryDao().lookup("seed-1"))
        assertNotEquals(
            "the pack version must not be written on rollback, or ensureInitialized would " +
                "treat the partial dictionary as up to date and never rebuild",
            PACK_VERSION,
            installedDictVersion()
        )
    }

    /**
     * 重复主键的坏包：解析行数够，实际持久化只有 1 条 —— 仍须回滚。
     *
     * `insertAll` 是 `OnConflictStrategy.REPLACE`，50,000 行同一个词在库里只剩 1 条。
     * 原来的判据用**解析行数**，所以这种包能通过阈值并把版本号写成 100，而用户的词库
     * 被替换成一个词。判据必须是数据库实际行数。
     */
    @Test
    fun install_packOfDuplicateKeys_rollsBackDespiteEnoughParsedLines() = runBlocking {
        seedBuiltInDictionary()
        val before = db.dictionaryDao().getCount()

        server.enqueue(csvResponse(uniqueWords = 1, repeatLines = 60_000))
        installer.install()

        assertEquals(
            "60,000 parsed lines collapsing to one row must not satisfy the threshold",
            before,
            db.dictionaryDao().getCount()
        )
        assertNotEquals(
            "the pack version must not be written on rollback",
            PACK_VERSION,
            installedDictVersion()
        )
    }

    /**
     * 太小的响应（比如拿到一个错误页面）同样归 CORRUPT。
     *
     * 与上一条走的是不同的抛出点（下载阶段 vs 事务内），但都必须落到同一个分类。
     */
    @Test
    fun install_responseTooSmall_reportsCorrupt() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>404</html>"))
        installer.install()

        val state = installer.state.value
        assertTrue("expected Failed, got $state", state is DictionaryPackState.Failed)
        assertEquals(
            DictionaryPackFailure.CORRUPT,
            (state as DictionaryPackState.Failed).reason
        )
    }

    /** HTTP 失败归 NETWORK —— 与坏包区分开，这才是「请检查网络」该出现的场合。 */
    @Test
    fun install_httpError_reportsNetwork() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        installer.install()

        val state = installer.state.value
        assertTrue("expected Failed, got $state", state is DictionaryPackState.Failed)
        assertEquals(
            DictionaryPackFailure.NETWORK,
            (state as DictionaryPackState.Failed).reason
        )
    }

    /**
     * 词条足够的好包：真的装上，且版本号落盘。
     *
     * 没有这条，上面四条失败用例可能在「install 永远失败」的实现下全部通过 —— 那是
     * 典型的空转判据。这一条确认成功路径仍然可达。
     */
    @Test
    fun install_validPack_replacesDictionaryAndWritesVersion() = runBlocking {
        seedBuiltInDictionary()

        server.enqueue(csvResponse(uniqueWords = 60_000))
        installer.install()

        val count = db.dictionaryDao().getCount()
        assertTrue("expected the pack to be installed, got $count rows", count >= 50_000)
        assertEquals(
            "version must be written after a successful commit",
            PACK_VERSION,
            installedDictVersion()
        )
        val state = installer.state.value
        assertTrue("expected Installed, got $state", state is DictionaryPackState.Installed)
    }

    /**
     * 刷新期间开始安装 → 过期的刷新结果不得覆盖「安装中」状态。
     *
     * ## 为什么这是缺陷而不是理论时序
     *
     * `SettingsScreen` 依据 `Downloading` / `Installing` 决定是否显示**取消按钮**。若过期的
     * refresh 把状态写回 `NotInstalled`，进度条与取消入口一起消失，而下载仍在后台跑 ——
     * 用户失去了停止一个 22 MB 传输的唯一入口。
     *
     * ## 为什么用可控挂起的 DAO 而不是真实时序
     *
     * 我第一版让真实安装跑起来、再调 `refreshState`，**对照不红** —— 因为
     * `setQueryExecutor(Runnable::run)` 让 DAO 查询完全同步，「进入时检查」与「写状态」之间
     * 没有挂起窗口，install 插不进去，第二个检查从未被考验。那是个空转测试。
     *
     * 这一版用只暴露一个挂起点的 DAO 包装：`getCount()` 挂住 → 期间让 installer 进入
     * `Downloading` → 放行查询 → 断言状态没被覆盖。这样才真的走到「查询返回后」那条路径。
     */
    @Test
    fun refreshState_doesNotOverwriteAnInstallThatStartedWhileTheQueryWasSuspended() = runBlocking {
        val queryEntered = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val real = db.dictionaryDao()

        // 只改写 getCount 的时序，其余委托给真实 DAO。
        val gatedDao = object : DictionaryDao {
            override suspend fun lookup(word: String) = real.lookup(word)
            override suspend fun insertAll(entries: List<DictionaryEntry>) = real.insertAll(entries)
            override suspend fun deleteAll() = real.deleteAll()
            override suspend fun decodeLiteralEscapes(): Int = real.decodeLiteralEscapes()
            override suspend fun getCount(): Int {
                queryEntered.complete(Unit)
                releaseQuery.await()
                return real.getCount()
            }
        }

        val gatedInstaller = DictionaryPackInstaller(context, gatedDao)
        val refreshJob = launch { gatedInstaller.refreshState() }

        // 查询已挂起：此刻模拟「用户点了下载」，让状态进入在途。
        queryEntered.await()
        gatedInstaller.beginDownloadingForTest()

        // 放行那个陈旧的查询。
        releaseQuery.complete(Unit)
        refreshJob.join()

        val state = gatedInstaller.state.value
        assertTrue(
            "a refresh whose query started before the install must not write its stale result; " +
                "got $state. Writing NotInstalled here hides the cancel button while the " +
                "download keeps running.",
            state is DictionaryPackState.Downloading
        )
    }

    /**
     * 陈旧的 refresh 不得覆盖**失败**状态。
     *
     * ## 为什么枚举「忙碌状态」是错的抽象
     *
     * 上一版守卫是 `isBusy()`，只认 `Downloading` 与 `Installing`。审计附录 D 的 F12 指出它
     * 挡不住这条：
     *
     * ```text
     * refresh 开始 → DAO 查询挂起 → 用户点下载 → 安装失败发布 Failed
     * → 旧查询返回 → isBusy() 为假 → 写 NotInstalled，失败提示消失
     * ```
     *
     * 用户看到「什么都没发生」，既不知道失败了也不知道该重试。同一个漏洞对 `Installed` 也
     * 成立（刚装成功却被写回 NotInstalled）。
     *
     * 改成单调序号后不需要枚举任何状态：谁发布新状态都会让序号变，refresh 放弃写入即可。
     */
    @Test
    fun refreshState_doesNotOverwriteAFailureThatArrivedWhileTheQueryWasSuspended() = runBlocking {
        val queryEntered = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val real = db.dictionaryDao()

        val gatedDao = object : DictionaryDao {
            override suspend fun lookup(word: String) = real.lookup(word)
            override suspend fun insertAll(entries: List<DictionaryEntry>) = real.insertAll(entries)
            override suspend fun deleteAll() = real.deleteAll()
            override suspend fun decodeLiteralEscapes(): Int = real.decodeLiteralEscapes()
            override suspend fun getCount(): Int {
                queryEntered.complete(Unit)
                releaseQuery.await()
                return real.getCount()
            }
        }

        val gatedInstaller = DictionaryPackInstaller(context, gatedDao).apply {
            packUrl = server.url("/ecdict.csv").toString()
        }
        val refreshJob = launch { gatedInstaller.refreshState() }

        // 查询挂起期间安装失败。
        queryEntered.await()
        server.enqueue(MockResponse().setResponseCode(503))
        gatedInstaller.install()
        assertTrue(
            "precondition: install must have failed",
            gatedInstaller.state.value is DictionaryPackState.Failed
        )

        // 放行那个陈旧的查询。
        releaseQuery.complete(Unit)
        refreshJob.join()

        val state = gatedInstaller.state.value
        assertTrue(
            "a stale refresh must not erase a newer failure; got $state. Writing NotInstalled " +
                "here leaves the user with no error message and no reason to retry.",
            state is DictionaryPackState.Failed
        )
    }

    /**
     * 已有下载在途时刷新，**不得**用库存快照替换它。
     *
     * ## 序号挡不住这一条
     *
     * 复审 D2-05 用独立 probe 实跑复现过（1 test / 1 failure，消息
     * `refresh replaced an active download with NotInstalled`）。时序不需要任何窄窗口：
     *
     * ```text
     * install 发布 Downloading → refresh 记下这个序号 → DAO 查询期间没有新进度
     * （慢网 / 等响应 / 进度节流）→ 序号仍相等 → refresh 写 NotInstalled
     * ```
     *
     * 我上一版只有序号比较，它的前提是「新状态在查询期间发布」。下载**在查询之前**就开始时，
     * 序号自然相等，守卫形同不存在。
     *
     * 用户影响：`SettingsScreen` 依 `inFlight` 显示取消按钮与进度条，状态变 `NotInstalled`
     * 后两者一起消失，而下载仍在后台跑 —— 用户失去停止一个 22 MB 传输的唯一入口。
     *
     * 这里不需要 gated DAO：查询期间**什么都不发生**才是触发条件。
     */
    @Test
    fun refreshState_doesNotReplaceAnAlreadyActiveDownload() = runBlocking {
        // 库里只有内置词库规模的数据 —— 这正是 refresh 会算出 NotInstalled 的前提。
        seedBuiltInDictionary()

        // 下载已经在途（真实 install 会连带发网络请求，时序不可控，所以用状态 hook）。
        installer.beginDownloadingForTest()
        assertTrue(
            "precondition: state must be Downloading",
            installer.state.value is DictionaryPackState.Downloading
        )

        installer.refreshState()

        val state = installer.state.value
        assertTrue(
            "refresh must not replace an active download with a stock snapshot; got $state. " +
                "Writing NotInstalled here hides the cancel button while the download keeps " +
                "running in the background.",
            state is DictionaryPackState.Downloading
        )
    }

    /**
     * 安装中（解析写库阶段）同样不得被刷新替换。
     *
     * 与上一条同因：`Installing` 也是「在途」，它的所有权属于那次安装。分开写是因为
     * `SettingsScreen` 对两个状态显示不同文案，而 `inFlight` 判定同时看它们。
     */
    @Test
    fun refreshState_doesNotReplaceAnInstallInProgress() = runBlocking {
        seedBuiltInDictionary()
        installer.beginInstallingForTest(processed = 12_000)

        installer.refreshState()

        val state = installer.state.value
        assertTrue(
            "refresh must not replace an in-progress install; got $state",
            state is DictionaryPackState.Installing
        )
    }

    /**
     * 从空闲状态竞争条件提交与安装发布，避免被既有在途状态提前短路。
     * 这是压力覆盖，不能确定性调度检查和写入之间的窄窗口；锁边界仍需结构复查。
     */
    @Test
    fun refreshState_concurrentWithPublish_neverReplacesAnInFlightState() = runBlocking {
        repeat(CONCURRENCY_ROUNDS) {
            val queryEntered = CompletableDeferred<Unit>()
            val releaseQuery = CompletableDeferred<Unit>()
            val gatedDao = object : DictionaryDao by db.dictionaryDao() {
                override suspend fun getCount(): Int {
                    queryEntered.complete(Unit)
                    releaseQuery.await()
                    return 0
                }
            }
            val idleInstaller = DictionaryPackInstaller(context, gatedDao)
            val expected = DictionaryPackState.Installing(it * 100)

            val refreshJob = launch(Dispatchers.Default) { idleInstaller.refreshState() }
            queryEntered.await()
            val publishJob = launch(Dispatchers.IO) {
                releaseQuery.complete(Unit)
                idleInstaller.beginInstallingForTest(expected.processedEntries)
            }
            refreshJob.join()
            publishJob.join()

            assertEquals(
                "round $it lost the installation's state to an inventory refresh",
                expected,
                idleInstaller.state.value
            )
        }
    }

    @Test
    fun refreshState_queryCancelled_propagatesWithoutPublishingInventory() = runBlocking {
        val cancellation = CancellationException("cancelled refresh")
        val cancellingDao = object : DictionaryDao by db.dictionaryDao() {
            override suspend fun getCount(): Int = throw cancellation
        }
        val cancellingInstaller = DictionaryPackInstaller(context, cancellingDao)

        val failure = runCatching { cancellingInstaller.refreshState() }.exceptionOrNull()

        assertTrue("cancellation must reach the caller", failure is CancellationException)
        assertTrue(
            "coroutine stack-trace recovery may copy the exception, but must retain its cause",
            generateSequence(failure) { it.cause }.any { it === cancellation }
        )
        assertEquals(DictionaryPackState.NotInstalled, cancellingInstaller.state.value)
    }

    /** 空闲时刷新仍然照常工作 —— 上一条的守卫不能把正常路径也挡掉。 */
    @Test
    fun refreshState_whenIdle_reportsInstalledFromRowCount() = runBlocking {
        db.dictionaryDao().replaceAll(
            (1..60_000).map {
                DictionaryEntry(word = "packed-$it", phonetic = null, chinese = "释义", english = null)
            }
        )

        installer.refreshState()

        val state = installer.state.value
        assertTrue("expected Installed, got $state", state is DictionaryPackState.Installed)
        assertEquals(60_000, (state as DictionaryPackState.Installed).entryCount)
    }

    @Test
    fun install_insertFailure_classifiesStorageAndNetworkCauses() = runBlocking {
        // SQLiteFullException may be wrapped in IOException; its storage cause must take precedence.
        listOf(
            Triple(
                "direct disk-full error",
                SQLiteFullException("database or disk is full"),
                DictionaryPackFailure.STORAGE
            ),
            Triple(
                "disk-full error wrapped in IO",
                IOException("write failed", SQLiteFullException("database or disk is full")),
                DictionaryPackFailure.STORAGE
            ),
            Triple("plain IO error", IOException("socket closed"), DictionaryPackFailure.NETWORK)
        ).forEach { (case, error, expected) ->
            val failingInstaller = DictionaryPackInstaller(context, daoThrowing(error)).apply {
                packUrl = server.url("/ecdict.csv").toString()
            }
            server.enqueue(csvResponse(uniqueWords = 60_000))

            failingInstaller.install()

            val state = failingInstaller.state.value
            assertTrue("$case: expected Failed, got $state", state is DictionaryPackState.Failed)
            assertEquals(case, expected, (state as DictionaryPackState.Failed).reason)
        }
    }

    /**
     * 让 `insertAll` 抛出指定异常的 DAO。
     *
     * 其余方法委托真实 DAO —— 只改一个注入点，保证失败确实来自插入而不是别处。
     */
    private fun daoThrowing(error: Throwable): DictionaryDao {
        val real = db.dictionaryDao()
        return object : DictionaryDao {
            override suspend fun lookup(word: String) = real.lookup(word)
            override suspend fun insertAll(entries: List<DictionaryEntry>): Unit = throw error
            override suspend fun deleteAll() = real.deleteAll()
            override suspend fun decodeLiteralEscapes(): Int = real.decodeLiteralEscapes()
            override suspend fun getCount(): Int = real.getCount()
        }
    }

    @Test
    fun remove_installedPack_restoresBuiltInAndPreservesVocabulary() = runBlocking {
        seedExtendedDictionary()
        val vocabulary = VocabularyEntity(word = "saved-word", articleId = null)
        val savedId = db.vocabularyDao().insertVocabulary(vocabulary)

        assertEquals(DictionaryPackRemovalResult.REMOVED, installer.remove())

        assertEquals(DictionaryPackState.NotInstalled, installer.state.value)
        assertEquals(7_005, db.dictionaryDao().getCount())
        assertNotNull(db.dictionaryDao().lookup("hello"))
        assertEquals(null, db.dictionaryDao().lookup("packword1"))
        assertEquals(2, installedDictVersion())
        assertEquals(listOf(vocabulary.copy(id = savedId)), db.vocabularyDao().getAllVocabulary().first())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun remove_databaseWriteFails_rollsBackWholeDictionaryAndVersion() = runBlocking {
        seedExtendedDictionary()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_dictionary_restore BEFORE INSERT ON dictionary " +
                "BEGIN SELECT RAISE(ABORT, 'restore rejected'); END"
        )

        assertEquals(DictionaryPackRemovalResult.FAILED, installer.remove())

        assertEquals(DictionaryPackState.Installed(50_000), installer.state.value)
        assertEquals(50_000, db.dictionaryDao().getCount())
        assertNotNull(db.dictionaryDao().lookup("packword1"))
        assertEquals(PACK_VERSION, installedDictVersion())
    }

    @Test
    fun remove_duringCommit_blocksMutationsAndFinishesBeforePropagatingCancellation() = runBlocking {
        seedExtendedDictionary()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val real = db.dictionaryDao()
        val gated = object : DictionaryDao by real {
            override suspend fun replaceAll(entries: List<DictionaryEntry>) {
                entered.complete(Unit)
                release.await()
                real.replaceAll(entries)
            }
        }
        val guardedInstaller = DictionaryPackInstaller(context, gated).apply {
            packUrl = server.url("/pack.csv").toString()
        }
        guardedInstaller.refreshState()
        server.enqueue(MockResponse().setResponseCode(503))
        val operation = launch { guardedInstaller.remove() }
        try {
            withTimeout(10_000) { entered.await() }
            guardedInstaller.install()
            assertEquals(DictionaryPackRemovalResult.BUSY, guardedInstaller.remove())
            guardedInstaller.refreshState()
            assertEquals(DictionaryPackState.Removing, guardedInstaller.state.value)
            assertEquals(0, server.requestCount)
            operation.cancel()
        } finally {
            release.complete(Unit)
            operation.join()
        }
        assertTrue(operation.isCancelled)
        assertEquals(DictionaryPackState.NotInstalled, guardedInstaller.state.value)
        assertEquals(7_005, real.getCount())
        assertEquals(2, installedDictVersion())
        guardedInstaller.install()
        assertEquals("the mutation gate must be released after cancellation", 1, server.requestCount)
    }

    private suspend fun seedExtendedDictionary() {
        db.dictionaryDao().replaceAll((1..50_000).map { index ->
            DictionaryEntry("packword$index", null, "释义 $index", null)
        })
        context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
            .edit().putInt("dict_version", PACK_VERSION).commit()
        installer.refreshState()
        assertEquals(DictionaryPackState.Installed(50_000), installer.state.value)
    }

    // --- helpers ---

    /** 模拟用户已有的内置词库。7,005 是 `dict_base.tsv` 的实际行数。 */
    private suspend fun seedBuiltInDictionary() {
        val seed = (1..7_005).map {
            DictionaryEntry(word = "seed-$it", phonetic = null, chinese = "释义 $it", english = null)
        }
        db.dictionaryDao().replaceAll(seed)
    }

    /**
     * 造一个 ECDICT 形状的 CSV 响应。
     *
     * 必须超过 `MIN_PACK_BYTES`（1 MB），否则会先被「太小」那道闸门拦下、测不到事务逻辑。
     * 所以用 `definition` 列填充字节数 —— 那一列在生产映射里是 `english`，长度不影响词条有效性。
     */
    private fun csvResponse(uniqueWords: Int, repeatLines: Int = 0): MockResponse {
        val lines = if (repeatLines > 0) repeatLines else uniqueWords
        val padding = "x".repeat((PADDING_BUDGET_CHARS + lines - 1) / lines)
        val body = Buffer().apply {
            writeUtf8("word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio\n")
            for (i in 1..lines) {
                val word = if (repeatLines > 0) "dup" else "packword$i"
                writeUtf8("$word,/test/,$padding,词条 $i,,,,,0,0,,,\n")
            }
        }
        assertTrue("fixture must reach transactional validation", body.size > 1_000_000)
        return MockResponse().setBody(body)
    }

    private fun installedDictVersion(): Int =
        context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
            .getInt("dict_version", 0)

    private fun clearDictVersion() {
        context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private companion object {
        /** 压力样本数，不承诺检出窄窗口竞态；比较与写入的锁边界仍需复查。 */
        const val CONCURRENCY_ROUNDS = 200

        /** 将超过 1 MB 下载下限所需的填充分配到所有行。 */
        const val PADDING_BUDGET_CHARS = 1_100_000

        /**
         * 扩展词库的版本号（`DictionaryPackInstaller.PACK_DICT_VERSION`）。
         *
         * 判据是「回滚后**没有**被写成这个值」，而不是「版本号为 0」。我第一版断言 0，
         * 实测得到 2 —— 那是 `DictionaryRepository.DICT_VERSION`：Robolectric 会真的构造
         * `EnglishReaderApp`，它的 `onCreate` 起协程调 `initializeFromAssets()`，往同一个
         * SharedPreferences 写 2，与 `@Before` 里的清理抢先后。
         *
         * assets 的 2 是合法值，与回滚无关。真正不能发生的是被写成 100 —— 那会让
         * `ensureInitialized` 认为残缺词库已是最新而永不重建。
         */
        const val PACK_VERSION = 100
    }
}
