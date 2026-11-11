package Nexotv.iptv.player.home
import Nexotv.iptv.player.*
import Nexotv.iptv.player.recordings.*
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Enumerates local and SAF recordings and converts them into launcher-ready catalog entries. */
internal class HomeScreenRecordingScanner {
    private data class Part(val uri: Uri, val name: String, val bytes: Long, val modified: Long)

    private val tag = "${BuildConfig.BRAND_NAME}HomeScanner".take(MAX_TAG_LENGTH)
    private val filePrefix = "${BuildConfig.BRAND_NAME}-"
    private val recordingExtensions = setOf("ts", "mp4")
    private val partSuffix = Regex("-part\\d+(?=\\.[^.]+$)")
    private val partNumber = Regex("-part(\\d+)(?=\\.[^.]+$)")
    private val recordingStamp = Regex("^" + Regex.escape(BuildConfig.BRAND_NAME) + "-(\\d+)")
    private val lock = Any()
    private var cachedTreeUri: String? = null
    private var cachedAtMs = 0L
    private var cached: List<HomeRecordingGroup>? = null

    fun load(
        context: Context,
        forceRefresh: Boolean = false,
        guideProvider: (Context) -> HomeGuideSnapshot,
    ): List<HomeRecordingGroup> {
        val treeUri = safely("No se pudo leer la preferencia de carpeta externa") {
            context.getSharedPreferences("${BuildConfig.BRAND_KEY}_player", Context.MODE_PRIVATE)
                .getString("recording_tree_uri", null)
        }?.takeIf(String::isNotBlank)
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val snapshot = cached
            if (snapshot != null && !forceRefresh && cachedTreeUri == treeUri && (now - cachedAtMs) in 0L..CACHE_TTL_MS) {
                return snapshot
            }

            val parts = mutableListOf<Part>()
            val localOk = collectLocal(context, parts)
            val externalOk = collectExternal(context, treeUri, parts)

            if ((!localOk || !externalOk) && snapshot != null && cachedTreeUri == treeUri) return snapshot

            val guide: HomeGuideSnapshot? = safely("No se pudo obtener la guía de programación") { guideProvider(context) }
            val dateFormat: SimpleDateFormat? = safely("No se pudo crear el formateador de fechas") {
                SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            }

            val groups = deduplicate(parts)
                .groupBy { it.name.replace(partSuffix, "") }
                .values
                .mapNotNull { group -> safely("No se pudo construir la grabación") { buildGroup(group, guide, dateFormat) } }
                .sortedByDescending(HomeRecordingGroup::modified)

            if (localOk && externalOk) {
                cachedTreeUri = treeUri
                cachedAtMs = now
                cached = groups
            }
            return groups
        }
    }

    private fun collectLocal(context: Context, out: MutableList<Part>): Boolean =
        safely("No se pudo leer la carpeta local de grabaciones") {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            directory?.listFiles()?.forEach { file ->
                safely("Archivo local ilegible") {
                    if (file.isFile && isRecordingName(file.name, file.extension)) {
                        out += Part(Uri.fromFile(file), file.name, file.length().coerceAtLeast(0L), file.lastModified().coerceAtLeast(0L))
                    }
                }
            }
            true
        } ?: false

    private fun collectExternal(context: Context, treeUri: String?, out: MutableList<Part>): Boolean {
        if (treeUri.isNullOrBlank()) return true
        return safely("No se pudo leer la carpeta externa de grabaciones") {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            val children = tree?.takeIf { it.exists() && it.isDirectory }?.listFiles()
            children?.forEach { doc ->
                safely("Documento externo ilegible") {
                    val name = doc.name.orEmpty()
                    if (doc.isFile && isRecordingName(name, name.substringAfterLast('.', ""))) {
                        out += Part(doc.uri, name, doc.length().coerceAtLeast(0L), doc.lastModified().coerceAtLeast(0L))
                    }
                }
            }
            true
        } ?: false
    }

    private fun deduplicate(parts: List<Part>): List<Part> =
        parts.groupBy(Part::name).values.mapNotNull { sameName ->
            sameName.maxWithOrNull(compareBy<Part>({ it.bytes }, { it.modified }))
        }

    private fun buildGroup(
        group: List<Part>,
        guide: HomeGuideSnapshot?,
        dateFormat: SimpleDateFormat?,
    ): HomeRecordingGroup? {
        val ordered = group.sortedWith(
            compareBy<Part>(
                { partNumber.find(it.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1 },
                { it.name },
            ),
        )
        val first = ordered.firstOrNull() ?: return null
        if (ordered.any { it.bytes <= 0L }) return null
        val stamp = recordingStamp.find(first.name)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        val program = if (stamp != null && guide != null) {
            safely("No se pudo resolver el programa de la grabación") {
                if (guide.programs.isNotEmpty()) guide.timeIndex.at(stamp) else null
            }
        } else {
            null
        }
        val fallbackTitle = stamp?.let { value ->
            dateFormat?.let { fmt -> safely("Fecha de grabación inválida") { synchronized(fmt) { fmt.format(Date(value)) } } }
        }?.let { "Grabación · $it" } ?: "Grabación de ${BuildConfig.BRAND_NAME}"
        val title = clean(program?.title, MAX_TITLE, keepNewlines = false).ifBlank { fallbackTitle }
        val uri = safely("Enlace de grabación inválido") {
            Uri.parse("${BuildConfig.DEEP_LINK_SCHEME}://recording").buildUpon()
                .appendQueryParameter("id", first.uri.toString()).build()
        } ?: return null
        return HomeRecordingGroup(
            providerKey = providerKey(first.name),
            title = title,
            uri = uri,
            parts = ordered.size,
            bytes = ordered.fold(0L) { acc, part -> if (Long.MAX_VALUE - acc < part.bytes) Long.MAX_VALUE else acc + part.bytes },
            modified = ordered.maxOf(Part::modified),
            description = clean(program?.description, MAX_DESCRIPTION, keepNewlines = true),
            icon = clean(program?.iconUrl, MAX_ICON, keepNewlines = false),
        )
    }

    private fun clean(value: String?, max: Int, keepNewlines: Boolean): String {
        if (value.isNullOrEmpty()) return ""
        val source = if (value.length > max * 4) value.substring(0, max * 4) else value
        val sb = StringBuilder(source.length)
        for (ch in source) {
            when {
                ch == '\n' && keepNewlines -> sb.append(ch)
                ch.isISOControl() || ch == '\u2028' || ch == '\u2029' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        var out = sb.toString().trim()
        if (out.length > max) {
            out = out.substring(0, max)
            if (out.isNotEmpty() && out.last().isHighSurrogate()) out = out.dropLast(1)
        }
        return out.trim()
    }

    private inline fun <T> safely(message: String, block: () -> T): T? =
        try {
            block()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(tag, message, e)
            null
        } catch (e: Exception) {
            Log.w(tag, message, e)
            null
        }

    private fun isRecordingName(name: String, extension: String): Boolean =
        name.startsWith(filePrefix) && extension.lowercase(Locale.ROOT) in recordingExtensions

    private fun providerKey(fileName: String): String = "recording:" + fileName.replace(partSuffix, "")

    private companion object {
        const val CACHE_TTL_MS = 3_000L
        const val MAX_TAG_LENGTH = 23
        const val MAX_TITLE = 256
        const val MAX_DESCRIPTION = 4_096
        const val MAX_ICON = 2_048
    }
}