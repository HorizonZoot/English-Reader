package io.github.zoot.englishreader.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.remote.dictionary.DictionaryApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * DictionaryRepository 单元测试
 *
 * 覆盖前几轮修复的关键逻辑：
 * - ensureInitialized 幂等：已有数据时不再解析/写库
 * - 初始化成功后 isInitialized 缓存，第二次调用走快速路径
 * - TSV 解析容错：跳过表头、字段不足的坏行被跳过、english 字段内的 Tab 被保留
 * - 空 TSV 不写库
 * - lookupOffline 传入前会 trim + lowercase
 */
class DictionaryRepositoryTest {

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var dao: DictionaryDao
    private lateinit var api: DictionaryApiService
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor
    private lateinit var repository: DictionaryRepository

    private val TSV_HEADER = "word\tphonetic\tchinese\tenglish"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        assets = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        api = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        prefsEditor = mockk(relaxed = true)
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns prefsEditor
        every { prefsEditor.putInt(any(), any()) } returns prefsEditor
        // 默认已安装版本 0（模拟全新安装 / 版本落后），使初始化逻辑正常走"需要重建"分支
        every { prefs.getInt(any(), any()) } returns 0
        repository = DictionaryRepository(context, dao, api)
    }

    private fun stubAsset(content: String) {
        every { assets.open("dict_base.tsv") } returns
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
    }

    /** 设置"已安装的词库版本号"。传一个很大的值表示"已是最新"，0 表示落后需重建。 */
    private fun stubInstalledDictVersion(version: Int) {
        every { prefs.getInt(any(), any()) } returns version
    }

    @Test
    fun ensureInitialized_emptyDb_parsesAndInsertsEntries() = runTest {
        coEvery { dao.getCount() } returns 0
        stubAsset(
            """
            $TSV_HEADER
            success	/səkˈses/	n. 成功；成就	achievement
            read	/riːd/	v. 阅读	to read
            """.trimIndent()
        )

        val slot = slot<List<DictionaryEntry>>()
        coEvery { dao.replaceAll(capture(slot)) } returns Unit

        repository.ensureInitialized()

        coVerify(exactly = 1) { dao.replaceAll(any()) }
        assertEquals(2, slot.captured.size)
        assertEquals("success", slot.captured[0].word)
        assertEquals("n. 成功；成就", slot.captured[0].chinese)
    }

    @Test
    fun ensureInitialized_dbAlreadyPopulatedAndVersionCurrent_skipsInsert() = runTest {
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999) // 已安装版本 >= 当前版本，视为最新

        repository.ensureInitialized()

        coVerify(exactly = 0) { dao.replaceAll(any()) }
    }

    @Test
    fun ensureInitialized_versionOutdated_rebuildsEvenIfDbPopulated() = runTest {
        // 库已有旧数据，但已安装版本落后（0 < 当前版本）→ 应强制重建
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(0)
        stubAsset("$TSV_HEADER\nword\t/wɜːrd/\tn. 单词\tword")
        coEvery { dao.replaceAll(any()) } returns Unit

        val keySlot = slot<String>()
        val versionSlot = slot<Int>()
        every { prefsEditor.putInt(capture(keySlot), capture(versionSlot)) } returns prefsEditor

        repository.ensureInitialized()

        // 版本落后必须重建，且写回正确的 key 与当前版本号
        // （防止写错 key 或写回旧值，导致老用户每次启动都全量重新解析词库）
        coVerify(exactly = 1) { dao.replaceAll(any()) }
        assertEquals("dict_version", keySlot.captured)
        assertEquals(EXPECTED_DICT_VERSION, versionSlot.captured)
    }

    @Test
    fun ensureInitialized_calledTwice_onlyInitializesOnce() = runTest {
        coEvery { dao.getCount() } returns 0
        stubAsset("$TSV_HEADER\nword\t/wɜːrd/\tn. 单词\tword")
        coEvery { dao.replaceAll(any()) } returns Unit

        repository.ensureInitialized()
        repository.ensureInitialized() // 第二次应走 isInitialized 快速路径

        // getCount 只在第一次被调用；replaceAll 只执行一次
        coVerify(exactly = 1) { dao.replaceAll(any()) }
        coVerify(exactly = 1) { dao.getCount() }
    }

    @Test
    fun parseTsv_skipsHeaderAndMalformedLines() = runTest {
        coEvery { dao.getCount() } returns 0
        stubAsset(
            """
            $TSV_HEADER
            good	/ɡʊd/	adj. 好的	good
            broken_line_without_tabs
            ok	/oʊˈkeɪ/	adj. 可以的	okay
            """.trimIndent()
        )
        val slot = slot<List<DictionaryEntry>>()
        coEvery { dao.replaceAll(capture(slot)) } returns Unit

        repository.ensureInitialized()

        // 坏行（无 tab，字段不足 3）被跳过，只剩 2 条
        assertEquals(2, slot.captured.size)
        assertTrue(slot.captured.any { it.word == "good" })
        assertTrue(slot.captured.any { it.word == "ok" })
    }

    @Test
    fun parseTsv_englishFieldWithTab_isPreserved() = runTest {
        coEvery { dao.getCount() } returns 0
        // english 字段内含 Tab，limit=4 应保证前 3 列正确、Tab 留在第 4 列
        stubAsset("$TSV_HEADER\nphrase\t/freɪz/\tn. 短语\tpart\tA\tpart B")
        val slot = slot<List<DictionaryEntry>>()
        coEvery { dao.replaceAll(capture(slot)) } returns Unit

        repository.ensureInitialized()

        assertEquals(1, slot.captured.size)
        val entry = slot.captured[0]
        assertEquals("phrase", entry.word)
        assertEquals("n. 短语", entry.chinese)     // 第 3 列未被污染
        assertEquals("part\tA\tpart B", entry.english) // 第 4 列保留内部 Tab
    }

    @Test
    fun ensureInitialized_emptyTsv_doesNotInsert() = runTest {
        coEvery { dao.getCount() } returns 0
        stubAsset(TSV_HEADER) // 只有表头，无数据行

        repository.ensureInitialized()

        coVerify(exactly = 0) { dao.replaceAll(any()) }
    }

    @Test
    fun ensureInitialized_emptyAssetsAndEmptyDb_retriesThenGivesUp() = runTest {
        // 空 assets + 空 DB：前几次调用应重试（不缓存失败），达上限后放弃、不再重试
        coEvery { dao.getCount() } returns 0
        stubInstalledDictVersion(0)
        stubAsset(TSV_HEADER) // 解析为空

        // 连续调用多于上限次数
        repeat(5) { repository.ensureInitialized() }

        // 达上限（3 次）后标记已初始化，之后走快速路径不再查库；
        // 因此 getCount 只被调用 EXPECTED_MAX_EMPTY_INIT_ATTEMPTS 次，而非 5 次
        coVerify(exactly = EXPECTED_MAX_EMPTY_INIT_ATTEMPTS) { dao.getCount() }
        coVerify(exactly = 0) { dao.replaceAll(any()) }
    }

    @Test
    fun ensureInitialized_emptyAssetsButDbPopulated_marksInitializedImmediately() = runTest {
        // 空 assets 但 DB 已有旧词库：立即标记已初始化沿用旧数据，不重试
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(0) // 版本落后触发重建尝试
        stubAsset(TSV_HEADER) // 解析为空

        repeat(3) { repository.ensureInitialized() }

        // DB 有数据 → 首次即标记，getCount 只调用一次
        coVerify(exactly = 1) { dao.getCount() }
        coVerify(exactly = 0) { dao.replaceAll(any()) }
    }

    @Test
    fun lookupOffline_trimsAndLowercasesWord() = runTest {
        // 已初始化且版本最新 → 跳过 assets 解析，直接走 DAO 查询
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        val entry = DictionaryEntry("hello", null, "你好", null)
        coEvery { dao.lookup("hello") } returns entry

        val result = repository.lookupOffline("  HELLO  ")

        // 直接命中：entry 为查得条目，inflectedForm 为 null（未经词形还原）
        assertEquals(entry, result?.entry)
        assertNull(result?.inflectedForm)
        coVerify { dao.lookup("hello") }
        // 确认走了跳过路径，未触发重建
        coVerify(exactly = 0) { dao.replaceAll(any()) }
    }

    @Test
    fun lookupOffline_inflectedWord_lemmatizesAndReturnsBaseForm() = runTest {
        // 非歧义变形词：running 的规则候选 run 命中，标注原始变形词供界面显示"running 的原形"。
        // （lives/leaves 这类歧义词走并列展示路径，见 lookupOffline_ambiguousWord_* 用例。）
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        val runEntry = DictionaryEntry("run", "/rʌn/", "v. 跑；运行", null)
        // dao 为 relaxed mock，未显式 stub 的候选会返回子 mock 使循环提前短路，
        // 故把 running 的其余候选（runne/runn）一并置 null，隔离出 run 命中这条路径。
        coEvery { dao.lookup(any()) } returns null
        coEvery { dao.lookup("run") } returns runEntry

        val result = repository.lookupOffline("running")

        assertEquals(runEntry, result?.entry)
        assertEquals("running", result?.inflectedForm)
        assertTrue(result!!.alternateEntries.isEmpty())
    }

    @Test
    fun lookupOffline_esThirdPerson_prefersDropSOverDropEs() = runTest {
        // 回归：uses 的还原候选顺序须为 [use, us]（去 s 优先于去 es）。
        // 词库里 us（代词）和 use（动词）同时存在，DictionaryRepository "首个命中即返回"——
        // 若候选顺序颠倒（us 在前），长按 uses 会弹出代词 us，而非动词原形 use。
        // 本用例把 us 与 use 都 stub 为命中，断言实际返回 use，兜住候选顺序回归。
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        val useEntry = DictionaryEntry("use", "/ju:s/", "n. 使用；vt. 使用", null)
        val usEntry = DictionaryEntry("us", "/ʌs/", "pron. 我们", null)
        coEvery { dao.lookup("uses") } returns null // 原词 miss，进入词形还原
        coEvery { dao.lookup("use") } returns useEntry
        coEvery { dao.lookup("us") } returns usEntry

        val result = repository.lookupOffline("uses")

        // 必须命中 use（去 s），而非 us（去 es）
        assertEquals(useEntry, result?.entry)
        assertEquals("uses", result?.inflectedForm)
    }

    @Test
    fun lookupOffline_ambiguousWord_returnsBothBaseForms() = runTest {
        // lives 的两种解释（live 三单 / life 复数）语法上都成立，且真实词库里 live 和 life
        // 都存在。规则法无上下文无从判别词性，故并列返回两者，由用户自行判断。
        // 主原形 live 在前（动词三单更常见），life 作为 alternateEntries 并列。
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        val liveEntry = DictionaryEntry("live", "/lɪv/", "v. 居住；生活", null)
        val lifeEntry = DictionaryEntry("life", "/laɪf/", "n. 生命；生活", null)
        coEvery { dao.lookup("lives") } returns null // 原词 miss，进入词形还原
        coEvery { dao.lookup("live") } returns liveEntry
        coEvery { dao.lookup("life") } returns lifeEntry

        val result = repository.lookupOffline("lives")

        assertEquals(liveEntry, result?.entry)
        assertEquals("lives", result?.inflectedForm)
        assertEquals(listOf(lifeEntry), result?.alternateEntries)
    }

    @Test
    fun lookupOffline_ambiguousWordOnlyOneBaseInDict_returnsThatOneWithoutAlternates() = runTest {
        // 歧义词但词库只收录了其中一个原形（如精简词库无 life）：退化为单释义，
        // 不应产生空洞的并列区块。
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        val liveEntry = DictionaryEntry("live", "/lɪv/", "v. 居住；生活", null)
        coEvery { dao.lookup("lives") } returns null
        coEvery { dao.lookup("live") } returns liveEntry
        coEvery { dao.lookup("life") } returns null

        val result = repository.lookupOffline("lives")

        assertEquals(liveEntry, result?.entry)
        assertEquals("lives", result?.inflectedForm)
        assertTrue(result!!.alternateEntries.isEmpty())
    }

    @Test
    fun lookupOffline_inflectedWordNoCandidateHits_returnsNull() = runTest {
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        // 原词及所有还原候选都 miss → 返回 null（上层降级在线）
        coEvery { dao.lookup(any()) } returns null

        val result = repository.lookupOffline("zzzzs")

        assertNull(result)
    }

    @Test
    fun lookupOffline_daoThrows_returnsNullInsteadOfCrashing() = runTest {
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999) // 已是最新，跳过 assets 解析
        coEvery { dao.lookup(any()) } throws RuntimeException("db locked")

        val result = repository.lookupOffline("word")

        assertNull(result)
    }

    @Test
    fun lookupOffline_initializationCancelled_propagatesCancellation() = runTest {
        coEvery { dao.getCount() } throws CancellationException("cancelled")

        val error = runCatching { repository.lookupOffline("word") }.exceptionOrNull()

        assertTrue(error is CancellationException)
        coVerify(exactly = 0) { dao.lookup(any()) }
    }

    @Test
    fun lookupOffline_queryCancelled_propagatesCancellation() = runTest {
        coEvery { dao.getCount() } returns 20
        stubInstalledDictVersion(999)
        coEvery { dao.lookup(any()) } throws CancellationException("cancelled")

        val error = runCatching { repository.lookupOffline("word") }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    companion object {
        /**
         * 期望写回的词库版本号，必须与生产代码 DictionaryRepository.DICT_VERSION 保持一致。
         * 生产常量为 private，此处镜像用于断言；生产侧 +1 时须同步更新此值。
         */
        private const val EXPECTED_DICT_VERSION = 2

        /**
         * 期望的空初始化最大重试次数，须与生产 DictionaryRepository.MAX_EMPTY_INIT_ATTEMPTS 一致。
         * 生产常量为 private，此处镜像用于断言；生产侧调整时须同步。
         */
        private const val EXPECTED_MAX_EMPTY_INIT_ATTEMPTS = 3
    }
}
