// File: TvProgram.kt
package Nexotv.iptv.player.tv
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
internal data class TvProgram(
    val channelId: String,
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val category: String = "",
    val rating: String = "",
    val iconUrl: String = "",
) {

    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    val isValid: Boolean
        get() = channelId.isNotBlank() &&
                title.isNotBlank() &&
                startMs >= 0L &&
                endMs > startMs

    fun hasStarted(nowMs: Long): Boolean = nowMs >= startMs

    fun hasEnded(nowMs: Long): Boolean = nowMs >= endMs

    fun isAiringAt(nowMs: Long): Boolean = isValid && nowMs >= startMs && nowMs < endMs

    fun overlaps(other: TvProgram): Boolean =
        channelId == other.channelId && startMs < other.endMs && other.startMs < endMs

    fun progressAt(nowMs: Long): Float {
        val duration = durationMs
        if (duration <= 0L) return 0f
        val elapsed = (nowMs - startMs).coerceIn(0L, duration)
        return elapsed.toFloat() / duration.toFloat()
    }
}

internal fun List<TvProgram>.distinctAndSorted(): List<TvProgram> =
    distinctBy { Triple(it.channelId, it.startMs, it.title) }
        .sortedWith(compareBy<TvProgram> { it.startMs }.thenBy { it.endMs })

internal fun List<TvProgram>.validOnly(): List<TvProgram> = filter { it.isValid }

internal fun List<TvProgram>.forChannel(channelId: String): List<TvProgram> =
    if (channelId.isBlank()) emptyList() else filter { it.channelId == channelId }

internal fun List<TvProgram>.airingAt(nowMs: Long): List<TvProgram> =
    filter { it.isAiringAt(nowMs) }

internal fun List<TvProgram>.currentFor(channelId: String, nowMs: Long): TvProgram? =
    forChannel(channelId).firstOrNull { it.isAiringAt(nowMs) }

internal fun List<TvProgram>.upcomingFrom(nowMs: Long, limit: Int = Int.MAX_VALUE): List<TvProgram> {
    if (limit <= 0) return emptyList()
    return filter { it.isValid && it.startMs > nowMs }
        .distinctAndSorted()
        .take(limit)
}

internal fun List<TvProgram>.sanitized(): List<TvProgram> =
    validOnly().distinctAndSorted()