package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Breathing rainbow glow around the four screen edges — "the agent is driving
 * this screen right now". Siri-style status light, not a control.
 *
 * Its own window, deliberately, and that's the whole reason this class exists
 * instead of a View inside [FloatingWindowUi]: the glow's main job is to be
 * visible while the card is *blanked* (folded away, screenshot), and
 * [FloatingWindowUi.setCompact] gets there by driving that window's alpha to
 * 0 — which would take a child View down with it.
 *
 * Always FLAG_NOT_TOUCHABLE. A status light that eats taps along every edge
 * would swallow back-gestures and notification pulls across the whole device.
 *
 * Being a separate window also means it is composited into
 * `AccessibilityService.takeScreenshot` on its own, so the capture path has to
 * blank it too — see [setVisibleForCapture].
 *
 * The load-bearing constraint, though, is touch: since Android 12 a synthesized
 * gesture is dropped when the app's overlays obscure the target at more than
 * 0.80 combined opacity, FLAG_NOT_TOUCHABLE or not, and the platform clamps an
 * untrusted non-touchable overlay to exactly 0.80. So this window plus the card
 * — also non-touchable while a gesture runs — compute to
 * `1 - 0.2 * 0.2 = 0.96` and every tap `ScreenController` injects gets thrown
 * away. [setVisibleForGesture] takes the window down for the ~1.5 s the glow
 * keeps breathing after a screenshot.
 *
 * Alpha 0 is not enough. A `MATCH_PARENT` overlay left in the window list at
 * alpha 0 still ate touches across the whole screen on HyperOS (measured,
 * Redmi K90 Pro Max), FLAG_NOT_TOUCHABLE included. The window exists only
 * while the glow is actually on screen — see [syncPresence].
 */
