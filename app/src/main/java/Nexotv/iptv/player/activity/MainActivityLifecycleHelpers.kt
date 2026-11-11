// Archivo: Nexotv/iptv/player/activity/ClockAndAlarmState.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*





import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.MainActivity.Companion.CLOCK_FORMAT_12H
import Nexotv.iptv.player.MainActivity.Companion.CLOCK_FORMAT_24H
import Nexotv.iptv.player.MainActivity.Companion.KEY_CLOCK_FORMAT
import Nexotv.iptv.player.MainActivity.Companion.KEY_SHOW_CLOCK
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val PATRON_RELOJ_12H = "h:mm a"
private const val PATRON_RELOJ_24H = "HH:mm"

internal fun formatClockTime(
    date: Date,
    preference: String?,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val pattern = if (preference == CLOCK_FORMAT_24H) PATRON_RELOJ_24H else PATRON_RELOJ_12H
    return SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(date)
}

internal fun MainActivity.refreshClock() {
    if (isFinishing || isDestroyed) return
    guarded("actualización del reloj") {
        val prefs = mainPrefs
        // El reloj comparte el panel de reproducción que se oculta solo, igual que el panel de información
        val visible = prefs.safeBoolean(KEY_SHOW_CLOCK, default = true) && playbackPanelVisible && !isInPipMode
        val clockPreference = prefs.safeString(KEY_CLOCK_FORMAT, CLOCK_FORMAT_12H)
        val hora = try {
            formatClockTime(Date(), clockPreference)
        } catch (e: Exception) {
            // Si la configuración regional falla, se usa un formato fijo para no dejar el reloj vacío
            Log.w(LOG_TAG, "Falló el formato de hora con la configuración regional", e)
            SimpleDateFormat(PATRON_RELOJ_24H, Locale.ROOT).format(Date())
        }
        playerInfo.setClock(hora, visible)
    }
}

internal fun MainActivity.syncExactAlarmState() {
    if (isFinishing || isDestroyed) return

    val scheduled = try {
        ProgramSchedule.all(this).any { it.reminder || it.recording }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "No se pudieron leer las programaciones", e)
        false
    }

    val puedeProgramarExactas = try {
        PermissionFlows.canScheduleExactAlarms(this)
    } catch (e: Exception) {
        // Ante la duda se asume sin permiso: es el caso que avisa al usuario en lugar de fallar en silencio
        Log.w(LOG_TAG, "No se pudo verificar el permiso de alarmas exactas", e)
        false
    }

    if (puedeProgramarExactas) {
        guarded("restauración de programaciones") { ProgramSchedule.restore(this) }
        exactAlarmWarningShown = false
    } else if (scheduled && !exactAlarmWarningShown) {
        exactAlarmWarningShown = true
        guarded("aviso de alarmas exactas") {
            showStatus("Activa Alarmas y recordatorios en Ajustes de Android para que funcionen tus programaciones")
        }
    }
}
