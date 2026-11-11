// GuideProgramStore.kt
package Nexotv.iptv.player.guide
import Nexotv.iptv.player.branding.BrandScheduleParser

import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.tv.TvProgram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

private const val STORE_ERROR_TAG = "GUIDE_ERROR"

private fun logStoreFailure(scope: String, error: Throwable) {
    DiagnosticTracker.log(STORE_ERROR_TAG, "programStore.$scope: ${error.javaClass.simpleName}")
}

/** Almacena y serializa programas de la guía independientemente de la UI de la Activity. */
internal object TvProgramJson {
    private const val MAX_PROGRAMS = 100_000
    private const val MAX_CHANNEL_LENGTH = 256
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_DESCRIPTION_LENGTH = 8_192
    private const val MAX_CATEGORY_LENGTH = 256
    private const val MAX_RATING_LENGTH = 64
    private const val MAX_URL_LENGTH = 2_048

    internal fun isValid(program: TvProgram): Boolean =
        program.startMs >= 0L && program.endMs >= program.startMs

    fun encode(programs: List<TvProgram>): String {
        val array = JSONArray()
        for (program in programs) {
            if (array.length() >= MAX_PROGRAMS) break
            try {
                array.put(
                    JSONObject()
                        .put("channel", program.channelId)
                        .put("title", program.title)
                        .put("description", program.description)
                        .put("start", program.startMs)
                        .put("end", program.endMs)
                        .put("category", program.category)
                        .put("rating", program.rating)
                        .put("icon", program.iconUrl),
                )
            } catch (e: Exception) {
                logStoreFailure("encodeItem", e)
            }
        }
        return array.toString()
    }

    fun decode(raw: String): List<TvProgram> {
        if (raw.isBlank()) return emptyList()
        val values = try {
            JSONArray(raw)
        } catch (e: Exception) {
            logStoreFailure("decodeArray", e)
            return emptyList()
        }
        val count = minOf(values.length(), MAX_PROGRAMS)
        val result = ArrayList<TvProgram>(count)
        for (index in 0 until count) {
            val item = values.optJSONObject(index) ?: continue
            val program = try {
                item.toProgram()
            } catch (_: Exception) {
                null
            } ?: continue
            if (!isValid(program)) continue
            result.add(program)
        }
        return result
    }

    private fun JSONObject.toProgram() = TvProgram(
        safeString("channel", MAX_CHANNEL_LENGTH),
        safeString("title", MAX_TITLE_LENGTH),
        safeString("description", MAX_DESCRIPTION_LENGTH),
        optLong("start"),
        optLong("end"),
        safeString("category", MAX_CATEGORY_LENGTH),
        safeString("rating", MAX_RATING_LENGTH),
        safeString("icon", MAX_URL_LENGTH),
    )

    // optString devuelve "null" para JSONObject.NULL; aquí se normaliza a cadena vacía
    private fun JSONObject.safeString(name: String, maxLength: Int): String =
        if (isNull(name)) "" else optString(name).take(maxLength)
}

internal data class SavedProgramsSnapshot(
    val programs: List<TvProgram>,
    val revision: Long,
)

internal class GuideProgramStore(context: Context) {
    private val preferences: SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    private val lock = Any()
    private var cachedSavedPrograms: List<TvProgram>? = null
    private var savedProgramsRevision = 0L

    private fun readInt(key: String, default: Int): Int = try {
        preferences.getInt(key, default)
    } catch (_: Exception) {
        default
    }

    private fun loadCachedLocked(): List<TvProgram>? {
        val raw = preferences.getString(MainActivity.KEY_EPG_CACHE, null)
        if (raw.isNullOrBlank()) return null
        val days = readInt(MainActivity.KEY_EPG_PAST_DAYS, DEFAULT_PAST_DAYS).coerceIn(0, MAX_PAST_DAYS)
        val now = System.currentTimeMillis()
        val threshold = if (days == 0) now else now - days.toLong() * MainActivity.DAY_MS
        val decoded = TvProgramJson.decode(raw)
        if (decoded.isEmpty()) return null
        val withArtwork = decoded.map { program ->
            try {
                BrandScheduleParser.withArtwork(program)
            } catch (_: Exception) {
                program
            }
        }
        val cleaned = try {
            XmlTvGuide.discardSharedArtwork(withArtwork)
        } catch (e: Exception) {
            logStoreFailure("discardArtwork", e)
            withArtwork
        }
        val filtered = cleaned.filter { it.endMs >= threshold }
        return if (filtered.isNotEmpty()) filtered else cleaned.takeIf { it.isNotEmpty() }
    }

