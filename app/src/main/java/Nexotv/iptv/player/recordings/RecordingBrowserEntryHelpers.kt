// RecordingBrowserEntryHelpers.kt
package Nexotv.iptv.player.recordings



import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.status.*
import java.text.SimpleDateFormat
import java.util.Date
import Nexotv.iptv.player.MainActivity.*

internal fun MainActivity.recordingBrowserLoadEntries(
    data: RecordingLibraryDataSource,
): List<RecordingEntry> =
    runCatching { data.loadEntries() }.getOrDefault(emptyList())

// El tipo de retorno se infiere de findGuideProgram (nullable).
internal fun MainActivity.recordingBrowserProgramOf(
    data: RecordingLibraryDataSource,
    entry: RecordingEntry,
) = runCatching { data.findGuideProgram(entry) }.getOrNull()

internal fun MainActivity.recordingBrowserTitleOf(
    data: RecordingLibraryDataSource,
    entry: RecordingEntry,
): String =
    recordingBrowserProgramOf(data, entry)?.title?.takeIf { it.isNotBlank() }
        ?: runCatching { data.recordingTitle(entry) }.getOrDefault("Grabación")

// Extensión (no función suelta): ::durationFor solo compila con receptor MainActivity.
internal fun MainActivity.recordingBrowserDurationOf(entry: RecordingEntry): Long =
    runCatching { entry.parts.sumOf(::durationFor) }.getOrDefault(0L).coerceAtLeast(0L)

internal fun MainActivity.recordingBrowserSavedPositionOf(entry: RecordingEntry): Long =
    runCatching {
        entry.parts.firstOrNull()?.let { HomeScreenChannel.resumePosition(this@recordingBrowserSavedPositionOf, it.name) } ?: 0L
    }.getOrDefault(0L).coerceAtLeast(0L)

internal fun recordingBrowserPercentOf(savedMs: Long, durationMs: Long): Int =
    if (durationMs > 0L) ((savedMs * 100L) / durationMs).toInt().coerceIn(0, 100) else 0

internal fun recordingBrowserIsUsbEntry(entry: RecordingEntry): Boolean =
    entry.parts.firstOrNull()?.file == null

internal fun recordingBrowserFormatDate(format: SimpleDateFormat, millis: Long): String =
    runCatching { format.format(Date(millis)) }.getOrDefault("")

internal fun MainActivity.recordingBrowserSafePlay(entry: RecordingEntry) {
    runCatching { playRecording(entry) }
        .onFailure { showTvToast("No se pudo reproducir la grabación.", long = true) }
}

