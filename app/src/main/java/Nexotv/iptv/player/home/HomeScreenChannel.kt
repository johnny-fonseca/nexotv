// app/src/main/java/Nexotv/iptv/player/home/HomeScreenChannel.kt
@file:Suppress("PackageName", "RestrictedApi", "ScheduleExactAlarm", "ConstVal")
@file:SuppressLint("PackageName", "RestrictedApi", "ScheduleExactAlarm", "ConstVal")

package Nexotv.iptv.player.home

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.guide.*
import Nexotv.iptv.player.guide.TvProgramJson

import Nexotv.iptv.player.guide.GuideProgramTimeIndex

import android.annotation.SuppressLint
import android.app.Activity
import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Publica la fila aprobada del inicio de Android TV que abre la transmisión en vivo. */
internal object HomeScreenChannel {
    @Suppress("ConstVal")
    private val TAG = "${BuildConfig.BRAND_NAME}Home"
    @Suppress("ConstVal")
    private val PREFS = "${BuildConfig.BRAND_KEY}_player"
    private const val CHANNEL_ID = "home_screen_channel_id"
    private const val PROGRAM_ID = "home_screen_program_id"
    private const val HIDDEN_RECORDINGS = "home_hidden_recordings"
    private const val HIDDEN_WATCH_NEXT = "home_hidden_watch_next"
    private const val RESUME_PREFIX = "recording_resume_"
    @Suppress("ConstVal")
    private val LIVE_PROVIDER_ID = "${BuildConfig.BRAND_KEY}-live"
    private const val MAX_RECORDINGS = 20
    private const val MAX_TITLE_LENGTH = 120
    private const val MAX_DESCRIPTION_LENGTH = 500
    private const val MAX_ERROR_DETAIL_LENGTH = 160
    private const val MIN_REFRESH_DELAY_MS = 15_000L

    private val liveUri: Uri = "${BuildConfig.DEEP_LINK_SCHEME}://live".toUri()
    private val liveProgramLock = Any()
    private val recordingsLock = Any()
    private var lastLiveCardKey: LiveCardKey? = null
    private val artworkRepository = HomeGuideArtworkRepository()
    private val channelPublisher = HomeScreenChannelPublisher(artworkRepository)
    private val recordingScanner = HomeScreenRecordingScanner()
    private val refreshScheduler = HomeProgramRefreshScheduler()
    private val watchNextManager = HomeWatchNextManager(recordingScanner) { artworkRepository.guideSnapshot(it) }

