package io.github.zoot.englishreader.tts.spike

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

enum class TtsSpikePhase { IDLE, LOADING, SYNTHESIZING, PLAYING, FINISHED, STOPPED, FAILED }

enum class TtsSpikeFailure { MODEL_MISSING, UNSUPPORTED_ABI, MODEL_LOAD, SYNTHESIS, AUDIO }

data class TtsSpikeState(
    val phase: TtsSpikePhase = TtsSpikePhase.IDLE,
    val speakerId: Int = 0,
    val modelLoadMillis: Long? = null,
    val requestToFirstBlockMillis: Long? = null,
    val lastFirstBlockMillis: Long? = null,
    val maxFirstBlockMillis: Long = 0,
    val lastRtf: Double? = null,
    val maxRtf: Double = 0.0,
    val completedUtterances: Int = 0,
    val elapsedMillis: Long = 0,
    val stopCallMillis: Long? = null,
    val peakThermalStatus: Int? = null,
    val failure: TtsSpikeFailure? = null
) {
    val running: Boolean
        get() = phase == TtsSpikePhase.LOADING || phase == TtsSpikePhase.SYNTHESIZING ||
            phase == TtsSpikePhase.PLAYING
}

internal fun synthesisRtf(
    generationNanos: Long,
    callbackNanos: Long,
    samples: Int,
    sampleRate: Int
): Double {
    require(generationNanos >= callbackNanos && callbackNanos >= 0)
    require(samples > 0 && sampleRate > 0)
    return (generationNanos - callbackNanos).toDouble() / 1_000_000_000 * sampleRate / samples
}