class EdgeRippleUi(private val context: Context) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var view: RippleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    // Folded into whether the window exists at all by [syncPresence]. Main thread only.
    private var captureHidden = false
    private var gestureHidden = false

    /** Whether the view is still drawing frames — see [syncPresence]. */
    private var glowing = false

    /** What [setBreathing] last asked for. Distinct from [glowing], which stays
     *  true through the fade so a stale idle callback cannot tear down a breath
     *  that just restarted. */
    private var breathWanted = false

    /** [hide] until the next [show]. A breath posted across that gap must not add a window. */
    private var closed = false

    /**
     * Nothing to put on screen yet. The window is created on the first breath.
     * Kept so startup and [net.kuafuai.andee.BodyCoreService.ensureOverlays]
     * can keep calling it.
     */
    fun show(): Boolean {
        closed = false
        return true
    }

    fun hide(): Boolean {
        closed = true
        breathWanted = false
        glowing = false
        captureHidden = false
        gestureHidden = false
        detach()
        view = null
        params = null
        return true
    }

    /** Build the view the first time a breath wants it. Does not add a window. */
    private fun ensureView(): Boolean {
        if (view != null) return true
        val v = RippleView(context) { onGlowIdle() }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            alpha = 1f
        }
        v.hideFromAccessibility()
        view = v
        params = p
        return true
    }

    private fun attach() {
        val v = view ?: return
        val p = params ?: return
        if (attached) return
        p.alpha = 1f
        try {
            wm.addView(v, p)
            attached = true
            v.invalidate()
            android.util.Log.i("Body", "edge ripple window added")
        } catch (t: Throwable) {
            android.util.Log.e("Body", "edge ripple addView failed", t)
        }
    }

    private fun detach() {
        val v = view ?: return
        if (!attached) return
        runCatching { wm.removeView(v) }
        attached = false
    }

    /**
     * Light the glow (fades in, breathes) or release it (fades out after a
     * minimum on-time, so a ~300 ms screenshot still reads as one breath
     * rather than a flicker).
     */
    fun setBreathing(on: Boolean) {
        ui.post {
            if (closed) return@post
            breathWanted = on
            if (on) {
                if (!ensureView()) return@post
                glowing = true
                view?.setBreathing(true)
                syncPresence()
            } else {
                view?.setBreathing(false)
                // Detached, nothing is drawing the fade, so the idle callback
                // will never arrive. Drop the window now.
                if (view == null || !attached) {
                    glowing = false
                    detach()
                }
            }
        }
    }

    /**
     * Same trick as [FloatingWindowUi.setVisibleForCapture], for the same
     * reason: without it every screenshot the brain receives has a glowing
     * border burned into it. Animation state is untouched, so the glow picks up
     * mid-breath when the capture is done.
     */
    fun setVisibleForCapture(visible: Boolean) {
        ui.post { captureHidden = !visible; syncPresence() }
    }

    /**
     * Drop out of the way of an injected gesture. Not cosmetic — see the class
     * KDoc: a glow still fading from the previous screenshot pushes the app past
     * the untrusted-touch ceiling and the tap silently never lands.
     */
    fun setVisibleForGesture(visible: Boolean) {
        ui.post { gestureHidden = !visible; syncPresence() }
    }

    /** Main thread only. */
    private fun onGlowIdle() {
        if (breathWanted) return
        glowing = false
        detach()
    }

    /**
     * Put the window up only while the glow is allowed to be seen.
     *
     * A capture or an injected gesture takes it down immediately — an alpha-0
     * fullscreen window is what was blocking the screen underneath. The fade
     * out stays attached: [setBreathing] leaves [glowing] set until this view
     * reports idle, and only then is the window removed.
     *
     * Main thread only.
     */
    private fun syncPresence() {
        if (closed || captureHidden || gestureHidden || !glowing) {
            detach()
            return
        }
        if (!ensureView()) return
        attach()
    }

    /**
     * Four inward LinearGradients, one per edge. Corners overlap and blend to
     * something brighter, which is what gives the effect its shape.
     *
     * Not a blurred stroked Path: BlurMaskFilter isn't hardware accelerated, so
     * a fullscreen one means a multi-megabyte software layer re-rasterized every
     * frame.
     */
    private class RippleView(
        context: Context,
        private val onIdle: () -> Unit,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * How far the glow reaches in from an edge. Thin on purpose: a wide
         * band covers real content and reads as the assistant still being in
         * the way, which is the opposite of what getting out of the way is for. This is a lit
         * rim with a short falloff, not a vignette.
         */
        private val maxBand = 24f * context.resources.displayMetrics.density

        private var band = 0f
        private var top: LinearGradient? = null
        private var bottom: LinearGradient? = null
        private var left: LinearGradient? = null
        private var right: LinearGradient? = null

        /**
         * The color, shared by all four edges. Centered on the view so hue
         * follows the angle out from the middle — which means it wraps around
         * all four corners with no seam, the thing a per-edge gradient can't
         * do. Rotated over time by [spin] for the sci-fi drift.
         */
        private var sweep: SweepGradient? = null
        private val spin = Matrix()
        private var cx = 0f
        private var cy = 0f

        private fun tint(color: Shader?, mask: Shader?): Shader? =
            if (color != null && mask != null) {
                ComposeShader(color, mask, PorterDuff.Mode.DST_IN)
            } else {
                mask
            }

        /** 0..1 fade envelope, independent of the breathing oscillation. */
        private var envelope = 0f
        private var lit = false
        private var litSince = 0L
        private var lastFrame = 0L
        private var phaseStart = 0L

        fun setBreathing(on: Boolean) {
            if (on) {
                // Restart the sine phase only on a cold start, so repeated
                // lights during one task don't stutter the rhythm.
                if (envelope <= 0f) phaseStart = SystemClock.uptimeMillis()
                litSince = SystemClock.uptimeMillis()
            }
            lit = on
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // Never let the bands meet in the middle on a narrow window.
            band = min(maxBand, min(w, h) * 0.22f)
            // Opaque white → transparent: these carry only the inward falloff.
            // The color comes from [sweep], and DST_IN keeps its pixels
            // wherever these are opaque.
            top = LinearGradient(0f, 0f, 0f, band, OPAQUE, CLEAR, Shader.TileMode.CLAMP)
            bottom = LinearGradient(0f, h.toFloat(), 0f, h - band, OPAQUE, CLEAR, Shader.TileMode.CLAMP)
            left = LinearGradient(0f, 0f, band, 0f, OPAQUE, CLEAR, Shader.TileMode.CLAMP)
            right = LinearGradient(w.toFloat(), 0f, w - band, 0f, OPAQUE, CLEAR, Shader.TileMode.CLAMP)
            cx = w / 2f
            cy = h / 2f
            sweep = SweepGradient(cx, cy, RAINBOW, null)
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            // First frame of a run has no previous timestamp; a dropped frame
            // shouldn't jump the envelope either.
            val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
            lastFrame = now

            val wantLit = lit || (now - litSince) < MIN_ON_MS
            envelope = if (wantLit) min(1f, envelope + dt / FADE_IN_S)
            else max(0f, envelope - dt / FADE_OUT_S)

            if (envelope > 0f) {
                // cos so a cold start begins dim and swells, rather than
                // snapping to mid-brightness.
                val t = (now - phaseStart) / 1000f
                val a = envelope * (0.55f - 0.45f * cos(t * BREATH_RATE))
                paint.alpha = (a.coerceIn(0f, 1f) * 255f).toInt()

                val w = width.toFloat()
                val h = height.toFloat()
                val s = sweep
                if (s != null) {
                    spin.setRotate(t * SPIN_DEG_S, cx, cy)
                    s.setLocalMatrix(spin)
                }
                // Rebuilt every frame on purpose. ComposeShader resolves its
                // children when it's constructed and isn't marked dirty when
                // one of them changes, so rotating the sweep in place does not
                // reach a composer built earlier. Four small shaders per frame,
                // only while the glow is lit.
                paint.shader = tint(s, top); canvas.drawRect(0f, 0f, w, band, paint)
                paint.shader = tint(s, bottom); canvas.drawRect(0f, h - band, w, h, paint)
                paint.shader = tint(s, left); canvas.drawRect(0f, 0f, band, h, paint)
                paint.shader = tint(s, right); canvas.drawRect(w - band, 0f, w, h, paint)
            }

            // `wantLit` has to keep the loop alive on its own: the cold-start
            // frame has dt == 0, so the envelope is still exactly 0 here and
            // keying the next frame off `envelope > 0f` alone would stall the
            // glow before it ever lights.
            if (wantLit || envelope > 0f) {
                postInvalidateOnAnimation()
            } else {
                // Idle costs nothing: the empty display list clears the
                // surface, and no further frames are scheduled. Posted rather
                // than called — the listener touches window layout, which has
                // no business happening inside a draw pass.
                lastFrame = 0L
                post(onIdle)
            }
        }

        companion object {
            /**
             * Hue around the ring. Last stop repeats the first so the sweep
             * closes on itself instead of showing a hard edge at 0°.
             */
            private val RAINBOW = intArrayOf(
                Color.parseColor("#FFFF4FA3"),  // pink
                Color.parseColor("#FFB14BFF"),  // violet
                Color.parseColor("#FF4F7BFF"),  // blue
                Color.parseColor("#FF2DE2FF"),  // cyan
                Color.parseColor("#FF3DFFB0"),  // spring
                Color.parseColor("#FFFFE04F"),  // amber
                Color.parseColor("#FFFF7A4F"),  // coral
                Color.parseColor("#FFFF4FA3"),  // pink again
            )

            /** Mask endpoints — only the alpha matters, see [onSizeChanged]. */
            private const val OPAQUE = Color.WHITE
            private const val CLEAR = Color.TRANSPARENT

            /** Degrees per second the rainbow drifts around the ring. */
            private const val SPIN_DEG_S = 18f

            private const val FADE_IN_S = 0.25f
            private const val FADE_OUT_S = 0.6f

            /** One breath is ~2.9 s at this rate. */
            private const val BREATH_RATE = 2.2f

            /** Long enough that a single screenshot still shows a full swell. */
            private const val MIN_ON_MS = 900L
        }
    }
}
