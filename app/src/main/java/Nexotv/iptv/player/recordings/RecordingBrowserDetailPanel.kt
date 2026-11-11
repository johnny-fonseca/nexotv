// RecordingBrowserDetailPanel.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

internal class RecordingBrowserDetailPanel(
    val root: LinearLayout,
    val image: ImageView,
    val badgeTv: TextView,
    val storagePillTv: TextView,
    val titleTv: TextView,
    val metaTv: TextView,
    val descTv: TextView,
    val progressBar: ProgressBar,
    val progressLabel: TextView,
    val btnPlay: TextView,
    val btnPlayStart: TextView,
    val btnDelete: TextView,
)

internal fun MainActivity.createRecordingBrowserDetailPanel(
    state: RecordingBrowserState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
): RecordingBrowserDetailPanel {
    val activity = this

    val image = ImageView(this).apply {
        setImageResource(R.drawable.recording_placeholder)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    val thumbCard = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xFF07101B.toInt())
        }
        clipToOutline = true
        addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xDD070D18.toInt())
            )
        }, FrameLayout.LayoutParams(-1, dp(90), Gravity.BOTTOM))
    }

    val badgeTv = TextView(this).apply {
        text = "● GRABACIÓN"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(getColor(R.color.tv_menu_red_btn))
        }
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }
    val storagePillTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        background = menuPill(activity, 0xC0070E1A.toInt(), 0xFF24364D.toInt())
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }
    val thumbTopRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(badgeTv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(storagePillTv)
    }
    thumbCard.addView(thumbTopRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

    val titleTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val metaTv = TextView(this).apply {
        setTextColor(0xFF8899AA.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    val descTv = TextView(this).apply {
        setTextColor(0xFFCCD6E2.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
        progressBackgroundTintList = ColorStateList.valueOf(0xFF1E2B3E.toInt())
    }
    val progressLabel = TextView(this).apply {
        setTextColor(0xFF8899AA.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
    }

    val btnPlay = createRecordingBrowserActionButton("Reproducir", Color.WHITE) {
        background = menuActionButton(activity, isRed = false, isCyanAccent = false)
        setOnClickListener {
            state.activeEntry?.let { entry ->
                onDismiss()
                recordingBrowserSafePlay(entry)
            }
        }
    }
    val btnPlayStart = createRecordingBrowserActionButton("Desde el inicio", getColor(R.color.tv_turquoise)) {
        background = menuActionButton(activity, isRed = false, isCyanAccent = true)
        setOnClickListener {
            state.activeEntry?.let { entry ->
                onDismiss()
                runCatching {
                    entry.parts.firstOrNull()?.let { HomeScreenChannel.saveResumePosition(activity, it.name, 0L) }
                }
                recordingBrowserSafePlay(entry)
            }
        }
    }
    val btnDelete = createRecordingBrowserActionButton("Eliminar", Color.WHITE) {
        background = menuActionButton(activity, isRed = true)
        setOnClickListener {
            state.activeEntry?.let { entry ->
                state.pendingDeleteEntry = entry
                onRefresh()
            }
        }
    }

    val buttonsCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(btnPlay, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        addView(btnPlayStart, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        addView(btnDelete, LinearLayout.LayoutParams(-1, dp(36)))
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuCardSurface(activity, 18f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        clipChildren = true
        clipToPadding = false

        addView(thumbCard, LinearLayout.LayoutParams(-1, dp(140)).apply { bottomMargin = dp(10) })
        addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        addView(metaTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        addView(descTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(progressLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(12) })
        addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        addView(buttonsCol, LinearLayout.LayoutParams(-1, -2))
    }

    return RecordingBrowserDetailPanel(
        root = root,
        image = image,
        badgeTv = badgeTv,
        storagePillTv = storagePillTv,
        titleTv = titleTv,
        metaTv = metaTv,
        descTv = descTv,
        progressBar = progressBar,
        progressLabel = progressLabel,
        btnPlay = btnPlay,
        btnPlayStart = btnPlayStart,
        btnDelete = btnDelete,
    )
}
