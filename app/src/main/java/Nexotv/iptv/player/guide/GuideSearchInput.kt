// GuideSearchInput.kt
@file:Suppress("SetTextI18n", "NewApi", "unused")

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.text.InputFilter
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

internal const val MAX_QUERY_LENGTH = 120
internal const val SUBTITLE_GUIDE = "Programas disponibles en la guía del canal."
internal const val GUIDE_SEARCH_LOG_TAG = "GuideSearch"

private const val SEARCH_INPUT_RAW_LIMIT = 1024
private const val SEARCH_CHIP_DEBOUNCE_MS = 250L

private val SEARCH_INPUT_FALLBACK_TURQUOISE = 0xFF40E0D0.toInt()
private val SEARCH_INPUT_FALLBACK_ROW = 0xFF121A20.toInt()

private val SEARCH_INVISIBLE_CHARS = Regex(
    "[\\u0000-\\u0008\\u000E-\\u001F\\u007F-\\u009F\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]",
)
private val SEARCH_WHITESPACE_RUN = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

private fun truncateAtBoundary(value: String, limit: Int): String {
    if (limit <= 0) return ""
    if (value.length <= limit) return value
    var end = limit
    if (Character.isHighSurrogate(value[end - 1])) end--
    return value.substring(0, end)
}

private fun dropLoneSurrogates(value: String): String {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
            out.append(c).append(value[i + 1])
            i += 2
        } else {
            if (!Character.isSurrogate(c)) out.append(c)
            i++
        }
    }
    return out.toString()
}

private fun Activity.searchColor(resId: Int, fallback: Int): Int =
    try {
        ContextCompat.getColor(this, resId)
    } catch (e: Exception) {
        fallback
    }

private fun guideSearchRowBackground(activity: Activity): Drawable =
    try {
        menuRowBackground(activity)
    } catch (e: Exception) {
        Log.w(GUIDE_SEARCH_LOG_TAG, "menuRowBackground failed", e)
        GradientDrawable().apply {
            cornerRadius = activity.guideSearchDp(10).toFloat()
            setColor(SEARCH_INPUT_FALLBACK_ROW)
        }
    }

internal fun Activity.guideSearchDp(value: Int): Int {
    val density = try {
        resources.displayMetrics.density
    } catch (e: Exception) {
        1f
    }
    val safeDensity = if (density.isFinite() && density > 0f) density else 1f
    return (value * safeDensity).toInt()
}

internal fun sanitizeQuery(raw: CharSequence?): String {
    if (raw == null) return ""
    val bounded: String = try {
        raw.subSequence(0, minOf(raw.length, SEARCH_INPUT_RAW_LIMIT)).toString()
    } catch (e: Exception) {
        return ""
    }
    val paired = dropLoneSurrogates(bounded)
    val normalized = try {
        Normalizer.normalize(paired, Normalizer.Form.NFC)
    } catch (e: Exception) {
        paired
    }
    val cleaned = normalized
        .replace(SEARCH_INVISIBLE_CHARS, "")
        .replace(SEARCH_WHITESPACE_RUN, " ")
        .trim()
    return truncateAtBoundary(cleaned, MAX_QUERY_LENGTH).trim()
}

internal inline fun safely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.w(GUIDE_SEARCH_LOG_TAG, "guarded block failed", e)
    }
}

internal inline fun safelyHandled(block: () -> Boolean): Boolean = try {
    block()
} catch (e: Exception) {
    Log.w(GUIDE_SEARCH_LOG_TAG, "guarded handler failed", e)
    false
}

internal fun Activity.requestSearchInputFocus(): Boolean = safelyHandled {
    (this as? MainActivity)?.guideSearchInput?.requestFocus() ?: false
}

internal fun capitalizeFirst(text: String): String =
    if (text.isEmpty()) {
        text
    } else {
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

internal fun View.applySearchFocusIndicator(activity: Activity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    safely {
        val radius = activity.guideSearchDp(10).toFloat()
        val strokeWidth = activity.guideSearchDp(3)
        val strokeColor = activity.searchColor(R.color.tv_turquoise, SEARCH_INPUT_FALLBACK_TURQUOISE)
        val focused = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth, strokeColor)
        }
        val idle = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
        }
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), idle)
        }
    }
}

