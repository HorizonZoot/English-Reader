package io.github.zoot.englishreader.data.audio

import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WordPronunciationTest {
    private var active: AudioPlayer.RequestToken? = null
    private val audio = mockk<AudioPlayer>(relaxed = true) {
        every { beginRequest() } answers { AudioPlayer.RequestToken().also { active = it } }
        every { isCurrent(any()) } answers { firstArg<AudioPlayer.RequestToken>() === active }
        every { stop(any()) } answers { if (firstArg<AudioPlayer.RequestToken>() === active) active = null }
    }
    private val network = mockk<NetworkChecker> { every { isOnline() } returns true }
    private val cache = mockk<PronunciationAudioCache>(relaxed = true)
    private val settings = mockk<SettingsPreferences> {
        every { allowNetworkTts } returns MutableStateFlow(false)
        every { ttsReadingSettings } returns MutableStateFlow(TtsReadingSettings())
    }

    @Test
    fun stop_oldScreenWithLateCacheResult_doesNotRevokeNewScreensPlayback() = runTest {
        val oldResult = CompletableDeferred<File?>()
        val newFile = File("new-word.mp3")
        coEvery { cache.get("old") } coAnswers { withContext(NonCancellable) { oldResult.await() } }
        coEvery { cache.get("new") } returns newFile
        val oldTts = mockk<TtsPlayer>(relaxed = true)
        val newTts = mockk<TtsPlayer>(relaxed = true)
        val old = WordPronunciation(this, audio, network, oldTts, cache, settings) { error("stale feedback") }
        val current = WordPronunciation(this, audio, network, newTts, cache, settings) { error("unexpected feedback") }
        old.play("context:1", "old")
        runCurrent()
        current.play("vocabulary:1", "new")
        runCurrent()
        val newToken = active
        old.stop()
        oldResult.complete(File("old-word.mp3"))
        runCurrent()
        assertSame(newToken, active)
        coVerify(exactly = 1) { audio.play(newToken!!, newFile.absolutePath, any(), any()) }
        coVerify(exactly = 0) { audio.play(any(), File("old-word.mp3").absolutePath, any(), any()) }
        assertNull(old.loadingKey.value)
        current.stop()
        assertNull(active)
    }

    @Test
    fun play_sameWordFromAnotherSource_ignoresOldMediaErrorAndUsesNewLoadingIdentity() = runTest {
        val firstPrepared = CompletableDeferred<Unit>()
        val secondPrepared = CompletableDeferred<Unit>()
        val errors = mutableListOf<(Exception) -> Unit>()
        coEvery { cache.get("bank") } returns null
        coEvery { audio.play(any(), any(), any(), any()) } coAnswers {
            errors += arg<(Exception) -> Unit>(3)
            if (errors.size == 1) firstPrepared.await() else secondPrepared.await()
        }
        val tts = mockk<TtsPlayer>(relaxed = true)
        val player = WordPronunciation(this, audio, network, tts, cache, settings) { error("stale feedback") }
        player.play("context:1", "bank")
        runCurrent()
        player.play("vocabulary:1", "bank")
        runCurrent()
        assertEquals("vocabulary:1", player.loadingKey.value)
        errors.first().invoke(IllegalStateException("old playback"))
        runCurrent()
        assertEquals("vocabulary:1", player.loadingKey.value)
        coVerify(exactly = 0) { tts.speakWord(any(), any(), any(), any()) }
        secondPrepared.complete(Unit)
        runCurrent()
        assertNull(player.loadingKey.value)
        player.stop()
    }
}
