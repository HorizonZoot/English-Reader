package io.github.zoot.englishreader.util

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频播放工具类
 * 使用 MediaPlayer 播放在线音频
 */
@Singleton
class AudioPlayer @Inject constructor() {

    // 单线程分发器：串行化所有 play 调用。否则快速连续长按会让两个 play 协程落在
    // Dispatchers.IO 线程池的不同线程上并发操作 MediaPlayer 生命周期（一个在 reset/release，
    // 另一个在 setDataSource/prepare），触发 IllegalStateException 或 native 崩溃。
    // 用 asCoroutineDispatcher（稳定 API），避免依赖 limitedParallelism（实验 API + 版本要求）。
    private val playbackDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AudioPlayer").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    // mediaPlayer 会被 playbackDispatcher 线程与主线程（stopAudio/onCleared 均非 suspend）同时访问，
    // 所有读写都在 synchronized(lock) 内；阻塞的 prepare() 在局部变量上完成、放锁外，
    // 避免主线程 stop() 等锁导致 ANR。
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null

    // 播放代次：prepare() 期间 MediaPlayer 只存在于局部变量，尚未赋给 mediaPlayer 字段，
    // 此时 stop() 看不到它、无法取消。若不作废，用户关闭弹窗/离开页面后 prepare 一返回
    // 仍会 start()，出现"关掉了却突然响"。stop() 递增此值使在途请求准备完即丢弃。
    private var generation = 0L

    /**
     * 播放音频 URL
     * @param url 音频 URL（支持 HTTP/HTTPS）
     * @param onComplete 播放完成回调
     * @param onError 播放失败回调
     */
    suspend fun play(
        url: String,
        onComplete: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        // 关键：在入队前（withContext 之前、调用方线程上）快照代次，而非进入单线程队列后再读。
        // 否则连续两次 play 时，第二条请求要等第一条执行完才出队读 generation——若期间 stop()
        // 已递增，第二条读到的就是新值，prepare 完检查 generation==myGeneration 反而通过，
        // 导致弹窗关闭后仍突然发声。入队前快照使 stop() 之前发起的所有请求（含排队中的）
        // 都持有旧代次，一次 stop() 递增即可全部作废。
        val myGeneration = synchronized(lock) { generation }
        withContext(playbackDispatcher) {
            try {
                Log.d("AudioPlayer", "Starting audio playback")

                // 在局部 player 上完成阻塞的 setDataSource/prepare（不持锁）。
                // prepare 失败时释放这个半成品 player，避免 native 资源泄漏。
                val player = MediaPlayer()
                try {
                    player.apply {
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(url)
                        setOnCompletionListener {
                            Log.d("AudioPlayer", "Playback completed")
                            onComplete()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e("AudioPlayer", "MediaPlayer error: what=$what, extra=$extra")
                            // 校验代次：作废请求的播放失败不上报（避免 stop() 后 TTS 机器音再响）
                            synchronized(lock) {
                                if (generation == myGeneration) {
                                    onError(Exception("MediaPlayer error: what=$what, extra=$extra"))
                                }
                            }
                            true
                        }
                        prepare() // 同步准备（在单线程播放分发器上，不阻塞主线程）
                    }
                } catch (e: Exception) {
                    player.release()
                    throw e
                }

                // 准备完成后持锁替换字段并启动：与外部 stop() 互斥，防止赋值与释放交错。
                synchronized(lock) {
                    if (generation != myGeneration) {
                        // prepare 期间被 stop() 作废（用户已关闭弹窗/离开页面）：直接释放，不发声。
                        player.release()
                        Log.d("AudioPlayer", "Playback cancelled during prepare")
                        return@withContext
                    }
                    releaseLocked()
                    mediaPlayer = player
                    player.start()
                }
                Log.d("AudioPlayer", "MediaPlayer prepared, playback started")
            } catch (e: Exception) {
                // MediaPlayer 抛出的异常可能回显可配置的音频 URL，故不打印异常详情。
                Log.e("AudioPlayer", "Failed to play audio")
                // 校验代次：若请求已被 stop() 作废（用户关闭弹窗/离开页面），prepare 抛出的
                // 网络异常等不应上报——否则手动播放的 onError 会触发 speakViaTts，
                // 导致停止后机器音再次响起。作废请求仅记日志，不向上层报告失败。
                // 判断与回调须在同一把锁内（同 setOnErrorListener）：否则锁外 stop() 可插在
                // 判断通过与 onError 之间递增代次，回调仍会误触发 TTS。onError 非阻塞
                // （speakViaTts 仅入队 TTS），持锁调用不会让主线程 stop() 长时间等锁。
                synchronized(lock) {
                    if (generation == myGeneration) {
                        onError(e)
                    }
                }
            }
        }
    }

    /**
     * 停止播放并释放资源（可从主线程调用）。
     *
     * 同时递增 [generation] 作废在途请求：正在 prepare() 的播放器尚未赋给字段，
     * 单靠 [releaseLocked] 碰不到它，必须靠代次让它准备完后自行释放、不发声。
     */
    fun stop() {
        synchronized(lock) {
            generation++
            releaseLocked()
        }
    }

    /**
     * 释放当前 MediaPlayer。调用方必须持有 [lock]。
     */
    private fun releaseLocked() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Failed to stop audio", e)
        } finally {
            mediaPlayer = null
        }
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean = synchronized(lock) { mediaPlayer?.isPlaying ?: false }
}
