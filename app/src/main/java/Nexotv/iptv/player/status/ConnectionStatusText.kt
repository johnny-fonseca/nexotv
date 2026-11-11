@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status


import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import java.util.Locale

private fun SpannableString.applySpan(span: Any, start: Int, end: Int) {
    if (start < 0 || end > length || start >= end) return
    setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun styledStatus(message: String): CharSequence {
    val cleanMessage = message.replace("\r\n", "\n")
    val styled = SpannableString(cleanMessage)
    val hasChannelHeader = cleanMessage.startsWith(STATUS_HEADER_PREFIX)
    var lineStart = 0
    cleanMessage.lines().forEachIndexed { index, line ->
        val lineEnd = lineStart + line.length
        val labelEnd = line.indexOf(STATUS_LABEL_SEPARATOR)
        if (labelEnd > 0) {
            val label = line.substring(0, labelEnd)
            if (label in STATUS_HIGHLIGHTED_LABELS) {
                val color = if (label == BuildConfig.BRAND_NAME.uppercase() && "EN VIVO" in line) STATUS_LIVE_LABEL_COLOR else STATUS_LABEL_COLOR
                styled.applySpan(ForegroundColorSpan(color), lineStart, lineStart + labelEnd)
                styled.applySpan(StyleSpan(Typeface.BOLD), lineStart, lineStart + labelEnd)
            }
        }
        if (hasChannelHeader && index == 0) {
            styled.applySpan(RelativeSizeSpan(0.78f), lineStart, lineEnd)
            styled.applySpan(ForegroundColorSpan(STATUS_HEADER_COLOR), lineStart, lineEnd)
        } else if (hasChannelHeader && index == 1) {
            styled.applySpan(StyleSpan(Typeface.BOLD), lineStart, lineEnd)
            styled.applySpan(RelativeSizeSpan(1.18f), lineStart, lineEnd)
            styled.applySpan(ForegroundColorSpan(Color.WHITE), lineStart, lineEnd)
        }
        lineStart = lineEnd + 1
    }
    return styled
}

private fun statusIconFor(message: String): Int {
    val value = message.lowercase(Locale.ROOT)
    fun containsAny(vararg terms: String) = terms.any { it in value }
    return when {
        containsAny("error", "falló", "fallo", "no se pudo", "insuficiente") -> R.drawable.tv_icon_warning
        containsAny("grab", "dvr") -> R.drawable.tv_icon_recordings
        containsAny("audio", "sonido") -> R.drawable.tv_icon_audio
        containsAny("video", "resolución", "fps") -> R.drawable.tv_icon_quality
        containsAny("conect", "buffer", "reintent", "red", "internet") -> R.drawable.tv_icon_tuning
        containsAny("guía", "guia", "program") -> R.drawable.tv_icon_guide
        containsAny("actualizad", "listo", "guardad", "activad", "desactivad") -> R.drawable.tv_icon_success
        else -> R.drawable.tv_icon_live
    }
}

internal fun MainActivity.setStatusText(message: String) {
    status.text = styledStatus(message)
    val icon = statusIconFor(message)
    val drawable = try {
        ContextCompat.getDrawable(this, icon)?.mutate()
    } catch (_: Exception) {
        null
    } ?: return
    drawable.setTint(
        when (icon) {
            R.drawable.tv_icon_warning -> STATUS_WARNING_TINT
            R.drawable.tv_icon_success -> STATUS_SUCCESS_TINT
            else -> STATUS_DEFAULT_TINT
        },
    )
    val size = dp(30)
    drawable.setBounds(0, 0, size, size)
    status.setCompoundDrawablesRelative(drawable, null, null, null)
    status.compoundDrawablePadding = dp(14)
}

