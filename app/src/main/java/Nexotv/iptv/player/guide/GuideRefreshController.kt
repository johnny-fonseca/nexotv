// GuideRefreshController.kt

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.*

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import Nexotv.iptv.player.MainActivity.Companion.DAY_MS
import Nexotv.iptv.player.MainActivity.Companion.KEY_EPG_PAST_DAYS
import Nexotv.iptv.player.MainActivity.Companion.KEY_EPG_URL
import Nexotv.iptv.player.MainActivity.Companion.PREFS
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val EPG_LEGACY_HOST = "epgshare01.online"
private const val FUTV_HOME_URL = "https://futvcr.com/"
private const val FUTV_HOME_URL_WITHOUT_SLASH = "https://futvcr.com"
private const val FUTV_LEGACY_WEEKLY_URL = "https://futvcr.com/programacion/"
private const val FUTV_LEGACY_WEEKLY_URL_WITHOUT_SLASH = "https://futvcr.com/programacion"
private const val EPG_DEFAULT_PAST_DAYS = 7
private const val EPG_MAX_PAST_DAYS = 3_650
private const val EPG_URL_MAX_CHARS = 2_048
private const val KEY_EPG_COVERAGE_REFRESH_DATE = "epg_coverage_refresh_date"
private const val KEY_EPG_COVERAGE_REFRESH_FAILURE_AT = "epg_coverage_refresh_failure_at"
private const val EPG_COVERAGE_RETRY_DELAY_MS = 15 * 60_000L
private const val GUIDE_REFRESH_DEBOUNCE_MS = 1_500L
private const val EPG_ERROR_DETAIL_MAX = 140
private const val EPG_STORED_ERROR_MAX = 300

private val MSG_REFRESHING = "Actualizando guía de ${BuildConfig.BRAND_NAME}…"
private val MSG_FETCH_EMPTY = "No se pudo obtener la programación de ${BuildConfig.BRAND_NAME}"
private const val MSG_NO_VALID_PROGRAMS = "La guía no contiene programas válidos"
private const val MSG_GENERIC_ERROR = "Error de actualización"
private const val MSG_NETWORK_ERROR = "error de red"
private val MSG_DOWNLOADED_EMPTY = "La guía se descargó, pero no encontró programas de ${BuildConfig.BRAND_NAME}"

private val WHITESPACE_REGEX = Regex("\\s+")
private val GUIDE_URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
private val COSTA_RICA_TIME_ZONE = TimeZone.getTimeZone("GMT-06:00")

private fun guideLog(tag: String, detail: String) {
    try {
        DiagnosticTracker.log(tag, detail)
    } catch (ignored: Exception) {
    }
}

private inline fun guideSafely(step: String, block: () -> Unit) {
    try {
        block()
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        guideLog("GUIDE_STEP_FAILED", "$step: ${failure.javaClass.simpleName}")
    }
}

private fun SharedPreferences.guidePrefInt(key: String, default: Int): Int =
    try {
        getInt(key, default)
    } catch (ignored: Exception) {
        default
    }

private fun SharedPreferences.guidePrefLong(key: String, default: Long): Long =
    try {
        getLong(key, default)
    } catch (ignored: Exception) {
        default
    }

private fun SharedPreferences.guidePrefString(key: String): String? =
    try {
        getString(key, null)
    } catch (ignored: Exception) {
        null
    }

private inline fun guidePrefEdit(prefs: SharedPreferences, action: SharedPreferences.Editor.() -> Unit) {
    try {
        prefs.edit { action() }
    } catch (failure: Exception) {
        guideLog("GUIDE_PREFS_WRITE_FAILED", failure.javaClass.simpleName)
    }
}

internal fun costaRicaCalendar(offsetDays: Int = 0, fromMs: Long = System.currentTimeMillis()): Calendar =
    Calendar.getInstance(COSTA_RICA_TIME_ZONE, Locale.ROOT).apply {
        timeInMillis = fromMs
        add(Calendar.DAY_OF_YEAR, offsetDays)
    }

internal fun costaRicaDateKey(calendar: Calendar): Int =
    calendar.get(Calendar.YEAR) * 10_000 + (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DAY_OF_MONTH)

