// ============================================================================
// Archivo: RecordingLibraryViewFactories.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView

private fun MainActivity.recordingActionBackground(): StateListDrawable = StateListDrawable().apply {
    val focused = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(0xFF1B3045.toInt())
        setStroke(dp(3), getColor(R.color.tv_turquoise))
    }
    val normal = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(0xFF263D54.toInt())
        setStroke(dp(1), 0xFF49647D.toInt())
    }
    addState(intArrayOf(android.R.attr.state_focused), focused)
    addState(intArrayOf(android.R.attr.state_pressed), focused)
    addState(intArrayOf(), normal)
}

internal fun MainActivity.createActionButton(label: String, bold: Boolean = false): TextView =
    TextView(this).apply {
        id = View.generateViewId()
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = recordingActionBackground()
        applyTvFocus(this)
    }

internal fun MainActivity.createCardBackground(strokeColor: Int): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF142335.toInt(), 0xFF101A28.toInt())
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), strokeColor)
    }

internal fun MainActivity.createStorageBar(tint: Int): ProgressBar =
    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(tint)
        progressBackgroundTintList = ColorStateList.valueOf(COLOR_PROGRESS_TRACK)
    }

internal fun MainActivity.createSmallLabel(): TextView = TextView(this).apply {
    setTextColor(COLOR_TEXT_BODY)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

internal fun View.animateFocusElevation(focused: Boolean) {
    animateTvFocus(this, focused, focusedScale = 1.025f, elevationDp = 6f)
}