    private data class LiveCardKey(
        val channelId: Long,
        val currentProgramKey: String?,
        val currentEndMs: Long?,
        val art: String,
        val description: String,
    )

    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast(activity, "La fila de contenido requiere Android TV 8 o posterior")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val (channelId, _) = channelPublisher.ensureLiveChannel(activity)
            check(channelId > 0L) { "Identificador de canal no válido" }
            // Es el único canal de la app, por lo que se trata como canal predeterminado
            TvContractCompat.requestChannelBrowsable(activity, channelId)
            refreshCurrentProgram(activity)
            syncRecordings(activity)
            toast(
                activity,
                "Se solicitó mostrar ${BuildConfig.BRAND_NAME} en el inicio. Si no aparece, revisa la sección de canales de la pantalla principal.",
            )
        } catch (_: ActivityNotFoundException) {
            toast(activity, "Este dispositivo no ofrece filas de contenido de Android TV")
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo publicar la fila de inicio", error)
            val detail = when (error) {
                is SecurityException -> "permiso del sistema de TV denegado"
                is IllegalArgumentException -> "datos de la fila no válidos"
                else -> describe(error)
            }
            toast(activity, "No se pudo añadir ${BuildConfig.BRAND_NAME}: $detail")
        }
    }

    fun remove(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast(activity, "La fila de contenido requiere Android TV 8 o posterior")
            return
        }
        val message = try {
            synchronized(liveProgramLock) { removeLocked(activity) }
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo eliminar la fila de inicio", error)
            "No se pudo eliminar ${BuildConfig.BRAND_NAME}: ${describe(error)}"
        }
        toast(activity, message)
    }

    private fun removeLocked(context: Context): String {
        val prefs = prefs(context)
        val channelId = prefs.safeLong(CHANNEL_ID)
        val programId = prefs.safeLong(PROGRAM_ID)
        if (channelId <= 0L) {
            cancelRefresh(context)
            return "${BuildConfig.BRAND_NAME} no tiene una fila añadida en el inicio"
        }

        val channelUri = TvContractCompat.buildChannelUri(channelId)
        if (channelPublisher.exists(context, channelUri)) {
            if (programId > 0L) {
                runCatching {
                    context.contentResolver.delete(
                        TvContractCompat.buildPreviewProgramUri(programId), null, null,
                    )
                }.onFailure { Log.w(TAG, "No se pudo eliminar la tarjeta en vivo", it) }
            }
            runCatching { channelPublisher.deleteChannelPrograms(context, channelId) }
                .onFailure { Log.w(TAG, "No se pudieron eliminar las tarjetas del canal", it) }
            context.contentResolver.delete(channelUri, null, null)
        }
        cancelRefresh(context)
        val resumeKeys: List<String> = runCatching {
            prefs.all.keys.filter { it.startsWith(RESUME_PREFIX) }
        }.getOrDefault(emptyList())
        prefs.edit {
            remove(CHANNEL_ID)
            remove(PROGRAM_ID)
            remove(HIDDEN_RECORDINGS)
            remove(HIDDEN_WATCH_NEXT)
            resumeKeys.forEach { remove(it) }
        }
        lastLiveCardKey = null
        runCatching { artworkRepository.invalidateChannelLogo() }
            .onFailure { Log.w(TAG, "No se pudo invalidar el logotipo del canal", it) }
        return "Se eliminó ${BuildConfig.BRAND_NAME} de la pantalla de inicio"
    }

    /** Mantiene la tarjeta en vivo alineada con el programa XMLTV actual. */
    fun refreshCurrentProgram(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        guarded("No se pudo actualizar el programa actual") {
            synchronized(liveProgramLock) { refreshCurrentProgramLocked(appContext(context)) }
        }
    }

    private fun refreshCurrentProgramLocked(context: Context) {
        val prefs = prefs(context)
        val channelId = prefs.safeLong(CHANNEL_ID)
        val programId = prefs.safeLong(PROGRAM_ID)
        if (channelId <= 0L || programId <= 0L) return
        val channelUri = TvContractCompat.buildChannelUri(channelId)
        val programUri = TvContractCompat.buildPreviewProgramUri(programId)
        if (!channelPublisher.exists(context, channelUri) || !channelPublisher.exists(context, programUri)) return
        runCatching { artworkRepository.ensureChannelLogo(context, channelId) }
            .onFailure { Log.w(TAG, "No se pudo actualizar el logotipo del canal", it) }
        val now = System.currentTimeMillis()
        val guide = runCatching { artworkRepository.guideSnapshot(context) }
            .onFailure { Log.w(TAG, "No se pudo leer la guía", it) }
            .getOrNull()
        val current = guide?.let { if (it.programs.isEmpty()) null else it.timeIndex.at(now) }
        val next = current ?: guide?.programs?.firstOrNull { it.startMs > now }
        val targetMs: Long? = current?.endMs ?: next?.startMs
        // Usa el póster del programa actual; si la guía falta, usa el arte incluido
        val art = recordingArtUri(context, current?.iconUrl?.takeIf { it.isNotBlank() }, R.drawable.guide_placeholder)
        val description = if (current != null) {
            val title = sanitizeText(current.title)
            val detail = sanitizeText(current.description)
            buildString {
                append("EN VIVO")
                if (title.isNotEmpty()) append(" · ").append(title)
                if (detail.isNotEmpty()) append(" · ").append(detail)
            }
        } else {
            "Abre la transmisión en directo de ${BuildConfig.BRAND_NAME}"
        }
        val liveCardKey = LiveCardKey(
            channelId = channelId,
            currentProgramKey = current?.let { "${it.channelId}:${it.startMs}" },
            currentEndMs = current?.endMs,
            art = art.toString(),
            description = description,
        )
        if (liveCardKey == lastLiveCardKey) {
            targetMs?.let { scheduleNextRefresh(context, it, now) }
            return
        }
        val values = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(clip("${BuildConfig.BRAND_NAME} en vivo", MAX_TITLE_LENGTH))
            .setDescription(clip(description, MAX_DESCRIPTION_LENGTH))
            .setPosterArtUri(art)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(liveUri)
            .setInternalProviderId(LIVE_PROVIDER_ID)
            .build()
            .toContentValues()
        if (context.contentResolver.update(programUri, values, null, null) == 0) {
            context.contentResolver.delete(programUri, null, null)
            val replacement = requireNotNull(
                context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values),
            ) { "No se pudo recrear la tarjeta de ${BuildConfig.BRAND_NAME} en vivo" }
            val replacementId = ContentUris.parseId(replacement)
            check(replacementId > 0L) { "Identificador de tarjeta no válido" }
            prefs.edit { putLong(PROGRAM_ID, replacementId) }
        }
        lastLiveCardKey = liveCardKey
        targetMs?.let { scheduleNextRefresh(context, it, now) }
    }

    /** Refleja las grabaciones guardadas más recientes en el canal aprobado. */
    fun syncRecordings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        guarded("No se pudo actualizar la fila de grabaciones") {
            synchronized(recordingsLock) { syncRecordingsLocked(appContext(context)) }
        }
    }

    private fun syncRecordingsLocked(context: Context) {
        val prefs = prefs(context)
        val channelId = prefs.safeLong(CHANNEL_ID)
        if (channelId <= 0L) return
        if (!channelPublisher.exists(context, TvContractCompat.buildChannelUri(channelId))) return
        val allRecordings = recordingScanner.load(context, forceRefresh = true, guideProvider = { artworkRepository.guideSnapshot(it) })
        val allKeys = allRecordings.mapTo(mutableSetOf(), HomeRecordingGroup::providerKey)
        val desired = allRecordings.take(MAX_RECORDINGS)
        val storedHidden = prefs.safeStringSet(HIDDEN_RECORDINGS)
        val hidden = storedHidden.intersect(allKeys)
        if (hidden != storedHidden) prefs.edit { putStringSet(HIDDEN_RECORDINGS, HashSet(hidden)) }
        val existing = channelPublisher.existingRecordingCards(context, channelId)
        val published = HashSet<String>()
        desired.forEachIndexed { index, recording ->
            val key = recording.providerKey
            if (key in hidden || !published.add(key)) return@forEachIndexed
            runCatching {
                val values = channelPublisher.preview(context, channelId, recording)
                    .setWeight((MAX_RECORDINGS - index).coerceAtLeast(0))
                    .build()
                    .toContentValues()
                val id = existing.remove(key)
                if (id == null) {
                    requireNotNull(
                        context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values),
                    ) { "No se pudo crear la tarjeta ${recording.title}" }
                } else if (channelPublisher.programChanged(context, id, values)) {
                    context.contentResolver.update(TvContractCompat.buildPreviewProgramUri(id), values, null, null)
                }
            }.onFailure { Log.w(TAG, "No se pudo publicar grabación ${recording.title}", it) }
        }
        existing.values.forEach { id ->
            runCatching { context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(id), null, null) }
                .onFailure { Log.w(TAG, "No se pudo quitar una tarjeta de grabación obsoleta", it) }
        }
        runCatching { watchNextManager.removeStale(context, allKeys) }
            .onFailure { Log.w(TAG, "No se pudieron limpiar las entradas obsoletas de Continuar viendo", it) }
    }

    fun updateWatchNext(context: Context, fileName: String, poster: Uri, positionMs: Long, durationMs: Long) {
        if (fileName.isBlank()) return
        guarded("No se pudo actualizar Continuar viendo") {
            watchNextManager.update(
                appContext(context),
                fileName,
                poster,
                positionMs.coerceAtLeast(0L),
                durationMs.coerceAtLeast(0L),
            )
        }
    }

    fun clearWatchNext(context: Context, fileName: String) {
        if (fileName.isBlank()) return
        guarded("No se pudo quitar la entrada de Continuar viendo") {
            watchNextManager.clearWatchNext(appContext(context), fileName)
        }
    }

    fun resumePosition(context: Context, fileName: String): Long {
        if (fileName.isBlank()) return 0L
        return try {
            watchNextManager.resumePosition(appContext(context), fileName).coerceAtLeast(0L)
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo leer la posición de reanudación", error)
            0L
        }
    }

    fun saveResumePosition(context: Context, fileName: String, positionMs: Long) {
        if (fileName.isBlank()) return
        guarded("No se pudo guardar la posición de reanudación") {
            watchNextManager.saveResumePosition(appContext(context), fileName, positionMs.coerceAtLeast(0L))
        }
    }

    fun onWatchNextHidden(context: Context, programId: Long) {
        if (programId <= 0L) return
        guarded("No se pudo procesar el ocultamiento de Continuar viendo") {
            watchNextManager.onWatchNextHidden(appContext(context), programId)
        }
    }

    fun onProgramHidden(context: Context, programId: Long) {
        if (programId <= 0L) return
        guarded("No se pudo procesar el ocultamiento de la tarjeta") {
            watchNextManager.onProgramHidden(appContext(context), programId)
        }
    }

    fun posterForRecording(context: Context, fileName: String): Uri =
        try {
            watchNextManager.posterForRecording(appContext(context), fileName)
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo obtener el póster de la grabación", error)
            fallbackArtUri(context, R.drawable.recording_placeholder)
        }

    internal fun recordingArtUri(context: Context, icon: String?, fallbackResource: Int = R.drawable.recording_placeholder): Uri =
        try {
            artworkRepository.recordingArtUri(context, icon, fallbackResource)
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo resolver el arte de la tarjeta", error)
            fallbackArtUri(context, fallbackResource)
        }

    private fun scheduleNextRefresh(context: Context, targetMs: Long, nowMs: Long) {
        val triggerAtMs = maxOf(targetMs, nowMs + MIN_REFRESH_DELAY_MS)
        runCatching { refreshScheduler.schedule(context, triggerAtMs) }
            .onFailure { Log.w(TAG, "No se pudo programar la próxima actualización", it) }
    }

    private fun cancelRefresh(context: Context) {
        runCatching { refreshScheduler.cancel(context) }
            .onFailure { Log.w(TAG, "No se pudo cancelar la actualización programada", it) }
    }

    private inline fun guarded(label: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, label, error)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, label, error)
        }
    }

    private fun appContext(context: Context): Context = context.applicationContext ?: context

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun SharedPreferences.safeLong(key: String): Long =
        try {
            getLong(key, 0L)
        } catch (error: ClassCastException) {
            Log.w(TAG, "Preferencia con tipo inválido: $key", error)
            0L
        }

    private fun SharedPreferences.safeStringSet(key: String): Set<String> =
        try {
            getStringSet(key, emptySet()).orEmpty().toSet()
        } catch (error: ClassCastException) {
            Log.w(TAG, "Preferencia con tipo inválido: $key", error)
            emptySet()
        }

    private fun toast(context: Context, message: String) {
        try {
            val toastContext = appContext(context)
            val show = Runnable {
                try {
                    Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Log.w(TAG, "No se pudo mostrar el aviso", error)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) show.run()
            else Handler(Looper.getMainLooper()).post(show)
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo programar el aviso", error)
        }
    }

    private fun describe(error: Throwable): String {
        val message = sanitizeText(error.localizedMessage)
        return clip(message.ifEmpty { error.javaClass.name }, MAX_ERROR_DETAIL_LENGTH)
    }

    private fun sanitizeText(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val builder = StringBuilder(text.length)
        for (char in text) builder.append(if (char.isISOControl()) ' ' else char)
        return builder.toString().trim()
    }

    private fun clip(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        if (max == 1) return "…"
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "…"
    }

    private fun fallbackArtUri(context: Context, resourceId: Int): Uri =
        "android.resource://${context.packageName}/$resourceId".toUri()
}

class HomeProgramUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val appContext = context.applicationContext ?: context
        val pending = try {
            goAsync()
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo iniciar el procesamiento asíncrono", error)
            null
        }
        if (pending == null) {
            refresh(appContext)
            return
        }
        try {
            worker.execute {
                try {
                    refresh(appContext)
                } finally {
                    finish(pending)
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "Ejecutor no disponible; se actualiza en línea", error)
            try {
                refresh(appContext)
            } finally {
                finish(pending)
            }
        }
    }

    private fun refresh(context: Context) {
        try {
            HomeScreenChannel.refreshCurrentProgram(context)
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo actualizar el programa actual", error)
        }
    }

    private fun finish(pending: BroadcastReceiver.PendingResult) {
        try {
            pending.finish()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "El resultado pendiente ya estaba finalizado", error)
        }
    }

    private companion object {
        const val TAG = "HomeProgramUpdate"

        val worker: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "HomeProgramUpdate").apply { isDaemon = true }
            }
        }
    }
}