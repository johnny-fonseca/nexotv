@file:Suppress("PackageName", "KotlinConstantConditions", "SimplifyBooleanWithConstants", "ConstantConditions")

// GuideProgramQueries.kt
package Nexotv.iptv.player.guide



import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.recordings.ScheduledProgram
import Nexotv.iptv.player.tv.TvProgram

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue

private val GuideLocale: Locale = Locale.forLanguageTag("es-CR")

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L
private const val SEARCH_RESULT_LIMIT = 80
private const val MAX_SEARCH_QUERY_LENGTH = 120
private const val EVENING_START_HOUR = 18
private const val AFTERNOON_START_HOUR = 12
private const val FALLBACK_TIME = "--:--"

private val SAVED_SECTION_TITLE = "MIS PROGRAMAS GUARDADOS · ${BuildConfig.CHANNEL_NAME.uppercase()}"

private fun safeDurationMs(program: TvProgram): Long =
    runCatching { Math.subtractExact(program.endMs, program.startMs) }.getOrDefault(0L).coerceAtLeast(0L)

private fun safeElapsedMs(laterMs: Long, earlierMs: Long): Long =
    runCatching { Math.subtractExact(laterMs, earlierMs) }.getOrDefault(0L)

private fun formatDayPattern(pattern: String, timeMs: Long): String = try {
    SimpleDateFormat(pattern, GuideLocale).format(Date(timeMs))
} catch (_: Exception) {
    ""
}

private fun DateFormat.formatSafely(timeMs: Long): String = try {
    format(Date(timeMs))
} catch (_: Exception) {
    FALLBACK_TIME
}

/** Indexed saved and scheduled state for rendering a batch of guide rows. */
internal class GuideProgramUiStateIndex(
    savedPrograms: List<TvProgram>,
    scheduledPrograms: List<ScheduledProgram>,
) {
    private val savedKeys: Set<String> = savedPrograms.mapTo(HashSet(savedPrograms.size.coerceAtLeast(1))) { programKey(it) }
    private val schedulesByKey: Map<String, ScheduledProgram> =
        scheduledPrograms.associateBy { programKey(it.program) }

    fun isSaved(program: TvProgram): Boolean = programKey(program) in savedKeys

    fun schedule(program: TvProgram): ScheduledProgram? = schedulesByKey[programKey(program)]
}

/** Reuses saved and scheduled state while creating a batch of search result rows. */
internal class GuideSearchProgramUiStateIndex(
    savedPrograms: List<TvProgram>,
    scheduledPrograms: List<ScheduledProgram>,
) {
    private data class Key(val title: String, val startMs: Long)

    private val savedKeys = savedPrograms.mapTo(HashSet(savedPrograms.size.coerceAtLeast(1))) { key(it.title, it.startMs) }
    private val schedulesByKey = buildMap {
        scheduledPrograms.forEach { scheduled ->
            val key = key(scheduled.program.title, scheduled.program.startMs)
            if (key !in this) put(key, scheduled)
        }
    }

    fun isSaved(program: TvProgram): Boolean = key(program.title, program.startMs) in savedKeys

    fun schedule(program: TvProgram): ScheduledProgram? = schedulesByKey[key(program.title, program.startMs)]

    private fun key(title: String, startMs: Long) = Key(title.lowercase(Locale.ROOT), startMs)
}

/** Finds the first guide entry covering a timestamp without rescanning the entire guide. */
internal class GuideProgramTimeIndex(programs: List<TvProgram>) {
    private data class Entry(val program: TvProgram, val originalIndex: Int)

    private val entries = programs.mapIndexed { index, program -> Entry(program, index) }
        .sortedWith(compareBy<Entry> { it.program.startMs }.thenBy { it.originalIndex })

    private val treeCapacity = (entries.size.toLong() * 4L)
        .coerceIn(1L, (Int.MAX_VALUE - 8).toLong())
        .toInt()
    private val maxEndByNode = LongArray(treeCapacity) { Long.MIN_VALUE }
    private val minOriginalIndexByNode = IntArray(treeCapacity) { Int.MAX_VALUE }

    init {
        if (entries.isNotEmpty()) buildIndex(1, 0, entries.size)
    }

    fun at(timestampMs: Long): TvProgram? {
        if (entries.isEmpty()) return null
        val startLimit = upperBoundStart(timestampMs)
        if (startLimit == 0) return null
        return findAt(1, 0, entries.size, startLimit, timestampMs, null)?.program
    }

    fun nextAfter(timestampMs: Long): TvProgram? =
        entries.getOrNull(upperBoundStart(timestampMs))?.program

