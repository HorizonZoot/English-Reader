package io.github.zoot.englishreader.util

import android.media.MediaPlayer
import androidx.lifecycle.ViewModelStore
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.viewmodel.ReadingViewModelFixture
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPlayerTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun play_cancelledDuringBlockingPrepare_releasesWithoutStartingOrFallback() = runTest {
        val preparing = CompletableDeferred<Unit>()
        val releasePrepare = CountDownLatch(1)
        val media = mockk<MediaPlayer>(relaxed = true)
        every { media.prepare() } answers {
            preparing.complete(Unit)
            check(releasePrepare.await(10, TimeUnit.SECONDS))
        }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val audio = AudioPlayer(dispatcher) { media }
        val token = audio.beginRequest()
        var failures = 0
        try {
            val playback = launch { audio.play(token, "test.mp3", onError = { failures++ }) }
            preparing.await()
            playback.cancel()
            releasePrepare.countDown()
            playback.join()

            verify(exactly = 0) { media.start() }
            verify(exactly = 1) { media.release() }
            assertFalse(audio.isCurrent(token))
            assertEquals(0, failures)
        } finally {
            releasePrepare.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun play_revokedBeforeQueueRuns_doesNotCreateMediaPlayer() = runTest {
        var playersCreated = 0
        val audio = AudioPlayer(StandardTestDispatcher(testScheduler)) {
            playersCreated++
            mockk(relaxed = true)
        }
        val old = audio.beginRequest()
        val playback = launch { audio.play(old, "old.mp3") }
        val current = audio.beginRequest()
        runCurrent()
        playback.join()
        assertEquals(0, playersCreated)
        assertTrue(audio.isCurrent(current))
        audio.stop(old)
        assertTrue(audio.isCurrent(current))
    }

    @Test
    fun play_newRequestWhileOldPrepareBlocks_onlyNewPlayerStarts() = runTest {
        val preparing = CompletableDeferred<Unit>()
        val releasePrepare = CountDownLatch(1)
        val old = mockk<MediaPlayer>(relaxed = true)
        val current = mockk<MediaPlayer>(relaxed = true)
        val events = mutableListOf<String>()
        every { old.prepare() } answers {
            preparing.complete(Unit)
            check(releasePrepare.await(10, TimeUnit.SECONDS))
        }
        every { old.release() } answers { events += "release-old" }
        every { current.start() } answers { events += "start-current" }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val players = ArrayDeque(listOf(old, current))
        val audio = AudioPlayer(dispatcher) { players.removeFirst() }
        try {
            val first = audio.beginRequest()
            val firstJob = launch { audio.play(first, "old.mp3") }
            preparing.await()
            val second = audio.beginRequest()
            val secondJob = launch { audio.play(second, "current.mp3") }
            runCurrent()
            audio.stop(first)
            releasePrepare.countDown()
            firstJob.join()
            secondJob.join()

            verify(exactly = 0) { old.start() }
            assertEquals(listOf("release-old", "start-current"), events)
            assertTrue(audio.isCurrent(second))
            audio.stop(second)
        } finally {
            releasePrepare.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun play_cancelledAfterStartBeforeReturning_releasesPublishedPlayer() = runTest {
        val media = mockk<MediaPlayer>(relaxed = true)
        val audio = AudioPlayer(StandardTestDispatcher(testScheduler)) { media }
        lateinit var playback: Job
        every { media.start() } answers { playback.cancel() }
        val token = audio.beginRequest()
        var errors = 0
        playback = launch { audio.play(token, "test.mp3", onError = { errors++ }) }
        runCurrent()
        playback.join()

        verify(exactly = 1) { media.start() }
        verify(exactly = 1) { media.release() }
        assertFalse(audio.isCurrent(token))
        assertEquals(0, errors)
    }

    @Test
    fun play_returnsAfterStartAndKeepsOnlyCurrentLateErrorCallback() = runTest {
        val media = mockk<MediaPlayer>(relaxed = true)
        val errorListener = slot<MediaPlayer.OnErrorListener>()
        every { media.setOnErrorListener(capture(errorListener)) } returns Unit
        val audio = AudioPlayer(StandardTestDispatcher(testScheduler)) { media }
        val token = audio.beginRequest()
        var errors = 0
        audio.play(token, "test.mp3", onError = { errors++ })
        verify(exactly = 1) { media.start() }
        assertTrue(audio.isCurrent(token))
        errorListener.captured.onError(media, 1, 0)
        assertEquals(1, errors)

        val next = audio.beginRequest()
        errorListener.captured.onError(media, 1, 0)
        audio.stop(token)
        assertEquals(1, errors)
        assertTrue(audio.isCurrent(next))
        verify(exactly = 1) { media.release() }
    }

    @Test
    fun stop_oldVocabularyOwnerAfterReadingStarts_keepsReadingPlayer() = runTest {
        val old = mockk<MediaPlayer>(relaxed = true)
        val current = mockk<MediaPlayer>(relaxed = true)
        every { current.isPlaying } returns true
        val players = ArrayDeque(listOf(old, current))
        val audio = AudioPlayer(StandardTestDispatcher(testScheduler)) { players.removeFirst() }
        val cache = mockk<PronunciationAudioCache>(relaxed = true)
        coEvery { cache.get(any()) } returns null
        val settings = mockk<SettingsPreferences> {
            every { allowNetworkTts } returns flowOf(false)
            every { ttsReadingSettings } returns flowOf(TtsReadingSettings())
        }
        val vocabularyTts = mockk<TtsPlayer>(relaxed = true)
        val vocabulary = VocabularyViewModel(
            mockk(relaxed = true), mockk(relaxed = true), audio,
            mockk { every { isOnline() } returns true }, vocabularyTts, cache, settings
        )
        val reading = ReadingViewModelFixture(audioPlayer = audio).create()
        val vocabularyStore = ViewModelStore().apply { put("vocabulary", vocabulary) }
        val readingStore = ViewModelStore().apply { put("reading", reading) }
        try {
            vocabulary.playWordAudio(VocabularyEntity(word = "old", articleId = 1))
            runCurrent()
            reading.playWordAudio("current", "current.mp3")
            runCurrent()
            verify(exactly = 1) { current.start() }

            vocabulary.stopAudio()
            vocabularyStore.clear()
            verify(exactly = 1) { old.release() }
            verify(exactly = 0) { current.release() }
            verify(exactly = 1) { vocabularyTts.shutdown() }
            assertTrue(audio.isPlaying())
        } finally {
            vocabularyStore.clear()
            readingStore.clear()
        }
    }
}
