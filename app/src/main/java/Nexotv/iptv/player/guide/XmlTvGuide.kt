// XmlTvGuide.kt
@file:Suppress(
    "PackageNaming",
    "PackageDirectoryMismatch",
    "PackageName",
    "ConstantConditionIf",
    "SimplifyBooleanWithConstants",
    "KotlinConstantConditions",
    "ConstantConditions"
)

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.branding.BrandScheduleParser
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*

import android.util.Log
import java.io.InputStream
import java.net.URL
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Obtiene y analiza la guía TV de la variante activa desde:
 * - API oficial
 * - Sitio web de marca (FUTV/OPA)
 * - XMLTV
 *
 * Diseño robusto:
 * - No expone query params/sensibles en logs (URLs dentro de mensajes de error se redactan).
 * - Tolerante a fallos parciales con fallback controlado.
 * - Evita "branding art" compartido entre títulos distintos.
 */
internal object XmlTvGuide {

    const val DEFAULT_URL: String = BuildConfig.DEFAULT_EPG_URL

    private const val TAG = "XmlTvGuide"

    private const val BRAND_FUTV = "futv"
    private const val BRAND_OPA = "opa"

    private const val FUTV_HOST = "futvcr.com"
    private const val FUTV_HOME_URL = "https://futvcr.com/"
    private const val FUTV_WEEKLY_PATH = "/programacion"
    private const val FUTV_WEEKLY_URL = "https://futvcr.com/programacion/"
    private const val FUTV_UPLOADS_PREFIX = "https://futvcr.com/wp-content/uploads/"

    private const val OPA_HOST = "genteopa.com"
    private const val OPA_SCHEDULE_PATH = "/programas"

    private const val OFFICIAL_SCHEDULE_PATH = "/api/schedule"

    private const val OFFICIAL_ACCEPT = "application/json, text/plain, */*"
    private const val HTML_ACCEPT = "text/html,application/xhtml+xml"

    private const val MAX_URL_LENGTH = 4096
    private const val MAX_ERROR_CHAIN = 3
    private const val MAX_LOG_MESSAGE_LENGTH = 300

    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    private val OFFICIAL_HOSTS: Set<String> =
        BuildConfig.OFFICIAL_EPG_HOSTS
            .split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Punto de entrada principal para obtener la guía.
     */
    fun fetch(url: String): List<TvProgram> {
        val target = normalizeUrl(url, DEFAULT_URL)
        if (target.isBlank()) {
            logWarning("No hay URL de guía configurada (entrada y DEFAULT_URL vacías)")
            return emptyList()
        }

        val rawPrograms = when {
            isFutvWebsite(target) -> fetchFutvWebsite(target)
            isOpaWebsite(target) -> fetchOpaWebsite(target)
            isOfficialApi(target) -> fetchOfficialApi(target)
            else -> fetchXmlTvWithFallback(target)
        }

        return discardSharedArtwork(rawPrograms)
    }

    /**
     * Descarta imágenes repetidas entre títulos distintos (suele ser branding de canal).
     */
    fun discardSharedArtwork(programs: List<TvProgram>): List<TvProgram> {
        if (programs.isEmpty()) return programs
        return try {
            removeSharedArtwork(programs)
        } catch (e: Exception) {
            logWarning("No se pudo depurar el arte compartido de la guía", e)
            programs
        }
    }

    private fun removeSharedArtwork(programs: List<TvProgram>): List<TvProgram> {
        val isFutvBrand = BuildConfig.BRAND_KEY == BRAND_FUTV
        val titlesByArtwork = HashMap<String, MutableSet<String>>()

        programs.forEach { program ->
            val icon = program.iconUrl.trim()
            if (icon.isEmpty()) return@forEach
            if (isFutvBrand && icon.startsWith(FUTV_UPLOADS_PREFIX, ignoreCase = true)) return@forEach
            val title = program.title.trim()
            if (title.isNotEmpty()) titlesByArtwork.getOrPut(icon) { HashSet() }.add(title)
        }

        val sharedArtwork = HashSet<String>()
        for ((icon, titles) in titlesByArtwork) {
            if (titles.size > 1) sharedArtwork.add(icon)
        }

        if (sharedArtwork.isEmpty()) return programs

        return programs.map { program ->
            if (program.iconUrl.trim() in sharedArtwork) program.copy(iconUrl = "") else program
        }
    }

    /**
     * API oficial (JSON/plano, parser de marca).
     */
    fun fetchOfficialApi(url: String): List<TvProgram> {
        validateHttpUrlOrThrow(url, source = "API oficial")

        val parsed = GuideHttpClient.request(url, OFFICIAL_ACCEPT) { stream ->
            val raw = GuideHttpClient.readText(stream)
            parseOfficialSchedule(raw)
        }

        check(parsed.isNotEmpty()) { "La fuente oficial no contiene emisiones válidas" }
        return parsed
    }

