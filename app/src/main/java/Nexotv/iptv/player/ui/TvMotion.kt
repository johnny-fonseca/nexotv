@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvMotion.kt
package Nexotv.iptv.player.ui

import Nexotv.iptv.player.R
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Motion helpers for the TV UI.
 *
 * Only translation, scale and alpha are animated, always through ViewPropertyAnimator.
 * No blur, no backdrop, no full-screen alpha and no hardware layer is ever requested.
 * When the system animator duration scale is 0 every helper applies the final values
 * directly, so the UI still lands in a correct state.
 */
internal object TvMotion {

    private val decelerate = DecelerateInterpolator(1.6f)

    private var scaleCheckedAtMs = 0L
    private var animationsEnabled = true

    fun enabled(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val ttlMs = context.resources.getInteger(R.integer.tv_ui_motion_cache_ms).toLong().coerceAtLeast(1L)
        if (now - scaleCheckedAtMs < ttlMs) return animationsEnabled
        animationsEnabled = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        } catch (_: RuntimeException) {
            true
        }
        scaleCheckedAtMs = now
        return animationsEnabled
    }

    /** Call after returning from system settings so a changed scale applies immediately. */
    fun invalidateScaleCache() {
        scaleCheckedAtMs = 0L
    }

    fun cancel(view: View) {
        view.animate().cancel()
    }

    /**
     * Focus motion: 1.0 -> [scale] plus a small upward lift. The ring colour is carried by
     * the focusable background, so the indicator never depends on this animation.
     */
    fun focus(view: View, focused: Boolean, scale: Float, liftPx: Int, durationMs: Long) {
        view.animate().cancel()
        val targetScale = if (focused) scale else IDLE_SCALE
        val targetLift = if (focused) -liftPx.toFloat() else IDLE_TRANSLATION
        if (!enabled(view.context) || durationMs <= 0L) {
            view.scaleX = targetScale
            view.scaleY = targetScale
            view.translationY = targetLift
            return
        }
        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationY(targetLift)
            .setDuration(durationMs)
            .setInterpolator(decelerate)
            .start()
    }

    /** Entrance for a panel: alpha 0 -> 1 and a short upward slide. */
    fun panelIn(view: View, durationMs: Long, offsetPx: Int) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (!enabled(view.context) || durationMs <= 0L) {
            view.alpha = 1f
            view.translationY = IDLE_TRANSLATION
            return
        }
        view.alpha = 0f
        view.translationY = offsetPx.toFloat()
        view.animate()
            .alpha(1f)
            .translationY(IDLE_TRANSLATION)
            .setDuration(durationMs)
            .setInterpolator(decelerate)
            .start()
    }

    /** Exit for a panel. The view is hidden only when the animation ends. */
    fun panelOut(view: View, durationMs: Long, offsetPx: Int, onEnd: (() -> Unit)? = null) {
        view.animate().cancel()
        if (!enabled(view.context) || durationMs <= 0L) {
            view.visibility = View.GONE
            view.alpha = 1f
            view.translationY = IDLE_TRANSLATION
            onEnd?.invoke()
            return
        }
        view.animate()
            .alpha(0f)
            .translationY(offsetPx.toFloat())
            .setDuration(durationMs)
            .setInterpolator(decelerate)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.translationY = IDLE_TRANSLATION
                onEnd?.invoke()
            }
            .start()
    }

    /** Fade for chips and toasts. Never use it on a full-screen surface. */
    fun fade(view: View, visible: Boolean, durationMs: Long, onEnd: (() -> Unit)? = null) {
        view.animate().cancel()
        if (!enabled(view.context) || durationMs <= 0L) {
            view.alpha = if (visible) 1f else 0f
            view.visibility = if (visible) View.VISIBLE else View.GONE
            onEnd?.invoke()
            return
        }
        if (visible) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(durationMs).setInterpolator(decelerate).start()
        } else {
            view.animate()
                .alpha(0f)
                .setDuration(durationMs)
                .setInterpolator(decelerate)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    onEnd?.invoke()
                }
                .start()
        }
    }

    private const val IDLE_SCALE = 1f
    private const val IDLE_TRANSLATION = 0f
}
