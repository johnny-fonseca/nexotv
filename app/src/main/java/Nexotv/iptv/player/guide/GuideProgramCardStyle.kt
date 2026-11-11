// GuideProgramCardStyle.kt
package Nexotv.iptv.player.guide


import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GUIDE_FALLBACK_CLOCK = "--:--"
private const val GUIDE_FALLBACK_DAY_CLOCK = "--"
private const val GUIDE_MAX_STATUS_LABEL_LENGTH = 120

internal fun Activity.guideDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Activity.guideIsUsable(): Boolean = !isFinishing && !isDestroyed

internal fun guideIsHttpUrl(url: String?): Boolean {
    val candidate = url?.trim().orEmpty()
    return candidate.startsWith("http://", ignoreCase = true) ||
        candidate.startsWith("https://", ignoreCase = true)
}

internal fun guideFormatClock(ms: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
} catch (_: Exception) {
    GUIDE_FALLBACK_CLOCK
}

internal fun guideFormatDayClock(ms: Long): String = try {
    SimpleDateFormat("EEEE d MMM · HH:mm", Locale.getDefault()).format(Date(ms))
} catch (_: Exception) {
    GUIDE_FALLBACK_DAY_CLOCK
}

internal enum class GuideProgramStatus {
    LIVE,
    NEXT,
    LATER,
    ENDED,
}

internal object GuideProgramStatusResolver {
    fun resolve(program: TvProgram, nextProgram: TvProgram?, nowMs: Long): GuideProgramStatus = when {
        program.startMs <= nowMs && program.endMs > nowMs -> GuideProgramStatus.LIVE
        program == nextProgram -> GuideProgramStatus.NEXT
        program.endMs <= nowMs -> GuideProgramStatus.ENDED
        else -> GuideProgramStatus.LATER
    }
}

internal object GuideProgramStatusBadge {
    fun create(activity: Activity, status: GuideProgramStatus): TextView = TextView(activity).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.2f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        setPadding(activity.guideDp(7), activity.guideDp(3), activity.guideDp(7), activity.guideDp(3))
        val (label, foreground, backgroundColor) = when (status) {
            GuideProgramStatus.LIVE -> Triple(
                "AL AIRE",
                activity.getColor(R.color.tv_menu_bg),
                activity.getColor(R.color.tv_turquoise),
            )
            GuideProgramStatus.NEXT -> Triple(
                "SIGUIENTE",
                activity.getColor(R.color.tv_menu_bg),
                activity.getColor(R.color.tv_warning),
            )
            GuideProgramStatus.ENDED -> Triple(
                "FINALIZADO",
                activity.getColor(R.color.tv_text_secondary),
                activity.getColor(R.color.tv_menu_row_normal),
            )
            GuideProgramStatus.LATER -> Triple(
                "MÁS TARDE",
                activity.getColor(R.color.tv_text_primary),
                activity.getColor(R.color.tv_menu_row_normal),
            )
        }
        text = label
        setTextColor(foreground)
        background = GradientDrawable().apply {
            cornerRadius = activity.guideDp(6).toFloat()
            setColor(backgroundColor)
            setStroke(activity.guideDp(1), activity.getColor(R.color.tv_menu_panel_stroke))
        }
    }
}

/** Compact status indicator used in the selected-program detail panel. */
internal object GuideDetailStatusLine {
    fun create(activity: Activity, iconRes: Int, label: String, color: Int): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.guideDp(1), 0, activity.guideDp(1))
            val icon = try {
                ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(color)
                }
            } catch (_: Exception) {
                null
            }
            if (icon != null) {
                addView(icon, LinearLayout.LayoutParams(activity.guideDp(12), activity.guideDp(12)).apply {
                    marginEnd = activity.guideDp(6)
                })
            }
            addView(TextView(activity).apply {
                text = label.take(GUIDE_MAX_STATUS_LABEL_LENGTH)
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
}