    /**
     * Sitio FUTV:
     * - Si es /programacion usa parse semanal.
     * - Si falla, intenta portada.
     */
    private fun fetchFutvWebsite(url: String): List<TvProgram> {
        validateHttpsUrlOrThrow(url, source = "sitio FUTV")

        val parsedUrl = parseUrlOrNull(url)
        val path = parsedUrl?.path.orEmpty().trimEnd('/')
        val isWeeklyUrl = path == FUTV_WEEKLY_PATH
        var lastFailure: Throwable? = null

        fun trySource(label: String, request: () -> List<TvProgram>): List<TvProgram> = try {
            request()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            lastFailure = failure
            logWarning("Falló $label", failure)
            emptyList()
        }

        val primary = if (isWeeklyUrl) {
            trySource("guía semanal FUTV") { fetchFutvWeekly(url) }
        } else {
            trySource("portada FUTV") { fetchHome(url) }
        }
        if (primary.isNotEmpty()) return primary

        val fallback = if (isWeeklyUrl) {
            logWarning("La tabla semanal de FUTV no produjo programas; se probará la portada")
            trySource("portada FUTV") { fetchHome(FUTV_HOME_URL) }
        } else {
            logWarning("La portada de FUTV no produjo programas; se probará la tabla semanal")
            trySource("guía semanal FUTV") { fetchFutvWeekly(FUTV_WEEKLY_URL) }
        }

        if (fallback.isEmpty() && lastFailure != null) throw lastFailure!!
        check(fallback.isNotEmpty()) { "Las fuentes de FUTV no contienen emisiones reconocibles" }
        return fallback
    }

    private fun fetchFutvWeekly(target: String): List<TvProgram> =
        GuideHttpClient.request(target, HTML_ACCEPT) { stream ->
            BrandScheduleParser.parseWeek(
                GuideHttpClient.readText(stream),
                System.currentTimeMillis(),
            )
        }

    private fun fetchHome(target: String): List<TvProgram> {
        validateHttpsUrlOrThrow(target, source = "portada de marca")
        return GuideHttpClient.request(target, HTML_ACCEPT) { stream ->
            BrandScheduleParser.parse(
                GuideHttpClient.readText(stream),
                System.currentTimeMillis()
            )
        }
    }

    /**
     * Sitio OPA: parse semanal web.
     */
    private fun fetchOpaWebsite(target: String): List<TvProgram> {
        validateHttpsUrlOrThrow(target, source = "sitio OPA")

        val programs = GuideHttpClient.request(target, HTML_ACCEPT) { stream ->
            BrandScheduleParser.parseWeek(
                GuideHttpClient.readText(stream),
                System.currentTimeMillis()
            )
        }

        check(programs.isNotEmpty()) { "La página de OPA no contiene horarios reconocibles" }
        return programs
    }

    fun parseOfficialSchedule(rawJson: String, nowMs: Long = System.currentTimeMillis()): List<TvProgram> {
        if (rawJson.isBlank()) return emptyList()
        return BrandScheduleParser.parse(rawJson, nowMs)
    }

    fun fetchXmlTv(url: String): List<TvProgram> {
        validateHttpUrlOrThrow(url, source = "XMLTV")
        return GuideHttpClient.request(url) { stream -> parse(stream) }
    }

    fun parse(input: InputStream): List<TvProgram> = XmlTvParser.parse(input)

    /**
     * Intenta XMLTV. Si falla o queda vacío, usa fuente por defecto.
     */
    private fun fetchXmlTvWithFallback(url: String): List<TvProgram> {
        val xmlPrograms = safeRequest("lectura XMLTV (${describeSource(url)})") {
            fetchXmlTv(url)
        }.orEmpty()

        if (xmlPrograms.isNotEmpty()) return xmlPrograms

        logWarning("Source ${describeSource(url)} no devolvió programas; fallback a DEFAULT_URL")
        return fetchFromDefaultSafely()
    }

    private fun fetchFromDefaultSafely(): List<TvProgram> {
        val fallback = DEFAULT_URL.trim()
        if (fallback.isBlank()) {
            logWarning("DEFAULT_URL está en blanco; no hay fallback disponible")
            return emptyList()
        }

        return safeRequest("fallback DEFAULT_URL (${describeSource(fallback)})") {
            when {
                isFutvWebsite(fallback) -> fetchFutvWebsite(fallback)
                isOpaWebsite(fallback) -> fetchOpaWebsite(fallback)
                isOfficialApi(fallback) -> fetchOfficialApi(fallback)
                else -> fetchXmlTv(fallback)
            }
        }.orEmpty()
    }

