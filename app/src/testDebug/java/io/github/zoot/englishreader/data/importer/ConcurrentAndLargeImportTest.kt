package io.github.zoot.englishreader.data.importer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.repository.BookImporter
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 并发导入与大文件导入。
 *
 * ## 并发：为什么这是真实风险而不是理论风险
 *
 * [EpubBookParser] 是 `@Singleton`，所以多个导入共用同一个实例。它在 `parse` 里**每次新建**
 * [EpubTextExtractor]，而那个类持有可变的 `inflatedBytes` 计数器（`EpubTextExtractor.kt:57`）。
 * 生产代码的注释写明了原因：
 *
 * > 在 @Singleton 上复用它会让计数跨导入累加，数百本后永久报 InvalidEpub。
 *
 * 也就是说「每次新建」是一条**正确性约束**，不是风格选择。此前没有任何用例同时导入两本书，
 * 现有覆盖只有单线程取消（`BookImporterAndroidTest.importFromUri_jobCancelled_rethrowsAndCleansTemp`）。
 *
 * 本类因此分两路，它们抓的是**不同**的失效模式：
 *
 * - [concurrentImports_produceIdenticalResultsToSequential] 抓立即显现的状态损坏 ——
 *   两个导入同时读写共享字段导致结果错乱。
 * - [repeatedImports_doNotAccumulateInflatedBytes] 抓累加型泄漏 —— 计数器不归零，
 *   要够多次导入才撞上上限。
 *
 * 需要说清的是：**这两条都抓不到 `inflatedBytes` 累加**。把 extractor 提成共享字段之后两条
 * 全绿，因为 `requireNotEncrypted` 只读 container/OPF/encryption（每次约 68 KB），要 368 次
 * 战争与和平才撞上 24 MiB 上限。完整推导见
 * [repeatedImports_produceStableResults] 的说明。那条隐患目前只由生产注释守，无自动化判据。
 *
 * [BookArchivePreflight] 是 `object`，但它的 `var` 全在 `inspect()` 内部是局部变量，
 * 无对象级共享状态 —— 这一点也由本类的并发用例覆盖。
 *
 * ## 大文件
 *
 * 语料里最大的 archive 是 `gutenberg3-1342`（24 MiB，含插图），它过得了
 * [ImportBudget.MAX_EPUB_ARCHIVE_BYTES]（32 MiB）因此会走完 preflight 与 Readium 打开，
 * 但被 [ImportBudget.MAX_CHAPTER_CHARS] 拒。最大**可导入**的是 `se-war-and-peace`
 * （2.4 MiB archive，318 万字符，393 章）。两者测的是不同的东西：前者是「大压缩包不会
 * 在预检阶段崩」，后者是「大正文能真的导进来」。
 *
 * 语料在 `build/epub-corpus/`（gitignore），缺失时跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConcurrentAndLargeImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 与生产一致：单例 parser，被多个并发导入共用。 */
    private val parser by lazy { EpubBookParser(context) }

    private fun corpusBook(name: String): File? =
        File(System.getProperty("user.dir"), "../build/epub-corpus/$name.epub").takeIf { it.isFile }

    /**
     * 四个导入并发跑，每个结果必须与它单独跑时**逐字段相同**。
     *
     * 判据是指纹与章节数：指纹是全章正文的摘要，任何跨导入的状态泄漏都会改变它。先顺序跑一遍
     * 拿基线，再并发跑一遍比对 —— 只断言「并发没抛异常」是不够的，状态泄漏的典型表现是
     * 结果**错但不抛**。
     */
    @Test
    fun concurrentImports_produceIdenticalResultsToSequential() = runBlocking {
        val books = CONCURRENT_BOOKS.mapNotNull { corpusBook(it) }
        assumeTrue(
            "need ${CONCURRENT_BOOKS.size} corpus books; run: python tools/epub-corpus/fetch_corpus.py",
            books.size == CONCURRENT_BOOKS.size
        )

        // 基线：顺序导入。
        val sequential = books.map { file ->
            val book = parser.parse(file)
            file.name to Fingerprint(
                chapters = book.chapters.size,
                totalChars = book.chapters.sumOf { it.content.length },
                contentFingerprint = book.metadata.contentFingerprint,
                title = book.metadata.title
            )
        }.toMap()

        // 并发：同一个单例 parser，四个协程同时进。
        val concurrent = coroutineScope {
            books.map { file ->
                async(Dispatchers.IO) {
                    val book = parser.parse(file)
                    file.name to Fingerprint(
                        chapters = book.chapters.size,
                        totalChars = book.chapters.sumOf { it.content.length },
                        contentFingerprint = book.metadata.contentFingerprint,
                        title = book.metadata.title
                    )
                }
            }.awaitAll().toMap()
        }

        assertEquals("concurrent run produced a different set of books", sequential.keys, concurrent.keys)
        sequential.forEach { (name, expected) ->
            assertEquals(
                "$name: concurrent import differs from sequential. A mismatch here means state " +
                    "leaked between imports -- most likely EpubTextExtractor's inflatedBytes " +
                    "counter became shared instead of per-call (see EpubBookParser.kt:50-52).",
                expected,
                concurrent.getValue(name)
            )
        }

        // 指纹必须互不相同，否则上面的比对可能在「所有书都返回同一个结果」时也通过。
        val fingerprints = sequential.values.map { it.contentFingerprint }
        assertEquals(
            "fingerprints must be distinct across different books; identical values would make " +
                "the sequential-vs-concurrent comparison vacuous",
            fingerprints.size,
            fingerprints.toSet().size
        )

        println("CONCURRENT-IMPORT books=${books.size} distinctFingerprints=${fingerprints.toSet().size}")
    }

    /**
     * 重复导入同一本书，结果每次相同。
     *
     * 覆盖的是**每次调用的状态隔离**：临时文件、preflight 的局部计数、Readium 的
     * publication 生命周期。若其中任何一处把状态留到下次调用，重复导入的结果会漂移。
     *
     * ## 它**不**覆盖 `inflatedBytes` 累加，原因值得记下来
     *
     * 生产注释警告复用 [EpubTextExtractor] 会让计数「数百本后永久报 InvalidEpub」。我原本以为
     * 几次导入就能撞上，写了 8 次 —— **两次估算都错**：
     *
     * 第一次以为并发四本就够（合计正文解压 5.7 MiB），实测把 extractor 提成共享字段后并发用例
     * 照绿。第二次拿战争与和平的正文解压量 3.75 MiB 算出「7 次」，也错 —— 因为
     * `requireNotEncrypted` 根本不读正文，它只经 `readEntryBytes` 读 container.xml、OPF 和
     * encryption.xml。实测每次导入的累加量：
     *
     * ```text
     * se-war-and-peace   container 247 + opf 67,955 = 68,202 bytes/import  -> 368 imports to 24 MiB
     * gutenberg-11       container 252 + opf  4,436 =  4,688 bytes/import  -> 5,368 imports
     * ```
     *
     * 368 次约 2.5 分钟，太慢，不能进套件。所以那条隐患**没有**自动化判据，只有生产代码里的
     * 注释和这段说明。它同时说明生产注释的「数百本」是精确的，不是修辞。
     */
    @Test
    fun repeatedImports_produceStableResults() = runBlocking {
        val file = corpusBook(LARGEST_IMPORTABLE)
        assumeTrue("$LARGEST_IMPORTABLE absent", file != null)
        requireNotNull(file)

        val first = parser.parse(file)
        val baseline = Fingerprint(
            chapters = first.chapters.size,
            totalChars = first.chapters.sumOf { it.content.length },
            contentFingerprint = first.metadata.contentFingerprint,
            title = first.metadata.title
        )

        repeat(REPEATED_IMPORTS - 1) { round ->
            val book = parser.parse(file)
            assertEquals(
                "import ${round + 2} of $REPEATED_IMPORTS differs from the first; state is " +
                    "surviving between calls to a @Singleton parser",
                baseline,
                Fingerprint(
                    chapters = book.chapters.size,
                    totalChars = book.chapters.sumOf { it.content.length },
                    contentFingerprint = book.metadata.contentFingerprint,
                    title = book.metadata.title
                )
            )
        }

        println("REPEATED-IMPORT book=${file.name} imports=$REPEATED_IMPORTS allIdentical=true")
    }

    /**
     * 并发导入不得在 `cacheDir` 留下临时文件。
     *
     * `BookImporter.copyToTempFile` 用 `File.createTempFile` 因此文件名唯一，但清理是在
     * `finally` 里做的 —— 并发下若有一条路径漏了 finally，泄漏会随并发度线性增长。
     * 这里数导入前后的临时文件数，不看具体文件名。
     */
    @Test
    fun concurrentImports_leaveNoTempFiles() = runBlocking {
        val books = CONCURRENT_BOOKS.mapNotNull { corpusBook(it) }
        assumeTrue("corpus incomplete", books.size == CONCURRENT_BOOKS.size)

        val importer = BookImporter(context, parser)
        val before = tempImportFileCount()

        coroutineScope {
            books.map { file ->
                async(Dispatchers.IO) { importer.importFromUri(Uri.fromFile(file)) }
            }.awaitAll()
        }

        assertEquals(
            "temp files leaked across ${books.size} concurrent imports " +
                "(before=$before after=${tempImportFileCount()})",
            before,
            tempImportFileCount()
        )
    }

    /**
     * 并发导入中取消一个，其余不受影响。
     *
     * 取消是并发下最容易出错的路径：若取消把共享状态置成中间态，其余导入会拿到错误结果或
     * 抛无关异常。判据是「被取消的那个抛 CancellationException，其余的结果与顺序基线一致」。
     */
    @Test
    fun cancellingOneConcurrentImport_doesNotAffectOthers() = runBlocking {
        val victim = corpusBook(CANCEL_VICTIM)
        val survivors = SURVIVOR_BOOKS.mapNotNull { corpusBook(it) }
        assumeTrue(
            "corpus incomplete",
            victim != null && survivors.size == SURVIVOR_BOOKS.size
        )
        requireNotNull(victim)

        val baseline = survivors.associate { file ->
            file.name to parser.parse(file).metadata.contentFingerprint
        }

        var cancelled = false
        val victimStarted = CompletableDeferred<Unit>()
        coroutineScope {
            val victimJob = launch(Dispatchers.IO) {
                try {
                    // 循环解析而不是解析一次：单次解析可能在 cancel() 之前就返回，那时 cancel()
                    // 成为空操作而测试照绿 —— 取消路径完全没被执行。循环保证 cancel() 到达时
                    // 总有解析在飞行中。`EpubBookParser` 在每个 reading-order item 上调
                    // `coroutineContext.ensureActive()`（`:88`），战争与和平 393 章即 393 个
                    // 检查点，所以取消必然被观测到。
                    repeat(VICTIM_PARSE_ROUNDS) { round ->
                        if (round == 0) victimStarted.complete(Unit)
                        parser.parse(victim)
                    }
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            val others = survivors.map { file ->
                async(Dispatchers.IO) { file.name to parser.parse(file).metadata.contentFingerprint }
            }

            // 等 victim 确认进入解析循环，再等一小会儿让它走进 spine 遍历，然后取消。
            // 只靠 delay 是不够的：调度抖动下 victim 可能还没开始。
            victimStarted.await()
            withContext(Dispatchers.Default) { delay(CANCEL_DELAY_MS) }
            victimJob.cancel()
            victimJob.join()

            val results = others.awaitAll().toMap()
            baseline.forEach { (name, expected) ->
                assertEquals(
                    "$name: fingerprint changed after a concurrent import was cancelled; " +
                        "cancellation must not touch state shared with other imports",
                    expected,
                    results.getValue(name)
                )
            }
        }

        // **必须**断言取消真的发生过。上一版写的是「不断言 cancelled 一定为 true：取消可能落在
        // parse 返回之后，那不是缺陷」—— 那句话把测试的假阳性路径合理化了：若 victim 先跑完，
        // cancel() 是空操作，「取消不污染其余导入」这个命题一次都没被检验，而测试仍然绿。
        // 审查指出后改成循环解析 + 启动屏障，取消窗口从「一次解析的尾部」变成 393 个检查点，
        // 于是可以硬断言。
        assertTrue(
            "the victim finished all $VICTIM_PARSE_ROUNDS rounds before cancel() landed, so the " +
                "cancellation path never executed and this test proved nothing. Raise " +
                "VICTIM_PARSE_ROUNDS or lower CANCEL_DELAY_MS.",
            cancelled
        )
        println("CANCEL-CONCURRENT victimCancelledMidParse=$cancelled survivors=${survivors.size}")
    }

    /**
     * 24 MiB 的插图密集 archive 走完 preflight 与 Readium 并成功导入。
     *
     * 这条覆盖的是「大压缩包不会在预检阶段崩」：`gutenberg3-1342` 有 24 MiB，过得了
     * [ImportBudget.MAX_EPUB_ARCHIVE_BYTES]（32 MiB）与
     * [ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES]（24 MiB，只统计正文类资源，图片不计）。
     *
     * 若它报 `BookArchiveTooLarge` 或 `InvalidEpub`，说明预检把插图算进了正文预算 ——
     * 那会让所有插图书都导不进来，而用户看到的是「文件已损坏」。
     *
     * ## 判据从「被章节预算拒」改成「导入成功」
     *
     * 本用例原先拿 [ImportFailure.ChapterTooLong] 当**探针**：只要拒绝来自章节预算而非预检，
     * 就说明图片没被计入正文。章节切分让这本书变成可导入（它此前是 19 本 `ChapterTooLong`
     * 之一），探针随之失效。
     *
     * 换成断言导入成功是**更强**的判据，不是退让：原判据只证明流程走到了正文预算那一步，
     * 新判据证明它一路走完。预检若误算图片字节，这里同样第一时间红。
     */
    @Test
    fun largeIllustratedArchive_passesPreflightAndImports() = runBlocking {
        val file = corpusBook(LARGE_ARCHIVE)
        assumeTrue("$LARGE_ARCHIVE absent", file != null)
        requireNotNull(file)

        assertTrue(
            "$LARGE_ARCHIVE should be a large archive; got ${file.length()} bytes",
            file.length() > 20L * 1024 * 1024
        )
        assertTrue(
            "$LARGE_ARCHIVE must stay under MAX_EPUB_ARCHIVE_BYTES for this test to be meaningful",
            file.length() <= ImportBudget.MAX_EPUB_ARCHIVE_BYTES
        )

        val result = runCatching { parser.parse(file) }
        val error = result.exceptionOrNull()
        val failure = (error as? ImportException)?.failure

        // 失败消息要能区分两种红：预检误算图片（本用例存在的理由），与切分回归（新增覆盖）。
        assertTrue(
            "expected a successful import, got ${failure ?: error}. A preflight failure here " +
                "would mean image bytes are being charged against the text budget, which would " +
                "reject every illustrated book with a 'file is corrupt' style message. A " +
                "ChapterTooLong would mean chapter splitting regressed.",
            result.isSuccess
        )
        val book = result.getOrThrow()
        // 每个产物都必须在上限内：切分若只是把异常吞掉而没真正分块，这里会红。
        book.chapters.forEach { chapter ->
            assertTrue(
                "chapter ${chapter.chapterIndex} is ${chapter.content.length} chars, " +
                    "above the ${ImportBudget.MAX_CHAPTER_CHARS} ceiling",
                chapter.content.length <= ImportBudget.MAX_CHAPTER_CHARS
            )
        }

        println(
            "LARGE-ARCHIVE ${file.name} bytes=${file.length()} " +
                "chapters=${book.chapters.size} chars=${book.totalChars}"
        )
    }

    /**
     * 最大可导入的书（318 万字符 / 393 章）真的能导入，且耗时不失控。
     *
     * 不设绝对毫秒阈值 —— JVM 与真机 ART 无关。断言的是「完成」与「章节数正确」，耗时只打印，
     * 供真机基准取参考点。
     */
    @Test
    fun largestImportableBook_completesAndYieldsAllChapters() = runBlocking {
        val file = corpusBook(LARGEST_IMPORTABLE)
        assumeTrue("$LARGEST_IMPORTABLE absent", file != null)
        requireNotNull(file)

        lateinit var book: ImportedBook
        val elapsed = measureTimeMillis { book = parser.parse(file) }

        assertTrue("expected many chapters, got ${book.chapters.size}", book.chapters.size > 300)
        assertEquals(
            "chapterIndex must be dense across 300+ chapters",
            book.chapters.indices.toList(),
            book.chapters.map { it.chapterIndex }
        )
        val totalChars = book.chapters.sumOf { it.content.length }
        assertTrue("expected 3M+ chars, got $totalChars", totalChars > 3_000_000)
        assertTrue(
            "total must stay under MAX_BOOK_TEXT_CHARS",
            totalChars <= ImportBudget.MAX_BOOK_TEXT_CHARS
        )

        println(
            "LARGE-BOOK ${file.name} chapters=${book.chapters.size} chars=$totalChars " +
                "parseMs=$elapsed (JVM, not comparable to device)"
        )
    }

    private fun tempImportFileCount(): Int =
        context.cacheDir.listFiles { f: File -> f.name.startsWith("book-import-") }?.size ?: 0

    private data class Fingerprint(
        val chapters: Int,
        val totalChars: Int,
        val contentFingerprint: String,
        val title: String
    )

    private companion object {
        /**
         * 并发用的四本书，刻意混格式与规模：EPUB2/EPUB3、13 章到 393 章。
         * 同格式同规模的四本无法暴露与格式相关的状态泄漏。
         */
        val CONCURRENT_BOOKS = listOf(
            "gutenberg-11",              // EPUB2, 13 章
            "se-a-tale-of-two-cities",   // EPUB3, 54 章
            "gutenberg-345",             // EPUB2, 31 章
            "se-war-and-peace"           // EPUB3, 393 章，最大
        )

        /** 取消用最大的那本，确保取消落在解析中途。 */
        const val CANCEL_VICTIM = "se-war-and-peace"

        val SURVIVOR_BOOKS = listOf("gutenberg-11", "se-a-tale-of-two-cities")

        /**
         * 取消前等待：victim 已确认进入解析循环之后再等这么久，让它走进 spine 遍历。
         * 战争与和平在 JVM 上约 290-420ms，40ms 落在解析早期。
         */
        const val CANCEL_DELAY_MS = 40L

        /**
         * victim 循环解析的轮数。
         *
         * 单轮不够：解析可能在 cancel() 到达前就返回，那时取消路径完全没执行而测试照绿 ——
         * 这正是审查指出的假阳性路径。多轮保证 cancel() 到达时总有解析在飞行中。
         * 每轮 393 个 `ensureActive()` 检查点，所以 3 轮已经远超需要。
         */
        const val VICTIM_PARSE_ROUNDS = 3

        /** 24 MiB 插图密集包。 */
        const val LARGE_ARCHIVE = "gutenberg3-1342"

        /** 语料里最大的可导入书。 */
        const val LARGEST_IMPORTABLE = "se-war-and-peace"

        /**
         * 重复导入次数。
         *
         * 4 次足够验状态隔离（漂移会在第二次就出现），且约 1.7 秒。**不要**为了追
         * `inflatedBytes` 累加而调高它 —— 那需要 368 次，见
         * [repeatedImports_produceStableResults]。
         */
        const val REPEATED_IMPORTS = 4
    }
}
