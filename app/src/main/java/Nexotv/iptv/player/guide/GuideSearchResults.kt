// GuideSearchResults.kt
@file:Suppress("SetTextI18n", "NewApi", "unused")

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.guide.GuideSearchEmptyState
import Nexotv.iptv.player.guide.GuideProgramMeta

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Utilidades defensivas privadas de este archivo. */
private object GuideSearchResultsGuard {
    const val MAX_RENDERED_RESULTS = 500
    const val MAX_TITLE_LENGTH = 200
    const val MAX_DESCRIPTION_LENGTH = 400
    const val CLICK_DEBOUNCE_MS = 350L
    const val UPCOMING_WINDOW_MS = 60L * 60L * 1_000L
    const val EMPTY_TITLE = "—"

    private val WHITESPACE = Regex("\\s+")

    fun isUsable(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed

    fun color(activity: Activity, resId: Int, fallback: Int): Int =
        try {
            activity.getColor(resId)
        } catch (e: Exception) {
            fallback
        }

    fun text(activity: Activity, resId: Int, fallback: String = ""): String =
        try {
            activity.getString(resId)
        } catch (e: Exception) {
            fallback
        }

    fun sanitize(raw: String?, maxLength: Int): String {
        if (raw.isNullOrEmpty() || maxLength <= 0) return ""
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        if (collapsed.length <= maxLength) return collapsed
        var end = maxLength
        if (end > 0 && Character.isHighSurrogate(collapsed[end - 1])) end--
        return collapsed.substring(0, end).trimEnd()
    }

    fun title(program: TvProgram): String =
        try {
            sanitize(program.title.orEmpty(), MAX_TITLE_LENGTH).ifEmpty { EMPTY_TITLE }
        } catch (e: Exception) {
            EMPTY_TITLE
        }

    fun schedule(startMs: Long, endMs: Long): String {
        if (startMs <= 0L) return ""
        return try {
            val locale = Locale.getDefault()
            val start = SimpleDateFormat("EEE d MMM · HH:mm", locale).format(Date(startMs))
            val end = SimpleDateFormat("HH:mm", locale).format(Date(maxOf(endMs, startMs)))
            "$start–$end"
        } catch (e: Exception) {
            ""
        }
    }

    fun debounced(action: () -> Unit): () -> Unit {
        var lastInvocation = -CLICK_DEBOUNCE_MS
        return {
            val now = SystemClock.elapsedRealtime()
            if (now - lastInvocation >= CLICK_DEBOUNCE_MS) {
                lastInvocation = now
                action()
            }
        }
    }

    fun detach(view: View) {
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (e: Exception) {
        }
    }
}

/** Presenta un programa de la guía como una fila de resultado enfocable. */
internal object GuideSearchResultRow {
    @SuppressLint("SetTextI18n")
    fun create(
        activity: Activity,
        program: TvProgram,
        saved: Boolean,
        scheduled: ScheduledProgram?,
        onSelected: () -> Unit,
    ): View =
        try {
            buildRow(activity, program, saved, scheduled, onSelected)
        } catch (e: Exception) {
            buildFallbackRow(activity, program, onSelected)
        }

    @SuppressLint("SetTextI18n")
    private fun buildRow(
        activity: Activity,
        program: TvProgram,
        saved: Boolean,
        scheduled: ScheduledProgram?,
        onSelected: () -> Unit,
    ): View {
        fun px(value: Int): Int = activity.guideSearchDp(value)

        val now = System.currentTimeMillis()
        val startMs = program.startMs
        val endMs = maxOf(program.endMs, startMs)
        val schedule = GuideSearchResultsGuard.schedule(startMs, endMs)
        val title = GuideSearchResultsGuard.title(program)
        val description = try {
            GuideSearchResultsGuard.sanitize(
                GuideProgramMeta.description(
                    program,
                    GuideSearchResultsGuard.text(activity, R.string.tv_guide_missing_description),
                ),
                GuideSearchResultsGuard.MAX_DESCRIPTION_LENGTH,
            )
        } catch (e: Exception) {
            ""
        }
        val chips = buildChips(activity, startMs, endMs, now, saved, scheduled)
        val secondaryLine = listOf(schedule, description)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        val accentColor = GuideSearchResultsGuard.color(activity, R.color.tv_turquoise, Color.CYAN)
        val secondaryColor = GuideSearchResultsGuard.color(activity, R.color.tv_text_secondary, Color.LTGRAY)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = true
            minimumHeight = px(96)
            setPadding(px(18), px(14), px(18), px(14))
            background = menuRowBackground(activity)
            applyTvFocus(this, scaleTo = 1.025f)
            applySearchFocusIndicator(activity)

            val statusAccent = View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = px(3).toFloat()
                    setColor(accentColor)
                }
            }
            addView(statusAccent, LinearLayout.LayoutParams(px(3), px(46)).apply {
                marginEnd = px(14)
            })

