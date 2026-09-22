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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal interface LocalTtsBackend {
    fun voices(): List<TtsVoiceOption>
    fun refresh(onComplete: () -> Unit)
    fun prepare(voiceId: String?): Job?
    fun speak(text: String, voiceId: String, rate: Float, callback: (TtsPlaybackResult) -> Unit)
    fun stop(preservePreparation: Boolean = false)
    fun shutdown()
}

/** The native lifetime is separate from AudioTrack; preparation must never synthesize or play. */
internal interface LocalTtsModel {
    val sampleRate: Int
    fun generate(text: String, speaker: Int, rate: Float, callback: (FloatArray) -> Int)
    fun release()
}

class LocalModelTtsBackend internal constructor(
    private val repository: TtsModelRepository,
    private val createModel: (TtsModelEntry, File) -> LocalTtsModel
) : LocalTtsBackend {
    @Inject constructor(repository: TtsModelRepository) : this(repository, ::loadNativeModel)

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    private var preparationGeneration = 0L
    private var track: AudioTrack? = null
    private var session: Session? = null
    private var speechJob: Job? = null
    private var preparationJob: Job? = null
    private var preparationTarget: String? = null
    private var precedingRelease: Deferred<Boolean>? = null

    // Each session owns its executor and native instance, including asynchronous release.
    private class Session(val precedingRelease: Deferred<Boolean>?) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "LocalTts").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var model: LocalTtsModel? = null
        var modelId: String? = null
        var modelRevision = -1L
        var releaseFailed = false
    }

    private fun session(): Session = synchronized(lock) {
        session ?: Session(precedingRelease).also { session = it }
    }

    override fun voices(): List<TtsVoiceOption> = TtsModelCatalog.entries
        .filter { it.bundled || repository.states.value[it.id] == TtsModelState.Installed }
        .flatMap { entry -> entry.voices.map { voice ->
            TtsVoiceOption(entry.voiceId(voice.speakerId), voice.localeTag, TtsVoiceMode.LOCAL_MODEL, 500, voice.nameRes)
        } }

    override fun refresh(onComplete: () -> Unit) {
        val owner = session()
        owner.scope.launch {
            repository.refresh()
            handler.post { if (currentSession(owner)) onComplete() }
        }
    }

    override fun prepare(voiceId: String?): Job? {
        val entry = TtsModelCatalog.findVoice(voiceId)?.first
        if (entry != null && speechJob?.isActive == true) return null
        if (entry?.id == preparationTarget && preparationJob?.isActive == true) return preparationJob
        val token = synchronized(lock) { ++preparationGeneration }
        preparationJob?.cancel()
        preparationJob = null
        preparationTarget = entry?.id
        if (entry == null) return null
        val owner = session()
        preparationJob = owner.scope.launch {
            try {
                repository.withModelRevision(entry.id) { directory, revision ->
                    if (!currentPreparation(owner, token)) return@withModelRevision
                    obtainModel(owner, entry, directory, revision)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TtsModelException) {
                Log.w("LocalTts", "stage=prepare category=model_unavailable")
            } catch (_: LinkageError) {
                Log.w("LocalTts", "stage=prepare category=native_unavailable")
            } catch (_: Exception) {
                Log.w("LocalTts", "stage=prepare category=initialization_failed")
            }
        }
        return preparationJob
    }

    override fun speak(text: String, voiceId: String, rate: Float, callback: (TtsPlaybackResult) -> Unit) {
        val selection = TtsModelCatalog.findVoice(voiceId)
        // Keep a matching silent preparation alive: cancelling an in-flight first extraction
        // would delete its temporary files and make this utterance repeat all that work.
        stopSpeech(keepPreparationFor = selection?.first?.id)
        val token = synchronized(lock) { ++generation }
        val owner = session()
        if (selection == null) {
            callback(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
            return
        }
        val (entry, voice) = selection
        val requestedAt = SystemClock.elapsedRealtime()
        speechJob = owner.scope.launch {
            try {
                repository.withModelRevision(entry.id) { directory, revision ->
                    if (!current(token)) return@withModelRevision
                    val model = obtainModel(owner, entry, directory, revision)
                    if (!current(token)) return@withModelRevision
                    play(token, model, text, voice.speakerId, rate) {
                        Log.d("LocalTts", "stage=first_pcm elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}")
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

    private suspend fun obtainModel(owner: Session, entry: TtsModelEntry, directory: File, revision: Long): LocalTtsModel {
        coroutineContext.ensureActive()
        if (owner.releaseFailed || owner.precedingRelease?.await() == false) {
            throw TtsModelException(TtsModelFailure.UNAVAILABLE)
        }
        if (owner.modelId != entry.id || owner.modelRevision != revision) releaseModel(owner)
        val model = owner.model ?: run {
            coroutineContext.ensureActive()
            if (!currentSession(owner)) throw CancellationException("Local TTS session closed")
            val started = SystemClock.elapsedRealtime()
            val loaded = createModel(entry, directory)
            Log.d("LocalTts", "stage=load elapsedMs=${SystemClock.elapsedRealtime() - started}")
            // Native construction is not cooperatively cancellable. Keep ownership even
            // after shutdown so serialized cleanup records release failures before reopen.
            owner.model = loaded
            owner.modelId = entry.id
            owner.modelRevision = revision
            if (!currentSession(owner)) throw CancellationException("Local TTS session closed")
            loaded
        }
        coroutineContext.ensureActive()
        return model
    }

    private fun releaseModel(owner: Session) {
        val old = owner.model
        owner.model = null
        owner.modelId = null
        owner.modelRevision = -1L
        if (old != null) {
            // A failed native release makes further model construction unsafe.
            owner.releaseFailed = true
            old.release()
            owner.releaseFailed = false
        }
    }

    private fun play(token: Long, model: LocalTtsModel, text: String, speaker: Int, rate: Float, onStarted: () -> Unit) {
        val sampleRate = model.sampleRate
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
            model.generate(text, speaker, rate, callback)
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

    override fun stop(preservePreparation: Boolean) =
        stopSpeech(keepPreparationFor = preparationTarget.takeIf { preservePreparation })

    private fun stopSpeech(keepPreparationFor: String?) {
        synchronized(lock) {
            generation++
            try {
                track?.pause()
                track?.flush()
            } catch (_: IllegalStateException) {
                Log.w("LocalTts", "Audio stop failed")
            }
            if (keepPreparationFor == null || keepPreparationFor != preparationTarget) {
                preparationGeneration++
                preparationJob?.cancel()
                preparationJob = null
                preparationTarget = null
            }
        }
        speechJob?.cancel()
        speechJob = null
    }

    override fun shutdown() {
        stop()
        val (old, released) = synchronized(lock) {
            val old = session ?: return
            session = null
            val released = CompletableDeferred<Boolean>()
            precedingRelease = released
            old to released
        }
        old.scope.cancel()
        // Cleanup outlives the cancelled session and preserves the full rapid-reopen release chain.
        CoroutineScope(old.dispatcher).launch {
            var succeeded = false
            try {
                val predecessorsReleased = old.precedingRelease?.await() != false
                releaseModel(old)
                succeeded = predecessorsReleased && !old.releaseFailed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("LocalTts", "Model release failed")
            } finally {
                released.complete(succeeded)
                old.dispatcher.close()
            }
        }
    }

    private fun currentSession(owner: Session) = synchronized(lock) { session === owner } && owner.scope.isActive
    private fun currentPreparation(owner: Session, token: Long) =
        synchronized(lock) { preparationGeneration == token && session === owner } && owner.scope.isActive
    private fun current(token: Long) = synchronized(lock) { generation == token }
    private fun publish(token: Long, callback: (TtsPlaybackResult) -> Unit, result: TtsPlaybackResult) {
        handler.post { if (current(token)) callback(result) }
    }
}

private fun loadNativeModel(entry: TtsModelEntry, directory: File): LocalTtsModel {
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
    return object : LocalTtsModel {
        override val sampleRate: Int get() = model.sampleRate()
        override fun generate(text: String, speaker: Int, rate: Float, callback: (FloatArray) -> Int) {
            model.generateWithCallback(text, speaker, rate, callback)
        }
        override fun release() = model.release()
    }
}
