@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvTokens.kt
package Nexotv.iptv.player.ui

import Nexotv.iptv.player.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.FractionRes
import androidx.annotation.IntegerRes
import androidx.annotation.StyleRes

/**
 * Resolved design tokens for the rebuilt 10-foot UI.
 *
 * Rules enforced here:
 * - Screen code carries no hex, dp, sp or ms literals: it asks this object or reads a token.
 * - One accent, three surfaces, three text levels and five type styles.
 * - The safe area is a fraction of the real panel, never a fixed dp.
 */
internal object TvTokens {

    fun px(context: Context, @DimenRes id: Int): Int = context.resources.getDimensionPixelSize(id)

    fun dim(context: Context, @DimenRes id: Int): Float = context.resources.getDimension(id)

    fun color(context: Context, @ColorRes id: Int): Int = context.getColor(id)

    fun integer(context: Context, @IntegerRes id: Int): Int = context.resources.getInteger(id)

    fun fraction(context: Context, @FractionRes id: Int): Float = context.resources.getFraction(id, 1, 1)

    /**
     * Applies one of the five TvUiText* styles. Size and family always come from the style;
     * this method only chooses an optional text colour and the label line limit.
     */
    fun text(
        view: TextView,
        @StyleRes style: Int,
        @ColorRes colorRes: Int = 0,
        maxLines: Int = LABEL_MAX_LINES,
    ) {
        view.setTextAppearance(style)
        view.maxLines = maxLines
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        if (colorRes != 0) view.setTextColor(color(view.context, colorRes))
    }

    /** 16:9 card height for a measured width, from the aspect tokens. */
    fun cardHeight(context: Context, widthPx: Int): Int {
        val aspectWidth = integer(context, R.integer.tv_ui_card_aspect_w).coerceAtLeast(1)
        val aspectHeight = integer(context, R.integer.tv_ui_card_aspect_h).coerceAtLeast(1)
        return (widthPx.toLong() * aspectHeight / aspectWidth).toInt()
    }

    /** Applies the 5% overscan margin as a fraction of the real screen. */
    fun applySafeArea(view: View) {
        val metrics = view.resources.displayMetrics
        val horizontal = (metrics.widthPixels * fraction(view.context, R.fraction.tv_safe_margin_horizontal)).toInt()
        val vertical = (metrics.heightPixels * fraction(view.context, R.fraction.tv_safe_margin_vertical)).toInt()
        view.setPadding(horizontal, vertical, horizontal, vertical)
    }

    /**
     * Two-state colour list for children of a focusable row. With
     * `isDuplicateParentStateEnabled = true` the icon/label follows the row's focus.
     */
    fun focusColorStateList(
        context: Context,
        @ColorRes normal: Int,
        @ColorRes focused: Int,
    ): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(color(context, focused), color(context, normal)),
    )

    /** Rounded opaque surface. Pass [stroke] = 0 for no border. */
    fun surface(
        context: Context,
        @DimenRes radius: Int,
        @ColorRes fill: Int,
        @ColorRes stroke: Int = 0,
        @DimenRes strokeWidth: Int = R.dimen.tv_ui_hairline,
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dim(context, radius)
        setColor(color(context, fill))
        if (stroke != 0) setStroke(px(context, strokeWidth), color(context, stroke))
    }

    /** Opaque two-tone surface, used by the home hero. No alpha, no blur. */
    fun verticalSurface(
        context: Context,
        @DimenRes radius: Int,
        @ColorRes top: Int,
        @ColorRes bottom: Int,
        @ColorRes stroke: Int = 0,
        @DimenRes strokeWidth: Int = R.dimen.tv_ui_hairline,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(color(context, top), color(context, bottom)),
    ).apply {
        cornerRadius = dim(context, radius)
        if (stroke != 0) setStroke(px(context, strokeWidth), color(context, stroke))
    }

    /** Small status dot for the navigation footer. */
    fun oval(context: Context, @ColorRes fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(context, fill))
    }

    /**
     * Static vertical scrim for the bottom information bar. It is a gradient drawable,
     * never a blur or a full-screen alpha layer, so the video keeps its centre visible.
     */
    fun bottomScrim(context: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(color(context, R.color.tv_ui_scrim_top), color(context, R.color.tv_ui_scrim_bottom)),
    )

    /** Labels wrap at most twice; deeper copy belongs in a panel. */
    private const val LABEL_MAX_LINES = 2
}
