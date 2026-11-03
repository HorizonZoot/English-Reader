package io.github.zoot.englishreader.ui.theme

import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    @Test
    fun spec_isHundredMillisecondTween() {
        val spec = Motion.spec<Float>() as TweenSpec<Float>

        assertEquals(100, Motion.DURATION_MS)
        assertEquals(Motion.DURATION_MS, spec.durationMillis)
        assertEquals(0, spec.delay)
    }
}
