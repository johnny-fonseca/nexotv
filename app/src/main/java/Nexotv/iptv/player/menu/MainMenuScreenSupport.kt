// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenSupport.kt
// Utilidades generales del menú principal: registro de fallos, guardas, prefs y formato de textos/fechas.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.home.*
import android.content.Context
import android.text.SpannableString
import android.content.res.Resources
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Tiempos y formatos
private const val MENU_TIME_PATTERN = "h:mma"
private const val MENU_FAILURE_LOG_MAX_SIZE = 64

internal class MenuScreenFormats(private val context: Context) {
    private val secondsPerMinute = recordingFormatValues(context.resources).first
    private val minutesPerHour = recordingFormatValues(context.resources).second
    private val secondsPerHour = recordingFormatValues(context.resources).third
    private val timeFormat = SimpleDateFormat(MENU_TIME_PATTERN, Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("$MENU_TIME_PATTERN  EEE d MMM", Locale.forLanguageTag("es-CR"))

    fun time(ms: Long): String = try {
        synchronized(timeFormat) { timeFormat.format(Date(ms)) }.lowercase(Locale.ROOT)
    } catch (e: Exception) {
        menuScreenReport("formatoHora", e)
        "--:--"
    }

    fun dateTime(ms: Long): String = try {
        synchronized(dateTimeFormat) { dateTimeFormat.format(Date(ms)) }.menuScreenCapitalized()
    } catch (e: Exception) {
        menuScreenReport("formatoFechaHora", e)
        ""
    }

    fun recordingElapsed(startedAt: Long, now: Long): String? {
        if (startedAt <= 0L) return null
        return formatRecordingElapsed((now - startedAt).coerceAtLeast(0L))
    }

    fun formatRecordingElapsed(elapsedMs: Long): String? {
        val seconds = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
        val hours = seconds / secondsPerHour
        val minutes = seconds / secondsPerMinute % minutesPerHour
        val remainingSeconds = seconds % secondsPerMinute
        return runCatching { context.getString(R.string.tv_menu_recording_elapsed, hours, minutes, remainingSeconds) }
            .onFailure { menuScreenReport("formatoTiempoGrabacion", it) }
            .getOrNull()
    }

    companion object {
        @Volatile private var cachedRecordingFormat: Triple<Int, Int, Int>? = null

        private fun recordingFormatValues(resources: Resources): Triple<Int, Int, Int> {
            cachedRecordingFormat?.let { return it }
            return synchronized(this) {
                cachedRecordingFormat ?: Triple(
                    runCatching { resources.getInteger(R.integer.tv_menu_seconds_per_minute) }.getOrDefault(60).coerceAtLeast(1),
                    runCatching { resources.getInteger(R.integer.tv_menu_minutes_per_hour) }.getOrDefault(60).coerceAtLeast(1),
                    runCatching { resources.getInteger(R.integer.tv_menu_seconds_per_hour) }.getOrDefault(3600).coerceAtLeast(1),
                ).also { cachedRecordingFormat = it }
            }
        }
    }
}

internal fun TextView.setTextIfChanged(value: CharSequence?) {
    if (!TextUtils.equals(text, value)) text = value
}

internal fun View.setVisibilityIfChanged(v: Int) {
    if (visibility != v) visibility = v
}

private val menuScreenLoggedFailures = LinkedHashSet<String>()

internal fun menuScreenReport(section: String, error: Throwable) {
    synchronized(menuScreenLoggedFailures) {
        if (menuScreenLoggedFailures.size >= MENU_FAILURE_LOG_MAX_SIZE) {
            // Iterator.remove() exige un next() previo; se descarta el registro más antiguo
            val oldest = menuScreenLoggedFailures.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        if (menuScreenLoggedFailures.add(section)) {
            Log.w(MENU_SCREEN_TAG, "Fallo en '$section'", error)
        }
    }
}

internal inline fun menuScreenGuard(section: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        menuScreenReport(section, e)
    }
}

internal inline fun <T> menuScreenGuardOr(section: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        menuScreenReport(section, e)
        fallback
    }

internal fun String.menuScreenCapitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

internal fun menuScreenDotted(text: String, dotColor: Int, restColor: Int): SpannableString {
    if (text.isEmpty()) return SpannableString("")
    return SpannableString(text).apply {
        setSpan(ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (length > 1) {
            setSpan(ForegroundColorSpan(restColor), 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

internal fun Context.menuScreenPrefs() =
    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
