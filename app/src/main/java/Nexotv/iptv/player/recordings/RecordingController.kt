// app/src/main/java/Nexotv/iptv/player/recordings/RecordingController.kt

package Nexotv.iptv.player.recordings




import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.content.Context.*
import Nexotv.iptv.player.MainActivity.*
import android.os.SystemClock
import android.graphics.Color
import android.view.Gravity
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.ProgressBar
import android.media.MediaMetadataRetriever
import android.graphics.Typeface
import android.content.res.ColorStateList
import java.util.Locale
import java.io.File
import androidx.media3.common.MediaItem
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

private const val LOW_SPACE_WARNING_BYTES = 2L * 1024 * 1024 * 1024
private val RECORDING_PART_SUFFIX = Regex("-part\\d+(?=\\.[^.]+$)")
private val RECORDING_PART_INDEX = Regex("-part(\\d+)(?=\\.[^.]+$)")

private fun MainActivity.recordingPrefs() = recordingStorageController.recordingPrefs()

private fun MainActivity.recordingTreeUri(): Uri? =
    recordingStorageController.recordingTreeUri()

private fun String.recordingBaseName(): String = replace(RECORDING_PART_SUFFIX, "")

private fun MainActivity.recordingDialogMessage(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextColor(getColor(R.color.tv_text_secondary))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setPadding(dp(8), dp(10), dp(8), dp(10))
}

private fun MainActivity.recordingSectionLabel(label: String): TextView = TextView(this).apply {
    text = label
    setTextColor(getColor(R.color.tv_text_secondary))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTypeface(typeface, Typeface.BOLD)
    letterSpacing = 0.1f
}

private fun MainActivity.surfaceRecordingNotice(notice: String?) {
    if (notice.isNullOrBlank()) {
        surfacedRecordingNotice = null
    } else if (notice != surfacedRecordingNotice) {
        surfacedRecordingNotice = notice
        showStatus(notice)
        hideStatusAfterDelay()
    }
}

internal fun MainActivity.showRecordingNoticeIfNeeded() {
    val notice = recordingPrefs().getString(MainActivity.KEY_RECORDING_ERROR, null)
    val recording = isRecording()
    updateRecordingIndicator()
    when {
        recording -> surfaceRecordingNotice(notice)
        notice?.contains("espacio", ignoreCase = true) == true || notice?.contains("512 MB") == true -> {
            surfacedRecordingNotice = notice
            renderPlayerStatus(PlayerStatus.NoSpace { showRecordingLibrary() })
        }
        else -> surfaceRecordingNotice(notice)
    }
}

internal fun MainActivity.toggleRecording() {
    if (!isRecording()) {
        confirmRecordingStorage {
            invalidateRecordingSummary()
            recordingPrefs().edit().remove(MainActivity.KEY_RECORDING_ERROR).apply()
            requestNotificationsForRecording()
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, HlsRecordingService::class.java))
            }.onSuccess {
                showStatus("Iniciando grabación HLS…")
            }.onFailure {
                showTvToast("No se pudo iniciar la grabación: ${it.message ?: "servicio no disponible"}", long = true)
            }
            hideStatusAfterDelay()
        }
        return
    }
    val intent = Intent(this, HlsRecordingService::class.java)
    intent.action = HlsRecordingService.ACTION_STOP
    invalidateRecordingSummary()
    runCatching { startService(intent) }
        .onSuccess { showStatus("Deteniendo grabación…") }
        .onFailure { showTvToast("No se pudo detener la grabación: ${it.message ?: "servicio no disponible"}", long = true) }
    hideStatusAfterDelay()
}

internal fun MainActivity.recordingDirectory() = recordingStorageController.recordingDirectory()

internal fun MainActivity.invalidateRecordingSummary() {
    recordingSummaryGeneration++
    recordingSummaryDirty = true
}

private fun MainActivity.computeRecordingSummary(): Pair<Int, Long> =
    recordingStorageController.computeRecordingSummary()

internal fun MainActivity.recordingLibrarySummary(): Pair<Int, Long> {
    return recordingStorageController.librarySummary()
}

internal fun MainActivity.showRecordingDestination() = recordingDestinationController.showRecordingDestination()

internal fun MainActivity.openUsbFolderPicker() = recordingDestinationController.openUsbFolderPicker()

/** Libera un permiso SAF obsoleto solo después de que el destino activo haya cambiado. */
internal fun MainActivity.releaseRecordingTreePermission(previousTree: String?, keep: Uri? = null) {
    recordingStorageController.releaseRecordingTreePermission(previousTree, keep)
}

internal fun MainActivity.confirmRecordingStorage(onContinue: () -> Unit) = recordingDestinationController.confirmRecordingStorage(onContinue)

