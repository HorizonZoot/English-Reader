package io.github.zoot.englishreader.ui.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 句子弹窗定位。全部用例传 [LayoutDirection.Ltr]，**这不是遗漏**。
 *
 * `calculatePosition` 刻意不读自己那个 `layoutDirection` 参数——该参数是 Compose
 * `PopupPositionProvider` 接口强制的签名，而方向解析发生在更上游：调用方
 * （`ReadingScreen.kt` 与 `SentenceActionPopup.kt`）用 `LocalLayoutDirection` 从
 * `WindowInsets.safeDrawing.getLeft/getRight` 取值，传进来的 [SentencePopupSafeInsets]
 * 已经是方向解析后的绝对边距。本函数按绝对坐标钳制，因此对布局方向不敏感。
 *
 * 早先有两条用例传 `Rtl`，读起来像在验证 RTL 定位，实际传什么都得到同一结果——
 * 那是虚假信心。改为 `Ltr` 以免暗示不存在的覆盖。
 *
 * 若将来真要支持 RTL 语言（当前 manifest 未声明 `supportsRtl`，`res/` 也没有 RTL
 * 语言资源），需要的不是在这里补断言，而是先决定弹窗是否应水平镜像。
 */
class SentencePopupPositionProviderTest {
    private val parent = IntRect(100, 200, 500, 500)
    private val glyph = Rect(40f, 80f, 140f, 110f)
    private val popup = IntSize(160, 60)
    private val window = IntSize(600, 800)

    @Test
    fun calculatePosition_prefersBelowWhenSpaceExists() {
        val position = provider().calculatePosition(parent, window, LayoutDirection.Ltr, popup)

        assertEquals(318, position.y)
        assertEquals(110, position.x)
    }

    @Test
    fun calculatePosition_fallsAboveWhenBelowDoesNotFit() {
        val position = SentencePopupPositionProvider(
            localAnchor = glyph,
            preferAbove = false
        ).calculatePosition(IntRect(100, 650, 500, 800), window, LayoutDirection.Ltr, popup)

        assertEquals(662, position.y)
    }

    @Test
    fun calculatePosition_clampsHorizontalToWindow() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(0f, 80f, 20f, 110f)
        ).calculatePosition(IntRect(0, 200, 40, 500), window, LayoutDirection.Ltr, popup)

        assertEquals(0, position.x)
    }

    @Test
    fun calculatePosition_respectsSafeInsets() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(0f, 0f, 20f, 20f),
            preferAbove = false,
            safeInsets = SentencePopupSafeInsets(left = 24, top = 32, right = 24, bottom = 48)
        ).calculatePosition(IntRect(0, 40, 100, 200), window, LayoutDirection.Ltr, popup)

        assertEquals(24, position.x)
        assertEquals(68, position.y)
    }

    @Test
    fun calculatePosition_oversizedPopupIsClampedWithoutNegativeCoordinates() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(0f, 0f, 10f, 10f),
            safeInsets = SentencePopupSafeInsets(left = 12, top = 16, right = 12, bottom = 16)
        ).calculatePosition(IntRect(0, 0, 10, 10), IntSize(120, 80), LayoutDirection.Ltr, IntSize(300, 200))

        assertEquals(12, position.x)
        assertEquals(16, position.y)
    }

    @Test
    fun calculatePosition_neitherSideFits_respectsBelowPreferenceBeforeClamping() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(40f, 140f, 140f, 160f),
            preferAbove = false
        ).calculatePosition(
            anchorBounds = IntRect(0, 0, 300, 300),
            windowSize = IntSize(300, 300),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(160, 180)
        )

        assertEquals(120, position.y)
    }

    @Test
    fun calculatePosition_usesCompleteSentenceBoundsForVerticalPlacement() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(40f, 20f, 60f, 44f),
            localSentenceBounds = Rect(0f, 20f, 180f, 164f),
            preferAbove = false
        ).calculatePosition(
            anchorBounds = IntRect(0, 0, 300, 300),
            windowSize = IntSize(300, 500),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(160, 100)
        )

        // 被按下的字形底边止于 44，但整句一直占到 164。
        assertEquals(172, position.y)
    }

    @Test
    fun calculatePosition_readingViewportExcludesToolbarAndFooter() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(40f, 50f, 60f, 70f),
            localSentenceBounds = Rect(0f, 50f, 180f, 90f),
            safeInsets = SentencePopupSafeInsets(top = 24, bottom = 32),
            readingViewportBounds = Rect(24f, 120f, 576f, 640f)
        ).calculatePosition(
            anchorBounds = IntRect(0, 500, 300, 640),
            windowSize = window,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(200, 120)
        )

        // 下方 590 + 8 + 120 会穿过 640 的阅读底边；上方为 550 - 8 - 120。
        assertEquals(422, position.y)
        assertEquals(24, position.x)
    }

    @Test
    fun calculatePosition_topEdgeSentence_staysBelowAndInsideReadingViewport() {
        val position = SentencePopupPositionProvider(
            localAnchor = Rect(40f, 0f, 60f, 20f),
            localSentenceBounds = Rect(0f, 0f, 180f, 80f),
            readingViewportBounds = Rect(16f, 120f, 584f, 640f)
        ).calculatePosition(
            anchorBounds = IntRect(0, 124, 300, 300),
            windowSize = window,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(200, 160)
        )

        assertEquals(212, position.y)
        assertEquals(16, position.x)
    }

    @Test
    fun safeBounds_intersectsMeasuredViewportWithSystemInsets() {
        assertEquals(
            IntRect(24, 100, 360, 600),
            sentencePopupSafeBounds(
                windowSize = IntSize(400, 700),
                safeInsets = SentencePopupSafeInsets(left = 24, top = 40, right = 40, bottom = 32),
                readingViewportBounds = Rect(8f, 100f, 390f, 600f)
            )
        )
    }

    private fun provider() = SentencePopupPositionProvider(localAnchor = glyph)
}
