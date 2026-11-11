// app/src/main/java/Nexotv/iptv/player/activity/MainActivityStreamConfig.kt
package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*

import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.player.*
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import java.util.Locale

internal val DEFAULT_STREAM_URL: String = BuildConfig.DEFAULT_STREAM_URL

internal const val TV_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val STREAM_CONFIG_LOG_TAG = "StreamConfig"
private const val HEADER_NAME_SPECIAL_CHARS = "!#$%&'*+-.^_`|~"
private const val HEADER_SEPARATOR = ':'
private const val MAX_HEADER_COUNT = 64
private const val MAX_HEADER_NAME_LENGTH = 128
private const val MAX_HEADER_VALUE_LENGTH = 8_192
private const val MAX_HEADERS_TEXT_LENGTH = MAX_HEADER_COUNT * (MAX_HEADER_NAME_LENGTH + MAX_HEADER_VALUE_LENGTH + 4)
private const val MAX_STORED_HEADERS_LENGTH = MAX_HEADERS_TEXT_LENGTH * 2
private const val MAX_STREAM_URL_LENGTH = 8_192
private const val MAX_HEADER_VALUE_CODE = 0xFF
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5

private val ALLOWED_STREAM_SCHEMES = setOf("http", "https")
private val RESERVED_HEADER_NAMES = setOf("host", "connection", "content-length", "accept-encoding", "user-agent")
private val FORBIDDEN_HEADER_NAMES = setOf(
    "transfer-encoding", "upgrade", "te", "trailer", "keep-alive", "proxy-connection", "expect"
)

private fun isHeaderNameChar(char: Char): Boolean =
    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in HEADER_NAME_SPECIAL_CHARS

private fun isLineBreak(char: Char): Boolean = char == '\r' || char == '\n'

private fun isForbiddenControlChar(char: Char): Boolean = char.isISOControl() && char != '\t'

private fun isUnencodableHeaderChar(char: Char): Boolean = char.code > MAX_HEADER_VALUE_CODE

private fun isValidPort(port: Int): Boolean = port == -1 || port in MIN_PORT..MAX_PORT

private fun logDiscardedHeaders(error: Exception) {
    runCatching { Log.w(STREAM_CONFIG_LOG_TAG, "Encabezados del stream descartados por ser inválidos", error) }
}

private fun hasWellFormedPort(encodedAuthority: String?): Boolean {
    val hostPort = encodedAuthority?.substringAfterLast('@') ?: return false
    val portText: String = if (hostPort.startsWith('[')) {
        val close = hostPort.indexOf(']')
        if (close < 0) return false
        val rest = hostPort.substring(close + 1)
        if (rest.isEmpty()) return true
        if (!rest.startsWith(':')) return false
        rest.substring(1)
    } else {
        if (hostPort.count { it == ':' } > 1) return false
        hostPort.substringAfter(':', "")
    }
    if (portText.isEmpty()) return true
    if (portText.length > MAX_PORT_DIGITS || !portText.all { it in '0'..'9' }) return false
    return portText.toInt() in MIN_PORT..MAX_PORT
}

private fun isValidStreamUrl(candidate: String): Boolean = runCatching {
    if (candidate.isBlank() || candidate.length > MAX_STREAM_URL_LENGTH) return@runCatching false
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return@runCatching false
    val uri = candidate.toUri()
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    scheme != null &&
            scheme in ALLOWED_STREAM_SCHEMES &&
            !uri.host.isNullOrBlank() &&
            isValidPort(uri.port) &&
            hasWellFormedPort(uri.encodedAuthority)
}.getOrDefault(false)

private fun storedHeaderValue(json: JSONObject, key: String): String? {
    if (json.isNull(key)) return null
    return when (val value = json.get(key)) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> throw IllegalArgumentException("Los encabezados guardados contienen un valor inválido.")
    }
}

@UnstableApi
internal fun Context.currentStreamUrl(): String {
    val configured = runCatching {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .safeString(MainActivity.KEY_STREAM_URL, DEFAULT_STREAM_URL)
            ?.trim()
    }.getOrNull()
    return configured?.takeIf(::isValidStreamUrl) ?: DEFAULT_STREAM_URL
}

@UnstableApi
internal fun Context.usesBuiltInStream(): Boolean = currentStreamUrl() == DEFAULT_STREAM_URL

internal fun parseStreamHeaders(text: String): Map<String, String> {
    require(text.length <= MAX_HEADERS_TEXT_LENGTH) { "Los encabezados son demasiado largos." }
    val headers = linkedMapOf<String, String>()
    val seen = mutableSetOf<String>()
    text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
        val separator = line.indexOf(HEADER_SEPARATOR)
        require(separator > 0) { "Cada encabezado debe tener el formato Nombre: valor." }
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        require(name.isNotEmpty() && name.length <= MAX_HEADER_NAME_LENGTH && name.all(::isHeaderNameChar)) {
            "El nombre de un encabezado no es válido."
        }
        require(value.none(::isLineBreak)) { "El valor de un encabezado contiene un salto de línea." }
        require(value.none(::isForbiddenControlChar)) {
            "El valor de un encabezado contiene caracteres de control."
        }
        require(value.none(::isUnencodableHeaderChar)) {
            "El valor de un encabezado contiene caracteres no válidos."
        }
        require(value.length <= MAX_HEADER_VALUE_LENGTH) { "El valor de un encabezado es demasiado largo." }
        val normalizedName = name.lowercase(Locale.ROOT)
        require(normalizedName !in RESERVED_HEADER_NAMES) {
            "Configura User-Agent en su ajuste independiente; ese encabezado no se puede repetir aquí."
        }
        require(normalizedName !in FORBIDDEN_HEADER_NAMES) { "Ese encabezado no está permitido." }
        require(seen.add(normalizedName)) { "No repitas el encabezado «$name»." }
        require(headers.size < MAX_HEADER_COUNT) { "Hay demasiados encabezados." }
        headers[name] = value
    }
    return headers
}

@UnstableApi
internal fun Context.currentStreamHeaders(): Map<String, String> {
    return try {
        val url = currentStreamUrl()
        if (url == DEFAULT_STREAM_URL || isCleartextUrl(url)) return emptyMap()
        val raw = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .safeString(MainActivity.KEY_STREAM_HEADERS, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        require(raw.length <= MAX_STORED_HEADERS_LENGTH) { "Los encabezados guardados son demasiado largos." }
        val json = JSONObject(raw)
        val lines = json.keys().asSequence().mapNotNull { key ->
            val value = storedHeaderValue(json, key) ?: return@mapNotNull null
            require(key.none(::isLineBreak) && value.none(::isLineBreak)) {
                "Los encabezados guardados contienen saltos de línea."
            }
            require(HEADER_SEPARATOR !in key) {
                "Los encabezados guardados contienen un nombre inválido."
            }
            "$key$HEADER_SEPARATOR $value"
        }.toList().joinToString("\n")
        parseStreamHeaders(lines)
    } catch (e: Exception) {
        logDiscardedHeaders(e)
        emptyMap()
    }
}

@UnstableApi
internal fun Context.hasCustomStreamHeaders(): Boolean {
    if (usesBuiltInStream()) return false
    val raw = runCatching {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .safeString(MainActivity.KEY_STREAM_HEADERS, null)
    }.getOrNull() ?: return false
    if (raw.length > MAX_STORED_HEADERS_LENGTH) return false
    return runCatching { JSONObject(raw).length() > 0 }.getOrDefault(false)
}