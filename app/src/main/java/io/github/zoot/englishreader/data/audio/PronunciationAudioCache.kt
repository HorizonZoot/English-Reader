package io.github.zoot.englishreader.data.audio

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 缓存占用统计，供设置页显示。 */
data class PronunciationCacheStats(val wordCount: Int, val totalBytes: Long)

/**
 * 查词发音的本地缓存。
 *
 * ## 为什么需要
 *
 * 查词的释义来自本地 SQLite（毫秒级），但发音要走一趟有道 `dictvoice` 的网络往返 ——
 * `MediaPlayer.prepare()` 是同步的，它内部建立 HTTPS 连接、下载音频头、缓冲。用户实测反馈
 * 「单词已经显示了，语音会慢一截」，慢的就是这一步。
 *
 * 缓存后第二次查同一个词是本地文件，`prepare()` 变成毫秒级。
 *
 * ## 为什么不「下载完再播」
 *
 * 最直觉的做法是下载 → 存文件 → 播文件，但那会让**首次播放更慢**：现在 `prepare()` 是流式的，
 * 拿到足够缓冲就开始播，不必等整个文件。改成先下完再播，首次延迟反而变长。
 *
 * 所以设计是：首次照常流式播远端 URL，**同时**后台另起一个请求把文件下载入缓存。首次延迟不变，
 * 第二次起为零。代价是首次会发两个请求（多约 19 KB 流量）—— 这是刻意的，不是 bug。
 *
 * ## 为什么自己维护索引而不用文件系统时间戳
 *
 * LRU 需要「最后访问时间」，而 Android 上**读文件不更新 mtime**，只有写会更新。若靠
 * `File.lastModified()` 做 LRU，缓存命中不刷新时间戳，LRU 会退化成 FIFO —— 把用户高频查的词
 * 删掉、留下只查过一次的。`setLastModified()` 在部分 Android 版本上静默失败，也不可靠。
 *
 * 所以维护 [INDEX_FILE_NAME]：`{"<hash>": {"word":"...","bytes":N,"lastAccess":millis}}`。
 * 代价是多一次 IO；收益是 LRU 语义确定，且索引顺带记了 `word`（文件名是 hash，不可人读）。
 *
 * ## 失败安全
 *
 * 缓存的任何失败都不得影响发音：取不到就走网络，写不进就只是没缓存上。所有 IO 都包
 * `runCatching`，[get] 在任何异常下返回 null，[put] 静默放弃。
 */
