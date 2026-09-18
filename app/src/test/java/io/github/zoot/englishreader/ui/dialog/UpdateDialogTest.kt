package io.github.zoot.englishreader.ui.dialog

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dialog_markdownAndVersion_showsNotesAndLaterDoesNotUpdate() {
        var dismissed = 0
        var updated = 0
        compose.setContent {
            EnglishReaderTheme {
                UpdateDialog("v0.2.0", "## What's New\n- **Improved** import", { updated++ }, { dismissed++ })
            }
        }
        compose.onNodeWithText("v0.2.0").assertIsDisplayed()
        compose.onNodeWithText("What's New\n• Improved import").assertIsDisplayed()
        compose.onNodeWithTag(UPDATE_DIALOG_DISMISS_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, updated)
        }
    }

    @Test
    fun dialog_emptyBody_showsFallbackAndConfirmCallsOnlyUpdate() {
        var updated = 0
        var dismissed = 0
        compose.setContent {
            EnglishReaderTheme {
                UpdateDialog("v0.2.0", null, { updated++ }, { dismissed++ })
            }
        }
        compose.onNodeWithText("本次更新没有提供说明。").assertIsDisplayed()
        compose.onNodeWithTag(UPDATE_DIALOG_CONFIRM_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, updated)
            assertEquals(0, dismissed)
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h360dp-land")
    fun dialog_longNotesInCompactDarkWindow_scrollsWhileButtonsRemainReachable() {
        val notes = (1..120).joinToString("\n") { "- Improvement $it" }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                EnglishReaderTheme(darkTheme = true) {
                    UpdateDialog("v0.2.0", notes, {}, {})
                }
            }
        }
        val scroll = compose.onNode(hasScrollAction())
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.value() > 0)
        assertEquals(range.maxValue(), range.value(), 1f)
        compose.onNodeWithTag(UPDATE_DIALOG_DISMISS_TAG).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag(UPDATE_DIALOG_CONFIRM_TAG).assertIsDisplayed().assertHasClickAction()
    }
}
