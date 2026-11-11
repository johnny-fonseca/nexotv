// ============================================================================
// Archivo: RecordingLibraryModels.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Locale
import Nexotv.iptv.player.MainActivity.*

/**
 * Estado mutable de la pantalla. Reemplaza las variables locales que antes
 * capturaban los closures dentro de showRecordingLibrary().
 */
internal class RecordingLibraryState {
    var showScheduledView: Boolean = false
    var pendingDeleteEntry: RecordingEntry? = null
    var latestRecording: RecordingEntry? = null
    var recordings: List<RecordingEntry> = emptyList()
    var scheduled: List<ScheduledProgram> = emptyList()
}

internal class RecordingLibraryDateFormats(
    val dateFormat: SimpleDateFormat,
    val shortDateFormat: SimpleDateFormat,
    val heroDateFormat: SimpleDateFormat,
)

internal fun createRecordingLibraryDateFormats(prefs: SharedPreferences): RecordingLibraryDateFormats {
    val use24HourClock = safeCall("leer formato de reloj", false) {
        prefs.getString(MainActivity.KEY_CLOCK_FORMAT, DEFAULT_CLOCK_FORMAT) == CLOCK_FORMAT_24H
    }
    val timePattern = if (use24HourClock) "HH:mm" else "h:mm a"
    val localeCr = Locale.forLanguageTag("es-CR")
    return RecordingLibraryDateFormats(
        dateFormat = SimpleDateFormat("$timePattern  EEE d MMM", localeCr),
        shortDateFormat = SimpleDateFormat("EEE d MMM · $timePattern", Locale.getDefault()),
        heroDateFormat = SimpleDateFormat("EEE d MMM · $timePattern", localeCr),
    )
}