    private fun upperBoundStart(timestampMs: Long): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (entries[middle].program.startMs <= timestampMs) low = middle + 1
            else high = middle
        }
        return low
    }

    private fun buildIndex(node: Int, start: Int, end: Int) {
        if (end - start == 1) {
            val entry = entries[start]
            maxEndByNode[node] = entry.program.endMs
            minOriginalIndexByNode[node] = entry.originalIndex
            return
        }
        val middle = (start + end) ushr 1
        val left = node * 2
        val right = left + 1
        buildIndex(left, start, middle)
        buildIndex(right, middle, end)
        maxEndByNode[node] = maxOf(maxEndByNode[left], maxEndByNode[right])
        minOriginalIndexByNode[node] = minOf(minOriginalIndexByNode[left], minOriginalIndexByNode[right])
    }

    private fun findAt(
        node: Int,
        start: Int,
        end: Int,
        startLimit: Int,
        timestampMs: Long,
        currentBest: Entry?,
    ): Entry? {
        if (start >= startLimit || maxEndByNode[node] <= timestampMs ||
            minOriginalIndexByNode[node] >= (currentBest?.originalIndex ?: Int.MAX_VALUE)
        ) return currentBest

        if (end - start == 1) return entries[start]

        val middle = (start + end) ushr 1
        val left = node * 2
        val right = left + 1
        val bestFromLeft = findAt(left, start, middle, startLimit, timestampMs, currentBest)
        return findAt(right, middle, end, startLimit, timestampMs, bestFromLeft)
    }
}

/** Selects and orders guide entries for the active guide tab. */
internal object GuideProgramSelection {
    fun forDay(programs: List<TvProgram>, startMs: Long, endMs: Long): List<TvProgram> {
        if (endMs <= startMs || programs.isEmpty()) return emptyList()
        val selected = ArrayList<TvProgram>()
        var ordered = true
        var previousStart = Long.MIN_VALUE
        programs.forEach { program ->
            if (program.startMs < previousStart) ordered = false
            previousStart = program.startMs
            if (program.startMs < endMs && program.endMs > startMs) selected.add(program)
        }
        return if (ordered) selected else selected.sortedBy(TvProgram::startMs)
    }

    fun saved(programs: List<TvProgram>): List<TvProgram> {
        var previousStart = Long.MIN_VALUE
        for (program in programs) {
            if (program.startMs < previousStart) return programs.sortedBy(TvProgram::startMs)
            previousStart = program.startMs
        }
        return programs
    }

    fun nextAiring(programs: List<TvProgram>, nowMs: Long): TvProgram? =
        programs.asSequence()
            .filter { it.startMs > nowMs }
            .minByOrNull(TvProgram::startMs)
}

internal enum class GuideStatusKind {
    NO_GUIDE,
    NO_GUIDE_AFTER_FAILURE,
    CURRENT,
    STALE,
    CACHED_AFTER_FAILURE,
}

internal object GuideStatusResolver {
    fun resolve(
        programs: List<TvProgram>,
        updatedAtMs: Long,
        refreshFailed: Boolean,
        refreshIntervalHours: Int,
        nowMs: Long,
    ): GuideStatusKind {
        if (refreshFailed) {
            return if (programs.isNotEmpty()) GuideStatusKind.CACHED_AFTER_FAILURE
            else GuideStatusKind.NO_GUIDE_AFTER_FAILURE
        }
        if (programs.isEmpty()) {
            return if (updatedAtMs > 0L) GuideStatusKind.STALE else GuideStatusKind.NO_GUIDE
        }
        val expiredByInterval = updatedAtMs <= 0L ||
            (refreshIntervalHours > 0 &&
                safeElapsedMs(nowMs, updatedAtMs) > refreshIntervalHours.toLong() * MILLIS_PER_HOUR)
        val expiredByCoverage = programs.none { it.endMs > nowMs }
        return if (expiredByInterval || expiredByCoverage) GuideStatusKind.STALE else GuideStatusKind.CURRENT
    }
}

internal enum class GuideSearchEmptyState {
    NO_MATCHES,
    NO_GUIDE,
    NO_GUIDE_AFTER_FAILURE,
    STALE_OR_FAILED,
}

internal object GuideSearchEmptyStateSelector {
    fun select(status: GuideStatusKind): GuideSearchEmptyState = when (status) {
        GuideStatusKind.NO_GUIDE -> GuideSearchEmptyState.NO_GUIDE
        GuideStatusKind.NO_GUIDE_AFTER_FAILURE -> GuideSearchEmptyState.NO_GUIDE_AFTER_FAILURE
        GuideStatusKind.STALE, GuideStatusKind.CACHED_AFTER_FAILURE -> GuideSearchEmptyState.STALE_OR_FAILED
        GuideStatusKind.CURRENT -> GuideSearchEmptyState.NO_MATCHES
    }
}

internal data class GuideProgramFocusKey(val channelId: String, val startMs: Long, val title: String)

internal object GuideProgramFocus {
    fun key(program: TvProgram) = GuideProgramFocusKey(program.channelId, program.startMs, program.title)

    fun restore(programs: List<TvProgram>, focusedKey: GuideProgramFocusKey?, nowMs: Long): TvProgram? {
        if (programs.isEmpty()) return null
        if (focusedKey == null) return programs.firstOrNull { it.isAiringAt(nowMs) }
        return programs.firstOrNull { key(it) == focusedKey }
            ?: programs.firstOrNull {
                (it.channelId != focusedKey.channelId || it.startMs != focusedKey.startMs) &&
                    it.isAiringAt(nowMs)
            }
    }
}


