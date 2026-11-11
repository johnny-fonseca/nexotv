@file:UnstableApi

package Nexotv.iptv.player.settings


import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * Coordina exportación, importación y respaldo de configuraciones.
 * La Activity conserva los diálogos y callbacks del framework; este controlador
 * posee el formato JSON, validación, límites y persistencia de las copias.
 */
internal class SettingsTransferController(private val host: Host) {
    interface Host {
        val context: Context
        val contentResolver: ContentResolver
        val appPreferences: SharedPreferences
        val brandName: String
        val brandKey: String
fun showTvToast(message: String, long: Boolean)
        fun applyImportedSettings(resizeMode: Int)
        fun launchSettingsFilePicker(intent: Intent)
        fun safeStartActivity(intent: Intent, unavailableMessage: String)
        fun logImportFailure(error: Exception)
    }

    fun importFrom(uri: Uri) {
        val text = try {
            readTextLimited(uri)
        } catch (_: SettingsFileTooLargeException) {
            host.showTvToast("El archivo de ajustes es demasiado grande", long = true)
            return
        } catch (error: Exception) {
            host.logImportFailure(error)
            host.showTvToast("No se pudo abrir la copia de ajustes", long = true)
            return
        }
        if (text == null) {
            host.showTvToast("No se pudo leer el archivo", long = true)
            return
        }
        importSettings(text)
    }

