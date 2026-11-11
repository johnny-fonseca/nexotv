package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.R
import Nexotv.iptv.player.menu.animateTvFocus
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

private const val HEADER_TAG = "GuideHeader"
private const val TAB_SCHEDULE = 0
private const val TAB_SAVED = 1

private val FALLBACK_ROW_FOCUSED = 0xFF1E2A33.toInt()
private val FALLBACK_ROW_NORMAL = 0xFF121A20.toInt()
private val FALLBACK_ROW_STROKE = 0xFF2A3640.toInt()
private val FALLBACK_TURQUOISE = 0xFF40E0D0.toInt()

private fun Activity.headerDp(value: Int): Int {
    val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    return (value * density).toInt()
}

private fun Activity.headerColor(resId: Int, fallback: Int): Int =
    try {
        getColor(resId)
    } catch (e: Exception) {
        fallback
    }

/** Views owned by the guide header and day selector. */
internal data class GuideHeaderBinding(
    val view: LinearLayout,
    val scheduleTab: TextView,
    val savedTab: TextView,
    val daysContainer: LinearLayout,
    val daysScrollView: HorizontalScrollView,
    val refreshStyles: () -> Unit,
)

/** Builds the guide title, tabs, and horizontal day-selector viewport. */
internal fun Activity.buildGuideHeader(
    selectedTab: () -> Int,
    onTabSelected: (Int) -> Unit,
    guidePill: View,
    livePill: View,
    colorAccent: Int,
    colorSoft: Int,
): GuideHeaderBinding {
    val activity = this

    val subHeaderRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    val subHeaderLeftColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = false
    }

    val premiumHighlight = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = headerDp(8).toFloat()
            setColor(colorAccent)
            setAlpha(120)
            setStroke(headerDp(1), colorAccent)
        }
        elevation = 2f
    }

    val tabsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    fun createTab(label: String?): TextView = TextView(this).apply {
        id = View.generateViewId()
        if (label != null) text = label
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setPadding(headerDp(18), headerDp(9), headerDp(18), headerDp(9))
        layoutParams = LinearLayout.LayoutParams(headerDp(74), headerDp(78))
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        elevation = 0f
    }

    val scheduleTab = createTab("Guía")
    val savedTab = createTab(null)
    val baseTypeface: Typeface? = scheduleTab.typeface

    fun currentTab(): Int =
        try {
            selectedTab()
        } catch (e: Exception) {
            Log.w(HEADER_TAG, "selectedTab failed", e)
            TAB_SCHEDULE
        }

    fun savedProgramCount(): Int =
        try {
            ((activity as? MainActivity)?.savedPrograms()?.size ?: 0).coerceAtLeast(0)
        } catch (e: Exception) {
            0
        }

    val refreshStyles: () -> Unit = {
        try {
            val savedLabel = "Mis prog. · ${savedProgramCount()}"
            if (savedTab.text?.toString() != savedLabel) savedTab.text = savedLabel

            val rowFocused = activity.headerColor(R.color.tv_menu_row_focused, FALLBACK_ROW_FOCUSED)
            val rowNormal = activity.headerColor(R.color.tv_menu_row_normal, FALLBACK_ROW_NORMAL)
            val rowNormalStroke = activity.headerColor(R.color.tv_menu_row_normal_stroke, FALLBACK_ROW_STROKE)
            val turquoise = activity.headerColor(R.color.tv_turquoise, FALLBACK_TURQUOISE)
            val current = currentTab()

            listOf(scheduleTab to (current == TAB_SCHEDULE), savedTab to (current == TAB_SAVED))
                .forEach { (button, active) ->
                    val focused = button.isFocused
                    button.isSelected = active
                    button.background = if (active) {
                        GradientDrawable().apply {
                            cornerRadius = headerDp(12).toFloat()
                            setColor(rowFocused)
                            setStroke(headerDp(if (focused) 3 else 1), colorAccent)
                        }
                    } else {
                        GradientDrawable().apply {
                            cornerRadius = headerDp(12).toFloat()
                            setColor(if (focused) rowFocused else rowNormal)
                            setStroke(
                                headerDp(if (focused) 3 else 1),
                                if (focused) turquoise else rowNormalStroke,
                            )
                        }
                    }
                    button.setTextColor(colorSoft)
                    button.setTypeface(baseTypeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                }
        } catch (e: Exception) {
            Log.w(HEADER_TAG, "refreshStyles failed", e)
        }
    }

    fun dispatchTab(index: Int) {
        try {
            onTabSelected(index)
        } catch (e: Exception) {
            Log.e(HEADER_TAG, "onTabSelected($index) failed", e)
        }
    }

    scheduleTab.setOnClickListener { dispatchTab(TAB_SCHEDULE) }
    savedTab.setOnClickListener { dispatchTab(TAB_SAVED) }
    listOf(scheduleTab, savedTab).forEach { button ->
        button.setOnFocusChangeListener { view, focused ->
            refreshStyles()
            try {
                animateTvFocus(view, focused, focusedScale = 1.025f, elevationDp = 4f)
            } catch (e: Exception) {
                Log.w(HEADER_TAG, "animateTvFocus failed", e)
            }
        }
    }

    scheduleTab.nextFocusLeftId = scheduleTab.id
    scheduleTab.nextFocusRightId = savedTab.id
    savedTab.nextFocusLeftId = scheduleTab.id
    savedTab.nextFocusRightId = savedTab.id
    if (guidePill.id != View.NO_ID) scheduleTab.nextFocusUpId = guidePill.id
    if (livePill.id != View.NO_ID) savedTab.nextFocusUpId = livePill.id

    subHeaderLeftColumn.addView(
        premiumHighlight,
        LinearLayout.LayoutParams(-1, headerDp(2)).apply { bottomMargin = headerDp(6) },
    )
    subHeaderLeftColumn.addView(
        TextView(this).apply {
            text = "Guía de TV"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(-2, -2),
    )
    subHeaderRow.addView(subHeaderLeftColumn, LinearLayout.LayoutParams(0, -2, 1f))

    tabsRow.addView(
        scheduleTab,
        LinearLayout.LayoutParams(headerDp(74), headerDp(78)).apply { marginEnd = headerDp(8) },
    )
    tabsRow.addView(savedTab, LinearLayout.LayoutParams(headerDp(74), headerDp(78)))

    val daysContainer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(headerDp(3), 0, headerDp(3), 0)
    }
    val daysScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        clipChildren = false
        clipToPadding = false
        addView(daysContainer, ViewGroup.LayoutParams(-2, -1))
    }
    val controlsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(
            tabsRow,
            LinearLayout.LayoutParams(headerDp(156), headerDp(78)).apply { marginEnd = headerDp(12) },
        )
        addView(daysScrollView, LinearLayout.LayoutParams(0, headerDp(86), 1f))
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(subHeaderRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = headerDp(8) })
        addView(controlsRow, LinearLayout.LayoutParams(-1, headerDp(86)).apply { bottomMargin = headerDp(8) })
    }
    refreshStyles()
    return GuideHeaderBinding(root, scheduleTab, savedTab, daysContainer, daysScrollView, refreshStyles)
}