package net.kuafuai.andee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Receives system Back while the expanded card is up.
 *
 * HyperOS gesture-back follows the focused *activity*. The card is a
 * `TYPE_APPLICATION_OVERLAY`, and on the K90 `dumpsys` keeps focus on whatever
 * app is behind it, so `FLAG_NOT_FOCUSABLE` cleared on the overlay and an
 * `OnBackInvokedCallback` registered there never run. This activity is the
 * focused window instead. It draws nothing the user sees — the card overlay
 * is already covering the display — and Back folds that card the same way ✕
 * does. It does not stop the service.
 *
 * Not translucent: a translucent window is easy for this ROM to leave out of
 * the back chain. The overlay sits above this window, so the opaque frame is
 * not what the user looks at.
 */
class CardBackActivity : Activity() {

    private var handled = false

    @Volatile
    var resumed = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { fold() }
        }
        live = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    @Deprecated("API 33+ delivers the gesture to the callback; this is the key below that")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        fold()
    }

    private fun fold() {
        if (handled || isFinishing) return
        val folded = onFold?.invoke() == true
        if (!folded) {
            // The card does not want this Back (typing, settings, already
            // folded). Leave so the next one is not stuck on an invisible window.
            handled = true
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        handled = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        resumed = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Body"

        /**
         * Fold the card. Returns false when Back should not fold (the service
         * wires this). The activity finishes either way when it was the one
         * that received the gesture and the fold was refused.
         */
        @Volatile
        var onFold: (() -> Boolean)? = null

        private var live: WeakReference<CardBackActivity>? = null

        fun ensure(context: Context) {
            val cur = live?.get()
            if (cur != null && !cur.isFinishing && cur.resumed) return
            val intent = Intent(context, CardBackActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "back catcher not started", it) }
        }

        fun dismiss() {
            val activity = live?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    activity.finish()
                    @Suppress("DEPRECATION")
                    activity.overridePendingTransition(0, 0)
                }
            }
        }
    }
}
