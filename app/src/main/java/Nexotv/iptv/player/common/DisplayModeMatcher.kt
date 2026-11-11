// File: Nexotv/iptv/player/common/DisplayModeMatcher.kt
package Nexotv.iptv.player.common

import android.os.Build
import android.view.Display
import kotlin.math.abs

/** Elige un modo de pantalla cuya tasa de refresco sea múltiplo entero de los FPS del video. */
internal object DisplayModeMatcher {

    private const val MAX_MULTIPLE = 8
    private const val DEFAULT_TOLERANCE = 0.002

    fun bestModeId(display: Display, fps: Float): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (!isValidFps(fps)) return null

        return try {
            val current = display.mode ?: return null
            if (multipleOf(current.refreshRate, fps) != null) return null

            val supported = display.supportedModes ?: return null

            var bestId: Int? = null
            var bestMultiple = Int.MAX_VALUE
            var bestError = Double.MAX_VALUE

            for (mode in supported) {
                if (mode == null) continue
                if (mode.physicalWidth != current.physicalWidth || mode.physicalHeight != current.physicalHeight) continue

                val multiple = multipleOf(mode.refreshRate, fps) ?: continue
                val error = relativeError(mode.refreshRate, fps, multiple) ?: continue

                val better = multiple < bestMultiple ||
                        (multiple == bestMultiple && error < bestError)
                if (better) {
                    bestId = mode.modeId
                    bestMultiple = multiple
                    bestError = error
                }
            }
            bestId
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isValidFps(fps: Float): Boolean = fps.isFinite() && fps > 0f

    private fun isValidRefresh(refresh: Float): Boolean = refresh.isFinite() && refresh > 0f

    private fun relativeError(refresh: Float, fps: Float, multiple: Int): Double? {
        if (!isValidRefresh(refresh) || !isValidFps(fps) || multiple < 1) return null
        val target = multiple.toDouble() * fps.toDouble()
        if (target <= 0.0 || !target.isFinite()) return null
        val error = abs(refresh.toDouble() / target - 1.0)
        return if (error.isFinite()) error else null
    }

    private fun multipleOf(refresh: Float, fps: Float): Int? {
        if (!isValidRefresh(refresh) || !isValidFps(fps)) return null
        for (multiple in 1..MAX_MULTIPLE) {
            val error = relativeError(refresh, fps, multiple) ?: continue
            if (error <= DEFAULT_TOLERANCE) return multiple
        }
        return null
    }
}