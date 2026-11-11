// RecordingBrowserModels.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.SharedPreferences
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Locale
import Nexotv.iptv.player.MainActivity.*

internal const val RECORDING_BROWSER_SORT_RECENT = 0
internal const val RECORDING_BROWSER_SORT_ALPHA = 1
internal const val RECORDING_BROWSER_SORT_SIZE = 2
internal const val RECORDING_BROWSER_SORT_MODE_COUNT = 3

/**
 * Estado mutable compartido por todos los bloques del navegador.
 * Reemplaza las variables locales capturadas de la función original.
 */
internal class RecordingBrowserState {
    var searchQuery: String = ""
    var sortMode: Int = RECORDING_BROWSER_SORT_RECENT
    var focusedIndex: Int = 0
    var pendingDeleteEntry: RecordingEntry? = null
    var activeEntry: RecordingEntry? = null
    var recordings: List<RecordingEntry> = emptyList()
}

/** Formatos de fecha/hora (mismos patrones y locales que el original). */
internal class RecordingBrowserFormats(use24HourClock: Boolean) {
    val locale: Locale = Locale.forLanguageTag("es-CR")

    private val timePattern = if (use24HourClock) "HH:mm" else "h:mm a"

    val dateFormat = SimpleDateFormat("$timePattern  EEE d MMM", locale)
    val detailDateFormat = SimpleDateFormat("EEE d MMM · $timePattern", locale)
    val rowDateFormat = SimpleDateFormat("d MMM · $timePattern", Locale.getDefault())
}

/** Todas las vistas construidas, agrupadas por bloque. */
internal class RecordingBrowserViews(
    val topBar: RecordingBrowserTopBar,
    val catalog: RecordingBrowserCatalogPanel,
    val detail: RecordingBrowserDetailPanel,
    val deleteBanner: RecordingBrowserDeleteBanner,
    val root: LinearLayout,
)

/**
 * Contexto de enlace para los bind* y el refresco.
 * [dismissDialog] y [refresh] se resuelven al invocarse, por lo que pueden
 * apuntar a los lateinit locales del entry point.
 */
internal class RecordingBrowserContext(
    val prefs: SharedPreferences,
    val state: RecordingBrowserState,
    val data: RecordingLibraryDataSource,
    val formats: RecordingBrowserFormats,
    val views: RecordingBrowserViews,
    val dismissDialog: () -> Unit,
    val refresh: () -> Unit,
)
