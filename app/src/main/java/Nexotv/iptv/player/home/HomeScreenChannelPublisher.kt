package Nexotv.iptv.player.home

import Nexotv.iptv.player.*
import Nexotv.iptv.player.recordings.*
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns mutations and queries against the Android TV channel/program provider. */
internal class HomeScreenChannelPublisher(
    private val artworkRepository: HomeGuideArtworkRepository,
) {
    private val tag = "${BuildConfig.BRAND_NAME}HomePublisher".take(MAX_TAG_LENGTH)
    private val brandName: String = BuildConfig.BRAND_NAME.ifBlank { DEFAULT_BRAND }
    private val liveProviderId = "${BuildConfig.BRAND_KEY}-live"
    private val liveUri: Uri = Uri.parse("${BuildConfig.DEEP_LINK_SCHEME}://live")
    private val prefsName = "${BuildConfig.BRAND_KEY}_player"
    private val channelInitLock = Any()

    private data class ProgramRow(val id: Long, val providerKey: String)

    fun ensureLiveChannel(context: Context): Pair<Long, Long> = synchronized(channelInitLock) {
        val appContext = appContext(context)
        val prefs = prefs(appContext)
        var channelId = prefs.safeLong(CHANNEL_ID_KEY)
        if (channelId <= 0L || !exists(appContext, TvContractCompat.buildChannelUri(channelId))) {
            prefs.edit(commit = true) {
                remove(CHANNEL_ID_KEY)
                remove(PROGRAM_ID_KEY)
            }
            channelId = createChannel(appContext, prefs)
        }
        var programId = prefs.safeLong(PROGRAM_ID_KEY)
        if (programId <= 0L || !exists(appContext, TvContractCompat.buildPreviewProgramUri(programId))) {
            prefs.edit(commit = true) { remove(PROGRAM_ID_KEY) }
            programId = adoptLiveProgram(appContext, channelId, prefs)
                ?: createLiveProgram(appContext, channelId, prefs)
        }
        channelId to programId
    }

    fun exists(context: Context, uri: Uri): Boolean {
        val cursor = context.contentResolver.query(uri, ID_PROJECTION, null, null, null)
            ?: throw IllegalStateException("El proveedor de TV no respondió al consultar $uri")
        return cursor.use { it.moveToFirst() }
    }

    private fun createChannel(context: Context, prefs: SharedPreferences): Long {
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(clip(brandName, MAX_TITLE_LENGTH))
            .setAppLinkIntentUri(liveUri)
            .build()
        val uri = checkNotNull(
            context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()),
        ) { "No se pudo crear la fila" }
        val id = parseInsertedId(uri, "fila")
        if (!persistLong(prefs, CHANNEL_ID_KEY, id)) {
            runCatching { context.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null) }
                .onFailure { Log.w(tag, "No se pudo revertir la fila $id", it) }
            throw IllegalStateException("No se pudo guardar el identificador de la fila")
        }
        return id
    }

    private fun createLiveProgram(context: Context, channelId: Long, prefs: SharedPreferences): Long {
        val program = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(clip("Ver $brandName en vivo", MAX_TITLE_LENGTH))
            .setDescription(clip("Abre la transmisión en directo de $brandName", MAX_DESCRIPTION_LENGTH))
            .setPosterArtUri(liveArtUri(context))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(liveUri)
            .setInternalProviderId(liveProviderId)
            .build()
        val uri = checkNotNull(
            context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues()),
        ) { "No se pudo crear la tarjeta" }
        val id = parseInsertedId(uri, "tarjeta")
        if (!persistLong(prefs, PROGRAM_ID_KEY, id)) {
            deleteProgram(context, id)
            throw IllegalStateException("No se pudo guardar el identificador de la tarjeta")
        }
        return id
    }

    private fun adoptLiveProgram(context: Context, channelId: Long, prefs: SharedPreferences): Long? {
        val rows = try {
            queryChannelPrograms(context, channelId)
        } catch (error: Exception) {
            Log.w(tag, "No se pudo buscar una tarjeta en vivo existente", error)
            null
        } ?: return null
        val matches = rows.filter { it.providerKey == liveProviderId }
        val keeper = matches.firstOrNull() ?: return null
        if (!persistLong(prefs, PROGRAM_ID_KEY, keeper.id)) return null
        matches.drop(1).forEach { deleteProgram(context, it.id) }
        return keeper.id
    }

    fun deleteChannelPrograms(context: Context, channelId: Long) {
        if (channelId <= 0L) return
        val rows = queryChannelPrograms(context, channelId) ?: return
        rows.forEach { deleteProgram(context, it.id) }
    }

    fun existingRecordingCards(context: Context, channelId: Long): MutableMap<String, Long> {
        require(channelId > 0L) { "Identificador de canal no válido" }
        val rows = checkNotNull(queryChannelPrograms(context, channelId)) {
            "El proveedor de TV no respondió al listar las tarjetas"
        }
        val existing = LinkedHashMap<String, Long>()
        val duplicates = ArrayList<Long>()
        for (row in rows) {
            if (!row.providerKey.startsWith(RECORDING_PREFIX)) continue
            if (existing.containsKey(row.providerKey)) duplicates.add(row.id) else existing[row.providerKey] = row.id
        }
        duplicates.forEach { deleteProgram(context, it) }
        return existing
    }

    fun programChanged(context: Context, id: Long, values: ContentValues): Boolean {
        if (id <= 0L) return true
        return try {
            val columns = CHANGE_COLUMNS
            val cursor = context.contentResolver.query(
                TvContractCompat.buildPreviewProgramUri(id), columns, null, null, null,
            ) ?: return true
            cursor.use {
                if (it.columnCount < columns.size || !it.moveToFirst()) return true
                columns.indices.any { index ->
                    val current = if (it.isNull(index)) null else it.getString(index)
                    current != values.getAsString(columns[index])
                }
            }
        } catch (error: Exception) {
            Log.w(tag, "No se pudo comparar la tarjeta $id", error)
            true
        }
    }

    fun preview(context: Context, channelId: Long, item: HomeRecordingGroup): PreviewProgram.Builder {
        require(channelId > 0L) { "Identificador de canal no válido" }
        require(item.providerKey.isNotBlank()) { "Clave de proveedor de grabación vacía" }
        val title = sanitizeText(item.title).ifEmpty { DEFAULT_RECORDING_TITLE }
        val details = ArrayList<String>(5)
        details.add("Grabación guardada")
        formatDate(item.modified)?.let { details.add(it) }
        sanitizeText(item.description).takeIf { it.isNotEmpty() }?.let { details.add(it) }
        details.add(partsLabel(item.parts.coerceAtLeast(0)))
        details.add("${(item.bytes / BYTES_PER_MB).coerceAtLeast(0L)} MB")
        val description = details.joinToString(" · ")
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(clip("GRABACIÓN · $title", MAX_TITLE_LENGTH))
            .setDescription(clip(description, MAX_DESCRIPTION_LENGTH))
            .setPosterArtUri(recordingArtUri(context, item.icon))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(item.uri)
            .setInternalProviderId(item.providerKey)
    }

    private fun queryChannelPrograms(context: Context, channelId: Long): List<ProgramRow>? {
        require(channelId > 0L) { "Identificador de canal no válido" }
        val cursor = context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            PROGRAM_ROW_PROJECTION, null, null, null,
        ) ?: return null
        val rows = ArrayList<ProgramRow>()
        cursor.use {
            val idIndex = it.getColumnIndex(COLUMN_ID)
            val keyIndex = it.getColumnIndex(COLUMN_INTERNAL_PROVIDER_ID)
            if (idIndex < 0) return rows
            while (it.moveToNext()) {
                if (it.isNull(idIndex)) continue
                val id = it.getLong(idIndex)
                if (id <= 0L) continue
                val key = if (keyIndex >= 0 && !it.isNull(keyIndex)) it.getString(keyIndex).orEmpty() else ""
                rows.add(ProgramRow(id, key))
            }
        }
        return rows
    }

    private fun deleteProgram(context: Context, id: Long) {
        if (id <= 0L) return
        try {
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(id), null, null)
        } catch (error: Exception) {
            Log.w(tag, "No se pudo eliminar la tarjeta $id de la fila", error)
        }
    }

    private fun parseInsertedId(uri: Uri, label: String): Long {
        val id = try {
            ContentUris.parseId(uri)
        } catch (error: Exception) {
            Log.w(tag, "Identificador de $label ilegible en $uri", error)
            -1L
        }
        check(id > 0L) { "Identificador de $label no válido" }
        return id
    }

    private fun persistLong(prefs: SharedPreferences, key: String, value: Long): Boolean =
        try {
            prefs.edit().putLong(key, value).commit()
        } catch (error: Exception) {
            Log.w(tag, "No se pudo guardar la preferencia $key", error)
            false
        }

    private fun liveArtUri(context: Context): Uri =
        try {
            artworkRepository.liveArtUri(context)
        } catch (error: Exception) {
            Log.w(tag, "No se pudo resolver el arte de la tarjeta en vivo", error)
            fallbackArtUri(context, R.drawable.guide_placeholder)
        }

    private fun recordingArtUri(context: Context, icon: String?): Uri =
        try {
            artworkRepository.recordingArtUri(context, icon?.takeIf { it.isNotBlank() })
        } catch (error: Exception) {
            Log.w(tag, "No se pudo resolver el arte de la grabación", error)
            fallbackArtUri(context, R.drawable.recording_placeholder)
        }

    private fun fallbackArtUri(context: Context, resourceId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resourceId")

    private fun formatDate(modifiedMs: Long): String? {
        if (modifiedMs <= 0L) return null
        return try {
            SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(modifiedMs))
        } catch (error: Exception) {
            Log.w(tag, "No se pudo formatear la fecha de la grabación", error)
            null
        }
    }

    private fun appContext(context: Context): Context = context.applicationContext ?: context

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun SharedPreferences.safeLong(key: String): Long =
        try {
            getLong(key, 0L)
        } catch (error: ClassCastException) {
            Log.w(tag, "Preferencia con tipo inválido: $key", error)
            0L
        }

    private fun partsLabel(parts: Int) = "$parts ${if (parts == 1) "parte" else "partes"}"

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

    private companion object {
        const val CHANNEL_ID_KEY = "home_screen_channel_id"
        const val PROGRAM_ID_KEY = "home_screen_program_id"
        const val RECORDING_PREFIX = "recording:"
        const val COLUMN_ID = "_id"
        const val COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val DEFAULT_BRAND = "IPTV"
        const val DEFAULT_RECORDING_TITLE = "Sin título"
        const val MAX_TAG_LENGTH = 23
        const val MAX_TITLE_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 500
        const val BYTES_PER_MB = 1024L * 1024L
        val ID_PROJECTION = arrayOf(COLUMN_ID)
        val PROGRAM_ROW_PROJECTION = arrayOf(COLUMN_ID, COLUMN_INTERNAL_PROVIDER_ID)
        val CHANGE_COLUMNS = arrayOf("title", "short_description", "poster_art_uri", "intent_uri", "weight")
    }
}