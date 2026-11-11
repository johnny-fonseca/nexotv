// GuideSearchController.kt
@file:Suppress("SetTextI18n", "NewApi", "unused")

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.MainActivity

import androidx.core.content.edit
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.speech.RecognizerIntent
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray

private const val SEARCH_TAG = "GuideSearchHistory"

/** Persiste el conjunto reducido de las búsquedas más recientes de la guía. */
internal class GuideSearchHistory(context: Context) {
    private val preferences: SharedPreferences? = try {
        (context.applicationContext ?: context).getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        Log.w(SEARCH_TAG, "SharedPreferences unavailable", e)
        null
    }

    fun recent(): List<String> = synchronized(LOCK) { readHistory() }

    fun remember(query: String) {
        val clean = sanitizeQuery(query)
        if (clean.isEmpty()) return
        val prefs = preferences ?: return
        synchronized(LOCK) {
            try {
                val updated = (listOf(clean) + readHistory())
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(MAX_ENTRIES)
                val json = JSONArray().apply { updated.forEach { put(it) } }.toString()
                prefs.edit {
                    putString(KEY_HISTORY, json)
                }
            } catch (e: Exception) {
                Log.w(SEARCH_TAG, "remember failed", e)
            }
        }
    }

    private fun readHistory(): List<String> {
        val prefs = preferences ?: return emptyList()
        return try {
            val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            if (raw.length > MAX_STORED_CHARS) {
                discardCorrupt(prefs)
                return emptyList()
            }
            val values = JSONArray(raw)
            val limit = minOf(values.length(), MAX_SCANNED_ENTRIES)
            val result = ArrayList<String>(MAX_ENTRIES)
            val seen = HashSet<String>()
            var index = 0
            while (index < limit && result.size < MAX_ENTRIES) {
                val item = values.opt(index) as? String
                if (item != null) {
                    val clean = sanitizeQuery(item)
                    if (clean.isNotEmpty() && seen.add(clean.lowercase(Locale.ROOT))) {
                        result.add(clean)
                    }
                }
                index++
            }
            result
        } catch (e: Exception) {
            Log.w(SEARCH_TAG, "history unreadable; resetting", e)
            discardCorrupt(prefs)
            emptyList()
        }
    }

    private fun discardCorrupt(prefs: SharedPreferences) {
        try {
            prefs.edit { remove(KEY_HISTORY) }
        } catch (e: Exception) {
            Log.w(SEARCH_TAG, "could not reset history", e)
        }
    }

    private companion object {
        const val KEY_HISTORY = "guide_search_history"
        const val MAX_ENTRIES = 8
        const val MAX_SCANNED_ENTRIES = 64
        const val MAX_STORED_CHARS = 32_768
        val LOCK = Any()
    }
}