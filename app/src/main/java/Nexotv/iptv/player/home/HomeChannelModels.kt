// File: Nexotv/iptv/player/home/HomeModels.kt
package Nexotv.iptv.player.home

import android.net.Uri
import Nexotv.iptv.player.guide.GuideProgramTimeIndex
import Nexotv.iptv.player.tv.TvProgram

/** Immutable snapshot shared by guide-driven home-row components. */
internal data class HomeGuideSnapshot(
    val programs: List<TvProgram>,
    val timeIndex: GuideProgramTimeIndex,
) {
    /** True when the snapshot carries no programs. */
    val isEmpty: Boolean
        get() = programs.isEmpty()

    companion object {
        /**
         * Builds a snapshot holding a defensive copy of [programs], so later mutation of a
         * caller-owned backing list cannot alter the published snapshot.
         */
        fun of(
            programs: List<TvProgram>,
            timeIndex: GuideProgramTimeIndex,
        ): HomeGuideSnapshot = HomeGuideSnapshot(
            programs = programs.toList(),
            timeIndex = timeIndex,
        )
    }
}

/** Recording metadata prepared for publication in the Android TV home channel. */
internal data class HomeRecordingGroup(
    val providerKey: String,
    val title: String,
    val uri: Uri,
    val parts: Int,
    val bytes: Long,
    val modified: Long,
    val description: String,
    val icon: String,
) {
    /** True when the group carries the minimum metadata required for channel publication. */
    val isPublishable: Boolean
        get() = providerKey.isNotBlank() &&
            title.isNotBlank() &&
            parts > 0 &&
            uri.toString().isNotBlank()

    /**
     * Returns a copy with text fields trimmed, stripped of control characters and length-bounded,
     * and numeric fields coerced into their valid, non-negative ranges.
     */
    fun sanitized(): HomeRecordingGroup = copy(
        providerKey = sanitizeHomeText(providerKey, MAX_KEY_LENGTH, preserveLineBreaks = false),
        title = sanitizeHomeText(title, MAX_TITLE_LENGTH, preserveLineBreaks = false),
        parts = parts.coerceAtLeast(0),
        bytes = bytes.coerceAtLeast(0L),
        modified = modified.coerceAtLeast(0L),
        description = sanitizeHomeText(description, MAX_DESCRIPTION_LENGTH, preserveLineBreaks = true),
        icon = sanitizeHomeText(icon, MAX_ICON_LENGTH, preserveLineBreaks = false),
    )

    companion object {
        const val MAX_KEY_LENGTH: Int = 256
        const val MAX_TITLE_LENGTH: Int = 256
        const val MAX_DESCRIPTION_LENGTH: Int = 1024
        const val MAX_ICON_LENGTH: Int = 2048
    }
}

private fun sanitizeHomeText(
    value: String,
    maxLength: Int,
    preserveLineBreaks: Boolean,
): String {
    if (maxLength <= 0 || value.isEmpty()) return ""

    val builder = StringBuilder(minOf(value.length, maxLength))
    for (ch in value) {
        when {
            ch == '\n' && preserveLineBreaks -> builder.append(ch)
            ch.isISOControl() || ch == '\u2028' || ch == '\u2029' -> builder.append(' ')
            else -> builder.append(ch)
        }
    }

    var result = builder.toString().trim()
    if (result.length > maxLength) {
        var end = maxLength
        if (end > 0 && Character.isHighSurrogate(result[end - 1])) end -= 1
        result = result.substring(0, end).trimEnd()
    }
    return result
}