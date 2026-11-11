package Nexotv.iptv.player.recordings


import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.status.*
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Posee el estado visual y el ciclo del ticker del indicador de grabación.
 *
 * La composición, animaciones y cálculo de duración siguen en
 * PlayerConnectionStatus.kt; este controlador sólo mantiene sus referencias y
 * coordina la actualización periódica mediante el host temporal.
 */
internal class RecordingStatusController(private val activity: MainActivity) {
    internal var indicator: LinearLayout? = null
    internal var indicatorText: TextView? = null
    internal var indicatorIcon: ImageView? = null
    internal var indicatorCollapsed = false
    internal var indicatorBlinking = false
    internal var debugRecordingStartedAt: Long? = null
    internal var tickerScheduled = false
    internal var surfacedNotice: String? = null

    internal val ticker = Runnable {
        tickerScheduled = false
        if (indicator != null && (activity.isRecording() || debugRecordingStartedAt != null)) {
            try {
                activity.updateRecordingIndicator()
                activity.showRecordingNoticeIfNeeded()
            } catch (error: Exception) {
                Log.e(TAG, "Fallo al actualizar el indicador de grabación", error)
            }
            activity.scheduleRecordingStatusTicker()
        }
    }

    private companion object {
        const val TAG = "RecordingStatusController"
    }
}

