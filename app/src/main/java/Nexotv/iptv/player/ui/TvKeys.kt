@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvKeys.kt
package Nexotv.iptv.player.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/** The only remote keys the rebuilt UI acts on. Everything else is left to the system. */
internal enum class TvKey {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    CHANNEL_UP,
    CHANNEL_DOWN,
    MENU,
    OTHER
    ;

    companion object {
        fun from(keyCode: Int): TvKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> OK
            KeyEvent.KEYCODE_BACK -> BACK
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PAUSE
            KeyEvent.KEYCODE_CHANNEL_UP -> CHANNEL_UP
            KeyEvent.KEYCODE_CHANNEL_DOWN -> CHANNEL_DOWN
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> MENU
            else -> OTHER
        }
    }
}

/**
 * Receiver for remote keys. Returning false leaves the event to the focused view or to
 * the platform, which is how OK reaches buttons on the home screen and in panels.
 */
internal interface TvKeyHandler {
    fun onTvKey(key: TvKey, event: KeyEvent): Boolean

    /** Called once when the OK key has been held for the configured duration. */
    fun onTvLongOk(): Boolean = false

    /**
     * True only when no focusable control is in front of the video. The router then owns
     * OK (tap and 2s hold); otherwise OK is dispatched to the focused control natively.
     */
    fun ownsOk(): Boolean = false
}

/**
 * Normalises remote key events and implements the 2s OK hold without consuming OK while
 * a focused control needs it. D-pad auto-repeat is forwarded with its repeat count, so
 * holding a direction scrolls fast without losing focus.
 */
internal class TvKeyRouter(
    private val handler: TvKeyHandler,
    private val longOkMs: Long,
) {

    private val main = Handler(Looper.getMainLooper())

    private var okTracking = false
    private var okLongFired = false
    private var okDownAtMs = 0L

    private val longOk = Runnable {
        if (okTracking && !okLongFired) {
            okLongFired = true
            handler.onTvLongOk()
        }
    }

    fun handle(event: KeyEvent): Boolean {
        val key = TvKey.from(event.keyCode)
        if (key == TvKey.OTHER || key == TvKey.BACK) return false
        if (key == TvKey.OK) return handleOk(event)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount == 0) return handler.onTvKey(key, event)
        val repeatable = key == TvKey.UP || key == TvKey.DOWN || key == TvKey.LEFT || key == TvKey.RIGHT
        return if (repeatable) handler.onTvKey(key, event) else true
    }

    private fun handleOk(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return okTracking
                if (!handler.ownsOk()) return false
                okTracking = true
                okLongFired = false
                okDownAtMs = event.eventTime
                main.removeCallbacks(longOk)
                main.postDelayed(longOk, longOkMs)
                return true
            }

            KeyEvent.ACTION_UP -> {
                if (!okTracking) return false
                okTracking = false
                main.removeCallbacks(longOk)
                if (event.isCanceled || okLongFired) {
                    okLongFired = false
                    return true
                }
                val heldMs = event.eventTime - okDownAtMs
                if (heldMs >= longOkMs) {
                    // The main thread may have been busy when the timer was due.
                    handler.onTvLongOk()
                    return true
                }
                handler.onTvKey(TvKey.OK, event)
                return true
            }
        }
        return false
    }

    fun release() {
        main.removeCallbacks(longOk)
        okTracking = false
        okLongFired = false
    }
}

/** A Back layer. Returning true consumes Back; the layer is popped even if it does not. */
internal fun interface TvBackHandler {
    fun handle(): Boolean
}

/**
 * Back always goes one level up: panel, then overlay, then home, then exit confirmation.
 * Layers are pushed when opened and popped automatically by Back; call the returned
 * lambda when a layer closes by another route so it never handles a stale Back.
 */
internal class TvBackStack {

    private val handlers = ArrayList<TvBackHandler>(INITIAL_CAPACITY)

    val depth: Int get() = handlers.size

    fun push(handler: TvBackHandler): () -> Unit {
        handlers.add(handler)
        return {
            handlers.remove(handler)
            Unit
        }
    }

    fun onBack(): Boolean {
        while (handlers.isNotEmpty()) {
            val handler = handlers.removeAt(handlers.size - 1)
            if (handler.handle()) return true
        }
        return false
    }

    fun clear() {
        handlers.clear()
    }

    private companion object {
        const val INITIAL_CAPACITY = 4
    }
}

/**
 * Two-press exit confirmation with a visible hint. The clock is injectable so the timing
 * can be verified without instrumentation.
 */
internal class TvExitGuard(
    private val windowMs: Long,
    private val onHint: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private var armedUntilMs = 0L

    /** Returns true when the caller must finish; false after showing the hint. */
    fun requestExit(): Boolean {
        val now = clock()
        if (armedUntilMs > 0L && now <= armedUntilMs) {
            armedUntilMs = 0L
            return true
        }
        armedUntilMs = now + windowMs
        onHint()
        return false
    }

    fun disarm() {
        armedUntilMs = 0L
    }

    fun isArmed(): Boolean = armedUntilMs > 0L

    companion object {
        fun shouldExit(confirmExit: Boolean, guard: TvExitGuard): Boolean =
            !confirmExit || guard.requestExit()
    }
}