            val stateColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            chips.forEach { (icon, label, colorRes) ->
                stateColumn.addView(TextView(activity).apply {
                    text = "$icon $label".trim()
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(GuideSearchResultsGuard.color(activity, colorRes, Color.LTGRAY))
                    setPadding(0, px(1), 0, px(1))
                })
            }
            addView(stateColumn, LinearLayout.LayoutParams(px(150), -2).apply {
                marginEnd = px(12)
            })

            val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            info.addView(TextView(activity).apply {
                text = secondaryLine
                textSize = 12f
                setTextColor(secondaryColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(px(2).toFloat(), 1f)
            })
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))

            contentDescription = listOf(chips.joinToString(". ") { it.second }, title, schedule)
                .filter(String::isNotBlank)
                .joinToString(". ")
            bindInteraction(this, activity, onSelected)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) }
        }
    }

    private fun buildChips(
        activity: Activity,
        startMs: Long,
        endMs: Long,
        now: Long,
        saved: Boolean,
        scheduled: ScheduledProgram?,
    ): List<Triple<String, String, Int>> {
        fun chip(iconRes: Int, labelRes: Int, colorRes: Int): Triple<String, String, Int> =
            Triple(
                GuideSearchResultsGuard.text(activity, iconRes),
                GuideSearchResultsGuard.text(activity, labelRes),
                colorRes,
            )

        return buildList {
            if (now >= startMs && now < endMs) {
                add(chip(R.string.tv_guide_icon_live, R.string.tv_guide_live, R.color.tv_live))
            } else if (startMs > now && startMs - now < GuideSearchResultsGuard.UPCOMING_WINDOW_MS) {
                add(chip(R.string.tv_guide_icon_next, R.string.tv_guide_next, R.color.tv_blue))
            } else {
                add(chip(R.string.tv_guide_icon_later, R.string.tv_guide_later, R.color.tv_text_secondary))
            }
            if (saved) {
                add(chip(R.string.tv_guide_icon_saved, R.string.tv_guide_saved, R.color.tv_turquoise))
            }
            if (scheduled?.reminder == true) {
                add(chip(R.string.tv_guide_icon_reminder, R.string.tv_guide_reminder, R.color.tv_warning))
            }
            if (scheduled?.recording == true) {
                add(chip(R.string.tv_guide_icon_recording, R.string.tv_guide_recording_scheduled, R.color.tv_error))
            }
        }
    }

    private fun bindInteraction(view: View, activity: Activity, onSelected: () -> Unit) {
        val select = GuideSearchResultsGuard.debounced { safely { onSelected() } }
        view.setOnClickListener { select() }
        view.setOnKeyListener { _, keyCode, event ->
            event?.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                safelyHandled { activity.requestSearchInputFocus() }
        }
    }

    private fun buildFallbackRow(activity: Activity, program: TvProgram, onSelected: () -> Unit): View {
        val view = TextView(activity)
        try {
            view.text = GuideSearchResultsGuard.title(program)
            view.textSize = 17f
            view.setTextColor(Color.WHITE)
            view.maxLines = 1
            view.ellipsize = TextUtils.TruncateAt.END
            view.isFocusable = true
            view.isClickable = true
            view.isFocusableInTouchMode = true
            bindInteraction(view, activity, onSelected)
        } catch (e: Exception) {
        }
        try {
            val padding = activity.guideSearchDp(14)
            view.setPadding(padding, padding, padding, padding)
            view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = activity.guideSearchDp(10)
            }
            view.applySearchFocusIndicator(activity)
        } catch (e: Exception) {
        }
        return view
    }
}

