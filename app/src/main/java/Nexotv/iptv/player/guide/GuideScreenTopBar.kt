package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.R
import Nexotv.iptv.player.menu.MENU_BRAND_BADGE_SIZE_DP
import Nexotv.iptv.player.menu.animateTvFocus
import Nexotv.iptv.player.menu.applyTvFocus
import Nexotv.iptv.player.tv.dp
import Nexotv.iptv.player.menu.menuBrandBadge
import Nexotv.iptv.player.menu.menuPill
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private const val TOPBAR_TAG = "GuideTopBar"
private const val TOPBAR_ACTION_DEBOUNCE_MS = 350L

private val FALLBACK_TURQUOISE = 0xFF40E0D0.toInt()
private val FALLBACK_ROW_FOCUSED = 0xFF1E2A33.toInt()
private val FALLBACK_ROW_NORMAL = 0xFF121A20.toInt()
private val FALLBACK_ROW_STROKE = 0xFF2A3640.toInt()
private val FALLBACK_PANEL_STROKE = 0xFF2A3640.toInt()
private val FALLBACK_MENU_BG = 0xFF0B1116.toInt()

private fun MainActivity.topBarColor(resId: Int, fallback: Int): Int =
    try {
        getColor(resId)
    } catch (e: Exception) {
        Log.w(TOPBAR_TAG, "color resource $resId unavailable", e)
        fallback
    }

private fun MainActivity.topBarPillBackground(fill: Int, stroke: Int): Drawable =
    try {
        menuPill(this, fill, stroke)
    } catch (e: Exception) {
        Log.w(TOPBAR_TAG, "menuPill failed", e)
        GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

private fun safely(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.w(TOPBAR_TAG, "$what failed", e)
    }
}

private fun guardedAction(name: String, action: () -> Unit): () -> Unit {
    var lastInvokedAtMs = 0L
    return {
        val nowMs = SystemClock.elapsedRealtime()
        val withinDebounce = lastInvokedAtMs != 0L && nowMs - lastInvokedAtMs in 0L until TOPBAR_ACTION_DEBOUNCE_MS
        if (!withinDebounce) {
            lastInvokedAtMs = nowMs
            try {
                action()
            } catch (e: Exception) {
                Log.e(TOPBAR_TAG, "action '$name' failed", e)
            }
        }
    }
}

private fun View.linkFocus(left: View, right: View) {
    if (left.id != View.NO_ID) nextFocusLeftId = left.id
    if (right.id != View.NO_ID) nextFocusRightId = right.id
}

internal data class GuideTopBarBinding(
    val view: LinearLayout,
    val guidePill: View,
    val livePill: View,
    val recordingsPill: View,
    val morePill: View,
    val searchBox: View,
    val settingsButton: View,
    val clockText: TextView,
)

