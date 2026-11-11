package Nexotv.iptv.player.recordings




import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import java.io.File
import Nexotv.iptv.player.MainActivity.*

/**
 * Coordina la reproducción local de grabaciones y su posición reanudable.
 *
 * Conserva el orden Media3 existente y usa MainActivity sólo como host temporal
 * para mantener los contratos internos usados por el resto del reproductor.
 */
internal class RecordingPlaybackController(
    private val activity: MainActivity,
) {
    internal val progressTicker = object : Runnable {
        override fun run() {
            runCatching { saveRecordingProgress() }
            if (activity.recordingPlayback) {
                activity.handler.postDelayed(this, MainActivity.RECORDING_PROGRESS_INTERVAL_MS)
            }
        }
    }

    fun recordingDuration(part: RecordingPart): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (part.file != null) {
                retriever.setDataSource(part.file.absolutePath)
            } else {
                retriever.setDataSource(activity, part.uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)

    fun durationFor(part: RecordingPart): Long =
        activity.recordingDurationCache[durationCacheKey(part)] ?: 0L

    private fun durationCacheKey(part: RecordingPart): String =
        "${part.id}|${part.bytes}|${part.modified}"

    fun saveRecordingProgress() {
        val entry = activity.activeRecording ?: return
        if (!activity.recordingPlayback || !activity.isPlayerInitialized) return
        val first = entry.parts.firstOrNull() ?: return
        val durations = entry.parts.map { durationFor(it) }
        val before = durations
            .take(activity.player.currentMediaItemIndex.coerceIn(0, durations.size))
            .sum()
        val position = before + activity.player.currentPosition.coerceAtLeast(0L)
        val duration = durations.sum()
        HomeScreenChannel.saveResumePosition(activity, first.name, position)
        if (duration > 0L) {
            HomeScreenChannel.updateWatchNext(
                activity,
                first.name,
                activity.activeRecordingPoster ?: HomeScreenChannel.posterForRecording(activity, first.name),
                position,
                duration,
            )
        }
    }

    fun finishRecordingPlayback() {
        activity.activeRecording?.parts?.firstOrNull()?.let {
            HomeScreenChannel.clearWatchNext(activity, it.name)
        }
        activity.handler.removeCallbacks(progressTicker)
        activity.activeRecording = null
        activity.activeRecordingPoster = null
        activity.recordingPlayback = false
        activity.showStatus("Grabación finalizada")
    }

    fun seekRecordingToSavedPosition(entry: RecordingEntry) {
        val first = entry.parts.firstOrNull() ?: return
        val saved = HomeScreenChannel.resumePosition(activity, first.name)
        val durations = entry.parts.map { durationFor(it) }
        val items = entry.parts.map { MediaItem.fromUri(it.uri) }
        if (saved <= 0L || durations.any { it <= 0L }) {
            activity.player.setMediaItems(items)
            return
        }
        var remaining = saved
        var index = 0
        while (index < durations.lastIndex && remaining >= durations[index]) {
            remaining -= durations[index]
            index++
        }
        activity.player.setMediaItems(items, index, remaining.coerceAtLeast(0L))
    }

    fun startRecordingPlaybackTicker() {
        activity.handler.removeCallbacks(progressTicker)
        activity.handler.postDelayed(progressTicker, MainActivity.RECORDING_PROGRESS_INTERVAL_MS)
    }

    private fun prepareRecordingPlayback(entry: RecordingEntry, status: String) {
        runCatching {
            activity.recordingIoExecutor.execute {
                val durations = entry.parts.map(::recordingDuration)
                val poster = entry.parts.firstOrNull()?.name
                    ?.let { HomeScreenChannel.posterForRecording(activity, it) }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed || !activity.recordingPlayback ||
                        activity.activeRecording?.id != entry.id
                    ) return@runOnUiThread
                    entry.parts.zip(durations).forEach { (part, duration) ->
                        activity.recordingDurationCache[durationCacheKey(part)] = duration
                    }
                    activity.activeRecordingPoster = poster
                    seekRecordingToSavedPosition(entry)
                    activity.player.prepare()
                    activity.player.playWhenReady = true
                    startRecordingPlaybackTicker()
                    activity.showStatus(status)
                }
            }
        }.onFailure { error ->
            if (activity.activeRecording?.id == entry.id) {
                activity.recordingPlayback = false
                activity.activeRecording = null
                activity.showTvToast("No se pudo preparar la grabación", long = true)
            }
            android.util.Log.w("${BuildConfig.BRAND_NAME}RecordingPlayback", "No se pudieron leer las duraciones", error)
        }
    }

    fun playRecording(entry: RecordingEntry) {
        activity.clearScreenBackStack()
        val firstPart = entry.parts.firstOrNull() ?: return
        if (entry.bytes <= 0L || entry.parts.any { it.file?.let { file -> !file.isFile } == true }) return
        if (activity.recordingPlayback && activity.activeRecording?.id != entry.id) {
            saveRecordingProgress()
        }
        activity.recordingStorageController.recordingPrefs()
            .edit()
            .putString(MainActivity.KEY_RECORDING_FILE, entry.id)
            .apply()
        activity.recordingPlayback = true
        activity.activeRecording = entry
        activity.activeRecordingPoster = null
        prepareRecordingPlayback(entry, "Reproduciendo grabación: ${entry.name}")
    }

    fun toggleRecordingPlayback() {
        if (activity.recordingPlayback) {
            saveRecordingProgress()
            activity.recordingPlayback = false
            activity.activeRecording = null
            activity.handler.removeCallbacks(progressTicker)
            activity.startStream()
            activity.showStatus("Volviendo a ${BuildConfig.BRAND_NAME} en vivo…")
            return
        }
        val prefs = activity.recordingStorageController.recordingPrefs()
        val path = prefs.getString(MainActivity.KEY_RECORDING_FILE, null)
        if (path == null) {
            activity.showTvToast("Todavía no hay una grabación disponible", long = true)
            return
        }
        val isContentUri = path.startsWith("content://")
        val file = if (isContentUri) null else File(path)
        if (file != null && (!file.isFile || file.length() == 0L)) {
            activity.showTvToast("Todavía no hay una grabación disponible", long = true)
            return
        }
        val uri = if (isContentUri) android.net.Uri.parse(path) else android.net.Uri.fromFile(File(path))
        val entry = activity.findRecording(path)
        activity.recordingPlayback = true
        activity.activeRecording = entry
        if (entry != null) {
            activity.activeRecordingPoster = null
            prepareRecordingPlayback(
                entry,
                "Reproduciendo grabación: ${prefs.getString(MainActivity.KEY_RECORDING_NAME, entry.name)}",
            )
            return
        }
        activity.player.setMediaItem(MediaItem.fromUri(uri))
        activity.player.prepare()
        activity.player.playWhenReady = true
        startRecordingPlaybackTicker()
        activity.showStatus(
            "Reproduciendo grabación: ${prefs.getString(MainActivity.KEY_RECORDING_NAME, file?.name ?: "USB")}",
        )
    }

    fun clearActiveRecording() {
        activity.activeRecording = null
    }

    fun clearActiveRecordingPoster() {
        activity.activeRecordingPoster = null
    }
}