/** Dibuja los resultados de búsqueda o el estado vacío en el panel activo. */
internal object GuideSearchResultsView {
    fun render(
        activity: Activity,
        container: LinearLayout,
        matches: List<TvProgram>,
        emptyState: GuideSearchEmptyState,
        createRow: (TvProgram) -> View,
        createButton: (String, () -> Unit) -> TextView,
        onRetry: () -> Unit,
        onRefresh: () -> Unit,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                activity.runOnUiThread {
                    render(activity, container, matches, emptyState, createRow, createButton, onRetry, onRefresh)
                }
            } catch (e: Exception) {
            }
            return
        }
        if (!GuideSearchResultsGuard.isUsable(activity)) return

        try {
            container.removeAllViews()
            val rendered = matches.isNotEmpty() && renderMatches(activity, container, matches, createRow)
            if (!rendered) {
                renderEmptyState(activity, container, emptyState, createButton, onRetry, onRefresh)
            }
        } catch (e: Exception) {
            try {
                container.removeAllViews()
                renderEmptyState(activity, container, emptyState, createButton, onRetry, onRefresh)
            } catch (inner: Exception) {
                try {
                    container.removeAllViews()
                } catch (last: Exception) {
                }
            }
        }
    }

    private fun renderEmptyState(
        activity: Activity,
        container: LinearLayout,
        emptyState: GuideSearchEmptyState,
        createButton: (String, () -> Unit) -> TextView,
        onRetry: () -> Unit,
        onRefresh: () -> Unit,
    ) {
        fun px(value: Int): Int = activity.guideSearchDp(value)

        val messageRes = when (emptyState) {
            GuideSearchEmptyState.NO_MATCHES -> R.string.tv_guide_search_no_matches
            GuideSearchEmptyState.NO_GUIDE -> R.string.tv_guide_search_no_guide
            GuideSearchEmptyState.NO_GUIDE_AFTER_FAILURE -> R.string.tv_guide_search_no_guide_error
            GuideSearchEmptyState.STALE_OR_FAILED -> R.string.tv_guide_search_stale_or_failed
        }
        val message = GuideSearchResultsGuard.text(activity, messageRes)
        val pillFill = GuideSearchResultsGuard.color(activity, R.color.tv_menu_row_normal, Color.DKGRAY)
        val pillStroke = GuideSearchResultsGuard.color(activity, R.color.tv_menu_panel_stroke, Color.GRAY)
        val iconTint = GuideSearchResultsGuard.color(activity, R.color.tv_turquoise, Color.CYAN)
        val primaryText = GuideSearchResultsGuard.color(activity, R.color.tv_text_primary, Color.WHITE)

        val emptyCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = menuCardSurface(activity, 16f)
            setPadding(px(20), px(18), px(20), px(18))
            addView(FrameLayout(activity).apply {
                background = menuPill(activity, pillFill, pillStroke)
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.tv_icon_search)
                    imageTintList = ColorStateList.valueOf(iconTint)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(48), px(48)).apply {
                bottomMargin = px(14)
            })
            addView(TextView(activity).apply {
                text = message
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
        }
        container.addView(emptyCard, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = px(10)
        })

        val leftToInput = View.OnKeyListener { _, keyCode, event ->
            event?.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                safelyHandled { activity.requestSearchInputFocus() }
        }

        val btnRetry = createGuardedButton(activity, createButton, R.string.tv_guide_search_retry_btn, onRetry, leftToInput)
        val btnRefresh = createGuardedButton(activity, createButton, R.string.tv_guide_search_refresh_btn, onRefresh, leftToInput)
        if (btnRetry != null && btnRefresh != null) {
            btnRetry.nextFocusDownId = btnRefresh.id
            btnRefresh.nextFocusUpId = btnRetry.id
        }

        btnRetry?.let {
            GuideSearchResultsGuard.detach(it)
            container.addView(it, LinearLayout.LayoutParams(-1, px(44)))
        }
        btnRefresh?.let {
            GuideSearchResultsGuard.detach(it)
            container.addView(it, LinearLayout.LayoutParams(-1, px(44)).apply {
                topMargin = px(6)
            })
        }
    }

    private fun createGuardedButton(
        activity: Activity,
        createButton: (String, () -> Unit) -> TextView,
        labelRes: Int,
        action: () -> Unit,
        keyListener: View.OnKeyListener,
    ): TextView? =
        try {
            createButton(
                GuideSearchResultsGuard.text(activity, labelRes),
                GuideSearchResultsGuard.debounced { safely { action() } },
            ).apply {
                id = View.generateViewId()
                applySearchFocusIndicator(activity)
                setOnKeyListener(keyListener)
            }
        } catch (e: Exception) {
            null
        }

    private fun createRowSafely(createRow: (TvProgram) -> View, program: TvProgram): View? =
        try {
            createRow(program)
        } catch (e: Exception) {
            null
        }

    private fun renderMatches(
        activity: Activity,
        container: LinearLayout,
        matches: List<TvProgram>,
        createRow: (TvProgram) -> View,
    ): Boolean {
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val usedIds = HashSet<Int>()

        for (program in matches) {
            if (list.childCount >= GuideSearchResultsGuard.MAX_RENDERED_RESULTS) break
            val row = createRowSafely(createRow, program) ?: continue
            GuideSearchResultsGuard.detach(row)
            var rowId = row.id
            if (rowId == View.NO_ID || !usedIds.add(rowId)) {
                rowId = View.generateViewId()
                row.id = rowId
                usedIds.add(rowId)
            }
            try {
                list.addView(row)
            } catch (e: Exception) {
                usedIds.remove(rowId)
                continue
            }
        }

        val count = list.childCount
        if (count == 0) return false

        for (i in 0 until count) {
            val row = list.getChildAt(i)
            row.nextFocusUpId = if (i > 0) list.getChildAt(i - 1).id else row.id
            row.nextFocusDownId = if (i < count - 1) list.getChildAt(i + 1).id else row.id
            row.nextFocusRightId = row.id
        }

        container.addView(ScrollView(activity).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            addView(list)
            try {
                scrollFocusedDescendantIntoView()
            } catch (e: Exception) {
            }
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        list.getChildAt(0)?.let { first ->
            first.post {
                if (GuideSearchResultsGuard.isUsable(activity) && first.isAttachedToWindow) {
                    try {
                        first.requestFocus()
                    } catch (e: Exception) {
                    }
                }
            }
        }
        return true
    }
}