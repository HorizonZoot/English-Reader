package io.github.zoot.englishreader.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.data.tts.TtsModelEntry
import io.github.zoot.englishreader.data.tts.TtsModelException
import io.github.zoot.englishreader.data.tts.TtsModelFailure
import io.github.zoot.englishreader.data.tts.TtsModelKind
import io.github.zoot.englishreader.data.tts.TtsModelRepository
import io.github.zoot.englishreader.data.tts.TtsModelState
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal interface LocalTtsBackend {
    fun voices(): List<TtsVoiceOption>
    fun refresh(onComplete: () -> Unit)
    fun speak(text: String, voiceId: String, rate: Float, callback: (TtsPlaybackResult) -> Unit)
    fun stop()
    fun shutdown()
}

class LocalModelTtsBackend @Inject constructor(private val repository: TtsModelRepository) : LocalTtsBackend {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    private var track: AudioTrack? = null
    private var session: Session? = null
    private var speechJob: Job? = null

    // Each session owns its executor and native instance, including asynchronous release.
    private class Session {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "LocalTts").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var model: OfflineTts? = null
        var modelId: String? = null
    }

    private fun session(): Session = session ?: Session().also { session = it }

    override fun voices(): List<TtsVoiceOption> = TtsModelCatalog.entries
        .filter { it.bundled || repository.states.value[it.id] == TtsModelState.Installed }
        .flatMap { entry -> entry.voices.map { voice ->
            TtsVoiceOption(entry.voiceId(voice.speakerId), voice.localeTag, TtsVoiceMode.LOCAL_MODEL, 500, voice.nameRes)
        } }

    override fun refresh(onComplete: () -> Unit) {
        session().scope.launch {
            repository.refresh()
            handler.post(onComplete)
        }
    }

    override fun speak(text: String, voiceId: String, rate: Float, callback: (TtsPlaybackResult) -> Unit) {
        stop()
        val token = synchronized(lock) { ++generation }
        val owner = session()
        val selection = TtsModelCatalog.findVoice(voiceId)
        if (selection == null) {
            callback(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
            return
        }
        val (entry, voice) = selection
        speechJob = owner.scope.launch {
            try {
                repository.withModel(entry.id) { directory ->
                    if (!current(token)) return@withModel
                    if (owner.modelId != entry.id) {
                        owner.model?.release()
                        owner.model = null
                        owner.modelId = null
                    }
                    val model = owner.model ?: load(entry, directory).also {
                        owner.model = it
                        owner.modelId = entry.id
                    }
                    if (!current(token)) return@withModel
                    play(token, model, text, voice.speakerId, rate) {
                        publish(token, callback, TtsPlaybackResult.Started("local_tts_$token", TtsVoiceMode.LOCAL_MODEL))
                    }
                    publish(token, callback, TtsPlaybackResult.Finished("local_tts_$token"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TtsModelException) {
                publish(token, callback, TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
            } catch (_: LinkageError) {
                publish(token, callback, TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
            } catch (_: Exception) {
                publish(token, callback, TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED))
            }
        }
    }

    private fun load(entry: TtsModelEntry, directory: File): OfflineTts {
        fun path(name: String) = File(directory, name).absolutePath
        val config = OfflineTtsModelConfig(numThreads = 2, debug = false, provider = "cpu")
        when (entry.kind) {
            TtsModelKind.VITS -> config.vits = OfflineTtsVitsModelConfig(
                model = path(entry.modelFile), tokens = path("tokens.txt"), dataDir = path("espeak-ng-data")
            )
            TtsModelKind.KOKORO -> config.kokoro = OfflineTtsKokoroModelConfig(
                model = path(entry.modelFile), voices = path("voices.bin"),
                tokens = path("tokens.txt"), dataDir = path("espeak-ng-data")
            )
        }
        val model = try {
            OfflineTts(config = OfflineTtsConfig(model = config, maxNumSentences = 1))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw TtsModelException(TtsModelFailure.CORRUPT)
        }
        if (model.sampleRate() != entry.sampleRate || model.numSpeakers() != entry.speakerCount) {
            model.release()
            throw TtsModelException(TtsModelFailure.CORRUPT)
        }
        return model
    }

    private fun play(token: Long, model: OfflineTts, text: String, speaker: Int, rate: Float, onStarted: () -> Unit) {
        val sampleRate = model.sampleRate()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0)
        val audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 4 / 5))
            .build()
        try {
            check(audio.state == AudioTrack.STATE_INITIALIZED)
            synchronized(lock) {
                if (!current(token)) return
                track = audio
                audio.play()
            }
            var written = 0L
            val callback = PcmCallback { samples ->
                if (!current(token)) return@PcmCallback 0
                if (samples.isNotEmpty()) {
                    if (written == 0L) onStarted()
                    written += write(token, audio, samples)
                }
                if (current(token)) 1 else 0
            }
            model.generateWithCallback(text, speaker, rate, callback)
            callback.failure?.let { throw it }
            if (!current(token)) return
            check(written > 0)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (current(token) && (audio.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                check(SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(10)
            }
        } finally {
            synchronized(lock) { if (track === audio) track = null }
            audio.release()
        }
    }

    private fun write(token: Long, audio: AudioTrack, samples: FloatArray): Int {
        var offset = 0
        var lastWrite = SystemClock.elapsedRealtime()
        while (offset < samples.size && current(token)) {
            val count = synchronized(lock) {
                if (!current(token)) return offset
                audio.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            check(count >= 0)
            if (count == 0) {
                check(SystemClock.elapsedRealtime() - lastWrite < 5_000)
                SystemClock.sleep(5)
            } else {
                offset += count
                lastWrite = SystemClock.elapsedRealtime()
            }
        }
        return offset
    }

    // JNI looks up invoke([F)Ljava/lang/Integer; by name. Exceptions must return through managed code.
    @Keep
    private class PcmCallback(private val consume: (FloatArray) -> Int) : (FloatArray) -> Int {
        var failure: Exception? = null
        override fun invoke(samples: FloatArray): Int = try {
            consume(samples)
        } catch (error: Exception) {
            failure = error
            0
        }
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            try {
                track?.pause()
                track?.flush()
            } catch (_: IllegalStateException) {
                Log.w("LocalTts", "Audio stop failed")
            }
        }
        speechJob?.cancel()
        speechJob = null
    }

    override fun shutdown() {
        stop()
        val old = session ?: return
        session = null
        old.scope.cancel()
        old.executor.execute {
            try {
                old.model?.release()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("LocalTts", "Model release failed")
            } finally {
                old.model = null
            }
        }
        old.dispatcher.close()
    }

    private fun current(token: Long) = synchronized(lock) { generation == token }
    private fun publish(token: Long, callback: (TtsPlaybackResult) -> Unit, result: TtsPlaybackResult) {
        handler.post { if (current(token)) callback(result) }
    }
}
