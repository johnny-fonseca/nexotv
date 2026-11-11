// File: Nexotv/iptv/player/common/ByteSizeFormat.kt
package Nexotv.iptv.player.common

import java.util.Locale

private const val BYTE_UNIT_STEP = 1024.0
private const val ROUNDING_CARRY_THRESHOLD = 1023.95

/** Formatea bytes como B/KB/MB/GB/TB con un decimal. */
internal fun formatByteSize(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < 1024L) return "$value B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = -1
    while (size >= ROUNDING_CARRY_THRESHOLD && unit < units.lastIndex) {
        size /= BYTE_UNIT_STEP
        unit++
    }
    val safeUnit = unit.coerceIn(0, units.lastIndex)

    return try {
        String.format(Locale.US, "%.1f %s", size, units[safeUnit])
    } catch (_: RuntimeException) {
        "$value B"
    }
}