internal fun costaRicaDateKey(atMs: Long = System.currentTimeMillis()): Int =
    costaRicaDateKey(costaRicaCalendar(fromMs = atMs))

internal fun MainActivity.shouldRefreshGuide(): Boolean {
    val now = System.currentTimeMillis()
    val programs = guidePrograms
    if (programs.isEmpty() || programs.none { it.endMs > now }) return true

    val lastGuideUpdate = try {
        mainPrefs.safeLong(MainActivity.KEY_EPG_UPDATED, 0L)
    } catch (ignored: Exception) {
        0L
    }
    val epgHours = try {
        mainPrefs.safeInt(
            MainActivity.KEY_EPG_INTERVAL_HOURS,
            MainActivity.DEFAULT_EPG_INTERVAL_HOURS,
        )
    } catch (ignored: Exception) {
        MainActivity.DEFAULT_EPG_INTERVAL_HOURS
    }

    if (epgHours == -1) return true
    if (epgHours <= 0) return false
    val intervalMs = epgHours.toLong() * MainActivity.HOUR_MS
    val elapsedMs = now - lastGuideUpdate
    return elapsedMs < 0L || elapsedMs > intervalMs
}

private fun guideCoversTodayAndUpcoming(programs: List<TvProgram>, todayMs: Long): Boolean {
    val day = costaRicaCalendar(fromMs = todayMs).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startMs = day.timeInMillis
    day.add(Calendar.DAY_OF_YEAR, 1)
    val endMs = day.timeInMillis
    return programs.any { it.startMs < endMs && it.endMs > startMs } && programs.any { it.endMs > todayMs }
}

private fun MainActivity.isGuideRefreshStale(requestGeneration: Long): Boolean =
    try {
        Thread.currentThread().isInterrupted ||
            !guideSession.guideRefreshLifecycle.isCurrent(requestGeneration) ||
            !isUiAvailable()
    } catch (ignored: Exception) {
        true
    }

private fun MainActivity.releaseGuideRefreshIfCurrent(requestGeneration: Long) {
    val current = try {
        guideSession.guideRefreshLifecycle.isCurrent(requestGeneration)
    } catch (ignored: Exception) {
        false
    }
    if (current) {
        guideLoading = false
        guideRefreshTask = null
    }
}

@UnstableApi
private fun MainActivity.isPlaybackActive(): Boolean =
    try {
        isPlayerInitialized && player.playWhenReady
    } catch (ignored: Exception) {
        false
    }

private fun MainActivity.isGuideExecutorShutdown(): Boolean =
    try {
        guideRefreshExecutor.isShutdown
    } catch (ignored: Exception) {
        false
    }

