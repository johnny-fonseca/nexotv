// ============================================================================
// Archivo: RecordingLibraryHelpers.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.util.Log

internal fun safeLong(block: () -> Long, fallback: Long = 0L): Long =
    try {
        block()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Lectura numérica fallida", e)
        fallback
    }

internal fun <T> safeCall(tag: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Operación fallida: $tag", e)
        fallback
    }

internal fun playbackPercent(savedMs: Long, durationMs: Long): Int =
    if (durationMs > 0L) ((savedMs.coerceAtLeast(0L) * 100L) / durationMs).toInt().coerceIn(0, 100) else 0

internal fun usedPercent(usable: Long, total: Long): Int {
    val safeTotal = total.coerceAtLeast(1L)
    return (((safeTotal - usable.coerceIn(0L, safeTotal)) * 100L) / safeTotal).toInt().coerceIn(0, 100)
}
