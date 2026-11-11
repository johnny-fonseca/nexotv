// RecordingLibraryHero.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.guide.GuideProgramMeta

import Nexotv.iptv.player.MainActivity.*
import android.app.Activity
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class RecordingHeroViews(
    val index: TextView,
    val progress: ProgressBar,
    val leftStat: TextView,
    val rightStat: TextView,
    val category: TextView,
    val title: TextView,
    val description: TextView,
    val genre: TextView,
    val rating: TextView,
    val storage: TextView,
    val playLabel: TextView,
    val playOrResume: View,
    val playFromStart: View,
    val delete: View,
)

/** Estado y acciones del panel principal para una grabación guardada seleccionada. */
internal class RecordingLibraryHero(
    private val activity: Activity,
    private val views: RecordingHeroViews,
    private val guideProgramFor: (RecordingEntry) -> TvProgram?,
    private val recordingTitle: (RecordingEntry) -> String,
    private val durationFor: (RecordingPart) -> Long,
    private val formatDuration: (Long) -> String,
    private val formatBytes: (Long) -> String,
    private val tvFreeSpace: () -> Long,
    private val usbFreeSpace: (Uri) -> Long,
    private val treeUri: () -> Uri?,
    private val onPlay: (RecordingEntry) -> Unit,
    private val onPlayFromStart: (RecordingEntry) -> Unit,
    private val onDelete: (RecordingEntry) -> Unit,
) {
    fun update(entry: RecordingEntry?, index: Int, total: Int) {
        if (entry == null) {
            showEmptyState()
            return
        }

        views.playOrResume.visibility = View.VISIBLE
        views.playFromStart.visibility = View.VISIBLE
        views.delete.visibility = View.VISIBLE

        val program = safeCall("guideProgramFor", null) { guideProgramFor(entry) }
        val title = program?.title?.takeIf(String::isNotBlank)
            ?: safeCall("recordingTitle", "") { recordingTitle(entry) }
        val description = program?.let { GuideProgramMeta.description(it, "") }.orEmpty()

        val firstPart = entry.parts.firstOrNull()
        val durationMs = totalDuration(entry)
        val savedMs = resolveSavedPosition(firstPart, durationMs)
        val percent = if (durationMs > 0L) ((savedMs * 100L) / durationMs).toInt().coerceIn(0, 100) else 0
        val remainingMinutes = ((durationMs - savedMs).coerceAtLeast(0L) / 60_000L).toInt()
        val usb = firstPart?.file == null
        val storageType = if (usb) "USB" else "TV"

        views.category.text = buildBreadcrumb(entry)
        views.title.text = title
        views.description.text = description
        views.description.visibility = if (description.isNotBlank()) View.VISIBLE else View.GONE
        views.genre.text = program?.category.orEmpty()
        views.genre.visibility = if (program?.category?.isNotBlank() == true) View.VISIBLE else View.GONE
        views.rating.text = program?.rating.orEmpty()
        views.rating.visibility = if (program?.rating?.isNotBlank() == true) View.VISIBLE else View.GONE

        val freeBytes = resolveFreeBytes(usb)
        views.storage.text = "$storageType · ${safeFormatBytes(freeBytes)} libres"
        views.index.text = "Lista ${index + 1} de $total"
        views.progress.progress = percent
        views.leftStat.text = if (percent > 0) {
            "$percent% visto · Quedan $remainingMinutes min"
        } else {
            "Duración ${safeCall("formatDuration", "--") { formatDuration(durationMs) }}"
        }
        views.rightStat.text = "${safeFormatBytes(entry.bytes)} · $storageType"
        views.playLabel.text = if (percent > 0) "Seguir viendo" else "Reproducir"

        views.playOrResume.setOnClickListener { safeAction("onPlay") { onPlay(entry) } }
        views.playFromStart.setOnClickListener { safeAction("onPlayFromStart") { onPlayFromStart(entry) } }
        views.delete.setOnClickListener { safeAction("onDelete") { onDelete(entry) } }
    }

    private fun showEmptyState() {
        views.index.text = "Sin grabaciones"
        views.progress.progress = 0
        views.leftStat.text = "0% visto"
        views.rightStat.text = "0 GB"
        views.category.text = "MIS GRABACIONES · Vacío"
        views.title.text = "No hay grabaciones guardadas"
        views.description.text = "Comienza a grabar ${BuildConfig.CHANNEL_NAME} para encontrar tus programas guardados aquí."
        views.genre.text = "Biblioteca"
        views.genre.visibility = View.VISIBLE
        views.rating.text = ""
        views.rating.visibility = View.GONE
        views.description.visibility = View.VISIBLE
        views.storage.text = "0% usado"
        views.playOrResume.visibility = View.GONE
        views.playFromStart.visibility = View.GONE
        views.delete.visibility = View.GONE
        views.playOrResume.setOnClickListener(null)
        views.playFromStart.setOnClickListener(null)
        views.delete.setOnClickListener(null)
    }

    private fun totalDuration(entry: RecordingEntry): Long =
        entry.parts.sumOf { part ->
            safeCall("durationFor", 0L) { durationFor(part) }.coerceAtLeast(0L)
        }

    private fun resolveSavedPosition(part: RecordingPart?, durationMs: Long): Long {
        if (part == null) return 0L
        val saved = safeCall("resumePosition", 0L) { HomeScreenChannel.resumePosition(activity, part.name) }
            .coerceAtLeast(0L)
        return if (durationMs > 0L) saved.coerceAtMost(durationMs) else saved
    }

    private fun resolveFreeBytes(usb: Boolean): Long {
        val usbTree = safeCall("treeUri", null) { treeUri() }
        val free = if (usb && usbTree != null) {
            safeCall("usbFreeSpace", 0L) { usbFreeSpace(usbTree) }
        } else {
            safeCall("tvFreeSpace", 0L) { tvFreeSpace() }
        }
        return free.coerceAtLeast(0L)
    }

    private fun buildBreadcrumb(entry: RecordingEntry): SpannableString {
        val stamp = safeCall("formatDate", "") {
            SimpleDateFormat(DATE_PATTERN, Locale.forLanguageTag("es-CR")).format(Date(entry.modified))
        }
        val text = if (stamp.isBlank()) "$BREADCRUMB_PREFIX · ${BuildConfig.CHANNEL_NAME}" else "$BREADCRUMB_PREFIX · $stamp · ${BuildConfig.CHANNEL_NAME}"
        val breadcrumb = SpannableString(text)
        val split = BREADCRUMB_PREFIX.length.coerceAtMost(breadcrumb.length)
        breadcrumb.setSpan(
            ForegroundColorSpan(activity.getColor(R.color.tv_live)),
            0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (split < breadcrumb.length) {
            breadcrumb.setSpan(
                ForegroundColorSpan(BREADCRUMB_MUTED_COLOR),
                split, breadcrumb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return breadcrumb
    }

    private fun safeFormatBytes(bytes: Long): String =
        safeCall("formatBytes", "--") { formatBytes(bytes.coerceAtLeast(0L)) }

    private inline fun <T> safeCall(name: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Fallo en $name", e)
            fallback
        }

    private inline fun safeAction(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Fallo en la acción $name", e)
        }
    }

    private companion object {
        const val TAG = "RecordingLibraryHero"
        const val BREADCRUMB_PREFIX = "MIS GRABACIONES"
        const val DATE_PATTERN = "EEE d MMM · h:mm a"
        val BREADCRUMB_MUTED_COLOR = 0xFF8899AA.toInt()
    }
}
