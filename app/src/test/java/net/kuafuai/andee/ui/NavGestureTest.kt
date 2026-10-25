package net.kuafuai.andee.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavGestureTest {
    private val w = 1080
    private val h = 2400
    private val edge = 96
    private val band = 108
    private val trigger = 192

    @Test fun edgeSwipeInwardIsBack() {
        assertEquals(
            NavGesture.Kind.BACK,
            NavGesture.classify(20f, 800f, 400f, 820f, w, h, edge, band, trigger),
        )
        assertEquals(
            NavGesture.Kind.BACK,
            NavGesture.classify(1060f, 800f, 700f, 790f, w, h, edge, band, trigger),
        )
    }

    @Test fun verticalMoveFromTheEdgeIsNotBack() {
        assertNull(NavGesture.classify(20f, 800f, 40f, 1200f, w, h, edge, band, trigger))
    }

    @Test fun shortMoveAndTapAreNotNavigation() {
        assertNull(NavGesture.classify(20f, 800f, 80f, 800f, w, h, edge, band, trigger))
        assertNull(NavGesture.classify(20f, 800f, 20f, 800f, w, h, edge, band, trigger))
    }

    @Test fun swipeUpFromTheBottomBandIsHome() {
        assertEquals(
            NavGesture.Kind.HOME,
            NavGesture.classify(500f, 2350f, 520f, 1800f, w, h, edge, band, trigger),
        )
    }

    @Test fun swipeUpFromTheMiddleAndSwipeDownAreNotHome() {
        assertNull(NavGesture.classify(500f, 1200f, 500f, 600f, w, h, edge, band, trigger))
        assertNull(NavGesture.classify(500f, 2350f, 500f, 2390f, w, h, edge, band, trigger))
    }

    @Test fun outwardEdgeMoveIsNotBack() {
        assertNull(NavGesture.classify(20f, 800f, -200f, 800f, w, h, edge, band, trigger))
    }

    @Test fun homeFoldsAndDoesNotReorderOverTheLauncher() {
        assertEquals(
            ForegroundNav.Action.FOLD,
            ForegroundNav.action("com.miui.home", "net.kuafuai.andee", home = true, systemUi = false),
        )
        assertEquals(
            ForegroundNav.Action.IGNORE,
            ForegroundNav.action("com.android.systemui", "net.kuafuai.andee", home = false, systemUi = true),
        )
        assertEquals(
            ForegroundNav.Action.IGNORE,
            ForegroundNav.action("net.kuafuai.andee", "net.kuafuai.andee", home = false, systemUi = false),
        )
        assertEquals(
            ForegroundNav.Action.CATCH,
            ForegroundNav.action("com.tencent.mm", "net.kuafuai.andee", home = false, systemUi = false),
        )
    }
}
