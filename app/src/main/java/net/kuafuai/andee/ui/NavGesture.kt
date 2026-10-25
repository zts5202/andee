package net.kuafuai.andee.ui

import kotlin.math.abs

/**
 * What a finger on the expanded card is asking for.
 *
 * The card is a `TYPE_APPLICATION_OVERLAY`, which is drawn above the navigation
 * bar. A swipe that starts on the card never becomes a system Back or Home, so
 * the decision has to be made here. No Android types: a unit test locks it.
 */
object NavGesture {
    enum class Kind { BACK, HOME }

    /**
     * @param edgePx how close to the left or right [downX] must be to be a back swipe
     * @param homeBandPx how close to the bottom [downY] must be to be a home swipe
     * @param triggerPx how far the finger must travel before it counts
     */
    fun classify(
        downX: Float,
        downY: Float,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        edgePx: Int,
        homeBandPx: Int,
        triggerPx: Int,
    ): Kind? {
        if (width <= 0 || height <= 0 || triggerPx <= 0) return null
        val dx = x - downX
        val dy = y - downY
        val adx = abs(dx)
        val ady = abs(dy)
        val fromLeft = downX <= edgePx
        val fromRight = downX >= width - edgePx
        val inward = (fromLeft && dx > 0f) || (fromRight && dx < 0f)
        if ((fromLeft || fromRight) && inward && adx >= triggerPx && adx > ady) return Kind.BACK
        if (downY >= height - homeBandPx && dy <= -triggerPx && ady > adx) return Kind.HOME
        return null
    }
}

/**
 * Another app just came to the front while the card is expanded.
 *
 * Home must fold the card and must not start our catcher activity back over
 * the launcher — that reorder is what makes a successful Home look like nothing
 * happened. System UI (the shade, recents) is not a destination. Anything else
 * may have stolen the back-key focus, so the catcher is brought back.
 */
object ForegroundNav {
    enum class Action { IGNORE, FOLD, CATCH }

    fun action(pkg: String, ours: String, home: Boolean, systemUi: Boolean): Action {
        if (pkg.isEmpty() || pkg == ours) return Action.IGNORE
        if (systemUi) return Action.IGNORE
        if (home) return Action.FOLD
        return Action.CATCH
    }

    fun isSystemUi(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg == "com.miui.systemui"
}