    fun exportSettings() {
        val serialized = runCatching { buildSettingsJson().toString(2) }.getOrElse {
            host.showTvToast("No se pudieron preparar los ajustes", long = true)
            return
        }
        if (serialized.length > MAX_SETTINGS_JSON_CHARS) {
            host.showTvToast("Los ajustes superan el tamaño máximo exportable", long = true)
            return
        }
        val share = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_SUBJECT, "Ajustes de ${host.brandName}")
            .putExtra(Intent.EXTRA_TEXT, serialized)
        host.safeStartActivity(
            Intent.createChooser(share, "Exportar ajustes"),
            "No hay una aplicación para compartir los ajustes",
        )
    }

    fun openSettingsFile() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        runCatching { host.launchSettingsFilePicker(picker) }
            .onFailure { host.showTvToast("No hay un selector de archivos disponible", long = true) }
    }

    fun importSettings(jsonText: String) {
        val entries = try {
            parseSettingsEntries(jsonText)
        } catch (failure: IllegalArgumentException) {
            host.showTvToast(failure.message ?: "JSON de ajustes inválido", long = true)
            return
        } catch (_: JSONException) {
            host.showTvToast("JSON de ajustes inválido", long = true)
            return
        }

        val prefs = host.appPreferences
        val stored = runCatching {
            val current = prefs.all
            val preserved = current.filter { (key, value) ->
                key in NON_PORTABLE_PREF_KEYS || (value is Set<*> && key !in entries)
            }
            prefs.edit {
                clear()
                entries.forEach { (key, value) ->
                    normalizeImportedValue(key, value, current[key])?.let { putAny(key, it) }
                }
                preserved.forEach { (key, value) -> putAny(key, value) }
            }
        }.isSuccess
        if (!stored) {
            host.showTvToast("No se pudieron aplicar los ajustes importados", long = true)
            return
        }

        runCatching {
            saveAutomaticBackup()
            val resizeMode = prefs.safeInt(MainActivity.KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
                .takeIf { it in AspectRatioFrameLayout.RESIZE_MODE_FIT..AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
                ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
            host.applyImportedSettings(resizeMode)
        }.onFailure {
            host.showTvToast("Ajustes importados; reinicia la aplicación para aplicarlos por completo", long = true)
        }
    }

    fun restoreAutomaticBackup() {
        val external = runCatching { AutoBackupStore.read(host.context) }.getOrNull()
        val internal = runCatching {
            File(host.context.filesDir, "backup/${host.brandKey}-backup.json")
                .takeIf(File::isFile)?.readText(Charsets.UTF_8)
        }.getOrNull()
        val backup = listOfNotNull(external, internal).firstOrNull { candidate ->
            candidate.isNotBlank() && runCatching { parseSettingsEntries(candidate) }.isSuccess
        }
        if (backup.isNullOrBlank()) {
            host.showTvToast("Todavía no existe un respaldo automático", long = true)
            return
        }
        importSettings(backup)
    }

    fun saveAutomaticBackup() {
        if (!host.appPreferences.safeBoolean(MainActivity.KEY_AUTO_BACKUP, true)) return
        val serialized = runCatching { buildSettingsJson().toString(2) }.getOrNull() ?: return
        runCatching {
            val target = File(host.context.filesDir, "backup/${host.brandKey}-backup.json")
            val directory = target.parentFile ?: return@runCatching
            if (!directory.isDirectory) directory.mkdirs()
            val temporary = File(directory, "${target.name}.tmp")
            temporary.writeText(serialized)
            if (!temporary.renameTo(target)) {
                target.writeText(serialized)
                temporary.delete()
            }
        }
        runCatching { AutoBackupStore.write(host.context, serialized) }
    }

    private fun readTextLimited(uri: Uri): String? {
        val stream = host.contentResolver.openInputStream(uri) ?: return null
        return stream.bufferedReader().use { reader ->
            val buffer = CharArray(MAX_SETTINGS_FILE_CHARS.coerceAtMost(8_192))
            val builder = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (builder.length + read > MAX_SETTINGS_FILE_CHARS) throw SettingsFileTooLargeException()
                builder.appendRange(buffer, 0, read)
            }
            builder.toString()
        }
    }

    private fun stripUrlCredentials(url: String): String = runCatching {
        val uri = Uri.parse(url)
        if (uri.userInfo.isNullOrEmpty()) return@runCatching url
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        uri.buildUpon().encodedAuthority("${uri.host.orEmpty()}$port").build().toString()
    }.getOrDefault(url)

    private fun buildSettingsJson(): JSONObject {
        val prefs = host.appPreferences.all.filterKeys { it !in NON_PORTABLE_PREF_KEYS && it != MainActivity.KEY_STREAM_HEADERS }
        val json = JSONObject().put("app", host.brandName).put("version", 1)
        prefs.forEach { (key, value) ->
            if (key.isBlank() || key.length > MAX_SETTINGS_KEY_LENGTH) return@forEach
            when (value) {
                is String -> json.put(key, if (key == MainActivity.KEY_STREAM_URL) stripUrlCredentials(value) else value)
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> if (value.isFinite()) json.put(key, value.toDouble())
            }
        }
        return json
    }

    private fun parseSettingsEntries(jsonText: String): Map<String, Any> {
        require(jsonText.length <= MAX_SETTINGS_JSON_CHARS) {
            "El archivo de ajustes excede el tamaño máximo permitido."
        }
        val trimmed = jsonText.trim()
        require(trimmed.isNotEmpty()) { "El texto de ajustes está vacío." }
        val json = JSONObject(trimmed)
        require(json.optString("app") == host.brandName) {
            "El archivo no corresponde a una copia de ${host.brandName}."
        }
        require(json.length() <= MAX_SETTINGS_ENTRIES) { "La copia contiene demasiadas entradas." }

        val ignored = setOf("app", "version") + NON_PORTABLE_PREF_KEYS
        val entries = LinkedHashMap<String, Any>()
        json.keys().forEach { key ->
            if (key in ignored) return@forEach
            require(key.isNotBlank() && key.length <= MAX_SETTINGS_KEY_LENGTH) {
                "La copia contiene una clave de ajuste inválida."
            }
            toSettingValue(json.get(key))?.let { entries[key] = it }
        }
        return entries
    }

    private fun toSettingValue(value: Any): Any? = when (value) {
        is String, is Boolean, is Int, is Long -> value
        is Number -> value.toFloat().takeIf { it.isFinite() }
        else -> null
    }

    private fun normalizeImportedValue(key: String, value: Any, existing: Any?): Any? {
        when (key) {
            MainActivity.KEY_STREAM_URL, MainActivity.KEY_EPG_URL ->
                return (value as? String)?.takeIf { isValidHttpUrl(it) }
            MainActivity.KEY_STREAM_HEADERS ->
                return (value as? String)?.takeIf { runCatching { JSONObject(it) }.isSuccess }
        }
        val number = value as? Number ?: return value
        return when {
            key == MainActivity.KEY_AUDIO_OFFSET ->
                number.toLong().coerceIn(AUDIO_OFFSET_LIMIT_MS * -1, AUDIO_OFFSET_LIMIT_MS)
            existing is Float -> number.toFloat()
            (existing is Long || key in LONG_PREF_KEYS) && value !is Float -> number.toLong()
            existing is Int && value is Long ->
                if (value.toInt().toLong() == value) value.toInt() else value
            else -> value
        }
    }

    private class SettingsFileTooLargeException : Exception()

    private companion object {
        const val MAX_SETTINGS_FILE_CHARS = 1_000_000
        const val MAX_SETTINGS_JSON_CHARS = 512_000
        const val MAX_SETTINGS_ENTRIES = 512
        const val MAX_SETTINGS_KEY_LENGTH = 128

        val NON_PORTABLE_PREF_KEYS: Set<String> = setOf(
            MainActivity.KEY_RECORDING,
            MainActivity.KEY_RECORDING_FILE,
            MainActivity.KEY_RECORDING_NAME,
            MainActivity.KEY_RECORDING_TREE,
            MainActivity.KEY_RECORDING_ERROR,
            MainActivity.KEY_EPG_CACHE,
            MainActivity.KEY_METERED_RX_BYTES,
        )
        val LONG_PREF_KEYS: Set<String> = setOf(MainActivity.KEY_AUDIO_OFFSET)
    }
}

