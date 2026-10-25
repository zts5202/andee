package net.kuafuai.andee.ui

/**
 * Ball ↔ card as one shape. [t] is 0 at the resting ball and 1 at the card.
 *
 * Expand walks 0→1 in [EXPAND_MS]; collapse walks 1→0 in [COLLAPSE_MS].
 * Content stays out until [t] passes [CONTENT_AT]. The ball icon fades across
 * the first [ICON_MS] of an expand and the last [ICON_MS] of a collapse.
 * No second view and no crossfade — the window's own edges and corner do it.
 *
 * The resting compact window stays the real ball (28 dp). The spec's ~56 dp
 * circle is the design reference for "starts round"; this window is already
 * that circle, and a larger idle frame would eat taps around the sphere.
 */
object MorphMotion {
    const val EXPAND_MS = 360L
    const val COLLAPSE_MS = 280L
    const val CONTENT_AT = 0.40f
    const val ICON_MS = 180L
    const val CORNER_CARD_DP = 22
    const val CONTENT_SLIDE_DP = 8f

    const val PRESS_SCALE = 0.92f
    const val PRESS_MS = 80L
    const val RELEASE_MS = 220L
    const val RELEASE_PEAK = 1.03f

    const val DRAG_SCALE = 1.06f
    const val DRAG_LIFT_MS = 80L
    const val DRAG_ELEVATION_DP = 12f
    const val REST_ELEVATION_DP = 6f

    const val SETTLE_MS = 200L
    const val EDGE_SNAP_MS = 320L
    const val EDGE_FRACTION = 0.28f
    const val EDGE_OVERSHOOT_DP = 4

    const val APPEAR_MS = 280L
    const val APPEAR_FROM = 0.6f
    const val DISMISS_MS = 200L
    const val DISMISS_TO = 0.7f

    /**
     * Content alpha, and how much of the 8 dp slide is left (1 = still 8 dp
     * below, 0 = seated). Flat zero until [CONTENT_AT], then linear.
     */
    fun content(t: Float): Pair<Float, Float> {
        val u = ((t - CONTENT_AT) / (1f - CONTENT_AT)).coerceIn(0f, 1f)
        return u to (1f - u)
    }

    /**
     * Ball-icon alpha. [expanding] is this animation's direction.
     * Expand: gone after the first [ICON_MS]. Collapse: back during the last
     * [ICON_MS].
     */
    fun iconAlpha(t: Float, expanding: Boolean): Float {
        val fadeBy = if (expanding) {
            ICON_MS.toFloat() / EXPAND_MS
        } else {
            ICON_MS.toFloat() / COLLAPSE_MS
        }
        return (1f - t / fadeBy).coerceIn(0f, 1f)
    }

    /** t = 0 is a circle (half the start side). t = 1 is [endRadiusPx]. */
    fun cornerRadiusPx(t: Float, startSidePx: Float, endRadiusPx: Float): Float {
        val start = startSidePx / 2f
        val u = t.coerceIn(0f, 1f)
        return start + (endRadiusPx - start) * u
    }

    enum class Edge { LEFT, RIGHT }

    /**
     * Which horizontal edge a released ball should snap to.
     *
     * Inside [fraction] of the screen width from the left or the right snaps
     * to that side. The middle does not: a release there settles where the
     * finger left it. If both zones contain the centre (only when [fraction]
     * is past a half), the midline picks. The 0.28 zones do not overlap.
     */
    fun edgeSnap(ballLeft: Int, ballSide: Int, screenWidth: Int): Edge? =
        edgeSnap(ballLeft, ballSide, screenWidth, EDGE_FRACTION)

    internal fun edgeSnap(ballLeft: Int, ballSide: Int, screenWidth: Int, fraction: Float): Edge? {
        if (screenWidth <= 0 || ballSide <= 0) return null
        val center = ballLeft + ballSide / 2f
        val threshold = screenWidth * fraction
        val nearLeft = center < threshold
        val nearRight = screenWidth - center < threshold
        return when {
            nearLeft && nearRight -> if (center * 2f <= screenWidth) Edge.LEFT else Edge.RIGHT
            nearLeft -> Edge.LEFT
            nearRight -> Edge.RIGHT
            else -> null
        }
    }

    /**
     * Tap-release scale. One overshoot to [RELEASE_PEAK], then back to 1.
     * Damping ≈ 0.7 is this shape: a single overshoot, no second bounce.
     * [from] is the scale under the finger (0.92 once the press has landed).
     */
    fun releaseScale(u: Float, from: Float = PRESS_SCALE): Float {
        val x = u.coerceIn(0f, 1f)
        return if (x < 0.45f) {
            val p = x / 0.45f
            val e = 1f - (1f - p) * (1f - p)
            from + (RELEASE_PEAK - from) * e
        } else {
            val p = (x - 0.45f) / 0.55f
            val e = 1f - (1f - p) * (1f - p)
            RELEASE_PEAK + (1f - RELEASE_PEAK) * e
        }
    }
}
