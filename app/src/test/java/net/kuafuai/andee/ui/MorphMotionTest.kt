package net.kuafuai.andee.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphMotionTest {
    @Test fun contentStaysOutUntilFortyPercent() {
        assertEquals(0f, MorphMotion.content(0f).first, 0.001f)
        assertEquals(1f, MorphMotion.content(0f).second, 0.001f)
        assertEquals(0f, MorphMotion.content(0.4f).first, 0.001f)
        assertEquals(1f, MorphMotion.content(0.4f).second, 0.001f)
        val mid = MorphMotion.content(0.7f)
        assertEquals(0.5f, mid.first, 0.001f)
        assertEquals(0.5f, mid.second, 0.001f)
        assertEquals(1f, MorphMotion.content(1f).first, 0.001f)
        assertEquals(0f, MorphMotion.content(1f).second, 0.001f)
    }

    @Test fun iconFadesInTheFirst180msOfExpandAndTheLast180msOfCollapse() {
        assertEquals(1f, MorphMotion.iconAlpha(0f, expanding = true), 0.001f)
        assertEquals(0.5f, MorphMotion.iconAlpha(0.25f, expanding = true), 0.001f)
        assertEquals(0f, MorphMotion.iconAlpha(0.5f, expanding = true), 0.001f)
        assertEquals(0f, MorphMotion.iconAlpha(1f, expanding = true), 0.001f)

        assertEquals(0f, MorphMotion.iconAlpha(1f, expanding = false), 0.001f)
        val fadeFrom = MorphMotion.ICON_MS.toFloat() / MorphMotion.COLLAPSE_MS
        assertEquals(0f, MorphMotion.iconAlpha(fadeFrom, expanding = false), 0.02f)
        assertEquals(1f, MorphMotion.iconAlpha(0f, expanding = false), 0.001f)
        assertTrue(MorphMotion.iconAlpha(fadeFrom / 2f, expanding = false) > 0.4f)
    }

    @Test fun cornerGoesFromCircleToCard() {
        assertEquals(40f, MorphMotion.cornerRadiusPx(0f, 80f, 22f), 0.001f)
        assertEquals(22f, MorphMotion.cornerRadiusPx(1f, 80f, 22f), 0.001f)
        assertEquals(31f, MorphMotion.cornerRadiusPx(0.5f, 80f, 22f), 0.001f)
    }

    @Test fun edgeSnapUsesTwentyEightPercentAndLeavesTheMiddle() {
        assertEquals(MorphMotion.Edge.LEFT, MorphMotion.edgeSnap(0, 28, 1000))
        assertEquals(MorphMotion.Edge.RIGHT, MorphMotion.edgeSnap(900, 28, 1000))
        assertNull(MorphMotion.edgeSnap(486, 28, 1000))
        // Exactly on the 28% line is not inside it.
        assertNull(MorphMotion.edgeSnap(280 - 14, 28, 1000))
    }

    @Test fun overlappingZonesBreakOnTheMidline() {
        assertEquals(MorphMotion.Edge.LEFT, MorphMotion.edgeSnap(45, 10, 100, 0.6f))
        assertEquals(MorphMotion.Edge.RIGHT, MorphMotion.edgeSnap(50, 10, 100, 0.6f))
    }

    @Test fun releaseSpringsOnceToThePeakThenRests() {
        assertEquals(MorphMotion.PRESS_SCALE, MorphMotion.releaseScale(0f), 0.001f)
        assertEquals(MorphMotion.RELEASE_PEAK, MorphMotion.releaseScale(0.45f), 0.001f)
        assertEquals(1f, MorphMotion.releaseScale(1f), 0.001f)
        var peak = 0f
        var peaked = false
        var prev = 0f
        for (i in 0..20) {
            val s = MorphMotion.releaseScale(i / 20f)
            if (!peaked && s + 0.0001f < prev) peaked = true
            if (peaked) assertTrue(s <= prev + 0.0001f)
            if (s > peak) peak = s
            prev = s
        }
        assertEquals(MorphMotion.RELEASE_PEAK, peak, 0.001f)
    }
}
