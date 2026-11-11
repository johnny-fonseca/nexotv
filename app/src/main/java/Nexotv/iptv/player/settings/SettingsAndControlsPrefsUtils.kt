@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi

private const val MAX_URL_LENGTH = 8_192

private val HTTP_SCHEMES: Set<String> = setOf("http", "https")

// ---------- Utilidades generales ----------

internal fun Context.appPreferences(): SharedPreferences =
    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

internal fun MainActivity.flashStatus(message: String) {
    showStatus(message)
    hideStatusAfterDelay()
}

internal fun MainActivity.flipBoolean(key: String, default: Boolean): Boolean {
    val prefs = appPreferences()
    val enabled = !prefs.safeBoolean(key, default)
    prefs.edit { putBoolean(key, enabled) }
    return enabled
}

internal fun SharedPreferences.bufferProfile() =
    BufferSizes.normalizedProfile(safeString(MainActivity.KEY_BUFFER, BufferSizes.PROFILE_HOTSPOT))

internal fun isValidHttpUrl(url: String): Boolean =
    url.isNotBlank() && url.length <= MAX_URL_LENGTH && runCatching {
        val uri = url.toUri()
        uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

internal fun simpleTextWatcher(onChange: () -> Unit): TextWatcher =
    object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange()
        override fun afterTextChanged(s: Editable?) = Unit
    }

internal fun MainActivity.safeStartActivity(intent: Intent, errorMessage: String): Boolean =
    runCatching { startActivity(intent) }
        .onFailure { showTvToast(errorMessage, long = true) }
        .isSuccess

internal fun SharedPreferences.Editor.putAny(key: String, value: Any?): SharedPreferences.Editor =
    when (value) {
        is String -> putString(key, value)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        else -> this
    }

