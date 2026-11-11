package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.common.formatByteSize
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import Nexotv.iptv.player.MainActivity.*

/**
 * Acceso al almacenamiento y catálogo de grabaciones.
 *
 * La referencia a MainActivity es temporal y no estática: permite conservar las
 * extensiones internas existentes mientras se migra el estado de caché a un
 * host más pequeño en una fase posterior.
 */
internal class RecordingStorageController(
    private val activity: MainActivity,
) {
    private val recordingPartSuffix = Regex("-part\\d+(?=\\.[^.]+$)")

    fun recordingPrefs() =
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    fun recordingTreeUri(): Uri? =
        recordingPrefs()
            .getString(MainActivity.KEY_RECORDING_TREE, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun recordingDirectory(): File =
        activity.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: activity.filesDir

    fun computeRecordingSummary(): Pair<Int, Long> {
        val files = mutableListOf<Pair<String, Long>>()
        runCatching {
            recordingDirectory().listFiles()?.filter { file ->
                file.isFile && file.name.startsWith("${BuildConfig.BRAND_NAME}-") &&
                    file.extension.lowercase(Locale.ROOT) in MainActivity.RECORDING_EXTENSIONS
            }?.forEach { file -> files += file.name to file.length() }
        }
        runCatching {
            recordingTreeUri()
                ?.let { DocumentFile.fromTreeUri(activity, it) }
                ?.listFiles()
                ?.filter { file ->
                    val name = file.name.orEmpty()
                    file.isFile && name.startsWith("${BuildConfig.BRAND_NAME}-") &&
                        name.substringAfterLast('.').lowercase(Locale.ROOT) in MainActivity.RECORDING_EXTENSIONS
                }
                ?.forEach { file -> files += file.name.orEmpty() to file.length() }
        }
        val grouped = files.groupBy { (name, _) -> name.replace(recordingPartSuffix, "") }
        return grouped.size to grouped.values.sumOf { parts -> parts.sumOf { it.second } }
    }

    fun librarySummary(): Pair<Int, Long> {
        val now = SystemClock.elapsedRealtime()
        val activeRecordingNow = activity.isRecording()
        val requestedGeneration = synchronized(this) {
            if (activeRecordingNow != activity.recordingSummaryWasRecording) {
                activity.recordingSummaryDirty = true
            }
            val expired = now - activity.recordingSummaryUpdatedAt >= MainActivity.RECORDING_SUMMARY_REFRESH_MS
            if ((activity.recordingSummaryDirty || expired) && !activity.recordingSummaryLoading) {
                activity.recordingSummaryLoading = true
                activity.recordingSummaryGeneration
            } else null
        }
        if (requestedGeneration != null) {
            runCatching {
                activity.recordingIoExecutor.execute {
                    val summary = runCatching { computeRecordingSummary() }.getOrDefault(0 to 0L)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) {
                            synchronized(this@RecordingStorageController) {
                                activity.recordingSummaryLoading = false
                            }
                            return@runOnUiThread
                        }
                        val recordingNow = activity.isRecording()
                        synchronized(this@RecordingStorageController) {
                            activity.recordingSummaryCache = summary
                            activity.recordingSummaryUpdatedAt = SystemClock.elapsedRealtime()
                            activity.recordingSummaryWasRecording = recordingNow
                            activity.recordingSummaryLoading = false
                            activity.recordingSummaryDirty =
                                activity.recordingSummaryGeneration != requestedGeneration
                        }
                        activity.refreshVisibleGuide?.invoke()
                    }
                }
            }.onFailure {
                synchronized(this) { activity.recordingSummaryLoading = false }
            }
        }
        return activity.recordingSummaryCache
    }

    fun releaseRecordingTreePermission(previousTree: String?, keep: Uri? = null) {
        val previousUri = previousTree
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return
        if (previousUri == keep) return
        val grant = activity.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == previousUri }
            ?: return
        val flags =
            (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) {
            runCatching {
                activity.contentResolver.releasePersistableUriPermission(previousUri, flags)
            }
        }
    }

    fun verifyUsbAppend(root: DocumentFile) {
        val probe = root.createFile("application/octet-stream", ".${BuildConfig.BRAND_KEY}-write-check")
            ?: error("No se pudo crear un archivo de prueba en la carpeta")
        try {
            activity.contentResolver.openOutputStream(probe.uri, "wt")
                ?.use { it.write(byteArrayOf(0x31)) }
                ?: error("No se pudo abrir el archivo de prueba")
            activity.contentResolver.openOutputStream(probe.uri, "wa")
                ?.use { it.write(byteArrayOf(0x32)) }
                ?: error("La USB no admite escritura por segmentos")
            val bytes = activity.contentResolver.openInputStream(probe.uri)?.use { it.readBytes() }
            check(bytes?.contentEquals(byteArrayOf(0x31, 0x32)) == true) {
                "La USB no conserva escrituras anexadas correctamente"
            }
        } finally {
            runCatching { probe.delete() }
        }
    }

    fun deleteRecordingFiles(entry: RecordingEntry): Boolean {
        var deletedAll = true
        entry.parts.forEach { part ->
            val deleted = runCatching {
                part.file?.let { file ->
                    !file.exists() || file.delete()
                } ?: run {
                    val document = DocumentFile.fromSingleUri(activity, part.uri)
                        ?: return@run false
                    !document.exists() || document.delete()
                }
            }.getOrDefault(false)
            if (!deleted) deletedAll = false
        }
        return deletedAll
    }

    fun findRecording(partId: String): RecordingEntry? {
        val all = mutableListOf<RecordingPart>()
        runCatching {
            recordingDirectory().listFiles()
                ?.filter {
                    it.isFile && it.name.startsWith("${BuildConfig.BRAND_NAME}-") &&
                        it.extension.lowercase(Locale.ROOT) in MainActivity.RECORDING_EXTENSIONS
                }
                ?.forEach {
                    all += RecordingPart(Uri.fromFile(it), it, it.name, it.length(), it.lastModified())
                }
        }
        runCatching {
            recordingTreeUri()
                ?.let { DocumentFile.fromTreeUri(activity, it) }
                ?.listFiles()
                ?.filter { doc ->
                    doc.isFile && doc.name.orEmpty().startsWith("${BuildConfig.BRAND_NAME}-")
                }
                ?.forEach { doc ->
                    all += RecordingPart(
                        doc.uri,
                        null,
                        doc.name.orEmpty(),
                        doc.length(),
                        doc.lastModified(),
                    )
                }
        }
        return all.groupBy { it.name.replace(recordingPartSuffix, "") }
            .values
            .map { RecordingEntry(it.sortedBy { part -> partIndex(part.name) }) }
            .firstOrNull { entry -> entry.parts.any { it.id == partId || it.uri.toString() == partId } }
    }

    companion object {
        fun formatBytes(bytes: Long): String = formatByteSize(bytes)
    }
}