private fun isAllowedGuideUrl(url: String): Boolean {
    if (url.isEmpty() || url.length > EPG_URL_MAX_CHARS) return false
    if (url.any { Character.isISOControl(it) }) return false
    val parsed = try {
        Uri.parse(url)
    } catch (ignored: Exception) {
        return false
    }
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    return (scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()
}

private fun resolveGuideUrl(storedUrl: String?): String {
    val candidate = storedUrl.orEmpty().trim()
    val useDefault = if (BuildConfig.BRAND_KEY == "futv") {
        candidate.isEmpty() ||
            candidate == FUTV_HOME_URL ||
            candidate == FUTV_HOME_URL_WITHOUT_SLASH ||
            candidate == FUTV_LEGACY_WEEKLY_URL ||
            candidate == FUTV_LEGACY_WEEKLY_URL_WITHOUT_SLASH
    } else {
        candidate.isEmpty() || candidate.contains(EPG_LEGACY_HOST, ignoreCase = true)
    }
    return if (useDefault || !isAllowedGuideUrl(candidate)) XmlTvGuide.DEFAULT_URL else candidate
}

private fun fetchGuideSafely(url: String, propagateFailure: Boolean = false): List<TvProgram> = try {
    XmlTvGuide.fetch(url)
} catch (failure: Exception) {
    if (failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
    val detail = "${failure.javaClass.simpleName}: ${summarizeError(failure)}"
    guideLog("GUIDE_FETCH_FAILED", detail)
    runCatching { android.util.Log.w("GuideRefresh", detail) }
    if (propagateFailure || isCleartextBlocked(failure)) throw failure
    emptyList()
}

private fun downloadPrograms(url: String): List<TvProgram> {
    var fetched = fetchGuideSafely(url, propagateFailure = url == XmlTvGuide.DEFAULT_URL)
    if (fetched.isEmpty() && url != XmlTvGuide.DEFAULT_URL) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        fetched = fetchGuideSafely(XmlTvGuide.DEFAULT_URL, propagateFailure = true)
    }
    check(fetched.isNotEmpty()) { MSG_FETCH_EMPTY }
    return fetched
}

private fun filterPrograms(fetched: List<TvProgram>, pastDays: Int, now: Long): List<TvProgram> {
    val safeDays = pastDays.coerceIn(0, EPG_MAX_PAST_DAYS)
    val cutoff = if (safeDays == 0) now else now - safeDays.toLong() * DAY_MS
    val programs = fetched.filter { it.endMs >= cutoff }
        .ifEmpty { fetched.filter { it.endMs >= now - DAY_MS } }
        .ifEmpty { fetched }
    check(programs.isNotEmpty()) { MSG_NO_VALID_PROGRAMS }
    return programs
}

private fun sanitizeMessage(raw: String?, maxChars: Int): String? {
    if (raw == null || maxChars <= 0) return null
    val collapsed = raw
        .replace(WHITESPACE_REGEX, " ")
        .filterNot { Character.isISOControl(it) }
        .trim()
    if (collapsed.isEmpty()) return null
    var end = minOf(collapsed.length, maxChars)
    if (end < collapsed.length && end > 0 && Character.isHighSurrogate(collapsed[end - 1])) end--
    return collapsed.substring(0, end).trimEnd().takeIf(String::isNotEmpty)
}

private fun summarizeError(failure: Throwable): String =
    sanitizeMessage(failure.message?.let(::redactGuideUrls), EPG_ERROR_DETAIL_MAX)
        ?: sanitizeMessage(failure.cause?.message?.let(::redactGuideUrls), EPG_ERROR_DETAIL_MAX)
        ?: MSG_NETWORK_ERROR

private fun redactGuideUrls(message: String): String = GUIDE_URL_IN_TEXT.replace(message) { match ->
    val uri = runCatching { Uri.parse(match.value) }.getOrNull()
    val scheme = uri?.scheme ?: return@replace "[URL]"
    val host = uri.host ?: return@replace "[URL]"
    "$scheme://$host${uri.path.orEmpty()}"
}

private fun storedErrorMessage(failure: Throwable): String =
    failure.message?.let(::redactGuideUrls)?.let { sanitizeMessage(it, EPG_STORED_ERROR_MAX) }
        ?: MSG_GENERIC_ERROR

/** Refresh a missing daily guide at most once successfully per Costa Rica date. */
@UnstableApi
internal fun MainActivity.refreshGuideIfUpcomingCostaRicaWeekMissing(todayDateKey: Int) {
    val now = System.currentTimeMillis()
    if (todayDateKey != costaRicaDateKey(now)) return
    if (guideLoading) return
    if (guideCoversTodayAndUpcoming(guidePrograms, now)) return

    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    if (prefs.guidePrefInt(KEY_EPG_COVERAGE_REFRESH_DATE, 0) == todayDateKey) return
    val sinceFailureMs = now - prefs.guidePrefLong(KEY_EPG_COVERAGE_REFRESH_FAILURE_AT, 0L)
    if (sinceFailureMs in 0L until EPG_COVERAGE_RETRY_DELAY_MS) return
    refreshGuide(silent = true, coverageDateKey = todayDateKey, deferWhilePlaying = true)
}

@UnstableApi
internal fun MainActivity.refreshGuide(
    silent: Boolean = false,
    coverageDateKey: Int? = null,
    deferWhilePlaying: Boolean = true,
) {
    val requestedAt = System.currentTimeMillis()

    val activeTask = guideRefreshTask
    if (guideLoading && activeTask != null && activeTask.isDone) {
        guideLoading = false
        guideRefreshTask = null
    }
    if (guideLoading) {
        guideLog("GUIDE_REFRESH_SKIPPED", "Ya existe una actualización de guía activa")
        return
    }
    if (isGuideExecutorShutdown()) {
        guideLog("GUIDE_REFRESH_SKIPPED", "Ejecutor de guía detenido")
        return
    }
    if ((requestedAt - guideRefreshLastRequestAtMs) in 0L until GUIDE_REFRESH_DEBOUNCE_MS) {
        guideLog("GUIDE_REFRESH_DEBOUNCED", "Solicitud de actualización repetida ignorada")
        return
    }
    guideRefreshLastRequestAtMs = requestedAt
    guideLog(
        "GUIDE_REFRESH_REQUESTED",
        if (coverageDateKey == null) "manual/normal" else "cobertura diaria $coverageDateKey",
    )
    if (deferWhilePlaying && isPlaybackActive()) {
        guideRefreshDeferred = true
        if (coverageDateKey != null) deferredGuideCoverageDateKey = coverageDateKey
        guideLog("GUIDE_REFRESH_DEFERRED", "Actualización automática de guía aplazada durante reproducción")
        if (!silent) {
            runOnUiThread {
                if (isUiAvailable()) guideSafely("toastDeferred") {
                    showTvToast("La guía se actualizará al pausar la reproducción")
                }
            }
        }
        return
    }

    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val storedUrl = prefs.guidePrefString(KEY_EPG_URL)
    val url = resolveGuideUrl(storedUrl)
    if (storedUrl != url) {
        guidePrefEdit(prefs) { putString(KEY_EPG_URL, url) }
    }

    val requestGeneration = try {
        guideSession.guideRefreshLifecycle.begin()
    } catch (failure: Exception) {
        guideLog("GUIDE_REFRESH_ABORTED", failure.javaClass.simpleName)
        return
    }
    guideLoading = true
    guideLoadError = null

    runOnUiThread {
        if (!isUiAvailable()) return@runOnUiThread
        guideSafely("refreshVisibleGuide") { refreshVisibleGuide?.invoke() }
        if (!silent) guideSafely("toastRefreshing") { showTvToast(MSG_REFRESHING, long = true) }
    }

    val task = try {
        guideRefreshExecutor.submit {
            executeGuideRefresh(url, prefs, requestGeneration, silent, coverageDateKey)
        }
    } catch (failure: Exception) {
        guideLog("GUIDE_REFRESH_REJECTED", failure.javaClass.simpleName)
        null
    }

    if (task == null) {
        guideLoading = false
        guideRefreshTask = null
        guideLoadError = MSG_GENERIC_ERROR
        runOnUiThread {
            if (isUiAvailable()) guideSafely("refreshVisibleGuide") { refreshVisibleGuide?.invoke() }
        }
        return
    }

    guideRefreshTask = task
    if (task.isDone && guideRefreshTask === task) guideRefreshTask = null
}

@UnstableApi
private fun MainActivity.executeGuideRefresh(
    url: String,
    prefs: SharedPreferences,
    requestGeneration: Long,
    silent: Boolean,
    coverageDateKey: Int?,
): Result<List<TvProgram>> =
    runCatching {
        val fetched = downloadPrograms(url)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val pastDays = prefs.guidePrefInt(KEY_EPG_PAST_DAYS, EPG_DEFAULT_PAST_DAYS)
        filterPrograms(fetched, pastDays, System.currentTimeMillis())
    }.onSuccess { programs ->
        guideSafely("applyGuideSuccess") {
            applyGuideSuccess(programs, prefs, requestGeneration, silent, coverageDateKey)
        }
    }.onFailure { failure ->
        guideSafely("applyGuideFailure") {
            applyGuideFailure(failure, prefs, requestGeneration, silent, coverageDateKey)
        }
    }

@UnstableApi
private fun MainActivity.applyGuideSuccess(
    programs: List<TvProgram>,
    prefs: SharedPreferences,
    requestGeneration: Long,
    silent: Boolean,
    coverageDateKey: Int?,
) {
    if (isGuideRefreshStale(requestGeneration)) {
        releaseGuideRefreshIfCurrent(requestGeneration)
        return
    }
    guideLoading = false
    guideRefreshTask = null
    guideLoadError = null
    if (coverageDateKey != null) {
        guidePrefEdit(prefs) {
            putInt(KEY_EPG_COVERAGE_REFRESH_DATE, coverageDateKey)
            remove(KEY_EPG_COVERAGE_REFRESH_FAILURE_AT)
        }
    }

    guideSafely("saveCachedIfChanged") { guideProgramStore.saveCachedIfChanged(programs) }
    if (isGuideRefreshStale(requestGeneration)) return
    guideSafely("refreshCurrentProgram") { HomeScreenChannel.refreshCurrentProgram(this) }
    guideSafely("saveAutomaticBackup") { saveAutomaticBackup() }

    runOnUiThread {
        if (isGuideRefreshStale(requestGeneration)) return@runOnUiThread
        if (programs.isNotEmpty()) guidePrograms = programs
        guideSafely("refreshVisibleGuide") { refreshVisibleGuide?.invoke() }
        guideSafely("refreshVisiblePanel") { refreshVisiblePanel() }
        if (!silent) {
            guideSafely("toastUpdated") {
                showTvToast(
                    if (programs.isEmpty()) MSG_DOWNLOADED_EMPTY
                    else "Guía actualizada: ${programs.size} programas",
                    long = true,
                )
            }
        }
        guideSafely("refreshDeferredGuideIfSafe") { refreshDeferredGuideIfSafe() }
    }
}

@UnstableApi
private fun MainActivity.applyGuideFailure(
    failure: Throwable,
    prefs: SharedPreferences,
    requestGeneration: Long,
    silent: Boolean,
    coverageDateKey: Int?,
) {
    if (isGuideRefreshStale(requestGeneration)) {
        releaseGuideRefreshIfCurrent(requestGeneration)
        return
    }
    guideLoading = false
    guideRefreshTask = null
    val cleartextMessage = try {
        cleartextErrorMessageResId(failure)?.let { getString(it) }
    } catch (ignored: Exception) {
        null
    }
    val tlsMessage = try {
        tlsErrorMessageResId(failure)?.let { getString(it) }
    } catch (ignored: Exception) {
        null
    }
    guideLoadError = cleartextMessage ?: tlsMessage ?: storedErrorMessage(failure)
    if (coverageDateKey != null) {
        guidePrefEdit(prefs) { putLong(KEY_EPG_COVERAGE_REFRESH_FAILURE_AT, System.currentTimeMillis()) }
    }

    runOnUiThread {
        if (isGuideRefreshStale(requestGeneration)) return@runOnUiThread
        guideSafely("refreshVisibleGuide") { refreshVisibleGuide?.invoke() }
        if (!silent) {
            guideSafely("toastFailed") {
                if (cleartextMessage != null) {
                    showTvToast(getString(R.string.guide_cleartext_refresh_failed, cleartextMessage), long = true)
                } else if (tlsMessage != null) {
                    showTvToast(tlsMessage, long = true)
                } else {
                    showTvToast("No se pudo actualizar la guía: ${summarizeError(failure)}", long = true)
                }
            }
        }
        guideSafely("refreshDeferredGuideIfSafe") { refreshDeferredGuideIfSafe() }
    }
}

@UnstableApi
internal fun MainActivity.cancelGuideRefresh() {
    guideSafely("invalidateLifecycle") { guideSession.guideRefreshLifecycle.invalidate() }
    guideSafely("cancelTask") { guideRefreshTask?.cancel(true) }
    guideRefreshTask = null
    guideRefreshLastRequestAtMs = 0L
    guideSafely("shutdownExecutor") { guideRefreshExecutor.shutdownNow() }
    guideLoading = false
    guideRefreshDeferred = false
    deferredGuideCoverageDateKey = null
}

@UnstableApi
internal fun MainActivity.refreshDeferredGuideIfSafe() {
    if (!guideRefreshDeferred || guideLoading || isPlaybackActive() || !isUiAvailable()) return
    val coverageDateKey = deferredGuideCoverageDateKey
    guideRefreshDeferred = false
    deferredGuideCoverageDateKey = null
    guideRefreshLastRequestAtMs = 0L
    refreshGuide(silent = true, coverageDateKey = coverageDateKey)
}