@Singleton
class PronunciationAudioCache @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 索引读写与清理的串行化。并发查词会同时读写索引，不加锁会丢更新。 */
    private val mutex = Mutex()

    private val cacheDir: File
        get() = File(context.cacheDir, DIR_NAME)

    private val indexFile: File
        get() = File(cacheDir, INDEX_FILE_NAME)

    /**
     * 取缓存的音频文件，未命中返回 null。
     *
     * 命中时更新 `lastAccess` —— LRU 依赖它，见类 KDoc。
     *
     * **必须检查 `file.exists()`**：Android 在存储紧张时会清理 `cacheDir`，此时文件消失而索引
     * 还在。若不检查，`MediaPlayer.setDataSource` 会拿到一个不存在的路径而失败，而那个失败会被
     * 归类成「发音失败」并降级 TTS —— 真因（缓存陈旧）被完全掩盖。
     */
    suspend fun get(word: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val key = hash(word)
                val index = readIndex()
                val entry = index.optJSONObject(key) ?: return@withLock null
                val file = File(cacheDir, "$key$AUDIO_SUFFIX")
                if (!file.isFile) {
                    // 文件被系统清掉了，索引里的记录已失效。
                    index.remove(key)
                    writeIndex(index)
                    return@withLock null
                }
                entry.put(KEY_LAST_ACCESS, System.currentTimeMillis())
                writeIndex(index)
                file
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "Cache lookup failed")
            null
        }
    }

    /**
     * 下载音频并存入缓存。调用方应在播放已经启动之后调用（见类 KDoc）。
     *
     * 失败静默：这是「顺手缓存」，失败只意味着下次查这个词还得联网。不做重试 —— 下次查词
     * 自然会再试一次。
     */
    suspend fun download(word: String, url: String) = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                // 有界读取，不用 InputStream.readNBytes —— 那是 API 33，而 minSdk 是 24
                // （lint NewApi 抓到的）。手写循环同样有界，且不依赖平台版本。
                val bytes = body.byteStream().use { source ->
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    val sink = java.io.ByteArrayOutputStream()
                    while (sink.size() <= MAX_AUDIO_BYTES) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                    sink.toByteArray()
                }
                if (bytes.size > MAX_AUDIO_BYTES) {
                    // 实测最长的词（antidisestablishmentarianism）是 62,867 字节，
                    // 上限留了 3 倍余量。超限说明拿到的不是单词音频。
                    throw IOException("audio too large: ${bytes.size}")
                }
                if (bytes.size < MIN_AUDIO_BYTES) {
                    // 实测最短的词（a）是 7,725 字节。太小几乎肯定是错误页面。
                    throw IOException("audio too small: ${bytes.size}")
                }
                put(word, bytes)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            // 只记类名：有道 URL 把单词作为查询参数，异常信息可能回显它。
            Log.w(TAG, "Audio cache download failed: ${it.javaClass.simpleName}")
        }
        Unit
    }

    /** 写入一条缓存。写完检查上限，超限则 LRU 清理。 */
    suspend fun put(word: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
                    throw IOException("cannot create cache dir")
                }
                val key = hash(word)
                val file = File(cacheDir, "$key$AUDIO_SUFFIX")
                try {
                    file.writeBytes(bytes)
                } catch (io: IOException) {
                    // 磁盘满会留下半个文件，删掉避免它被后续 get 当成有效缓存。
                    file.delete()
                    throw io
                }
                val index = readIndex()
                index.put(
                    key,
                    JSONObject()
                        .put(KEY_WORD, word)
                        .put(KEY_BYTES, bytes.size)
                        .put(KEY_LAST_ACCESS, System.currentTimeMillis())
                )
                evictIfNeeded(index)
                writeIndex(index)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Audio cache write failed")
        }
        Unit
    }

    /**
     * 当前占用。
     *
     * 字节数取**磁盘上的实际总和**，词数取索引里的条目数。两者来源不同是刻意的：
     * 设置页要显示「已缓存 N 个词 · X.X MB」，其中 X.X 必须是用户清除后真正能释放的空间
     * （含孤儿文件），而 N 只有索引知道（文件名是 hash，从磁盘反推不出词）。
     *
     * 初版两者都只读索引，于是索引损坏时显示 0 MB，而磁盘上可能还占着几十 MB ——
     * 用户看到「0 MB」就不会去点清除，那些空间永远收不回来。
     *
     * 索引读不出来时词数为 0，但字节数仍然如实报告。
     */
    suspend fun stats(): PronunciationCacheStats = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val diskBytes = cacheDir.listFiles { f: File -> f.name.endsWith(AUDIO_SUFFIX) }
                    ?.sumOf { it.length() } ?: 0L
                val index = readIndex()
                var count = 0
                index.keys().forEach { key ->
                    if (index.optJSONObject(key) != null) count++
                }
                PronunciationCacheStats(count, diskBytes)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            PronunciationCacheStats(0, 0L)
        }
    }

    /**
     * 清空缓存目录与索引。
     *
     * 扫描目录删全部文件（含 `index.json` 与可能残留的 `.tmp`），而不是按索引逐条删 ——
     * 索引损坏时按索引删会漏掉孤儿，那正是用户点「清除」想收回的空间。
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                cacheDir.listFiles()?.forEach { it.delete() }
                Unit
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Audio cache clear failed")
        }
        Unit
    }

    /**
     * 删掉单个词的缓存条目，让下次查词重新走远端。
     *
     * ## 为什么需要它
     *
     * [download] 只校验 HTTP 成功与体积落在 1 KB..200 KB 之间，**不验证内容是否可播**。
     * 一个返回 200 的 HTML 错误页、或传输中途损坏的文件都能成为「有效」缓存项。而 [get]
     * 只检查文件是否存在。
     *
     * 于是那个词会**永久**静默失败：本地播放报错 → 不失效条目 → 也不重试远端（命中缓存会
     * 跳过下载）→ 下次查词又命中同一个坏文件。只有清空整个缓存才能恢复，而用户不会知道
     * 该这么做 —— 他看到的只是「这个词没声音」。
     *
     * 所以播放失败时必须失效它，让远端有机会重来一次。
     */
    suspend fun invalidate(word: String) = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val key = hash(word)
                File(cacheDir, "$key$AUDIO_SUFFIX").delete()
                val index = readIndex()
                index.remove(key)
                writeIndex(index)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Audio cache invalidate failed")
        }
        Unit
    }

    /**
     * 超限时按 `lastAccess` 升序删到 [EVICT_TARGET_BYTES]。
     *
     * 删到 80% 而非刚好压到上限：若每次只删到刚好等于上限，下一次写入又会立即触发清理，
     * 形成抖动。
     *
     * ## 为什么先扫描目录而不是只遍历索引
     *
     * 初版只遍历索引键，于是索引损坏时（`readIndex` 返回空对象）清理算出 `total = 0` 并立刻
     * 返回 —— **磁盘上的 mp3 一个都不删**，永久成为孤儿，20 MB 上限对它们不生效，目录无界增长。
     *
     * 实测证实：写 1 条 → 损坏索引 → 再写 3 条 → 目录里 4 个 mp3，索引里 3 条，孤儿 1 个。
     * 而当时已有的「索引损坏不抛异常」用例是**绿的** —— 它验的是「不崩」，没验「不泄漏」。
     *
     * 所以磁盘是事实来源，索引只是 LRU 所需的元数据加速结构。没有索引记录的文件先删掉：
     * 它们的 `lastAccess` 已经不可知，无法参与 LRU 排序，留着只会占额度。
     *
     * 调用方必须已持有 [mutex]。
     */
    private fun evictIfNeeded(index: JSONObject) {
        val audioFiles = cacheDir.listFiles { f: File -> f.name.endsWith(AUDIO_SUFFIX) }
            ?: return

        var total = 0L
        val entries = mutableListOf<Triple<String, Long, Long>>() // key, lastAccess, bytes
        val orphans = mutableListOf<File>()

        audioFiles.forEach { file ->
            val key = file.name.removeSuffix(AUDIO_SUFFIX)
            val meta = index.optJSONObject(key)
            if (meta == null) {
                orphans += file
            } else {
                // 用**磁盘上的实际长度**而非索引里记的字节数：两者不一致时（写入中途失败、
                // 索引被外部改动）应当信磁盘，否则额度会算错。
                val bytes = file.length()
                total += bytes
                entries += Triple(key, meta.optLong(KEY_LAST_ACCESS), bytes)
            }
        }

        // 孤儿先删：无索引记录意味着 lastAccess 不可知，无法参与 LRU，留着只占额度。
        orphans.forEach { it.delete() }
        if (orphans.isNotEmpty()) {
            Log.d(TAG, "Removed ${orphans.size} orphaned audio file(s)")
        }

        if (total <= MAX_CACHE_BYTES) return

        // 同 lastAccess 时按 key 排序，保证顺序确定 —— 否则同毫秒写入的多条在不同运行里
        // 被删的可能不是同一批，缺陷难复现。
        entries.sortWith(compareBy({ it.second }, { it.first }))
        for ((key, _, bytes) in entries) {
            if (total <= EVICT_TARGET_BYTES) break
            File(cacheDir, "$key$AUDIO_SUFFIX").delete()
            index.remove(key)
            total -= bytes
        }
        Log.d(TAG, "Evicted audio cache down to $total bytes")
    }

    /** 索引损坏时当作空索引：已有文件成为孤儿，下次 [get] 时按 `exists()` 检查清理。 */
    private fun readIndex(): JSONObject =
        runCatching { JSONObject(indexFile.readText()) }.getOrElse { JSONObject() }

    /**
     * 先写临时文件再原子 rename。
     *
     * 直接写目标文件的话，进程在写入中途被杀会留下半个 JSON，下次启动索引整体读不出来 ——
     * 全部缓存变成孤儿。
     */
    private fun writeIndex(index: JSONObject) {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return
        val tmp = File(cacheDir, "$INDEX_FILE_NAME.tmp")
        try {
            tmp.writeText(index.toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Api26.replace(tmp, indexFile)
            } else {
                // API 24/25 lack NIO; Android's POSIX rename atomically replaces the target.
                Os.rename(tmp.absolutePath, indexFile.absolutePath)
            }
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "Index temp cleanup failed")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    internal object Api26 {
        fun replace(source: File, target: File) {
            // File.renameTo does not replace an existing target on Windows/JDK 17.
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /**
     * 词 → 文件名。
     *
     * 不直接用词作文件名：词可能含 `/`、`?`、空格（词典里有短语），而大小写在某些文件系统上
     * 不区分。取 SHA-256 前 16 个 hex 字符 —— 64 bit，对几千条缓存碰撞概率可忽略。
     */
    private fun hash(word: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(word.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "PronunciationCache"

        private const val DIR_NAME = "pronunciation"
        private const val INDEX_FILE_NAME = "index.json"
        private const val AUDIO_SUFFIX = ".mp3"

        private const val KEY_WORD = "word"
        private const val KEY_BYTES = "bytes"
        private const val KEY_LAST_ACCESS = "lastAccess"

        /**
         * 缓存上限 20 MB。
         *
         * 实测有道 `dictvoice` 音频体积（curl 八个词，非估算）：`a` 7,725 / `the` 9,069 /
         * `soliloquy` 12,525 / `husbandry` 12,909 / `perpendicular` 14,637 /
         * `encyclopedia` 15,405 / `handwriting` 18,240 /
         * `antidisestablishmentarianism` 62,867 字节。平均约 19 KB。
         *
         * 20 MB ≈ 1,100 词，超过这个规模的生词本是极少数，而 20 MB 对手机存储无感。
         */
        internal const val MAX_CACHE_BYTES = 20L * 1024 * 1024

        /** 清理目标：上限的 80%，避免每次写入都触发删除。 */
        internal const val EVICT_TARGET_BYTES = (MAX_CACHE_BYTES * 8) / 10

        /** 单文件上限，实测最长 62,867 字节，留 3 倍余量。 */
        internal const val MAX_AUDIO_BYTES = 200 * 1024

        /** 单文件下限，实测最短 7,725 字节。低于此几乎肯定是错误页面而非音频。 */
        internal const val MIN_AUDIO_BYTES = 1_000

        private const val READ_BUFFER_BYTES = 16 * 1024

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 15L
    }
}
