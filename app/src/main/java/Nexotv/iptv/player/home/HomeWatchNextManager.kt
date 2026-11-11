package Nexotv.iptv.player.home
import Nexotv.iptv.player.*
import Nexotv.iptv.player.recordings.*
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/** Owns resume state and the Android TV Watch Next row for saved recordings. */
internal class HomeWatchNextManager(
    private val scanner: HomeScreenRecordingScanner,
    private val guideProvider: (Context) -> HomeGuideSnapshot,
) {
    private val tag = "${BuildConfig.BRAND_NAME}HomeWatchNext".take(MAX_TAG_LENGTH)
    private val prefsName = "${BuildConfig.BRAND_KEY}_player"
    private val hiddenRecordingsKey = "home_hidden_recordings"
    private val hiddenWatchNextKey = "home_hidden_watch_next"
    private val resumePrefix = "recording_resume_"
    private val recordingPrefix = "recording:"
    private val minWatchNextMs = 60_000L
    private val maxTitleLength = 120
    private val partSuffix = Regex("-part\\d+(?=\\.[^.]+$)")
    private val prefsLock = Any()

    fun update(context: Context, fileName: String, poster: Uri, positionMs: Long, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (fileName.isBlank()) return
        safely("No se pudo actualizar Seguir viendo") {
            performUpdate(context, fileName, poster, positionMs, durationMs)
        }
    }

    private fun performUpdate(context: Context, fileName: String, poster: Uri, positionMs: Long, durationMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        val key = providerKey(fileName)
        val prefs = prefs(context)
        val hidden = readSet(prefs, hiddenWatchNextKey)
        val existing = findWatchNextIds(context, key)
        // La guía de Continuar viendo exige más de un minuto de reproducción real
        val finished = duration > 0 && position >= duration - minWatchNextMs
        if (duration <= 0 || position < minWatchNextMs || duration - position < minWatchNextMs || key in hidden) {
            existing.forEach { deleteWatchNext(context, it) }
            if (finished) synchronized(prefsLock) { prefs.edit { remove(resumeKey(fileName)) } }
            return
        }
        val group = scanner.load(context, guideProvider = guideProvider).firstOrNull { it.providerKey == key } ?: return
        val title = clip(group.title.filterNot { it.isISOControl() }.trim(), maxTitleLength)
            .ifBlank { BuildConfig.BRAND_NAME }
        val values = WatchNextProgram.Builder()
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(title)
            .setDescription("Seguir viendo · ${partsLabel(group.parts)}")
            .setPosterArtUri(poster)
            .setInternalProviderId(key)
            .setIntentUri(group.uri)
            .setLastPlaybackPositionMillis(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .build()
            .toContentValues()
        safely("No se pudo sincronizar la fila Seguir viendo") {
            val primary = existing.firstOrNull()
            existing.drop(1).forEach { deleteWatchNext(context, it) }
            val updated = primary?.let {
                context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(it), values, null, null)
            } ?: 0
            if (primary == null || updated <= 0) {
                if (context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) == null) {
                    Log.w(tag, "El proveedor rechazó la inserción de Seguir viendo")
                }
            }
        }
        synchronized(prefsLock) { prefs.edit { putLong(resumeKey(fileName), position) } }
    }

    fun clearWatchNext(context: Context, fileName: String) {
        if (fileName.isBlank()) return
        safely("No se pudo limpiar Seguir viendo") {
            findWatchNextIds(context, providerKey(fileName)).forEach { deleteWatchNext(context, it) }
        }
        safely("No se pudo limpiar la posición guardada") {
            synchronized(prefsLock) { prefs(context).edit { remove(resumeKey(fileName)) } }
        }
    }

    fun resumePosition(context: Context, fileName: String): Long {
        if (fileName.isBlank()) return 0L
        return safely("No se pudo leer la posición guardada") {
            prefs(context).getLong(resumeKey(fileName), 0L)
        }?.coerceAtLeast(0L) ?: 0L
    }

    fun saveResumePosition(context: Context, fileName: String, positionMs: Long) {
        if (fileName.isBlank()) return
        safely("No se pudo guardar la posición") {
            synchronized(prefsLock) { prefs(context).edit { putLong(resumeKey(fileName), positionMs.coerceAtLeast(0L)) } }
        }
    }

    fun onWatchNextHidden(context: Context, programId: Long) {
        if (programId <= 0L) return
        hideRecordingCard(context, TvContractCompat.buildWatchNextProgramUri(programId), hiddenWatchNextKey, "Seguir viendo")
    }

    fun onProgramHidden(context: Context, programId: Long) {
        if (programId <= 0L) return
        hideRecordingCard(context, TvContractCompat.buildPreviewProgramUri(programId), hiddenRecordingsKey, "la tarjeta oculta")
    }

    fun posterForRecording(context: Context, fileName: String): Uri {
        val icon = if (fileName.isBlank()) {
            null
        } else {
            safely("No se pudo resolver el icono de la grabación") {
                val key = providerKey(fileName)
                scanner.load(context, guideProvider = guideProvider).firstOrNull { it.providerKey == key }?.icon
            }
        }
        return safely("No se pudo construir el póster de la grabación") {
            HomeScreenChannel.recordingArtUri(context, icon)
        } ?: Uri.EMPTY
    }

    private fun findWatchNextIds(context: Context, key: String): List<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val ids = mutableListOf<Long>()
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf("_id"),
            "internal_provider_id=?", arrayOf(key), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    private fun deleteWatchNext(context: Context, id: Long) {
        safely("No se pudo eliminar Seguir viendo") {
            context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
        }
    }

    private fun hideRecordingCard(context: Context, uri: Uri, hiddenSetKey: String, label: String) {
        safely("No se pudo quitar $label") {
            var key = ""
            context.contentResolver.query(uri, arrayOf("internal_provider_id"), null, null, null)?.use {
                if (it.moveToFirst()) key = it.getString(0).orEmpty()
            }
            if (!key.startsWith(recordingPrefix)) return@safely
            context.contentResolver.delete(uri, null, null)
            synchronized(prefsLock) {
                val prefs = prefs(context)
                val updated = readSet(prefs, hiddenSetKey) + key
                prefs.edit { putStringSet(hiddenSetKey, updated) }
            }
        }
    }

    fun removeStale(context: Context, keys: Set<String>) {
        safely("No se pudo depurar el estado de reanudación") {
            synchronized(prefsLock) {
                val prefs = prefs(context)
                val hidden = readSet(prefs, hiddenWatchNextKey)
                val staleResume = prefs.all.keys
                    .filter { it.startsWith(resumePrefix) && recordingPrefix + it.removePrefix(resumePrefix) !in keys }
                val hiddenStale = hidden.any { it !in keys }
                if (hiddenStale || staleResume.isNotEmpty()) {
                    prefs.edit {
                        if (hiddenStale) putStringSet(hiddenWatchNextKey, hidden.intersect(keys))
                        staleResume.forEach { remove(it) }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        safely("No se pudieron depurar las filas de Seguir viendo") {
            val stale = mutableListOf<Long>()
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf("_id", "internal_provider_id"), null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(1).orEmpty()
                    if (key.startsWith(recordingPrefix) && key !in keys) stale += cursor.getLong(0)
                }
            }
            stale.forEach { deleteWatchNext(context, it) }
        }
    }

    private fun readSet(prefs: SharedPreferences, key: String): Set<String> =
        safely("No se pudo leer el conjunto $key") {
            prefs.getStringSet(key, emptySet()).orEmpty().toSet()
        } ?: emptySet()

    private fun prefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun providerKey(fileName: String) = recordingPrefix + fileName.replace(partSuffix, "")

    private fun resumeKey(fileName: String) = resumePrefix + fileName.replace(partSuffix, "")

    private fun partsLabel(parts: Int) = "$parts ${if (parts == 1) "parte" else "partes"}"

    private fun clip(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        var cut = text.substring(0, max - 1)
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut + "…"
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

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}