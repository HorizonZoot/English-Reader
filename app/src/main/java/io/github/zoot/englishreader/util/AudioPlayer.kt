package io.github.zoot.englishreader.util

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** MediaPlayer preparation is serialized; each caller owns an independently revocable request. */
@Singleton
class AudioPlayer internal constructor(
    private val playbackDispatcher: CoroutineDispatcher,
    private val createPlayer: () -> MediaPlayer
) {
    @Inject
    constructor() : this(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AudioPlayer").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
        ::MediaPlayer
    )

    class RequestToken internal constructor()

    private class ActivePlayback(val token: RequestToken, val player: MediaPlayer)

    private val lock = Any()
    private var currentRequest: RequestToken? = null
    private var activePlayback: ActivePlayback? = null

    /** Register before cache/preference I/O, so stop can also revoke queued preparation. */
    fun beginRequest(): RequestToken = synchronized(lock) {
        RequestToken().also { currentRequest = it }
    }

    fun isCurrent(token: RequestToken): Boolean = synchronized(lock) { currentRequest === token }

    /** Returns after start, while the token remains valid for later playback callbacks. */
    suspend fun play(
        token: RequestToken,
        url: String,
        onComplete: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrent(token)) return
            withContext(playbackDispatcher) {
                val context = currentCoroutineContext()
                var player: MediaPlayer? = null
                var published = false
                try {
                    context.ensureActive()
                    if (!isCurrent(token)) return@withContext
                    val prepared = createPlayer()
                    player = prepared
                    prepared.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    prepared.setDataSource(url)
                    prepared.setOnCompletionListener {
                        synchronized(lock) {
                            if (currentRequest === token) onComplete()
                        }
                    }
                    prepared.setOnErrorListener { _, what, extra ->
                        synchronized(lock) {
                            if (currentRequest === token) {
                                onError(IllegalStateException("MediaPlayer error: what=$what, extra=$extra"))
                            }
                        }
                        true
                    }
                    // The local player is not published while blocking, so stop never waits for prepare.
                    prepared.prepare()
                    context.ensureActive()
                    synchronized(lock) {
                        context.ensureActive()
                        if (currentRequest !== token) return@withContext
                        releaseLocked()
                        activePlayback = ActivePlayback(token, prepared)
                        published = true
                        prepared.start()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    context.ensureActive()
                    Log.e(TAG, "Failed to play audio")
                    synchronized(lock) {
                        if (activePlayback?.player === player) releaseLocked()
                        if (currentRequest === token) onError(exception)
                    }
                } finally {
                    if (!published) player?.let(::releasePlayer)
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            // Includes cancellation during dispatch back to the caller after start().
            stop(token)
            throw cancellation
        }
    }

    /** Revoke this caller's pending request and/or published player without stopping another owner. */
    fun stop(token: RequestToken) {
        synchronized(lock) {
            if (currentRequest === token) currentRequest = null
            if (activePlayback?.token === token) releaseLocked()
        }
    }

    private fun releaseLocked() {
        val playback = activePlayback ?: return
        activePlayback = null
        releasePlayer(playback.player)
    }

    private fun releasePlayer(player: MediaPlayer) {
        try {
            player.release()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Log.e(TAG, "Failed to release audio")
        }
    }

    fun isPlaying(): Boolean = synchronized(lock) { activePlayback?.player?.isPlaying ?: false }

    private companion object {
        const val TAG = "AudioPlayer"
    }
}
