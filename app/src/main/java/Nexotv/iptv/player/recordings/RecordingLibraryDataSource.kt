// RecordingLibraryDataSource.kt

package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.guide.GuideProgramTimeIndex

import Nexotv.iptv.player.MainActivity.*
import android.app.Activity
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Carga los datos de grabaciones y programación usados por la pantalla de biblioteca. */
@UnstableApi
internal class RecordingLibraryDataSource(
    private val activity: Activity,
    private val directory: File,
    private val treeUri: String?,
    private val guidePrograms: List<TvProgram>,
    private val scheduledPrograms: () -> List<ScheduledProgram>,
) {
    private val guideProgramIndex = GuideProgramTimeIndex(guidePrograms)

    fun loadEntries(): List<RecordingEntry> {
        val parts = mutableListOf<RecordingPart>()
        parts += loadLocalParts()
        parts += loadTreeParts()
        return parts
            .groupBy { part ->
                // Un mismo nombre en almacenamiento local y USB representa grabaciones distintas.
                val source = if (part.file != null) "local" else "tree:${treeUri.orEmpty()}"
                "$source:${part.name.replace(PART_SUFFIX_REGEX, "")}" 
            }
            .values
            .mapNotNull { group ->
                if (group.isEmpty()) null
                else RecordingEntry(group.sortedBy { part -> partIndex(part.name) })
            }
            .sortedByDescending(RecordingEntry::modified)
    }

    fun loadScheduled(): List<ScheduledProgram> {
        val now = System.currentTimeMillis()
        val scheduled = try {
            scheduledPrograms()
        } catch (_: Exception) {
            emptyList()
        }
        return scheduled
            .filter { it.program.endMs > now }
            .sortedBy { it.program.startMs }
    }

    fun findGuideProgram(entry: RecordingEntry): TvProgram? {
        val stamp = extractStamp(entry.name) ?: return null
        return programAt(stamp)
    }

    fun recordingTitle(entry: RecordingEntry): String {
        val stamp = extractStamp(entry.name) ?: return DEFAULT_TITLE
        programAt(stamp)?.let { return it.title }
        return try {
            "Grabación · ${SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(stamp))}"
        } catch (_: Exception) {
            DEFAULT_TITLE
        }
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000L
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    fun formatDurationHuman(ms: Long): String {
        if (ms <= 0L) return "0 min"
        val minutes = (ms / 60_000L) % 60
        val hours = ms / 3_600_000L
        return if (hours > 0) "$hours h $minutes min" else "$minutes min"
    }

    fun partIndex(name: String): Int =
        PART_INDEX_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

    private fun loadLocalParts(): List<RecordingPart> = try {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && isRecordingFile(it.name, it.extension) }
            .map { file -> RecordingPart(Uri.fromFile(file), file, file.name, file.length(), file.lastModified()) }
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadTreeParts(): List<RecordingPart> {
        val rawUri = treeUri?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val tree = DocumentFile.fromTreeUri(activity, Uri.parse(rawUri)) ?: return emptyList()
            tree.listFiles()
                .filter { doc -> doc.isFile && isRecordingFile(doc.name, doc.name.orEmpty().substringAfterLast('.', "")) }
                .map { doc ->
                    RecordingPart(doc.uri, null, doc.name ?: DEFAULT_TITLE, doc.length(), doc.lastModified())
                }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isRecordingFile(name: String?, extension: String): Boolean =
        name.orEmpty().startsWith(FILE_PREFIX) && extension.lowercase(Locale.ROOT) in VALID_EXTENSIONS

    private fun extractStamp(name: String): Long? =
        STAMP_REGEX.find(name)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun programAt(stamp: Long): TvProgram? =
        guideProgramIndex.at(stamp)

    private companion object {
        val FILE_PREFIX = "${BuildConfig.BRAND_NAME}-"
        val DEFAULT_TITLE = "Grabación de ${BuildConfig.BRAND_NAME}"
        val VALID_EXTENSIONS = setOf("ts", "mp4")
        val PART_SUFFIX_REGEX = Regex("-part\\d+(?=\\.[^.]+$)")
        val PART_INDEX_REGEX = Regex("-part(\\d+)(?=\\.[^.]+$)")
        val STAMP_REGEX = Regex("${BuildConfig.BRAND_NAME}-(\\d+)")
    }
}
