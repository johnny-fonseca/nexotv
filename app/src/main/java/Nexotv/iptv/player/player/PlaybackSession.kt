package Nexotv.iptv.player.player



import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.diagnostics.SessionReport
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/**
 * Coordina la instancia de ExoPlayer y su ciclo de vida de Activity.
 *
 * TEMP: mantiene una referencia no estática a MainActivity durante la extracción
 * incremental. Las operaciones de reproducción ya existentes siguen siendo
 * extensiones de MainActivity para no duplicar Media3 ni alterar sus callbacks.
 */
@UnstableApi
internal class PlaybackSession(private val activity: MainActivity) {
    private var stoppedMediaItem: MediaItem? = null
    private var stoppedMediaItems: List<MediaItem> = emptyList()
    private var stoppedMediaIndex = 0
    private var stoppedMediaPositionMs = 0L
    private var stoppedWasRecording = false

    internal var isReleasedForStop = false
        private set

    fun createAndAttach(): Boolean {
        activity.player = try {
            activity.createPlayer()
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo crear el reproductor", error)
            Toast.makeText(activity, "No se pudo iniciar el reproductor de video", Toast.LENGTH_LONG).show()
            activity.finish()
            return false
        }
        activity.playerView.player = activity.player
        return true
    }

    fun releaseForStop() {
        // La sesión termina aquí: el reproductor se libera y la próxima reproducción
        // empieza una sesión nueva con sus propios KPIs.
        runCatching { SessionReport.finish(activity, "onStop") }
        activity.handler.removeCallbacksAndMessages(null)
        activity.diagnosticHudTicker = null
        activity.recordingStatusTickerScheduled = false
        activity.recoveryController.prepareForPlayerStop()
        activity.connectingStatusTask = null
        activity.networkResumePending = false
        activity.handler.removeCallbacks(activity.networkResumeTask)
        if (!activity.isPlayerInitialized || isReleasedForStop) return
        stoppedMediaItem = activity.player.currentMediaItem
        stoppedMediaItems = List(activity.player.mediaItemCount) { activity.player.getMediaItemAt(it) }
        stoppedMediaIndex = activity.player.currentMediaItemIndex
        stoppedMediaPositionMs = activity.player.currentPosition.coerceAtLeast(0L)
        stoppedWasRecording = activity.recordingPlayback
        activity.playerView.player = null
        activity.hotspotStabilizer.detach()
        activity.wifiPlaybackLock.release()
        activity.guardedForPlayback("liberación del reproductor") { activity.player.release() }
        isReleasedForStop = true
    }

    fun restoreAfterStop() {
        val restored = try {
            activity.createPlayer()
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo recrear el reproductor", error)
            activity.showTvToast("No se pudo reiniciar el reproductor", long = true)
            return
        }
        activity.player = restored
        activity.playerView.player = activity.player
        isReleasedForStop = false
        val stoppedItem = stoppedMediaItem
        if (stoppedWasRecording && stoppedItem != null) {
            activity.recordingPlayback = true
            if (stoppedMediaItems.size > 1 && stoppedMediaIndex in stoppedMediaItems.indices) {
                activity.player.setMediaItems(stoppedMediaItems, stoppedMediaIndex, stoppedMediaPositionMs)
            } else {
                activity.player.setMediaItem(stoppedItem, stoppedMediaPositionMs)
            }
            activity.player.prepare()
            activity.player.playWhenReady = activity.resumeAfterLifecycle
            activity.handler.removeCallbacks(activity.recordingProgressTicker)
            activity.handler.postDelayed(activity.recordingProgressTicker, MainActivity.RECORDING_PROGRESS_INTERVAL_MS)
        } else {
            activity.startStream(autoPlay = activity.resumeAfterLifecycle)
        }
        stoppedMediaItem = null
        stoppedMediaItems = emptyList()
        stoppedWasRecording = false
    }

    fun discardStoppedPlayback() {
        stoppedMediaItem = null
        stoppedMediaItems = emptyList()
        stoppedWasRecording = false
    }

    fun destroy() {
        activity.cleanupPlayerOnDestroy()
    }

    private companion object {
        const val TAG = "PlaybackSession"
    }
}

private fun MainActivity.guardedForPlayback(task: String, block: () -> Unit) {
    try {
        block()
    } catch (error: Exception) {
        Log.e("MainActivity", "Fallo en $task", error)
    }
}

