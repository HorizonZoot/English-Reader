package io.github.zoot.englishreader.data.audio

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PronunciationAudioCache] 的 LRU、失败安全与孤儿文件处理。
 *
 * ## 为什么这些性质需要判据
 *
 * 缓存的失效模式全是**静默**的：LRU 退化成 FIFO 不会报错，只是把用户高频查的词删掉；
 * 索引损坏不会崩，只是所有缓存变成孤儿；文件被系统清掉而索引还在，表现是「发音失败」
 * 并降级 TTS，真因被完全掩盖。这些都不会有异常抛出来提醒任何人。
 *
 * 所以每条都要有断言，而且 LRU 那条必须验**顺序**而不只是「删了一些东西」—— 后者在
 * FIFO 实现下也成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PronunciationAudioCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var cache: PronunciationAudioCache

    private val cacheDir: File
        get() = File(context.cacheDir, "pronunciation")

    @Before
    fun setUp() {
        cacheDir.deleteRecursively()
        cache = PronunciationAudioCache(context)
    }

    /**
     * AC2：缓存必须返回内容完整的真实文件，供 MediaPlayer.setDataSource 使用。
     * `ReadingViewModelTest.playWordAudio_cacheHit_playsLocalFileInsteadOfRemote`
     * 保证「ViewModel 会把它交给 player」。
     */
    @Test
    fun put_thenGet_returnsStoredFileWithSameBytes() = runBlocking {
        assertNull("unwritten word must miss", cache.get("handwriting"))
        // 18,240 字节是 handwriting 的实测音频大小（curl 有道 dictvoice）。
        val audio = ByteArray(18_240) { it.toByte() }
        cache.put("handwriting", audio)

        val file = requireNotNull(cache.get("handwriting")) { "cache miss right after put" }

        assertTrue(
            "the path handed to MediaPlayer must exist on disk; got '${file.absolutePath}'",
            file.isFile
        )
        assertEquals("cached file size must match what was written", 18_240L, file.length())
        assertArrayEquals("cached content differs", audio, file.readBytes())
        assertTrue(
            "the file must live under the app cache dir, not somewhere arbitrary",
            file.absolutePath.startsWith(cacheDir.absolutePath)
        )
    }

    @Test
    fun put_existingIndex_preservesBothWordsAcrossCacheInstances() = runBlocking {
        val first = ByteArray(2_000) { 1 }
        val second = ByteArray(3_000) { 2 }
        cache.put("first", first)
        cache.put("second", second)

        val reopened = PronunciationAudioCache(context)
        assertArrayEquals(first, requireNotNull(reopened.get("first")).readBytes())
        assertArrayEquals(second, requireNotNull(reopened.get("second")).readBytes())
        assertEquals(2, reopened.stats().wordCount)
        assertFalse(File(cacheDir, "index.json.tmp").exists())
    }

    @Test
    fun put_atomicReplacementFails_keepsOldIndexAndRemovesTempFile() = runBlocking {
        cache.put("kept", ByteArray(2_000))
        val index = File(cacheDir, "index.json")
        val previousIndex = index.readBytes()
        val temp = File(cacheDir, "index.json.tmp")

        mockkObject(PronunciationAudioCache.Api26) {
            every {
                PronunciationAudioCache.Api26.replace(temp, index)
            } throws IOException("replacement unavailable")

            cache.put("not-indexed", ByteArray(3_000))

            verify(exactly = 1) {
                PronunciationAudioCache.Api26.replace(temp, index)
            }
            assertTrue("failed replacement must not delete the old index", index.isFile)
            assertArrayEquals(
                "failed replacement must retain the complete previous index",
                previousIndex,
                index.readBytes()
            )
            assertFalse("failed replacement must not leave a temporary JSON file", temp.exists())
        }

        assertNotNull(PronunciationAudioCache(context).get("kept"))
        assertEquals(1, cache.stats().wordCount)
    }

    /**
     * `invalidate` 删掉单个词的条目，且不影响其他词。
     *
     * 这个方法存在的理由：[download] 只校验 HTTP 成功与体积区间，不验证内容可播。一个 200
     * 的 HTML 错误页能成为「有效」缓存项，而 `get` 只查文件是否存在。没有 invalidate 时，
     * 那个词会永久静默失败 —— 命中坏文件 → 播放报错 → 命中缓存又跳过下载 → 下次还是它。
     */
    @Test
    fun invalidate_removesOnlyThatWordAndAllowsRefetch() = runBlocking {
        cache.put("broken", ByteArray(3_000))
        cache.put("intact", ByteArray(3_000))
        assertEquals(2, cache.stats().wordCount)

        cache.invalidate("broken")

        assertNull("the invalidated word must miss so the remote can be retried", cache.get("broken"))
        assertNotNull("invalidate must not touch other words", cache.get("intact"))
        assertEquals(1, cache.stats().wordCount)
    }

    /** 失效一个没缓存过的词是无操作，不抛。 */
    @Test
    fun invalidate_unknownWord_isANoOp() = runBlocking {
        cache.put("kept", ByteArray(2_000))

        cache.invalidate("never-cached")

        assertNotNull(cache.get("kept"))
        assertEquals(1, cache.stats().wordCount)
    }

    /**
     * 文件被外部删除但索引还在 → 返回 null，且索引里那条被清掉。
     *
     * 这是真实场景：Android 在存储紧张时清理 `cacheDir`。若不检查 `exists()`，
     * `MediaPlayer.setDataSource` 会拿到不存在的路径而失败，用户看到的是降级 TTS，
     * 而真因（缓存陈旧）不留任何痕迹。
     */
    @Test
    fun get_fileDeletedButIndexRemains_returnsNullAndPrunesIndex() = runBlocking {
        cache.put("orphan", ByteArray(3_000))
        val file = cache.get("orphan")
        assertNotNull(file)

        // 模拟系统清理：删文件，留索引。
        assertTrue("test setup: could not delete the audio file", file!!.delete())

        assertNull("stale index entry must not yield a missing file", cache.get("orphan"))
        assertEquals(
            "index entry should have been pruned",
            0,
            cache.stats().wordCount
        )
    }

    /**
     * 访问过的旧条目在清理时优先保留 —— 这条直接验 LRU 语义。
     *
     * `old-0` 是最早写入的，本该最先被删；但在触发清理前访问它一次，`lastAccess` 更新，
     * 于是它应当活下来。FIFO 实现下这条会红。
     */
    @Test
    fun put_overLimit_boundsDiskUsageAndKeepsRecentlyAccessedEntries() = runBlocking {
        val chunk = ByteArray(1024 * 1024)
        repeat(15) { i -> cache.put("old-$i", chunk) }

        // 访问最早那个，把它的 lastAccess 刷新到最新。
        assertNotNull(cache.get("old-0"))

        // 再写够触发清理。
        repeat(10) { i -> cache.put("new-$i", chunk) }

        // 清理后仍可继续增长，最终占用只需不超过上限。
        val stats = cache.stats()
        assertTrue(
            "cache must never exceed ${PronunciationAudioCache.MAX_CACHE_BYTES} bytes, " +
                "got ${stats.totalBytes}",
            stats.totalBytes <= PronunciationAudioCache.MAX_CACHE_BYTES
        )
        assertNotNull("most recently written entry was evicted", cache.get("new-9"))
        assertNotNull("second most recent entry was evicted", cache.get("new-8"))
        assertNotNull(
            "old-0 was accessed most recently before eviction, so LRU must keep it; " +
                "if this is null the eviction order is FIFO, not LRU",
            cache.get("old-0")
        )
    }

    /** 索引 JSON 损坏 → 当作空索引，不抛。 */
    @Test
    fun corruptIndex_isTreatedAsEmptyWithoutThrowing() = runBlocking {
        cache.put("before", ByteArray(2_000))
        File(cacheDir, "index.json").writeText("{ this is not valid json")

        assertNull("corrupt index must not yield a hit", cache.get("before"))
        assertEquals(0, cache.stats().wordCount)

        // 且仍可继续写入。
        cache.put("after", ByteArray(2_000))
        assertNotNull(cache.get("after"))
    }

    /**
     * 索引损坏后，失去索引的文件**不得**永久留在磁盘上。
     *
     * ## 为什么这条必须单独存在
     *
     * 上面那条 `corruptIndex_isTreatedAsEmptyWithoutThrowing` 是绿的，但它只验「不崩」。
     * 初版实现的 `evictIfNeeded` 只遍历索引键，于是索引损坏时算出 `total = 0` 并立刻返回，
     * 磁盘上的 mp3 一个都不删 —— 20 MB 上限对它们不生效，目录无界增长。
     *
     * 实测轨迹（临时探针）：写 1 条 → 损坏索引 → 再写 3 条 → 目录 4 个 mp3、索引 3 条、
     * **孤儿 1 个**。而那条「不抛异常」的用例照绿。这是「测了但没测到点子上」的又一例。
     *
     * 判据是**磁盘上的文件数与索引条目数一致**，不是「没抛异常」。
     */
    @Test
    fun corruptIndex_doesNotLeakOrphanedFilesOnDisk() = runBlocking {
        cache.put("leaked", ByteArray(2_000))
        File(cacheDir, "index.json").writeText("{ broken")

        // 后续写入会触发清理路径，孤儿应在那时被回收。
        repeat(3) { i -> cache.put("more-$i", ByteArray(2_000)) }

        val mp3Count = cacheDir.listFiles { f: File -> f.name.endsWith(".mp3") }?.size ?: 0
        val indexed = cache.stats().wordCount

        assertEquals(
            "every .mp3 on disk must have an index entry; $mp3Count file(s) vs $indexed " +
                "indexed entr(ies) means ${mp3Count - indexed} orphan(s) that no eviction " +
                "will ever reclaim, because eviction used to iterate the index rather than " +
                "scan the directory",
            indexed,
            mp3Count
        )
    }

    /**
     * `stats()` 报告的字节数必须是**磁盘实际占用**，含孤儿。
     *
     * 设置页显示「已缓存 N 个词 · X.X MB」，X.X 是用户点「清除」后真正能释放的空间。
     * 初版只读索引，索引损坏时显示 0 MB 而磁盘上还占着 —— 用户看到 0 MB 就不会去清除，
     * 那些空间永远收不回来。
     */
    @Test
    fun stats_reportsDiskBytesIncludingUnindexedFiles() = runBlocking {
        cache.put("indexed", ByteArray(3_000))
        // 手工放一个没有索引记录的文件，模拟索引损坏后重建的残留。
        File(cacheDir, "deadbeefdeadbeef.mp3").writeBytes(ByteArray(5_000))

        assertEquals(
            "stats must count bytes present on disk, not only what the index knows about",
            8_000L,
            cache.stats().totalBytes
        )
    }

    /** `clear()` 后占用归零。 */
    @Test
    fun clear_emptiesCacheAndStats() = runBlocking {
        cache.put("one", ByteArray(3_000))
        cache.put("two", ByteArray(5_000))
        val stats = cache.stats()
        assertEquals(2, stats.wordCount)
        assertEquals(8_000L, stats.totalBytes)

        cache.clear()

        assertEquals(0, cache.stats().wordCount)
        assertEquals(0L, cache.stats().totalBytes)
        assertNull(cache.get("one"))
    }

    /**
     * 词里的特殊字符不能破坏文件名。
     *
     * 词典里有短语（含空格），而用户长按可能选到带撇号的词。若直接用词作文件名，
     * `/` 会被当成路径分隔符。
     */
    @Test
    fun specialCharactersInWord_doNotBreakStorage() = runBlocking {
        val tricky = listOf("don't", "New York", "a/b", "café")
        tricky.forEach { cache.put(it, ByteArray(1_500)) }

        tricky.forEach {
            assertNotNull("word '$it' could not be cached", cache.get(it))
        }
        assertEquals(tricky.size, cache.stats().wordCount)
    }

    /** 大小写不同的同一个词是不同的键 —— 调用方负责归一化，缓存不猜。 */
    @Test
    fun caseSensitivity_isTheCallersResponsibility() = runBlocking {
        cache.put("Word", ByteArray(1_000))

        assertNull(
            "cache must not silently fold case; ReadingViewModel already lowercases " +
                "before lookup, so folding here would hide a caller bug",
            cache.get("word")
        )
    }

}
