// RecordingLibraryCard.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Tarjeta enfocable de grabación usada por la biblioteca de grabaciones. */
internal object RecordingLibraryCard {

    private const val TAG = "RecordingLibraryCard"

    private const val CARD_WIDTH_DP = 154
    private const val CARD_MARGIN_END_DP = 8
    private const val CARD_PADDING_DP = 8
    private const val THUMBNAIL_HEIGHT_DP = 52
    private const val THUMBNAIL_RADIUS_DP = 8
    private const val BADGE_RADIUS_DP = 4
    private const val FOCUS_ELEVATION_DP = 6f

    private const val COLOR_THUMBNAIL_BACKGROUND = 0xFF09121F.toInt()
    private const val COLOR_DURATION_BADGE = 0xDD000000.toInt()
    private const val COLOR_META_MUTED = 0xFF8899AA.toInt()
    private const val COLOR_META_PROGRESS = 0xFF4ADE80.toInt()

    private const val TEXT_RECORDING = "● REC"
    private const val TEXT_WATCHING = "VIENDO"
    private const val TEXT_NEW = "Nuevo"

    fun create(
        activity: Activity,
        title: String,
        durationText: String,
        bytesText: String,
        progressPercent: Int,
        activelyRecording: Boolean,
        onFocused: (View) -> Unit,
        onSelected: () -> Unit,
    ): View {
        val progress = progressPercent.coerceIn(0, 100)

        return LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = menuRowBackground(activity)
            val padding = activity.dp(CARD_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            clipChildren = true
            clipToPadding = false

            addView(
                buildThumbnail(activity, durationText, progress, activelyRecording),
                LinearLayout.LayoutParams(MATCH_PARENT, activity.dp(THUMBNAIL_HEIGHT_DP))
            )
            addView(
                buildTitle(activity, title),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(3) }
            )
            addView(
                buildMetaRow(activity, bytesText, progress),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(1) }
            )

            setOnFocusChangeListener { view, focused ->
                animateFocus(view, focused)
                if (focused) safeInvoke("onFocused") { onFocused(view) }
            }
            setOnClickListener { safeInvoke("onSelected") { onSelected() } }
            layoutParams = LinearLayout.LayoutParams(activity.dp(CARD_WIDTH_DP), MATCH_PARENT).apply {
                marginEnd = activity.dp(CARD_MARGIN_END_DP)
            }
        }
    }

    private fun buildThumbnail(
        activity: Activity,
        durationText: String,
        progress: Int,
        activelyRecording: Boolean,
    ): FrameLayout = FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            cornerRadius = activity.dp(THUMBNAIL_RADIUS_DP).toFloat()
            setColor(COLOR_THUMBNAIL_BACKGROUND)
        }
        clipToOutline = true

        addView(
            ImageView(activity).apply {
                setImageResource(R.drawable.recording_placeholder)
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )

        if (activelyRecording || progress > 0) {
            addView(
                buildBadge(
                    activity = activity,
                    label = if (activelyRecording) TEXT_RECORDING else TEXT_WATCHING,
                    sizeSp = 8f,
                    color = activity.getColor(R.color.tv_live),
                    horizontalPaddingDp = 5,
                ),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(activity.dp(4), activity.dp(4), 0, 0)
                }
            )
        }

        addView(
            buildBadge(
                activity = activity,
                label = durationText,
                sizeSp = 8.5f,
                color = COLOR_DURATION_BADGE,
                horizontalPaddingDp = 4,
            ),
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, activity.dp(4), activity.dp(4))
            }
        )
    }

    private fun buildBadge(
        activity: Activity,
        label: String,
        sizeSp: Float,
        color: Int,
        horizontalPaddingDp: Int,
    ): TextView = TextView(activity).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        background = GradientDrawable().apply {
            cornerRadius = activity.dp(BADGE_RADIUS_DP).toFloat()
            setColor(color)
        }
        setPadding(activity.dp(horizontalPaddingDp), activity.dp(1), activity.dp(horizontalPaddingDp), activity.dp(1))
    }

    private fun buildTitle(activity: Activity, title: String): TextView = TextView(activity).apply {
        text = title
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buildMetaRow(activity: Activity, bytesText: String, progress: Int): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                TextView(activity).apply {
                    text = bytesText
                    setTextColor(COLOR_META_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            addView(
                TextView(activity).apply {
                    text = if (progress > 0) "$progress%" else TEXT_NEW
                    setTextColor(if (progress > 0) COLOR_META_PROGRESS else COLOR_META_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = activity.dp(4) }
            )
        }

    private fun animateFocus(view: View, focused: Boolean) =
        animateTvFocus(view, focused, focusedScale = 1.025f, elevationDp = FOCUS_ELEVATION_DP)

    // Aísla fallos de los callbacks externos para que no tumben la interfaz.
    private inline fun safeInvoke(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error en callback $name", e)
        }
    }
}

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()