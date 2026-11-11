// RecordingWindow.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
internal data class RecordingWindow(val startMs: Long, val endMs: Long) {

    val durationMs: Long
        get() = if (endMs > startMs) saturatingSubtract(endMs, startMs) else 0L

    val isValid: Boolean
        get() = endMs > startMs

    fun overlaps(other: RecordingWindow): Boolean = startMs < other.endMs && endMs > other.startMs

    fun contains(instantMs: Long): Boolean = instantMs >= startMs && instantMs < endMs

    fun merge(other: RecordingWindow): RecordingWindow = RecordingWindow(
        startMs = minOf(startMs, other.startMs),
        endMs = maxOf(endMs, other.endMs),
    )

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        fun fromProgram(startMs: Long, endMs: Long, beforeMinutes: Int, afterMinutes: Int) = RecordingWindow(
            startMs = saturatingSubtract(startMs, beforeMinutes.coerceAtLeast(0) * MILLIS_PER_MINUTE),
            endMs = saturatingAdd(endMs, afterMinutes.coerceAtLeast(0) * MILLIS_PER_MINUTE),
        )
    }
}

internal fun scheduledStartConflictsWithActiveCapture(
    workerAlive: Boolean,
    incomingScheduleId: String?,
    activeScheduleId: String?,
): Boolean = workerAlive && !incomingScheduleId.isNullOrBlank() && incomingScheduleId != activeScheduleId

// Suma y resta saturadas para evitar desbordamiento de Long
private fun saturatingAdd(a: Long, b: Long): Long {
    val result = a + b
    return if (((a xor result) and (b xor result)) < 0) {
        if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
    } else {
        result
    }
}

private fun saturatingSubtract(a: Long, b: Long): Long {
    val result = a - b
    return if (((a xor b) and (a xor result)) < 0) {
        if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
    } else {
        result
    }
}