@Suppress("WrongConstant")
internal fun MainActivity.acceptUsbFolder(data: Intent?) = recordingDestinationController.acceptUsbFolder(data)

internal fun MainActivity.verifyUsbAppend(root: DocumentFile) {
    recordingStorageController.verifyUsbAppend(root)
}

internal fun formatBytes(bytes: Long): String =
    RecordingStorageController.formatBytes(bytes)

internal fun MainActivity.recordingDuration(part: RecordingPart): Long =
    recordingPlaybackController.recordingDuration(part)

internal fun MainActivity.durationFor(part: RecordingPart): Long =
    recordingPlaybackController.durationFor(part)

internal fun MainActivity.saveRecordingProgress() =
    recordingPlaybackController.saveRecordingProgress()

internal fun MainActivity.finishRecordingPlayback() =
    recordingPlaybackController.finishRecordingPlayback()

internal fun MainActivity.seekRecordingToSavedPosition(entry: RecordingEntry) =
    recordingPlaybackController.seekRecordingToSavedPosition(entry)

private fun MainActivity.isActiveRecordingEntry(entry: RecordingEntry): Boolean {
    val activeId = recordingPrefs().getString(MainActivity.KEY_RECORDING_FILE, null)
    return entry.parts.any { it.id == activeId }
}

internal fun MainActivity.showRecordingActions(entry: RecordingEntry) {
    val active = isRecording() && isActiveRecordingEntry(entry)
    val actions = if (active) arrayOf("Grabación en curso", "Cerrar") else arrayOf("Reproducir", "Eliminar", "Cerrar")
    val title = recordingTitle(entry)
    val detail = "${entry.parts.size} ${if (entry.parts.size == 1) "parte" else "partes"} · ${formatBytes(entry.bytes)}"
    // AlertDialog no muestra la lista de opciones si también hay mensaje:
    // los metadatos van en el título para mantener visibles las acciones del D-pad.
    AlertDialog.Builder(this).setTitle("$title\n$detail")
        .setItems(actions) { _, which ->
            if (active || which == actions.lastIndex) return@setItems
            if (which == 0) playRecording(entry) else AlertDialog.Builder(this)
                .setTitle("Eliminar grabación").setMessage("Se eliminará «${recordingTitle(entry)}».")
                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                    val wasPlayingEntry = isActiveRecordingEntry(entry)
                    if (recordingPlayback && wasPlayingEntry) {
                        recordingPlayback = false
                        startStream()
                    }
                    if (!deleteRecordingFiles(entry)) {
                        showTvToast("No se pudieron eliminar todas las partes. Comprueba el acceso a la carpeta USB.", long = true)
                    }
                    invalidateRecordingSummary()
                    HomeScreenChannel.syncRecordings(this)
                    if (wasPlayingEntry) {
                        recordingPrefs().edit().remove(MainActivity.KEY_RECORDING_FILE).apply()
                    }
                    showRecordingLibrary()
                }.showForTv()
        }.showForTv()
}

/** Elimina todas las partes e informa de los fallos en lugar de dar por bueno un borrado SAF rechazado. */
internal fun MainActivity.deleteRecordingFiles(entry: RecordingEntry): Boolean {
    return recordingStorageController.deleteRecordingFiles(entry)
}

private fun MainActivity.startRecordingPlaybackTicker() =
    recordingPlaybackController.startRecordingPlaybackTicker()

internal fun MainActivity.playRecording(entry: RecordingEntry) =
    recordingPlaybackController.playRecording(entry)

internal fun partIndex(name: String): Int =
    RECORDING_PART_INDEX.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

internal fun MainActivity.toggleRecordingPlayback() =
    recordingPlaybackController.toggleRecordingPlayback()

internal fun MainActivity.findRecording(partId: String): RecordingEntry? {
    return recordingStorageController.findRecording(partId)
}

internal fun MainActivity.handleHomeRecordingIntent(intent: Intent): Boolean {
    val data = intent.data ?: return false
    if (data.scheme != BuildConfig.DEEP_LINK_SCHEME || data.host != "recording") return false
    val selected = data.getQueryParameter("id")
    val entry = selected?.let { findRecording(it) }
    if (entry == null) showTvToast("Esta grabación ya no está disponible. Comprueba que la USB esté conectada.", long = true)
    else playRecording(entry)
    return true
}

@Deprecated("Required for the Android TV channel approval flow")
internal fun MainActivity.clearActiveRecording() = recordingPlaybackController.clearActiveRecording()
internal fun MainActivity.clearActiveRecordingPoster() = recordingPlaybackController.clearActiveRecordingPoster()
internal fun MainActivity.isRecording() = recordingPrefs().getBoolean(MainActivity.KEY_RECORDING, false)


