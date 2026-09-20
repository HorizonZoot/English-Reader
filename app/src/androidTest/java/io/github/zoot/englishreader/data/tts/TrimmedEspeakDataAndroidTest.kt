package io.github.zoot.englishreader.data.tts

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Proves the bundled model still phonemizes and synthesizes English after the non-English
 * espeak-ng dictionaries were dropped from the archive. The JVM tests cannot cover this: the
 * espeak-ng failure modes ("Wrong version of espeak-ng-data", missing `en_dict`) only surface
 * inside the native library, which needs a real device.
 */
@RunWith(AndroidJUnit4::class)
class TrimmedEspeakDataAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry = TtsModelCatalog.libritts
    @get:Rule val temporary = TemporaryFolder(context.cacheDir)

    @Test
    fun bundledModel_afterDictionaryTrim_synthesizesEnglishAudio() {
        // An x86_64 emulator may advertise arm64 translation; require arm64 as its primary ABI.
        assumeTrue(
            "Native TTS test requires arm64-v8a as the primary ABI; found ${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        )
        val repository = TtsModelRepository(
            temporary.newFolder(), { context.assets.open(it) }, OkHttpClient(), Dispatchers.IO, listOf(entry)
        )
        runBlocking {
            repository.withModel(entry.id) { directory ->
                val dictionaries = File(directory, "espeak-ng-data").list().orEmpty()
                    .filter { it.endsWith("_dict") }
                assertEquals("only the English dictionary should ship", listOf("en_dict"), dictionaries.sorted())

                val config = OfflineTtsModelConfig(numThreads = 2, debug = false, provider = "cpu").apply {
                    vits = OfflineTtsVitsModelConfig(
                        model = File(directory, entry.modelFile).absolutePath,
                        tokens = File(directory, "tokens.txt").absolutePath,
                        dataDir = File(directory, "espeak-ng-data").absolutePath
                    )
                }
                val tts = OfflineTts(config = OfflineTtsConfig(model = config, maxNumSentences = 1))
                try {
                    // load() in LocalTtsBackend rejects the model unless both of these match.
                    assertEquals(entry.sampleRate, tts.sampleRate())
                    assertEquals(entry.speakerCount, tts.numSpeakers())

                    val text = "It expands our vocabulary, improves our focus, and enhances our imagination."
                    val started = System.nanoTime()
                    val audio = tts.generate(text, 100, 1.0f)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000

                    val samples = audio.samples
                    assertTrue("engine returned no samples", samples.isNotEmpty())

                    // Silence would also be "no crash", so require actual signal energy.
                    val peak = samples.maxOf { abs(it) }
                    assertTrue("synthesized audio is silent (peak=$peak)", peak > 0.01f)

                    val seconds = samples.size.toDouble() / entry.sampleRate
                    assertTrue("audio far too short for the sentence: ${"%.2f".format(seconds)}s", seconds > 2.0)
                    Log.i(
                        "TrimmedEspeak",
                        "TRIM-OK samples=${samples.size} seconds=${"%.2f".format(seconds)} " +
                            "peak=${"%.3f".format(peak)} synthesisMs=$elapsedMs rtf=${"%.3f".format(elapsedMs / 1000.0 / seconds)}"
                    )
                } finally {
                    tts.release()
                }
            }
        }
    }
}