    private fun isOfficialApi(url: String): Boolean {
        val parsed = parseUrlOrNull(url) ?: return false
        val host = parsed.host.orEmpty().lowercase(Locale.ROOT)
        val path = parsed.path.orEmpty().lowercase(Locale.ROOT).trimEnd('/')

        return DEFAULT_URL.isNotBlank() &&
                parsed.protocol.equals("https", ignoreCase = true) &&
                host.isNotEmpty() &&
                host in OFFICIAL_HOSTS &&
                (path == OFFICIAL_SCHEDULE_PATH || path.startsWith("$OFFICIAL_SCHEDULE_PATH/"))
    }

    private fun isFutvWebsite(url: String): Boolean {
        if (BuildConfig.BRAND_KEY != BRAND_FUTV) return false
        val parsed = parseUrlOrNull(url) ?: return false
        val path = parsed.path.orEmpty().trimEnd('/')

        return parsed.protocol.equals("https", ignoreCase = true) &&
                parsed.host.equals(FUTV_HOST, ignoreCase = true) &&
                (path.isEmpty() || path == FUTV_WEEKLY_PATH)
    }

    private fun isOpaWebsite(url: String): Boolean {
        if (BuildConfig.BRAND_KEY != BRAND_OPA) return false
        val parsed = parseUrlOrNull(url) ?: return false
        val path = parsed.path.orEmpty().trimEnd('/')

        return parsed.protocol.equals("https", ignoreCase = true) &&
                parsed.host.equals(OPA_HOST, ignoreCase = true) &&
                path.equals(OPA_SCHEDULE_PATH, ignoreCase = true)
    }

    private fun parseUrlOrNull(url: String): URL? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        return try {
            URL(trimmed)
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeUrl(input: String, fallback: String): String =
        input.trim().ifBlank { fallback.trim() }

    private fun validateHttpUrlOrThrow(url: String, source: String) {
        val parsed = parseUrlOrNull(url) ?: error("URL inválida para $source")
        val protocol = parsed.protocol.orEmpty().lowercase(Locale.ROOT)
        require(protocol == "http" || protocol == "https") {
            "Protocolo no soportado para $source: ${parsed.protocol}"
        }
        require(parsed.host.orEmpty().isNotBlank()) { "URL sin host para $source" }
    }

    private fun validateHttpsUrlOrThrow(url: String, source: String) {
        val parsed = parseUrlOrNull(url) ?: error("URL inválida para $source")
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Se requiere HTTPS para $source"
        }
        require(parsed.host.orEmpty().isNotBlank()) { "URL sin host para $source" }
    }

    // No loguear query/credenciales.
    private fun describeSource(url: String): String {
        val parsed = parseUrlOrNull(url) ?: return "(URL inválida)"
        return "${parsed.protocol}://${parsed.host}${parsed.path}"
    }

    private fun redactUrls(text: String): String =
        if (text.isEmpty()) text else URL_IN_TEXT.replace(text) { match -> describeSource(match.value) }

    /**
     * Resume la cadena de causas sin stack trace ni URLs completas
     * (los mensajes de IOException suelen incluir la URL con su query).
     */
    private fun describeError(error: Throwable): String {
        val parts = ArrayList<String>(MAX_ERROR_CHAIN)
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_ERROR_CHAIN) {
            val typeName = current.javaClass.simpleName.ifEmpty { current.javaClass.name }
            val message = redactUrls(current.message.orEmpty()).take(MAX_LOG_MESSAGE_LENGTH)
            parts.add(if (message.isEmpty()) typeName else "$typeName: $message")
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
        return parts.joinToString(" <- ")
    }

    private fun isCleartextBlockedSafely(error: Throwable): Boolean =
        try {
            isCleartextBlocked(error)
        } catch (e: Exception) {
            false
        }

    /**
     * Wrapper robusto para requests:
     * - Re-lanza cancelaciones (coroutines) e interrupciones (restaurando el flag).
     * - Trata cleartext bloqueado como fatal/relevante.
     * - Registra resto y devuelve null.
     */
    private inline fun <T> safeRequest(label: String, block: () -> T): T? {
        return try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (oom: OutOfMemoryError) {
            logWarning("Memoria insuficiente durante $label", oom)
            null
        } catch (sec: SecurityException) {
            logWarning("Error de seguridad durante $label", sec)
            null
        } catch (error: Throwable) {
            if (isCleartextBlockedSafely(error)) throw error
            logWarning("Falló $label", error)
            null
        }
    }

    private fun logWarning(message: String, error: Throwable? = null) {
        try {
            if (error != null) {
                DiagnosticTracker.log("GUIDE_SOURCE_FAILED", "$message: ${describeError(error)}")
            }
            Log.w(TAG, if (error == null) message else "$message: ${describeError(error)}")
        } catch (e: Exception) {
            // El registro nunca debe interrumpir el flujo de la guía.
        }
    }
}