internal fun MainActivity.buildGuideTopBar(
    colorMuted: Int,
    colorSoft: Int,
    colorAccent: Int,
    onLive: () -> Unit,
    onRecordings: () -> Unit,
    onMore: () -> Unit,
    onSearch: () -> Unit,
): GuideTopBarBinding {
    val turquoise = topBarColor(R.color.tv_turquoise, FALLBACK_TURQUOISE)
    val rowFocused = topBarColor(R.color.tv_menu_row_focused, FALLBACK_ROW_FOCUSED)
    val rowNormal = topBarColor(R.color.tv_menu_row_normal, FALLBACK_ROW_NORMAL)
    val rowNormalStroke = topBarColor(R.color.tv_menu_row_normal_stroke, FALLBACK_ROW_STROKE)
    val panelStroke = topBarColor(R.color.tv_menu_panel_stroke, FALLBACK_PANEL_STROKE)
    val menuBg = topBarColor(R.color.tv_menu_bg, FALLBACK_MENU_BG)

    val safeLive = guardedAction("live", onLive)
    val safeRecordings = guardedAction("recordings", onRecordings)
    val safeMore = guardedAction("more", onMore)
    val safeSearch = guardedAction("search", onSearch)

    fun pill(
        label: String,
        active: Boolean = false,
        minWidthDp: Int = 0,
        action: () -> Unit = {},
    ): TextView = TextView(this).apply {
        id = View.generateViewId()
        text = label
        gravity = Gravity.CENTER
        textSize = 12.5f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minimumHeight = dp(36)
        if (minWidthDp > 0) minimumWidth = dp(minWidthDp)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        fun style(focused: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (active) turquoise else if (focused) rowFocused else rowNormal)
                setStroke(dp(if (focused) 3 else 1), if (active) colorAccent else rowNormalStroke)
            }
            setTextColor(if (active) menuBg else if (focused) Color.WHITE else colorSoft)
        }
        style(false)
        setOnFocusChangeListener { focusedView, focused ->
            safely("pill style") { style(focused) }
            safely("animateTvFocus") {
                animateTvFocus(focusedView, focused, focusedScale = 1.025f, elevationDp = 7f)
            }
        }
        setOnClickListener { action() }
    }

    val guide = pill("Guía", active = true)
    val live = pill("En vivo", action = safeLive)
    val recordings = pill("Mis Clips", action = safeRecordings)
    val more = pill("Más", minWidthDp = 90, action = safeMore)
    val pills = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(guide, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        addView(live, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        addView(recordings, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        addView(more, LinearLayout.LayoutParams(dp(84), dp(38)))
    }
    val pillsScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(pills, LinearLayout.LayoutParams(-2, -2))
    }
    val search = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = topBarPillBackground(rowNormal, panelStroke)
        setPadding(dp(12), dp(4), dp(10), dp(4))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        safely("applyTvFocus(search)") { applyTvFocus(this, scaleTo = 1.04f) }
        addView(ImageView(this@buildGuideTopBar).apply {
            safely("search icon") {
                setImageResource(R.drawable.tv_icon_search)
                imageTintList = ColorStateList.valueOf(colorMuted)
            }
        }, LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(8) })
        addView(TextView(this@buildGuideTopBar).apply {
            text = "Buscar programa…"
            setTextColor(colorMuted)
            textSize = 10.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { safeSearch() }
    }
    val clockText = TextView(this).apply {
        setTextColor(colorSoft)
        textSize = 11f
        maxLines = 1
        setTypeface(typeface, Typeface.BOLD)
    }
    val clock = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = topBarPillBackground(rowNormal, panelStroke)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        addView(clockText)
    }
    val settings = FrameLayout(this).apply {
        id = View.generateViewId()
        background = topBarPillBackground(rowNormal, panelStroke)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        safely("applyTvFocus(settings)") { applyTvFocus(this, scaleTo = 1.08f) }
        addView(ImageView(this@buildGuideTopBar).apply {
            safely("settings icon") {
                setImageResource(R.drawable.tv_icon_settings)
                imageTintList = ColorStateList.valueOf(colorSoft)
            }
        }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        setOnClickListener { safeMore() }
    }
    val brandBadge: View = try {
        menuBrandBadge(this)
    } catch (e: Exception) {
        Log.w(TOPBAR_TAG, "menuBrandBadge failed", e)
        View(this)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, dp(2), dp(8))
        addView(
            brandBadge,
            LinearLayout.LayoutParams(dp(MENU_BRAND_BADGE_SIZE_DP), dp(MENU_BRAND_BADGE_SIZE_DP)).apply { marginEnd = dp(10) },
        )
        addView(pillsScroll, LinearLayout.LayoutParams(0, -2, 1f))
        addView(search, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        addView(settings, LinearLayout.LayoutParams(dp(32), dp(32)))
    }
    guide.linkFocus(left = guide, right = live)
    live.linkFocus(left = guide, right = recordings)
    recordings.linkFocus(left = live, right = more)
    more.linkFocus(left = recordings, right = search)
    search.linkFocus(left = more, right = settings)
    settings.linkFocus(left = search, right = settings)
    return GuideTopBarBinding(root, guide, live, recordings, more, search, settings, clockText)
}