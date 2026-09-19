package io.github.zoot.englishreader.data.tts

import androidx.annotation.StringRes
import io.github.zoot.englishreader.R

enum class TtsModelKind { VITS, KOKORO }

data class TtsModelVoice(val speakerId: Int, @StringRes val nameRes: Int, val localeTag: String = "en-US")

data class TtsModelEntry(
    val id: String,
    @StringRes val nameRes: Int,
    val kind: TtsModelKind,
    val archiveBytes: Long,
    val archiveSha256: String,
    val unpackedBytes: Long,
    val fileCount: Int,
    val modelFile: String,
    val sampleRate: Int,
    val speakerCount: Int,
    val voices: List<TtsModelVoice>,
    val license: String,
    val assetPath: String? = null,
    val downloadUrl: String? = null,
    val archiveRoot: String = ""
) {
    val bundled: Boolean get() = assetPath != null
    val requiredFiles: List<String> get() = listOf(modelFile, "tokens.txt", "espeak-ng-data/phondata") +
        if (kind == TtsModelKind.KOKORO) listOf("voices.bin") else emptyList()

    fun voiceId(speakerId: Int): String = "model/$id/$speakerId"
}

object TtsModelCatalog {
    // Piper's speaker_id_map maps 114 -> 100, 3922 -> 0, 6104 -> 12.
    // Reader names: https://www.openslr.org/resources/141/doc.tar.gz (speakers.tsv).
    val libritts = TtsModelEntry(
        id = "libritts_r-medium-int8",
        nameRes = R.string.tts_model_libritts,
        kind = TtsModelKind.VITS,
        // Repacked from the upstream release: the 112 non-English espeak-ng *_dict files are
        // dropped (ru_dict alone is 8.5 MB). espeak-ng loads dictionaries lazily per language
        // via "<dataDir>/<lang>_dict", so en_dict is the only one this app can reach. The
        // lang/ and voices/ trees stay — the engine resolves voice definitions through them.
        archiveBytes = 16_241_634,
        archiveSha256 = "06763246143d7d574c54e8ce7f8a73d7ae9647fccba3f8f0db28e2c25830c3c6",
        unpackedBytes = 23_377_312,
        fileCount = 247,
        modelFile = "en_US-libritts_r-medium.onnx",
        sampleRate = 22_050,
        speakerCount = 904,
        voices = listOf(
            TtsModelVoice(100, R.string.tts_voice_jen),
            TtsModelVoice(0, R.string.tts_voice_ashley),
            TtsModelVoice(12, R.string.tts_voice_juliana)
        ),
        license = "CC BY 4.0",
        assetPath = "tts/libritts_r-medium-int8.zip"
    )

    // The v0.19 IDs are different from Kokoro v1.x. Keep this version-specific mapping.
    // https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
    val kokoro = TtsModelEntry(
        id = "kokoro-int8-en-v0_19",
        nameRes = R.string.tts_model_kokoro,
        kind = TtsModelKind.KOKORO,
        archiveBytes = 103_248_205,
        archiveSha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
        unpackedBytes = 157_947_103,
        fileCount = 360,
        modelFile = "model.int8.onnx",
        sampleRate = 24_000,
        speakerCount = 11,
        voices = listOf(
            TtsModelVoice(1, R.string.tts_voice_bella),
            TtsModelVoice(2, R.string.tts_voice_nicole),
            TtsModelVoice(3, R.string.tts_voice_sarah),
            TtsModelVoice(4, R.string.tts_voice_sky),
            TtsModelVoice(5, R.string.tts_voice_adam),
            TtsModelVoice(6, R.string.tts_voice_michael),
            TtsModelVoice(7, R.string.tts_voice_emma, "en-GB"),
            TtsModelVoice(9, R.string.tts_voice_george, "en-GB")
        ),
        license = "Apache-2.0",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
        archiveRoot = "kokoro-int8-en-v0_19"
    )

    val entries = listOf(libritts, kokoro)
    val defaultVoiceId: String = libritts.voiceId(100)

    fun findVoice(id: String?): Pair<TtsModelEntry, TtsModelVoice>? = entries.firstNotNullOfOrNull { entry ->
        entry.voices.firstOrNull { entry.voiceId(it.speakerId) == id }?.let { entry to it }
    }

    fun isModelVoice(id: String?): Boolean = id?.startsWith("model/") == true
}

enum class TtsModelFailure { NETWORK, STORAGE, CORRUPT, UNAVAILABLE }

class TtsModelException(val reason: TtsModelFailure) : java.io.IOException()

sealed interface TtsModelState {
    data object NotInstalled : TtsModelState
    data class Downloading(val bytes: Long, val totalBytes: Long) : TtsModelState
    data object Installing : TtsModelState
    data object Installed : TtsModelState
    data object Removing : TtsModelState
    data class Failed(val reason: TtsModelFailure) : TtsModelState
}
