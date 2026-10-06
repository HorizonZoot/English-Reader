package io.github.zoot.englishreader.ui.component

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.ReadingPagination
import io.github.zoot.englishreader.model.ReadingTextKind
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingPageLayoutCacheTest {
    @get:Rule val compose = createComposeRule()
    private val size = mutableStateOf(IntSize(300, 600))
    private val density = mutableStateOf(Density(1f, 1f))
    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private val blocks = mutableStateOf(List(12) { index ->
        ReadingTextBlock(
            paragraphIndex = index,
            kind = ReadingTextKind.ORIGINAL,
            text = "Paragraph $index. " + "A sentence for pagination. ".repeat(6),
            style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
            gapBeforeDp = 12,
            textStartOffset = 10
        )
    })
    private var measurements = 0
    private var cancelAtMeasurement: Int? = null
    private lateinit var layoutState: State<ReadingPageLayout?>
    private val layout get() = layoutState.value

    @Test
    fun layout_heightOnlyChange_reusesMetricsAndUpdatesPagesWithoutLosingOffsets() {
        render()
        var previousPages = 0
        compose.runOnIdle {
            assertNotNull(layout)
            assertEquals(blocks.value.size, measurements)
            previousPages = ready().pages.size
            assertCoverage()
            size.value = IntSize(size.value.width, 300)
        }
        compose.runOnIdle {
            assertEquals(blocks.value.size, measurements)
            assertEquals(size.value, layout!!.size)
            assertNotEquals(previousPages, ready().pages.size)
            assertCoverage()
        }
    }

    @Test
    fun layout_widthContentTypographyAndEnvironmentChanges_invalidateMetrics() {
        render()
        val changes: List<() -> Unit> = listOf(
            { size.value = IntSize(240, size.value.height) },
            { blocks.value = blocks.value.map { it.copy(text = it.text + " Changed.") } },
            { blocks.value = blocks.value.map { it.copy(style = it.style.copy(fontSize = 20.sp)) } },
            { density.value = Density(1f, 1.2f) },
            { density.value = Density(1.5f, 1.2f) },
            { direction.value = LayoutDirection.Rtl }
        )
        changes.forEachIndexed { index, change ->
            compose.runOnIdle {
                assertEquals(blocks.value.size * (index + 1), measurements)
                change()
            }
            compose.runOnIdle {
                assertEquals(blocks.value.size * (index + 2), measurements)
                assertEquals(blocks.value, layout!!.blocks)
                assertEquals(size.value, layout!!.size)
                assertCoverage()
            }
        }
    }

    @Test
    fun layout_zeroHeightThenRestored_reusesCompletedMetrics() {
        render()
        compose.runOnIdle { size.value = IntSize(size.value.width, 0) }
        compose.runOnIdle {
            assertNull(layout)
            size.value = IntSize(size.value.width, 600)
        }
        compose.runOnIdle {
            assertNotNull(layout)
            assertEquals(blocks.value.size, measurements)
            assertCoverage()
        }
    }

    @Test
    fun layout_cancelledMeasurement_doesNotPublishOrReusePartialMetrics() {
        cancelAtMeasurement = 2
        render()
        compose.runOnIdle {
            assertEquals(2, measurements)
            assertNull(layout)
            cancelAtMeasurement = null
            size.value = IntSize(240, size.value.height)
        }
        compose.runOnIdle {
            assertNotNull(layout)
            assertEquals(2 + blocks.value.size, measurements)
            assertCoverage()
        }
    }

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides density.value,
                LocalLayoutDirection provides direction.value
            ) {
                val realMeasurer = rememberTextMeasurer(cacheSize = 0)
                val measurer = remember(realMeasurer) {
                    spyk(realMeasurer).also { spy ->
                        every { spy.measure(text = any<String>(), style = any(), constraints = any()) } answers {
                            measurements++
                            if (measurements == cancelAtMeasurement) throw CancellationException("cancel measurement")
                            callOriginal()
                        }
                    }
                }
                val result = rememberReadingPageLayout(blocks.value, size.value, measurer)
                SideEffect { layoutState = result }
            }
        }
    }

    private fun ready() = layout!!.pagination as ReadingPagination.Ready

    private fun assertCoverage() {
        val fragments = ready().pages.flatMap { it.fragments }
        blocks.value.forEachIndexed { index, block ->
            var cursor = block.textStartOffset
            fragments.filter { it.blockIndex == index }.forEach { fragment ->
                assertEquals(cursor, fragment.anchor.characterOffset)
                cursor = fragment.endOffset
            }
            assertEquals(block.textEndOffset, cursor)
        }
    }
}
