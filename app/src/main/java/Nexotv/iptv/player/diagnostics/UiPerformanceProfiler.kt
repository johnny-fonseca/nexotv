package Nexotv.iptv.player.diagnostics

import Nexotv.iptv.player.*
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.util.ArrayDeque

/** Métricas de UI solo para diagnóstico; no cambia el comportamiento visual. */
internal object UiPerformanceProfiler {
    private const val REBUILD_WINDOW_MS = 1_000L
    private const val SLOW_BUILD_MS = 32L
    private const val SLOW_UPDATE_MS = 16L
    private const val MAX_TRACKED_SCREENS = 64
    private const val MAX_SCREEN_NAME_LENGTH = 60
    private const val MAX_VIEW_COUNT = 50_000
    private const val DEFAULT_SCREEN = "Pantalla"
    private const val UNKNOWN_VIEW_COUNT = -1

    private data class ScreenState(var builds: Int = 0, var lastBuildAtMs: Long = 0L)

    private val states = LinkedHashMap<String, ScreenState>()

    private fun normalizeScreen(screen: String): String {
        val trimmed = screen.trim().ifEmpty { DEFAULT_SCREEN }
        if (trimmed.length <= MAX_SCREEN_NAME_LENGTH) return trimmed
        var end = MAX_SCREEN_NAME_LENGTH
        if (Character.isHighSurrogate(trimmed[end - 1])) end--
        return trimmed.substring(0, end)
    }

    private fun formatViewCount(count: Int): String =
        if (count < 0) "?" else if (count >= MAX_VIEW_COUNT) "$MAX_VIEW_COUNT+" else count.toString()

    @Synchronized
    private fun registerBuild(screen: String, now: Long): Pair<Int, Boolean> {
        var state = states[screen]
        if (state == null) {
            while (states.size >= MAX_TRACKED_SCREENS) {
                val iterator = states.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
            state = ScreenState()
            states[screen] = state
        }
        if (state.builds < Int.MAX_VALUE) state.builds++
        val previous = state.lastBuildAtMs
        val repeated = previous > 0L && now >= previous && now - previous <= REBUILD_WINDOW_MS
        state.lastBuildAtMs = now
        return state.builds to repeated
    }

    fun recordBuild(screen: String, root: View, startedAtMs: Long) {
        try {
            val name = normalizeScreen(screen)
            val now = SystemClock.elapsedRealtime()
            val duration = (now - startedAtMs).coerceAtLeast(0L)
            val viewCount = countViews(root)
            val (builds, repeated) = registerBuild(name, now)
            DiagnosticTracker.log(
                "UI_BUILD",
                "$name duración=${duration}ms vistas=${formatViewCount(viewCount)} construcción=$builds" +
                    if (repeated) " RECONSTRUCCIÓN_REPETIDA" else "",
            )
            if (repeated || duration >= SLOW_BUILD_MS) {
                DiagnosticTracker.log(
                    "UI_PERF_WARNING",
                    "$name: ${if (repeated) "reconstrucción repetida" else "construcción lenta"}; " +
                        "duración=${duration}ms, vistas=${formatViewCount(viewCount)}",
                )
            }
        } catch (_: Exception) {
        }
    }

    fun recordUpdate(screen: String, root: View, startedAtMs: Long) {
        try {
            val name = normalizeScreen(screen)
            val duration = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
            val viewCount = countViews(root)
            DiagnosticTracker.log("UI_UPDATE", "$name duración=${duration}ms vistas=${formatViewCount(viewCount)}")
            if (duration >= SLOW_UPDATE_MS) {
                DiagnosticTracker.log("UI_PERF_WARNING", "$name: actualización lenta ${duration}ms")
            }
        } catch (_: Exception) {
        }
    }

    private fun countViews(root: View): Int {
        return try {
            val pending = ArrayDeque<View>()
            pending.addLast(root)
            var count = 0
            while (pending.isNotEmpty() && count < MAX_VIEW_COUNT) {
                val view = pending.pollLast() ?: break
                count++
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index) ?: continue
                        pending.addLast(child)
                    }
                }
            }
            count
        } catch (_: Exception) {
            UNKNOWN_VIEW_COUNT
        }
    }
}