@HiltViewModel
class TtsSpikeViewModel @Inject constructor(
    private val application: Application
) : ViewModel() {
    private val mutableState = MutableStateFlow(TtsSpikeState())
    val state = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TtsSpike").apply { isDaemon = true }
    }
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var activeTrack: AudioTrack? = null
    // Only the executor accesses the native model, including its release.
    private var model: OfflineTts? = null

    fun selectSpeaker(speakerId: Int) {
        if (!mutableState.value.running && speakerId in SPEAKERS) {
            mutableState.value = mutableState.value.copy(speakerId = speakerId)
        }
    }

    fun start(repeat: Boolean) {
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        stop()
        val token = synchronized(lock) {
            if (closed) return
            ++generation
        }
        val initial = TtsSpikeState(
            phase = TtsSpikePhase.LOADING,
            speakerId = mutableState.value.speakerId
        )
        mutableState.value = initial
        executor.execute { run(token, requestedAt, repeat, initial) }
    }

    fun stop() {
        val started = SystemClock.elapsedRealtimeNanos()
        val stopped = synchronized(lock) {
            generation++
            try {
                activeTrack?.pause()
                activeTrack?.flush()
                true
            } catch (_: IllegalStateException) {
                false
            }
        }
        if (mutableState.value.running) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            mutableState.value = mutableState.value.copy(
                phase = if (stopped) TtsSpikePhase.STOPPED else TtsSpikePhase.FAILED,
                stopCallMillis = elapsed,
                failure = if (stopped) null else TtsSpikeFailure.AUDIO
            )
            Log.i(TAG, "stop_ms=$elapsed")
        }
    }

    private fun run(token: Long, requestedAt: Long, repeat: Boolean, initial: TtsSpikeState) {
        var snapshot = initial
        try {
            if (!isCurrent(token)) return
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            val tts = model ?: loadModel().also { model = it }
            snapshot = snapshot.copy(
                modelLoadMillis = (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1_000_000
            )
            if (!isCurrent(token)) return
            val sampleRate = tts.sampleRate()
            if (sampleRate != SAMPLE_RATE || tts.numSpeakers() != 904) {
                throw SpikeException(TtsSpikeFailure.MODEL_LOAD)
            }
            val loopStarted = SystemClock.elapsedRealtimeNanos()
            var utteranceRequestedAt = requestedAt
            do {
                if (!isCurrent(token)) return
                snapshot = snapshot.copy(phase = TtsSpikePhase.SYNTHESIZING)
                publish(token, snapshot)
                snapshot = synthesize(token, tts, sampleRate, utteranceRequestedAt, snapshot)
                if (!isCurrent(token)) return
                val thermal = thermalStatus()
                snapshot = snapshot.copy(
                    completedUtterances = snapshot.completedUtterances + 1,
                    elapsedMillis = (SystemClock.elapsedRealtimeNanos() - loopStarted) / 1_000_000,
                    peakThermalStatus = thermal?.let { maxOf(it, snapshot.peakThermalStatus ?: it) }
                )
                publish(token, snapshot)
                Log.i(TAG, "utterances=${snapshot.completedUtterances} " +
                    "load_ms=${snapshot.modelLoadMillis} " +
                    "first_block_ms=${snapshot.lastFirstBlockMillis} " +
                    "rtf=${snapshot.lastRtf} elapsed_ms=${snapshot.elapsedMillis} " +
                    "thermal=${snapshot.peakThermalStatus}")
                utteranceRequestedAt = SystemClock.elapsedRealtimeNanos()
            } while (repeat && snapshot.elapsedMillis < REPEAT_MILLIS)
            publish(token, snapshot.copy(phase = TtsSpikePhase.FINISHED))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SpikeException) {
            publish(token, snapshot.copy(phase = TtsSpikePhase.FAILED, failure = failure.reason))
            Log.w(TAG, "failure=${failure.reason}")
        } catch (_: LinkageError) {
            publish(token, snapshot.copy(phase = TtsSpikePhase.FAILED, failure = TtsSpikeFailure.MODEL_LOAD))
            Log.w(TAG, "failure=MODEL_LOAD")
        } catch (_: Exception) {
            publish(token, snapshot.copy(phase = TtsSpikePhase.FAILED, failure = TtsSpikeFailure.SYNTHESIS))
            Log.w(TAG, "failure=SYNTHESIS")
        }
    }

    private fun loadModel(): OfflineTts {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) {
            throw SpikeException(TtsSpikeFailure.UNSUPPORTED_ABI)
        }
        val filesDir = application.getExternalFilesDir(null)
            ?: throw SpikeException(TtsSpikeFailure.MODEL_MISSING)
        val directory = File(filesDir, "tts-models/vits-piper-en_US-libritts_r-medium-int8")
        val required = listOf("en_US-libritts_r-medium.onnx", "tokens.txt", "espeak-ng-data/phondata")
        if (required.any { !File(directory, it).isFile || File(directory, it).length() == 0L }) {
            throw SpikeException(TtsSpikeFailure.MODEL_MISSING)
        }
        return try {
            OfflineTts(config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(directory, required[0]).absolutePath,
                        tokens = File(directory, "tokens.txt").absolutePath,
                        dataDir = File(directory, "espeak-ng-data").absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            ))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw SpikeException(TtsSpikeFailure.MODEL_LOAD)
        }
    }

    private fun synthesize(
        token: Long,
        tts: OfflineTts,
        sampleRate: Int,
        requestedAt: Long,
        initial: TtsSpikeState
    ): TtsSpikeState {
        val track = createTrack(sampleRate)
        try {
            synchronized(lock) {
                if (!isCurrent(token)) return initial
                activeTrack = track
                track.play()
            }
            var snapshot = initial
            var callbackNanos = 0L
            var writtenSamples = 0L
            var callbackFailure: Exception? = null
            val synthesisStarted = SystemClock.elapsedRealtimeNanos()
            val audio = tts.generateWithCallback(
                text = application.getString(R.string.tts_spike_sentence),
                sid = initial.speakerId,
                speed = 1.0f
            ) { samples ->
                val callbackStarted = SystemClock.elapsedRealtimeNanos()
                try {
                    if (!isCurrent(token)) {
                        0
                    } else if (samples.isEmpty()) {
                        1
                    } else {
                        if (writtenSamples == 0L) {
                            val firstBlockMillis = (callbackStarted - requestedAt) / 1_000_000
                            snapshot = snapshot.copy(
                                phase = TtsSpikePhase.PLAYING,
                                requestToFirstBlockMillis = snapshot.requestToFirstBlockMillis ?: firstBlockMillis,
                                lastFirstBlockMillis = firstBlockMillis,
                                maxFirstBlockMillis = maxOf(snapshot.maxFirstBlockMillis, firstBlockMillis)
                            )
                            publish(token, snapshot)
                        }
                        writtenSamples += writeSamples(token, track, samples)
                        if (isCurrent(token)) 1 else 0
                    }
                } catch (failure: Exception) {
                    // JNI must see a normal stop result. Rethrow on the managed side after it returns.
                    callbackFailure = failure
                    0
                } finally {
                    callbackNanos += SystemClock.elapsedRealtimeNanos() - callbackStarted
                }
            }
            val synthesisNanos = SystemClock.elapsedRealtimeNanos() - synthesisStarted
            callbackFailure?.let { throw it }
            if (!isCurrent(token)) return snapshot
            if (audio.samples.isEmpty() || writtenSamples == 0L) {
                throw SpikeException(TtsSpikeFailure.SYNTHESIS)
            }
            val rtf = synthesisRtf(synthesisNanos, callbackNanos, audio.samples.size, sampleRate)
            // A successful write only queues PCM. Wait for the playback head before releasing it.
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (isCurrent(token) && (track.playbackHeadPosition.toLong() and 0xffffffffL) < writtenSamples) {
                if (SystemClock.elapsedRealtime() >= deadline) throw SpikeException(TtsSpikeFailure.AUDIO)
                SystemClock.sleep(10)
            }
            return snapshot.copy(lastRtf = rtf, maxRtf = maxOf(snapshot.maxRtf, rtf))
        } finally {
            synchronized(lock) {
                if (activeTrack === track) activeTrack = null
            }
            track.release()
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minimum <= 0) throw SpikeException(TtsSpikeFailure.AUDIO)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 4 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw SpikeException(TtsSpikeFailure.AUDIO)
        }
        return track
    }

    private fun writeSamples(token: Long, track: AudioTrack, samples: FloatArray): Int {
        var offset = 0
        var lastWriteAt = SystemClock.elapsedRealtime()
        while (offset < samples.size && isCurrent(token)) {
            // Non-blocking writes keep stop/pause/flush independent of playback backpressure.
            val written = synchronized(lock) {
                if (!isCurrent(token)) return offset
                track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (written < 0) throw SpikeException(TtsSpikeFailure.AUDIO)
            if (written == 0) {
                if (SystemClock.elapsedRealtime() - lastWriteAt >= 5_000) {
                    throw SpikeException(TtsSpikeFailure.AUDIO)
                }
                SystemClock.sleep(5)
            } else {
                offset += written
                lastWriteAt = SystemClock.elapsedRealtime()
            }
        }
        return offset
    }

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        application.getSystemService(PowerManager::class.java)?.currentThermalStatus
    } else {
        null
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { !closed && generation == token }

    private fun publish(token: Long, snapshot: TtsSpikeState) {
        handler.post {
            synchronized(lock) {
                if (isCurrent(token)) mutableState.value = snapshot
            }
        }
    }

    override fun onCleared() {
        stop()
        synchronized(lock) { closed = true }
        executor.execute {
            try {
                model?.release()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Log.w(TAG, "failure=MODEL_RELEASE")
            } finally {
                model = null
            }
        }
        executor.shutdown()
    }

    private class SpikeException(val reason: TtsSpikeFailure) : RuntimeException()

    companion object {
        val SPEAKERS = listOf(0, 12, 100)
        private const val TAG = "TtsSpike"
        private const val SAMPLE_RATE = 22_050
        private const val REPEAT_MILLIS = 5 * 60 * 1_000L
    }
}
