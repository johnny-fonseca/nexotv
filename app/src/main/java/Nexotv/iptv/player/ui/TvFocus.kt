@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvFocus.kt
package Nexotv.iptv.player.ui

import Nexotv.iptv.player.R
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.view.ViewCompat
import java.lang.ref.WeakReference

/** Visual recipe for a focusable surface. Every value is a resource token. */
internal data class TvFocusStyle(
    @param:DimenRes val radius: Int = R.dimen.tv_ui_radius_control,
    @param:ColorRes val fill: Int = R.color.tv_ui_surface_2,
    @param:ColorRes val focusedFill: Int = R.color.tv_ui_surface_3,
    @param:ColorRes val stroke: Int = R.color.tv_ui_stroke,
    @param:ColorRes val ring: Int = R.color.tv_ui_accent,
    @param:DimenRes val strokeWidth: Int = R.dimen.tv_ui_focus_ring_width,
) {
    companion object {
        val CONTROL = TvFocusStyle()
        val ROW = TvFocusStyle(radius = R.dimen.tv_ui_radius_control)
        val CARD = TvFocusStyle(
            radius = R.dimen.tv_ui_radius_card,
            fill = R.color.tv_ui_surface_1,
            focusedFill = R.color.tv_ui_surface_2,
        )
        val CHIP = TvFocusStyle(
            radius = R.dimen.tv_ui_radius_chip,
            fill = R.color.tv_ui_surface_2,
            focusedFill = R.color.tv_ui_surface_3,
        )
    }
}

/**
 * Focus framework for a D-pad-only UI.
 *
 * - One ring language: a constant 3dp stroke whose colour changes on focus, so the
 *   content never shifts by the stroke width. The fill also lifts one surface level.
 * - Scale 1.05 plus a 2dp upward translation, 130ms. Translation/scale only.
 * - The ring is part of a stateful GradientDrawable, so no allocation happens when
 *   the focus state changes and no layout pass is requested.
 */
internal object TvFocus {

    fun attach(
        view: View,
        style: TvFocusStyle = TvFocusStyle.CONTROL,
        onFocusChanged: ((View, Boolean) -> Unit)? = null,
    ) {
        val context = view.context
        val focused = intArrayOf(android.R.attr.state_focused)
        val normal = intArrayOf()
        val fill = ColorStateList(
            arrayOf(focused, normal),
            intArrayOf(TvTokens.color(context, style.focusedFill), TvTokens.color(context, style.fill)),
        )
        val stroke = ColorStateList(
            arrayOf(focused, normal),
            intArrayOf(TvTokens.color(context, style.ring), TvTokens.color(context, style.stroke)),
        )
        val drawable = GradientDrawable().apply {
            cornerRadius = TvTokens.dim(context, style.radius)
            setColor(fill)
            setStroke(TvTokens.px(context, style.strokeWidth), stroke)
        }
        view.background = drawable
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        val scalePercent = TvTokens.integer(context, R.integer.tv_ui_focus_scale_percent)
        val scale = (scalePercent / PERCENT).coerceAtLeast(IDLE_SCALE)
        val liftPx = TvTokens.px(context, R.dimen.tv_ui_focus_lift)
        val durationMs = TvTokens.integer(context, R.integer.tv_ui_focus_anim_ms).toLong()
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            TvMotion.focus(focusedView, hasFocus, scale, liftPx, durationMs)
            onFocusChanged?.invoke(focusedView, hasFocus)
            if (hasFocus) {
                focusedView.post {
                    if (focusedView.isFocused) {
                        focusedView.requestRectangleOnScreen(
                            Rect(0, 0, focusedView.width, focusedView.height),
                            false,
                        )
                    }
                }
            }
        }
    }

    /** Small upward translation on focus; containers must not clip children. */
    fun allowOverflow(container: ViewGroup) {
        container.clipChildren = false
        container.clipToPadding = false
    }

    /** Stops focus search from leaving a panel and entering the content behind it. */
    fun blockFocus(group: ViewGroup, blocked: Boolean) {
        group.descendantFocusability = if (blocked) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    /** Accessibility: every focusable view gets a description and an optional state. */
    fun describe(view: View, text: String, selected: Boolean? = null) {
        view.contentDescription = text
        if (selected != null) {
            ViewCompat.setStateDescription(
                view,
                if (selected) view.context.getString(R.string.tv_ui_state_selected) else null,
            )
        }
    }

    fun state(view: View, text: String?) {
        ViewCompat.setStateDescription(view, text)
    }

    /** Stable key used to restore focus when the view tree is rebuilt. */
    fun key(view: View, focusKey: String) {
        view.setTag(R.id.tv_ui_focus_key, focusKey)
    }
}