internal fun buildGuideSearchInput(activity: Activity, styleInput: (EditText) -> Unit): EditText {
    val query = EditText(activity).apply {
        id = View.generateViewId()
        hint = "Ej. noticias, deportes..."
        isSingleLine = true
        textSize = 18f
        minimumHeight = activity.guideSearchDp(62)
        setPadding(
            activity.guideSearchDp(16),
            activity.guideSearchDp(8),
            activity.guideSearchDp(16),
            activity.guideSearchDp(8),
        )
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MAX_QUERY_LENGTH))
        background = guideSearchRowBackground(activity)
    }
    safely { styleInput(query) }
    safely {
        val retained = query.filters.filterNot { it is InputFilter.LengthFilter }
        query.filters = (retained + InputFilter.LengthFilter(MAX_QUERY_LENGTH)).toTypedArray()
    }
    query.applySearchFocusIndicator(activity)
    safely {
        val icon = ContextCompat.getDrawable(activity, R.drawable.tv_icon_search)?.mutate()
        if (icon != null) {
            icon.setTint(activity.searchColor(R.color.tv_turquoise, SEARCH_INPUT_FALLBACK_TURQUOISE))
            val size = activity.guideSearchDp(22)
            icon.setBounds(0, 0, size, size)
            query.setCompoundDrawablesRelative(icon, null, null, null)
            query.compoundDrawablePadding = activity.guideSearchDp(12)
        }
    }
    return query
}

internal fun buildGuideSearchChip(activity: Activity, term: String, onSearch: () -> Unit): Button {
    var lastClickAtMs = 0L
    return Button(activity).apply {
        id = View.generateViewId()
        text = sanitizeQuery(term)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAllCaps = false else transformationMethod = null
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(
            activity.guideSearchDp(12),
            activity.guideSearchDp(5),
            activity.guideSearchDp(12),
            activity.guideSearchDp(5),
        )
        background = guideSearchRowBackground(activity)
        isFocusable = true
        isFocusableInTouchMode = true
        setTextColor(activity.searchColor(R.color.tv_text_primary, Color.WHITE))
        safely { applyTvFocus(this) }
        applySearchFocusIndicator(activity)
        setOnClickListener {
            val nowMs = SystemClock.elapsedRealtime()
            if (lastClickAtMs != 0L && nowMs - lastClickAtMs in 0L until SEARCH_CHIP_DEBOUNCE_MS) {
                return@setOnClickListener
            }
            lastClickAtMs = nowMs
            safely { onSearch() }
        }
    }
}

internal fun wireGuideSearchFocus(
    query: EditText,
    chipViews: List<Button>,
    btnSearch: TextView,
    btnVoice: TextView?,
    results: LinearLayout,
) {
    safely {
        val chips = chipViews.toList()
        val ensureId = { view: View -> if (view.id == View.NO_ID) view.id = View.generateViewId() }
        ensureId(query)
        ensureId(btnSearch)
        ensureId(results)
        btnVoice?.let { ensureId(it) }
        chips.forEach { ensureId(it) }

        query.nextFocusLeftId = query.id
        query.nextFocusUpId = query.id
        query.nextFocusDownId = chips.firstOrNull()?.id ?: btnSearch.id
        query.nextFocusRightId = results.id
        chips.forEachIndexed { idx, chip ->
            chip.nextFocusUpId = query.id
            chip.nextFocusDownId = btnSearch.id
            chip.nextFocusLeftId = chips.getOrNull(idx - 1)?.id ?: chip.id
            chip.nextFocusRightId = chips.getOrNull(idx + 1)?.id ?: results.id
        }
        btnSearch.nextFocusUpId = chips.firstOrNull()?.id ?: query.id
        btnSearch.nextFocusDownId = btnSearch.id
        btnSearch.nextFocusLeftId = btnSearch.id
        btnSearch.nextFocusRightId = btnVoice?.id ?: results.id
        btnVoice?.apply {
            nextFocusUpId = chips.lastOrNull()?.id ?: query.id
            nextFocusDownId = id
            nextFocusLeftId = btnSearch.id
            nextFocusRightId = results.id
        }
    }
}