/** Filters upcoming guide entries by title or description, in broadcast order. */
internal object GuideProgramSearch {
    fun find(
        programs: List<TvProgram>,
        query: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<TvProgram> {
        data class Candidate(val program: TvProgram, val index: Int)

        if (programs.isEmpty()) return emptyList()
        val trimmedQuery = query.trim().take(MAX_SEARCH_QUERY_LENGTH).trim()
        val order = compareBy<Candidate> { it.program.startMs }.thenBy { it.index }
        val reverseOrder = Comparator<Candidate> { a, b -> order.compare(b, a) }
        val earliestMatches = PriorityQueue(SEARCH_RESULT_LIMIT, reverseOrder)
        programs.forEachIndexed { index, program ->
            if (program.endMs < nowMs) return@forEachIndexed
            val matchesQuery = trimmedQuery.isEmpty() ||
                program.title.contains(trimmedQuery, ignoreCase = true) ||
                program.description.contains(trimmedQuery, ignoreCase = true)
            if (!matchesQuery) return@forEachIndexed
            val candidate = Candidate(program, index)
            if (earliestMatches.size < SEARCH_RESULT_LIMIT) {
                earliestMatches.add(candidate)
            } else {
                val worst = earliestMatches.peek()
                if (worst != null && order.compare(candidate, worst) < 0) {
                    earliestMatches.poll()
                    earliestMatches.add(candidate)
                }
            }
        }
        return earliestMatches.toList().sortedWith(order).map(Candidate::program)
    }
}

/** Text shown beneath a program title in the guide list. */
internal object GuideProgramMeta {
    fun describe(program: TvProgram): String {
        val durationMinutes = (safeDurationMs(program) / MILLIS_PER_MINUTE).coerceAtLeast(1L)
        val durationText = if (BuildConfig.BRAND_KEY != "futv") "$durationMinutes min" else null
        return listOfNotNull(
            program.category.takeIf(String::isNotBlank),
            durationText,
            program.rating.takeIf(String::isNotBlank),
        ).joinToString(" · ")
    }

    fun description(program: TvProgram, missingDescription: String): String {
        val supplied = program.description.trim()
        val isFutvPlaceholder = supplied.startsWith("Programación publicada por FUTV", ignoreCase = true) &&
            supplied.contains("La fuente no incluye descripción ni imagen", ignoreCase = true)
        if (supplied.isNotBlank() && !isFutvPlaceholder) return supplied
        return missingDescription
    }

    fun detail(program: TvProgram, selectedDayIndex: Int, timeFormat: DateFormat): String {
        val day = when (selectedDayIndex) {
            0 -> "Hoy"
            1 -> "Mañana"
            else -> formatDayPattern("EEE d MMM", program.startMs)
        }
        val range = "${timeFormat.formatSafely(program.startMs)} - ${timeFormat.formatSafely(program.endMs)}"
        return listOfNotNull(
            day.takeIf(String::isNotBlank),
            range,
            program.rating.takeIf(String::isNotBlank),
        ).joinToString(" · ")
    }
}

/** Formats the heading above the selected guide program list. */
internal object GuideSectionTitle {
    fun format(calendar: Calendar, selectedDayIndex: Int, selectedTab: Int, dateFormat: DateFormat): String {
        if (selectedTab != 0) return SAVED_SECTION_TITLE
        val date = try {
            dateFormat.format(calendar.time).uppercase(Locale.ROOT)
        } catch (_: Exception) {
            ""
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val period = when {
            selectedDayIndex == 0 && hour >= EVENING_START_HOUR -> "ESTA NOCHE"
            selectedDayIndex == 0 && hour < AFTERNOON_START_HOUR -> "ESTA MAÑANA"
            selectedDayIndex == 0 -> "ESTA TARDE"
            selectedDayIndex == 1 -> "MAÑANA"
            else -> formatDayPattern("EEEE", calendar.timeInMillis).uppercase(Locale.ROOT)
        }
        return when {
            period.isBlank() -> date
            date.isBlank() -> period
            else -> "$period · $date"
        }
    }
}

/** Human-readable age of the cached guide update timestamp. */
internal object GuideUpdateAge {
    fun format(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (updatedAtMs <= 0L) return "hace poco"
        val diffMs = safeElapsedMs(nowMs, updatedAtMs).coerceAtLeast(0L)
        val hours = diffMs / MILLIS_PER_HOUR
        val minutes = diffMs / MILLIS_PER_MINUTE
        return when {
            hours >= HOURS_PER_DAY -> "hace ${hours / HOURS_PER_DAY} d"
            hours >= 1L -> "hace $hours h"
            minutes >= 1L -> "hace $minutes min"
            else -> "recién actualizada"
        }
    }
}