package io.github.zoot.englishreader.data.dictionary

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ECDICT 的字面转义序列不得原样进入释义。
 *
 * ## 缺陷是什么
 *
 * ECDICT 用**两个字符**的反斜杠加 n 表示换行（真实换行会破坏 CSV 行结构）。原来
 * `parseCsvLine` 原样入库，而全链路没有任何地方解码它：`splitChineseDefinitions` 只按 `；`
 * 拆分，`WordDetailsBottomSheet` 直接显示。用户在释义里看到字面的反斜杠。
 *
 * ## 为什么旧 fixture 抓不到
 *
 * 内置 `dict_base.tsv` 是四列 TSV，没有这种转义形式。扩展词库是第一次把这类输入引进来的，
 * 所以既有的词典测试全绿也说明不了什么 —— 这正是外部审计发现它的原因。
 *
 * ## 实测的上游数据
 *
 * 有界取样上游 131,072 bytes（2,013 个完整数据行）实测 **189 处**这样的序列，而且第一条
 * 数据行 `'hood` 就有。所以这不是理论输入，是当前源数据的常态形式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryPackEscapeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: EnglishReaderDatabase
    private lateinit var server: MockWebServer
    private lateinit var installer: DictionaryPackInstaller

    /** 两个字符：反斜杠 + n。写成常量避免在 Kotlin 字面量里反复转义出错。 */
    private val literalEscape = "\\" + "n"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        server = MockWebServer().apply { start() }
        installer = DictionaryPackInstaller(context, db.dictionaryDao()).apply {
            packUrl = server.url("/ecdict.csv").toString()
        }
        context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 入库后的中文释义里不得留下字面转义，且必须变成真实换行。
     *
     * 完整释义必须保留原文与真实换行，不能仅删除转义字符。
     */
    @Test
    fun install_decodesEscapedDefinitionsAndPreservesPlainEntries() = runBlocking {
        server.enqueue(packWithEscapedTranslation())
        installer.install()

        val entry = requireNotNull(db.dictionaryDao().lookup("hood")) { "the escaped row was not stored" }

        assertEquals("n. 罩；风帽\nv. 覆盖；用头巾包", entry.chinese)
        val english = requireNotNull(entry.english) { "english column was dropped" }
        assertEquals("a covering\nfor the head", english)

        val plain = requireNotNull(db.dictionaryDao().lookup("plainword1"))
        assertEquals("释义 1", plain.chinese)
    }

    /**
     * 已入库的字面转义必须能**就地**修掉，不必重下 63 MB。
     *
     * ## 为什么需要这一条
     *
     * `decodeEscapes` 只修新安装。已经装过扩展词库的设备版本号已是 100，
     * `ensureInitialized` 判定「已是最新」直接返回 —— 那 189 处转义永久留在库里，
     * 用户在释义里一直看到反斜杠。审计附录 D 的 F11 指出：我在引入 `decodeEscapes` 的提交里
     * 写了「修正导入后需更新已安装数据」，然后没做。
     *
     * 断言分三层，各因不同缺陷而红：
     *  - 返回值 > 0：SQL 的 `WHERE` 真的匹配到了那些行
     *  - 中文与英文释义精确匹配：保留正文及换行
     *  - 干净的行逐字段不变：`WHERE` 没有把无关行也写一遍
     */
    @Test
    fun decodeLiteralEscapes_repairsAlreadyInstalledRowsInPlace() = runBlocking {
        val dao = db.dictionaryDao()
        dao.replaceAll(
            listOf(
                DictionaryEntry(
                    word = "hood",
                    phonetic = "hud",
                    chinese = "n. 罩；风帽" + literalEscape + "v. 覆盖",
                    english = "a covering" + literalEscape + "for the head"
                ),
                DictionaryEntry(
                    word = "clean",
                    phonetic = null,
                    chinese = "n. 干净；整洁",
                    english = "adj. free from dirt"
                )
            )
        )

        val fixed = dao.decodeLiteralEscapes()

        assertTrue("the WHERE clause must match the escaped row; fixed=$fixed", fixed > 0)

        val hood = requireNotNull(dao.lookup("hood"))
        assertEquals("n. 罩；风帽\nv. 覆盖", hood.chinese)
        assertEquals("a covering\nfor the head", hood.english)

        val clean = requireNotNull(dao.lookup("clean"))
        assertEquals("rows without escapes must be untouched", "n. 干净；整洁", clean.chinese)
        assertEquals("adj. free from dirt", clean.english)
    }

    /**
     * 修复是幂等的：第二次跑改动零行。
     *
     * 这保证「标记丢失后重跑」是安全的 —— 那正是 `repairLiteralEscapesOnce` 失败不写标记
     * 的前提。若不幂等，重跑会把已经是真实换行的内容再变换一次。
     */
    @Test
    fun decodeLiteralEscapes_isIdempotent() = runBlocking {
        val dao = db.dictionaryDao()
        dao.replaceAll(
            listOf(
                DictionaryEntry(
                    word = "hood",
                    phonetic = null,
                    chinese = "n. 罩" + literalEscape + "v. 覆盖",
                    english = null
                )
            )
        )

        val first = dao.decodeLiteralEscapes()
        val before = requireNotNull(dao.lookup("hood")).chinese
        val second = dao.decodeLiteralEscapes()
        val after = requireNotNull(dao.lookup("hood")).chinese

        assertTrue("first pass must fix something; got $first", first > 0)
        assertEquals("second pass must be a no-op; got $second", 0, second)
        assertEquals("content must not change on a second pass", before, after)
    }

    /**
     * NULL 的英文释义在修复后仍是 NULL，不能变成空串。
     *
     * SQL 里对 NULL 做 `replace` 返回 NULL，所以这本该成立 —— 但那依赖 SQLite 的
     * NULL 传播语义，值得钉住：变成空串会让「有释义但为空」和「没有释义」不可区分。
     */
    @Test
    fun decodeLiteralEscapes_preservesNullEnglish() = runBlocking {
        val dao = db.dictionaryDao()
        dao.replaceAll(
            listOf(
                DictionaryEntry(
                    word = "ast",
                    phonetic = null,
                    chinese = "abbr. 大西洋标准时间" + literalEscape + "n. 人名",
                    english = null
                )
            )
        )

        dao.decodeLiteralEscapes()

        assertEquals(null, requireNotNull(dao.lookup("ast")).english)
    }

    /**
     * 造一个含真实 ECDICT 形状的包：一条带转义的行 + 足够多的普通行以过阈值。
     *
     * 第一条模仿实测的 `'hood`：translation 里有反斜杠 n 分隔两个词性段。
     */
    private fun packWithEscapedTranslation(): MockResponse {
        val body = Buffer().apply {
            writeUtf8("word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio\n")
            // 带转义的那一行，双引号包裹（源数据就是这样，因为释义里含逗号）。
            writeUtf8(
                "hood,hʊd,\"a covering${literalEscape}for the head\"," +
                    "\"n. 罩；风帽${literalEscape}v. 覆盖；用头巾包\",,,,,0,0,,,\n"
            )
            for (i in 1..60_000) {
                writeUtf8("plainword$i,/test/,definition,释义 $i,,,,,0,0,,,\n")
            }
        }
        assertTrue("fixture must reach transactional validation", body.size > 1_000_000)
        return MockResponse().setBody(body)
    }
}
