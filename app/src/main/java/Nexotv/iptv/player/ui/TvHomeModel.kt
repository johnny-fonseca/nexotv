@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvHomeModel.kt
package Nexotv.iptv.player.ui

/** Destinations reachable from the home navigation panel. */
internal enum class TvHomeDestination {
    LIVE,
    GUIDE,
    SEARCH,
    CLIPS,
    MORE,
}

/**
 * Actions emitted by the home screen. The host maps them to the existing feature screens;
 * the screen itself never reaches into playback, network or storage code.
 */
internal interface TvHomeActions {
    fun onNavigate(destination: TvHomeDestination)
    fun onWatchNow()
    fun onToggleRecording()
    fun onOpenProgram(program: TvProgramUi)
    fun onRetryGuide()
    fun onTogglePlayback()
}

/** Formats a home recording duration as total minutes and seconds. */
internal fun homeRecordingElapsedLabel(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    return String.format(java.util.Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

/**
 * Focus and scroll memory for the home screen. The dialog is recreated on every open, so
 * the last focused rail card and the first visible rail position survive here.
 */
internal object TvHomeMemory {

    var railFirstVisible: Int = 0
    var lastRailKey: String? = null
    var focusedRail: Boolean = false

    fun clear() {
        railFirstVisible = 0
        lastRailKey = null
        focusedRail = false
    }
}

/**
 * The program airing now. If the guide overlaps, the most recently started one wins,
 * matching the legacy menu and the player overlay.
 */
internal fun airingProgram(programs: List<TvProgramUi>, nowMs: Long): TvProgramUi? {
    var best: TvProgramUi? = null
    for (program in programs) {
        if (program.startMs <= nowMs && program.endMs > nowMs) {
            if (best == null || program.startMs > best.startMs) best = program
        }
    }
    return best
}

/** The earliest program that starts after [nowMs]. */
internal fun nextProgram(programs: List<TvProgramUi>, nowMs: Long): TvProgramUi? {
    var best: TvProgramUi? = null
    for (program in programs) {
        if (program.startMs > nowMs) {
            if (best == null || program.startMs < best.startMs) best = program
        }
    }
    return best
}

/** The next [limit] programs in start-time order. */
internal fun upcomingPrograms(
    programs: List<TvProgramUi>,
    nowMs: Long,
    limit: Int,
): List<TvProgramUi> {
    if (limit <= 0) return emptyList()
    val future = ArrayList<TvProgramUi>(limit)
    for (program in programs) {
        if (program.startMs <= nowMs) continue
        if (future.size < limit) {
            future.add(program)
        } else {
            var latestIndex = 0
            for (index in 1 until future.size) {
                if (future[index].startMs > future[latestIndex].startMs) latestIndex = index
            }
            if (program.startMs < future[latestIndex].startMs) future[latestIndex] = program
        }
    }
    future.sortBy { it.startMs }
    return future
}

/** Progress in 0..[max]; safe for a zero-length or malformed program. */
internal fun progressPermille(program: TvProgramUi, nowMs: Long, max: Int): Int {
    if (max <= 0) return 0
    val duration = program.endMs - program.startMs
    if (duration <= 0L) return 0
    val elapsed = (nowMs - program.startMs).coerceIn(0L, duration)
    return (elapsed * max / duration).toInt().coerceIn(0, max)
}