    fun loadCached(): List<TvProgram>? = synchronized(lock) {
        try {
            loadCachedLocked()
        } catch (e: Exception) {
            logStoreFailure("loadCached", e)
            null
        }
    }

    fun saveCached(programs: List<TvProgram>) {
        synchronized(lock) {
            try {
                val encoded = TvProgramJson.encode(programs)
                preferences.edit {
                    putString(MainActivity.KEY_EPG_CACHE, encoded)
                    putLong(MainActivity.KEY_EPG_UPDATED, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                logStoreFailure("saveCached", e)
            }
        }
    }

    fun saveCachedIfChanged(programs: List<TvProgram>): Boolean {
        return synchronized(lock) {
            try {
                val encoded = TvProgramJson.encode(programs)
                if (preferences.getString(MainActivity.KEY_EPG_CACHE, null) == encoded) {
                    preferences.edit { putLong(MainActivity.KEY_EPG_UPDATED, System.currentTimeMillis()) }
                    false
                } else {
                    preferences.edit {
                        putString(MainActivity.KEY_EPG_CACHE, encoded)
                        putLong(MainActivity.KEY_EPG_UPDATED, System.currentTimeMillis())
                    }
                    true
                }
            } catch (e: Exception) {
                logStoreFailure("saveCachedIfChanged", e)
                false
            }
        }
    }

    // Devuelve null solo si la lectura de preferencias falla, para no persistir una lista vacía por error
    private fun loadSavedOrNull(): List<TvProgram>? = try {
        val raw = preferences.getString(MainActivity.KEY_MY_LIST, "[]").orEmpty()
        val unique = LinkedHashMap<String, TvProgram>()
        for (program in TvProgramJson.decode(raw)) {
            val key = programKey(program)
            if (key !in unique) unique[key] = program
            if (unique.size >= MAX_SAVED_PROGRAMS) break
        }
        unique.values.toList()
    } catch (e: Exception) {
        logStoreFailure("loadSaved", e)
        null
    }

    fun savedProgramsSnapshot(): SavedProgramsSnapshot = synchronized(lock) {
        val existing = cachedSavedPrograms
        val programs = if (existing != null) {
            existing
        } else {
            val loaded = loadSavedOrNull()
            if (loaded != null) cachedSavedPrograms = loaded
            loaded ?: emptyList()
        }
        SavedProgramsSnapshot(programs, savedProgramsRevision)
    }

    fun preloadSavedPrograms() {
        savedProgramsSnapshot()
    }

    fun loadSaved(): List<TvProgram> = savedProgramsSnapshot().programs

    fun setSaved(program: TvProgram, saved: Boolean) {
        synchronized(lock) {
            try {
                if (saved && !TvProgramJson.isValid(program)) return
                val key = programKey(program)
                val current = cachedSavedPrograms
                    ?: loadSavedOrNull()?.also { cachedSavedPrograms = it }
                    ?: return
                val alreadySaved = current.any { programKey(it) == key }
                if (alreadySaved == saved) return
                val entries = current.filterNot { programKey(it) == key }.toMutableList()
                if (saved) {
                    entries.add(program)
                    while (entries.size > MAX_SAVED_PROGRAMS) entries.removeAt(0)
                }
                val encoded = TvProgramJson.encode(entries)
                preferences.edit {
                    putString(MainActivity.KEY_MY_LIST, encoded)
                }
                cachedSavedPrograms = entries.toList()
                savedProgramsRevision++
            } catch (e: Exception) {
                logStoreFailure("setSaved", e)
            }
        }
    }

    private fun programKey(program: TvProgram) = "${program.channelId}:${program.startMs}"

    private companion object {
        const val DEFAULT_PAST_DAYS = 7
        const val MAX_PAST_DAYS = 3_650
        const val MAX_SAVED_PROGRAMS = 5_000
    }
}