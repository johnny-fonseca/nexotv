package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.recordings.toggleRecording
import Nexotv.iptv.player.menu.recordingElapsed
import Nexotv.iptv.player.recordings.isRecording
import Nexotv.iptv.player.R
import Nexotv.iptv.player.menu.applyTvFocus
import Nexotv.iptv.player.tv.dp

import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val RECORDING_TOGGLE_DEBOUNCE_MS = 400L
private const val DEFAULT_REFRESH_INTERVAL_HOURS = 6
private const val MIN_REFRESH_INTERVAL_HOURS = 1

private fun MainActivity.guideFooterColor(resId: Int, fallback: Int): Int =
    try {
        getColor(resId)
    } catch (e: Exception) {
        fallback
    }

private fun SharedPreferences.readLongOrDefault(key: String, default: Long): Long =
    try {
        getLong(key, default)
    } catch (e: Exception) {
        default
    }

private fun SharedPreferences.readIntOrDefault(key: String, default: Int): Int =
    try {
        getInt(key, default)
    } catch (e: Exception) {
        default
    }

private fun formatGuideTime(pattern: String, locale: Locale, zone: TimeZone, epochMs: Long): String? =
    try {
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(epochMs))
    } catch (e: Exception) {
        null
    }

/** Views and refresh callbacks owned by the guide footer. */
internal data class GuideFooterBinding(
    val view: LinearLayout,
    val recordingHelper: View,
    val refreshRecordingText: () -> Unit,
    val refreshGuideStatus: () -> Unit,
)

/** Builds the navigation hints, recording action, and XMLTV status shown below the guide. */
internal fun MainActivity.buildGuideFooter(
    prefs: SharedPreferences,
    colorMuted: Int,
    colorError: Int,
    timeZone: TimeZone,
    guideLocale: Locale,
): GuideFooterBinding {
    val liveColor = guideFooterColor(R.color.tv_live, colorError)

    val footerBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), 0)
    }

    fun footerHelper(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(colorMuted)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    footerBar.addView(footerHelper("↕ Navegar"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
    footerBar.addView(footerHelper("Atrás / Menú"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })

    val recordingText = TextView(this).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    val refreshRecordingText: () -> Unit = {
        try {
            val recording = isRecording()
            val label = if (recording) {
                val elapsed = try {
                    recordingElapsed()?.toString()?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }
                if (elapsed != null) "Detener grabación ($elapsed)" else "Detener grabación"
            } else {
                "Grabar ahora"
            }
            recordingText.text = label
            recordingText.setTextColor(if (recording) liveColor else colorError)
        } catch (e: Exception) {
            recordingText.text = "Grabar ahora"
            recordingText.setTextColor(colorError)
        }
    }
    refreshRecordingText()

    val recordingHelper = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        try {
            applyTvFocus(this, scaleTo = 1.05f)
        } catch (e: Exception) {
            // Focus styling is cosmetic; the footer must still render and remain clickable.
        }

        addView(View(this@buildGuideFooter).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(liveColor)
            }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
        addView(recordingText)

        var lastToggleAtMs = 0L
        setOnClickListener {
            val nowMs = SystemClock.elapsedRealtime()
            if (lastToggleAtMs != 0L && nowMs - lastToggleAtMs in 0 until RECORDING_TOGGLE_DEBOUNCE_MS) {
                return@setOnClickListener
            }
            lastToggleAtMs = nowMs
            try {
                toggleRecording()
            } catch (e: Exception) {
                try {
                    showTvToast("No se pudo cambiar la grabación", long = true)
                } catch (inner: Exception) {
                    // Toast failure must never propagate to the UI thread.
                }
            }
            refreshRecordingText()
        }
    }
    footerBar.addView(recordingHelper, LinearLayout.LayoutParams(0, -2, 1f))

    val statusText = TextView(this).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun resolveGuideStatusPresentation(): Pair<String, Boolean> {
        val now = System.currentTimeMillis()
        val updatedAt = prefs.readLongOrDefault(MainActivity.KEY_EPG_UPDATED, 0L).coerceAtLeast(0L)
        val intervalHours = prefs
            .readIntOrDefault(MainActivity.KEY_EPG_INTERVAL_HOURS, DEFAULT_REFRESH_INTERVAL_HOURS)
            .coerceAtLeast(MIN_REFRESH_INTERVAL_HOURS)

        val resolved = GuideStatusResolver.resolve(
            programs = guidePrograms,
            updatedAtMs = updatedAt,
            refreshFailed = guideLoadError != null,
            refreshIntervalHours = intervalHours,
            nowMs = now,
        )
        val status = if (resolved == GuideStatusKind.CURRENT && updatedAt == 0L) {
            GuideStatusKind.NO_GUIDE
        } else {
            resolved
        }
        val isError = status == GuideStatusKind.CACHED_AFTER_FAILURE ||
            status == GuideStatusKind.NO_GUIDE_AFTER_FAILURE

        val label = when (status) {
            GuideStatusKind.NO_GUIDE -> getString(R.string.tv_guide_status_no_guide)
            GuideStatusKind.NO_GUIDE_AFTER_FAILURE -> getString(R.string.tv_guide_status_no_guide_error)
            GuideStatusKind.STALE -> getString(R.string.tv_guide_status_pending_refresh)
            GuideStatusKind.CACHED_AFTER_FAILURE -> getString(R.string.tv_guide_status_cached_error)
            GuideStatusKind.CURRENT -> {
                val shownAt = minOf(updatedAt, now)
                val clock = formatGuideTime("HH:mm", guideLocale, timeZone, shownAt)
                if (clock == null) {
                    getString(R.string.tv_guide_status_no_guide)
                } else if (costaRicaDateKey(shownAt) == costaRicaDateKey(now)) {
                    getString(R.string.tv_guide_status_updated_today, clock)
                } else {
                    val date = formatGuideTime("d MMM", guideLocale, timeZone, shownAt)
                    if (date == null) {
                        getString(R.string.tv_guide_status_updated_today, clock)
                    } else {
                        getString(R.string.tv_guide_status_updated_date, date, clock)
                    }
                }
            }
        }
        return label to isError
    }

    val refreshGuideStatus: () -> Unit = {
        val (label, isError) = try {
            resolveGuideStatusPresentation()
        } catch (e: Exception) {
            val fallback = try {
                getString(R.string.tv_guide_status_no_guide)
            } catch (inner: Exception) {
                ""
            }
            fallback to false
        }
        statusText.text = label
        statusText.setTextColor(if (isError) colorError else colorMuted)
    }
    refreshGuideStatus()

    footerBar.addView(LinearLayout(this).apply { addView(statusText) }, LinearLayout.LayoutParams(-2, -2))
    return GuideFooterBinding(footerBar, recordingHelper, refreshRecordingText, refreshGuideStatus)
}