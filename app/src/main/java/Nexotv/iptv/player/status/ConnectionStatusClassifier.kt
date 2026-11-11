@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status
import Nexotv.iptv.player.activity.*




import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.*
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.net.NetworkCapabilities
import android.provider.Settings

internal fun MainActivity.statusPrefs(): SharedPreferences = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)

internal fun MainActivity.panelTimeoutSeconds(): Int =
    statusPrefs().safeInt(MainActivity.KEY_PANEL_TIMEOUT, 5).coerceIn(2, 10)

internal fun MainActivity.panelTextSizeSp(): Int =
    statusPrefs().safeInt(MainActivity.KEY_PANEL_TEXT_SIZE, resources.getInteger(R.integer.tv_panel_text_max_sp)).coerceIn(15, 24)

internal fun MainActivity.integerMs(resId: Int): Long = resources.getInteger(resId).toLong()

internal fun MainActivity.registerNetworkCallback() {
    try {
        networkController.register()
    } catch (e: Exception) {
        DiagnosticTracker.log("NETWORK_CALLBACK_ERROR", "No se pudo registrar el monitor de red: ${e.message ?: e.javaClass.simpleName}")
    }
}

internal fun MainActivity.hasValidatedInternet(): Boolean = try {
    // Se exige validación del sistema para no tratar redes cautivas o locales como acceso a internet.
    val network = connectivity.activeNetwork
    val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
    capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (_: Exception) {
    false
}

/**
 * Hay transporte con capacidad INTERNET aunque Android todavía no lo marque VALIDATED.
 *
 * La sonda de validación del sistema puede fallar en un enlace lento o detrás del proxy DNS
 * del teléfono. Esta condición permite usar el propio reproductor como sonda (bloque 1.6),
 * sin reintroducir `StreamReachabilityProbe` ni usar ICMP.
 */
internal fun MainActivity.hasInternetTransport(): Boolean = try {
    val network = connectivity.activeNetwork
    val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
    capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
} catch (_: Exception) {
    false
}

/**
 * ¿Se puede intentar reproducir ahora mismo?
 *
 * Con red validada, sí. Sin validación, solo si hay transporte e INTERNET y la bandera del
 * bloque 1.6 está activa; el HUD sigue mostrando "sin validación" para no ocultar el estado.
 */
internal fun MainActivity.networkAllowsPlaybackRetry(): Boolean =
    hasValidatedInternet() || (PlayerTuning.unvalidatedRetry && hasInternetTransport())


internal fun MainActivity.renderClassifiedConnectionFailure(failure: Throwable? = null) {
    val retry: () -> Unit = { retryFromStatus() }
    val settings: () -> Unit = { openConnectionSettings() }
    val cleartextHelpResId = failure?.let { cleartextErrorMessageResId(it, hasCustomStreamHeaders()) }
    val internetValidated = hasValidatedInternet()
    val hasHttpResponseError = failure?.let { error ->
        generateSequence(error) { it.cause }
            .take(16)
            .any { it.javaClass.simpleName == "InvalidResponseCodeException" }
    } == true
    val issue = classifyConnectionIssue(
        networkLost = networkWasLost,
        internetValidated = internetValidated,
        cleartextBlocked = cleartextHelpResId != null,
        httpResponseError = hasHttpResponseError,
        playbackFailure = failure != null,
    )
    renderPlayerStatus(
        if (issue == ConnectionIssue.NO_VALIDATED_INTERNET) {
            PlayerStatus.Offline(retry, settings, issue)
        } else {
            PlayerStatus.NoSignal(retry, settings, issue, cleartextHelpResId)
        },
    )
}

internal fun MainActivity.unregisterNetworkCallback() {
    try {
        networkController.unregister()
    } catch (_: Exception) {
    }
}


