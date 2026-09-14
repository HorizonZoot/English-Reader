package io.github.zoot.englishreader

import io.github.zoot.englishreader.data.local.ThemeOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityTest {

    @Test
    fun installContentAfterInitialTheme_beforeEmission_doesNotInstallThenUsesFirstValueOnce() = runTest {
        val themes = MutableSharedFlow<ThemeOption>()
        val installedThemes = mutableListOf<ThemeOption>()
        val job = launch {
            installContentAfterInitialTheme(themes, installedThemes::add)
        }

        advanceUntilIdle()
        assertTrue(installedThemes.isEmpty())

        themes.emit(ThemeOption.DARK)
        advanceUntilIdle()

        assertEquals(listOf(ThemeOption.DARK), installedThemes)
        job.join()
    }

    @Test
    fun installContentAfterInitialTheme_cancelledBeforeEmission_doesNotInstall() = runTest {
        val themes = MutableSharedFlow<ThemeOption>()
        val installedThemes = mutableListOf<ThemeOption>()
        val job = launch {
            installContentAfterInitialTheme(themes, installedThemes::add)
        }

        advanceUntilIdle()
        job.cancelAndJoin()

        assertTrue(installedThemes.isEmpty())
    }

    @Test
    fun installContentAfterInitialTheme_initialFlowFailure_installsDefault() = runTest {
        val installedThemes = mutableListOf<ThemeOption>()
        val failingThemes = flow<ThemeOption> {
            throw IllegalStateException("read failed")
        }

        installContentAfterInitialTheme(failingThemes, installedThemes::add)

        assertEquals(listOf(ThemeOption.DEFAULT), installedThemes)
    }
}
