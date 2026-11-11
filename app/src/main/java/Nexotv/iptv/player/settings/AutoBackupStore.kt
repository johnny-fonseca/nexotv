// AutoBackupStore.kt
package Nexotv.iptv.player.settings

import Nexotv.iptv.player.*
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Keeps an automatic JSON copy in the active brand's Documents folder. */
internal object AutoBackupStore {
    private val NAME = "${BuildConfig.BRAND_KEY}-backup.json"
    private val TEMP_NAME = "$NAME.tmp"
    private const val MIME_TYPE = "application/json"
    private val RELATIVE_DIRECTORY = "Documents/${BuildConfig.BRAND_NAME}/"
    private val LEGACY_DIRECTORY = "${BuildConfig.BRAND_NAME}"

    private val usesMediaStore: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun canWrite(context: Context): Boolean = usesMediaStore ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun write(context: Context, json: String): Boolean = runCatching {
        if (json.isBlank() || !canWrite(context)) return@runCatching false
        if (usesMediaStore) writeMediaStore(context, json) else writeLegacy(json)
        true
    }.getOrDefault(false)

    fun read(context: Context): String? = runCatching {
        val content = if (usesMediaStore) readMediaStore(context) else readLegacy(context)
        content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaCollection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findMediaStoreUri(context: Context): Uri? {
        val collection = mediaCollection()
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        return context.contentResolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                arrayOf(NAME, RELATIVE_DIRECTORY),
                "${MediaStore.MediaColumns._ID} DESC"
            )
            ?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreEntry(context: Context, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(mediaCollection(), values)
            ?: throw IOException("No se pudo crear el respaldo")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(context: Context, json: String) {
        val resolver = context.contentResolver
        val existing = findMediaStoreUri(context)
        // Retira temporales IS_PENDING abandonados por una ejecución interrumpida.
        deletePendingMediaStoreEntries(context)
        val uri = createMediaStoreEntry(context, TEMP_NAME)
        try {
            val stream = resolver.openOutputStream(uri, "wt")
                ?: throw IOException("No se pudo escribir el respaldo")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            val rows = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            if (rows <= 0) throw IOException("No se pudo finalizar el respaldo")
            // El nuevo respaldo ya está publicado; solo ahora se elimina el anterior.
            existing?.takeIf { it != uri }?.let { resolver.delete(it, null, null) }
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deletePendingMediaStoreEntries(context: Context) {
        val collection = mediaCollection()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND (${MediaStore.MediaColumns.DISPLAY_NAME}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME}=?)"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(RELATIVE_DIRECTORY, NAME, TEMP_NAME),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val stale = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                runCatching { context.contentResolver.delete(stale, null, null) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun readMediaStore(context: Context): String? {
        val uri = findMediaStoreUri(context) ?: return null
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
    }

    private fun legacyDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), LEGACY_DIRECTORY)

    private fun legacyFile(): File = File(legacyDirectory(), NAME)

    private fun writeLegacy(json: String) {
        val directory = legacyDirectory()
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("No se pudo crear el directorio del respaldo")
        }
        val target = legacyFile()
        val temp = File(directory, TEMP_NAME)
        try {
            temp.writeText(json, Charsets.UTF_8)
            // Escritura atómica: primero archivo temporal y luego reemplazo del definitivo
            if (!temp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun readLegacy(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
            !canWrite(context)
        ) return null
        return legacyFile().takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }
}