/**
 * Owns initial focus, focus memory and focus recovery for one screen or panel.
 *
 * The host screen keeps one controller alive while it is on screen. Panels suspend the
 * controller behind them with [setActive] and reactivate it on close, which restores the
 * exact view that had focus when the panel opened.
 */
internal class TvFocusController(
    private val root: ViewGroup,
    private val screenKey: String,
    private val initial: () -> View?,
) {

    private var installed = false
    private var active = true

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            install()
            ensureFocus()
        }

        override fun onViewDetachedFromWindow(v: View) {
            uninstall()
        }
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus != null) {
            if (active && isInside(newFocus)) TvFocusMemory.remember(screenKey, newFocus)
        } else if (active) {
            root.post { ensureFocus() }
        }
    }

    private val windowListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) ensureFocus()
    }

    init {
        root.addOnAttachStateChangeListener(attachListener)
    }

    fun attach() {
        install()
        root.post { ensureFocus() }
    }

    fun detach() {
        root.removeOnAttachStateChangeListener(attachListener)
        uninstall()
    }

    /**
     * Suspends focus recovery while a panel sits in front of this screen. Reactivating
     * restores the last focused item, not the first item.
     */
    fun setActive(value: Boolean) {
        active = value
        if (value) root.post { ensureFocus() }
    }

    /** Never leaves the screen without focus when the window regains focus. */
    fun ensureFocus() {
        if (!active || !root.isAttachedToWindow || !root.hasWindowFocus()) return
        val current = root.rootView.findFocus()
        if (current != null && isInside(current)) return
        if (restoreFocus()) return
        val target = initial() ?: firstFocusable(root)
        target?.requestFocus()
    }

    private fun restoreFocus(): Boolean {
        val view = TvFocusMemory.restore(screenKey, root) ?: return false
        if (!view.isAttachedToWindow ||
            !view.isFocusable ||
            view.visibility != View.VISIBLE ||
            !isInside(view)
        ) {
            return false
        }
        if (!view.requestFocus()) return false
        view.post {
            if (view.isAttachedToWindow && view.isFocused) {
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            }
        }
        return true
    }

    private fun isInside(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === root) return true
            node = node.parent as? View
        }
        return false
    }

    private fun install() {
        if (installed || !root.isAttachedToWindow) return
        installed = true
        val observer = root.viewTreeObserver
        observer.addOnGlobalFocusChangeListener(focusListener)
        observer.addOnWindowFocusChangeListener(windowListener)
    }

    private fun uninstall() {
        if (!installed) return
        installed = false
        val observer = root.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnGlobalFocusChangeListener(focusListener)
            observer.removeOnWindowFocusChangeListener(windowListener)
        }
    }

}

private const val PERCENT = 100f
private const val IDLE_SCALE = 1f

/**
 * Remembers the last focused view per screen. A weak reference keeps the common case
 * allocation-free; the stable tag is the fallback when the view tree is rebuilt.
 */
internal object TvFocusMemory {

    private val entries = HashMap<String, Entry>(INITIAL_CAPACITY)

    fun remember(screenKey: String, view: View) {
        val tag = view.getTag(R.id.tv_ui_focus_key) as? String
        entries[screenKey] = Entry(tag, WeakReference(view))
    }

    fun restore(screenKey: String, root: ViewGroup): View? {
        val entry = entries[screenKey] ?: return null
        val alive = entry.reference.get()
        if (alive != null &&
            alive.isAttachedToWindow &&
            alive.isFocusable &&
            alive.visibility == View.VISIBLE
        ) {
            return alive
        }
        val tag = entry.keyTag ?: return null
        return findByTag(root, tag)
    }

    fun forget(screenKey: String) {
        entries.remove(screenKey)
    }

    fun clear() {
        entries.clear()
    }

    private fun findByTag(group: ViewGroup, tag: String): View? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.visibility == View.VISIBLE &&
                child.isFocusable &&
                child.getTag(R.id.tv_ui_focus_key) == tag
            ) {
                return child
            }
            if (child is ViewGroup) findByTag(child, tag)?.let { return it }
        }
        return null
    }

    private class Entry(val keyTag: String?, val reference: WeakReference<View>)

    private const val INITIAL_CAPACITY = 8
}

internal fun firstFocusable(group: ViewGroup): View? {
    for (index in 0 until group.childCount) {
        val child = group.getChildAt(index)
        if (child.visibility != View.VISIBLE) continue
        if (child.isFocusable) return child
        if (child is ViewGroup) firstFocusable(child)?.let { return it }
    }
    return null
}
