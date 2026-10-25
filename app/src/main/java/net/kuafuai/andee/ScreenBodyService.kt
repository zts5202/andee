package net.kuafuai.andee

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * The screen half — a hand, and nothing else.
 *
 * This used to be the whole app. It was an `AccessibilityService`, so the ball,
 * the microphone, the card, the scrollback and the brain all lived and died
 * with one grant that a Chinese ROM is entitled to take back whenever it likes
 * (measured on HyperOS: enabled by the user, revoked by the system, and Settings
 * shows it off). When that happened the user did not lose "screen control" —
 * they lost the assistant, with no visible reason and no way back except knowing
 * which switch in Settings to find.
 *
 * So the body moved out ([BodyCoreService]) and this is what is left. Its whole
 * job: when accessibility comes up, hand the capability to the body and tell it
 * to start; when accessibility goes away, take the capability back and **leave
 * everything else alone**. See [BodyCoreService.attachScreen] and
 * [BodyCoreService.detachScreen].
 *
 * What that buys, exactly — the failure mode it removes is not "the ball
 * disappears", it is "the ball disappears *and so does everything else*". With
 * the split, a revoked service costs the user one capability: the ball stays on
 * screen, talking still works, the history is still readable, the persistent
 * notification still says the assistant is alive, and the way back is one tap
 * on that notification rather than a hunt through Settings.
 *
 * **This must not be deleted on the argument that it is now trivial.** It is
 * trivial, and it is also the only component in the app that can reach other
 * apps' windows: there is no non-root substitute for `dispatchGesture` or
 * `performGlobalAction`. The split changes what depends on it, not whether it
 * is needed.
 */
class ScreenBodyService : AccessibilityService() {

    override fun onServiceConnected() {
        instanceRef.set(this)
        // The manifest requests touch-exploration capability because some
        // WeChat builds expose their semantic tree only after this capability
        // is negotiated. Do not keep the request flag active at runtime: that
        // would turn ordinary tablet taps into TalkBack-style exploration.
        serviceInfo = serviceInfo.apply {
            flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            // The manifest asks for key filtering so the grant includes it.
            // Leave it off for good. Turning it on while the card is expanded
            // stops HyperOS from generating gesture Back and Home at all, and
            // while it is on every IME commit is also delivered here before
            // the editor. A finger swipe is handled on the card; a real
            // KEYCODE_BACK is handled by CardBackActivity.
            flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        // The body first, then the hand. `start` is asynchronous, so on a cold
        // device `get()` is still null one line later — the body pulls the
        // capability at the end of its own `onCreate` for exactly that reason.
        // A re-enable on a device where the body is already up takes the other
        // path and lands here.
        BodyCoreService.start(this)
        BodyCoreService.get()?.attachScreen(this)
    }

    /**
     * Accessibility went away. **Do not stop the body.**
     *
     * That one omission is the whole change. The user's ball, mic and history
     * used to survive this callback only because they were fields of this
     * object; now they survive because they never belonged to it.
     */
    override fun onDestroy() {
        BodyCoreService.get()?.detachScreen()
        instanceRef.compareAndSet(this, null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * System Back while the card is expanded folds it, the same as ✕.
     *
     * Only reached if something turns key filtering on. The service does not:
     * on HyperOS that flag stops gesture Back and Home from being generated.
     * Kept so a stale flag still folds instead of eating the key. Consumed
     * only for Back, and only while [BodyCoreService.wantsBackToFold] is true.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val body = BodyCoreService.get() ?: return false
        if (!body.wantsBackToFold()) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) body.foldCardFromBack()
        return true
    }

    /**
     * Forwarded, not interpreted.
     *
     * The one fact kept here rather than handed to the body:
     * [net.kuafuai.andee.device.DeviceState.foregroundPkg] is about the device,
     * and stays true whether or not a body is up to hear it. Everything else —
     * the scene logic, the stale-connection note — needs a live body, and is
     * dropped when there isn't one. That is the honest outcome rather than a
     * compromise: an event nobody can act on is not worth queueing.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                net.kuafuai.andee.device.DeviceState.foregroundPkg = pkg
                BodyCoreService.get()?.noteForegroundPackage(pkg)
            }
        }
        BodyCoreService.get()?.noteAccessibilityEvent(event)
    }

    companion object {
        private val instanceRef = AtomicReference<ScreenBodyService?>()

        /**
         * The enabled accessibility service, or null.
         *
         * Only two callers want this, and both are asking a question *about*
         * accessibility rather than asking it to do anything:
         * `ui/SelfCheckActivity` deciding whether the ball is really up, and
         * `device/SelfCheck` reading `serviceInfo.flags` to see whether the
         * input-method channel came up. Everything that wants the *assistant*
         * wants [BodyCoreService.get] instead.
         */
        fun get(): ScreenBodyService? = instanceRef.get()

        /**
         * Key filtering stays off. [BodyCoreService] calls this with false to
         * clear a flag the manifest requested. Do not pass true: on HyperOS
         * the filter stops gesture navigation, and it also puts every IME
         * commit on this main thread before the editor.
         */
        fun setKeyFilter(on: Boolean) {
            val svc = instanceRef.get() ?: return
            val info = svc.serviceInfo ?: return
            val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            val has = info.flags and flag != 0
            if (has == on) return
            info.flags = if (on) info.flags or flag else info.flags and flag.inv()
            runCatching { svc.serviceInfo = info }
        }
    }
}
