// GuideProgramCard.kt
package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.guide.GuideProgramMeta

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

/** Renders one guide program row; navigation and playback remain owned by the Activity. */
internal object GuideProgramCard {
    private const val ERROR_TAG = "GUIDE_ERROR"
    private const val FALLBACK_TIME = "--:--"
    private const val MIN_PROGRESS_FRACTION = 0.05f
    private const val MAX_DESCRIPTION_LINES = 4
    private const val MAX_TEXT_LENGTH = 1_000

    private inline fun guard(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            DiagnosticTracker.log(ERROR_TAG, "programCard.$operation: ${e.javaClass.simpleName}")
        }
    }

    private inline fun safeText(operation: String, fallback: String, block: () -> String?): String =
        try {
            (block() ?: fallback).take(MAX_TEXT_LENGTH)
        } catch (e: Exception) {
            DiagnosticTracker.log(ERROR_TAG, "programCard.$operation: ${e.javaClass.simpleName}")
            fallback
        }

    private fun progressFraction(startMs: Long, endMs: Long, nowMs: Long): Float {
        val duration = runCatching { Math.subtractExact(endMs, startMs) }.getOrDefault(1L).coerceAtLeast(1L)
        val elapsed = runCatching { Math.subtractExact(nowMs, startMs) }.getOrDefault(0L).coerceAtLeast(0L)
        val fraction = elapsed.toDouble() / duration.toDouble()
        if (fraction.isNaN()) return MIN_PROGRESS_FRACTION
        return fraction.toFloat().coerceIn(MIN_PROGRESS_FRACTION, 1f)
    }

    fun create(
        activity: Activity,
        program: TvProgram,
        status: GuideProgramStatus,
        saved: Boolean,
        reminder: Boolean,
        recording: Boolean,
        nowMs: Long,
        formatTime: (Long) -> String,
        onFocused: (View) -> Unit,
        onSelected: (isLive: Boolean, card: View) -> Unit,
        onNavigateRight: () -> Unit,
    ): LinearLayout {
        val live = status == GuideProgramStatus.LIVE
        val card = LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(activity.guideDp(12), activity.guideDp(12), activity.guideDp(12), activity.guideDp(12))
            // El foco del control remoto puede expandir el borde del elemento; impedir el recorte del halo.
            clipChildren = false
            clipToPadding = false
            background = menuRowBackground(activity)
        }

        val colorTurquoise = activity.getColor(R.color.tv_turquoise)
        val colorRowStroke = activity.getColor(R.color.tv_menu_row_normal_stroke)
        val colorLive = activity.getColor(R.color.tv_live)

        val accentBar = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = activity.guideDp(4).toFloat()
                setColor(if (live) colorTurquoise else colorRowStroke)
            }
        }
        card.addView(
            accentBar,
            LinearLayout.LayoutParams(activity.guideDp(4), activity.guideDp(46)).apply { marginEnd = activity.guideDp(10) },
        )

        val timeColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(activity).apply {
                text = safeText("startTime", FALLBACK_TIME) { formatTime(program.startMs) }
                setTextColor(activity.getColor(R.color.tv_text_primary))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            })
            addView(TextView(activity).apply {
                text = safeText("endTime", FALLBACK_TIME) { formatTime(program.endMs) }
                setTextColor(activity.getColor(R.color.tv_text_secondary))
                textSize = 10f
                maxLines = 1
            })
            val badge = runCatching { GuideProgramStatusBadge.create(activity, status) }
                .onFailure { guard("statusBadge") { throw it } }
                .getOrNull()
            if (badge != null) {
                addView(badge, LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.guideDp(4) })
            }
        }
        card.addView(timeColumn, LinearLayout.LayoutParams(activity.guideDp(78), -2))

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = safeText("title", "") { program.title }
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))

        fun addIcon(resource: Int, color: Int) {
            guard("icon") {
                titleRow.addView(ImageView(activity).apply {
                    setImageResource(resource)
                    imageTintList = ColorStateList.valueOf(color)
                }, LinearLayout.LayoutParams(activity.guideDp(14), activity.guideDp(14)).apply {
                    marginStart = activity.guideDp(6)
                })
            }
        }
        if (saved) addIcon(R.drawable.tv_icon_bookmark, colorTurquoise)
        if (reminder) addIcon(R.drawable.tv_icon_reminder, 0xFFFBBF24.toInt())
        if (recording) addIcon(R.drawable.tv_menu_record, colorLive)
        content.addView(titleRow)
        content.addView(TextView(activity).apply {
            text = safeText("meta", "") { GuideProgramMeta.describe(program) }
            setTextColor(activity.getColor(R.color.tv_text_primary))
            textSize = 12.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.guideDp(2) })
        content.addView(TextView(activity).apply {
            text = safeText("description", "") {
                GuideProgramMeta.description(program, activity.getString(R.string.tv_guide_missing_description))
            }
            setTextColor(activity.getColor(R.color.tv_text_secondary))
            textSize = 12f
            maxLines = MAX_DESCRIPTION_LINES
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.guideDp(2) })

        if (live) {
            val track = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = activity.guideDp(3).toFloat()
                    setColor(activity.getColor(R.color.tv_menu_panel_stroke))
                }
            }
            val fraction = progressFraction(program.startMs, program.endMs, nowMs)
            val fill = View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = activity.guideDp(3).toFloat()
                    setColor(colorLive)
                }
            }
            track.addView(fill, FrameLayout.LayoutParams(-1, -1))
            track.post {
                guard("progress") {
                    if (track.isAttachedToWindow && track.width > 0) {
                        val width = (track.width * fraction).toInt()
                            .coerceAtLeast(activity.guideDp(8))
                            .coerceAtMost(track.width)
                        fill.layoutParams = FrameLayout.LayoutParams(width, -1)
                    }
                }
            }
            content.addView(track, LinearLayout.LayoutParams(-1, activity.guideDp(4)).apply {
                topMargin = activity.guideDp(6)
            })
        }
        card.addView(content, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = activity.guideDp(8)
            marginEnd = activity.guideDp(10)
        })

        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        if (reminder || recording) right.addView(TextView(activity).apply {
            text = if (recording) "Grabación" else "Recordatorio"
            setTextColor(activity.getColor(if (recording) R.color.tv_live else R.color.tv_success))
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.guideDp(3) })
        card.addView(right)

        val rowRadius = runCatching { activity.resources.getDimension(R.dimen.tv_menu_row_radius) }
            .getOrDefault(activity.guideDp(12).toFloat())
        val focusedBackground = GradientDrawable().apply {
            cornerRadius = rowRadius
            setColor(activity.getColor(R.color.tv_menu_row_focused))
            setStroke(activity.guideDp(3), colorTurquoise)
        }
        val normalBackground = GradientDrawable().apply {
            cornerRadius = rowRadius
            setColor(activity.getColor(R.color.tv_menu_row_normal))
            setStroke(activity.guideDp(1), colorRowStroke)
        }
        val focusedElevation = activity.guideDp(5).toFloat()

        fun updateFocus(focused: Boolean) {
            accentBar.setBackgroundColor(if (focused || live) colorTurquoise else colorRowStroke)
            card.background = if (focused) focusedBackground else normalBackground
        }
        updateFocus(false)
        card.setOnFocusChangeListener { _, focused ->
            guard("focus") {
                updateFocus(focused)
                if (focused) onFocused(card)
                card.animate().cancel()
                card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(if (focused) focusedElevation else 0f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .withLayer()
                    .start()
            }
        }
        card.setOnClickListener { guard("select") { onSelected(live, card) } }
        card.setOnKeyListener { _, keyCode, event ->
            if (event != null && event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                guard("navigateRight") { onNavigateRight() }
                true
            } else false
        }
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.guideDp(8) }
        return card
    }
}