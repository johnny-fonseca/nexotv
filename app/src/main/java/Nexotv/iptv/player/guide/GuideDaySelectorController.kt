package Nexotv.iptv.player.guide

import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.tv.animateMenuLayoutChange

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Calendar

private fun MainActivity.dayDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt().coerceAtLeast(0)

internal class GuideDaySelectorController(
    private val activity: MainActivity,
    private val container: LinearLayout,
    private val scrollView: HorizontalScrollView,
    private val scheduleTab: View,
    private val savedTab: View,
    private val dates: () -> List<Calendar>,
    private val selectedTab: () -> Int,
    private val selectedDayIndex: () -> Int,
    private val onDaySelected: (Int) -> Unit,
    private val onRefresh: () -> Unit,
    private val weekdayFormat: DateFormat,
    private val shortWeekdayFormat: DateFormat,
    private val dayNumberFormat: DateFormat,
) {
    private companion object {
        const val MAX_DAYS = 31
        const val CARD_WIDTH_DP = 74
        const val CARD_HEIGHT_DP = 78
        const val CARD_MARGIN_DP = 8
        const val ERROR_TAG = "GUIDE_ERROR"
        const val TODAY_LABEL = "Hoy"
        const val TOMORROW_LABEL = "Mañana"
    }

    fun render(loading: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            renderInternal(loading)
        } catch (e: Exception) {
            DiagnosticTracker.log(ERROR_TAG, "daySelector.render: ${e.javaClass.simpleName}")
        }
    }

    private fun renderInternal(loading: Boolean) {
        ensureId(scheduleTab)
        ensureId(savedTab)

        val focusedIndex = (0 until container.childCount).firstOrNull {
            container.getChildAt(it)?.hasFocus() == true
        }
        runCatching { animateMenuLayoutChange(container) }
        container.removeAllViews()

        val tab = runCatching { selectedTab() }.getOrDefault(0)
        if (tab != 0) {
            scrollView.visibility = View.GONE
            savedTab.nextFocusRightId = savedTab.id
            return
        }
        scrollView.visibility = View.VISIBLE

        val days = runCatching { dates() }.getOrDefault(emptyList()).take(MAX_DAYS)
        val selectedIndex = runCatching { selectedDayIndex() }.getOrDefault(-1)
        val cardWidth = activity.dayDp(CARD_WIDTH_DP)
        val cardHeight = activity.dayDp(CARD_HEIGHT_DP)
        val cardMargin = activity.dayDp(CARD_MARGIN_DP)

        days.forEachIndexed { index, calendar ->
            val topLabel = when (index) {
                0 -> TODAY_LABEL
                1 -> TOMORROW_LABEL
                else -> weekdayFormat.safeFormat(calendar).replaceFirstChar { it.titlecase() }
            }
            val day = GuideDaySelectorCard.create(
                activity = activity,
                topLabel = topLabel,
                dayNumber = dayNumberFormat.safeFormat(calendar),
                weekday = shortWeekdayFormat.safeFormat(calendar).replaceFirstChar { it.titlecase() },
                selected = index == selectedIndex,
            ) { safeInvoke("onDaySelected") { onDaySelected(index) } }
            container.addView(
                day,
                LinearLayout.LayoutParams(cardWidth, cardHeight).apply { marginEnd = cardMargin },
            )
        }

        val refresh = GuideRefreshButton.create(activity, loading) {
            safeInvoke("onRefresh") { onRefresh() }
        }.apply { id = View.generateViewId() }
        container.addView(refresh, LinearLayout.LayoutParams(cardWidth, cardHeight))

        val count = container.childCount
        for (index in 0 until count) {
            val control = container.getChildAt(index) ?: continue
            ensureId(control)
            control.nextFocusLeftId = if (index == 0) {
                savedTab.id
            } else {
                container.getChildAt(index - 1)?.id ?: control.id
            }
            control.nextFocusRightId = container.getChildAt(index + 1)?.id ?: refresh.id
            control.nextFocusUpId = scheduleTab.id
        }

        val firstId = container.getChildAt(0)?.id ?: View.NO_ID
        savedTab.nextFocusRightId = if (firstId != View.NO_ID) firstId else savedTab.id
        scheduleTab.nextFocusRightId = savedTab.id
        refresh.nextFocusLeftId = if (count >= 2) {
            container.getChildAt(count - 2)?.id ?: refresh.id
        } else {
            refresh.id
        }
        refresh.nextFocusRightId = refresh.id
        refresh.nextFocusUpId = scheduleTab.id
        scheduleTab.nextFocusDownId = if (firstId != View.NO_ID) firstId else scheduleTab.id
        savedTab.nextFocusDownId = if (firstId != View.NO_ID) firstId else savedTab.id

        if (focusedIndex != null) {
            container.post {
                if (activity.isFinishing || activity.isDestroyed || !container.isAttachedToWindow) return@post
                val last = container.childCount - 1
                if (last < 0) return@post
                runCatching { container.getChildAt(focusedIndex.coerceIn(0, last))?.requestFocus() }
            }
        }
    }

    private fun ensureId(view: View) {
        if (view.id == View.NO_ID) view.id = View.generateViewId()
    }

    private fun DateFormat.safeFormat(calendar: Calendar): String = try {
        format(calendar.time)
    } catch (_: Exception) {
        ""
    }

    private inline fun safeInvoke(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            DiagnosticTracker.log(ERROR_TAG, "daySelector.$operation: ${e.javaClass.simpleName}")
        }
    }
}