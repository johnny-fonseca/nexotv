@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status






import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.*
import android.animation.ValueAnimator
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import java.util.Locale

internal fun MainActivity.scheduleHideStatus(delayMs: Long) {
    handler.removeCallbacks(hideStatus)
    handler.postDelayed(hideStatus, delayMs)
}
internal fun MainActivity.retryFromStatus() {
    recoveryController.retryFromStatus()
}

internal fun MainActivity.clearRetryStarting() {
    recoveryController.clearRetryStarting()
}

internal fun MainActivity.openConnectionSettings() {
    for (action in listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            startActivity(Intent(action))
            return
        } catch (_: Exception) {
        }
    }
    renderPlayerStatus(null)
    showDiagnostics()
}

internal fun MainActivity.showPlaybackPanel() {
    if (isDestroyed || !isPlayerInfoInitialized) return
    diagnosticsVisible = false
    playbackPanelVisible = true
    status.visibility = View.GONE
    handler.removeCallbacks(hideStatus)
    refreshPlayerInfo()
    clockTicker.run()
    if (isPlayerInitialized && !player.playWhenReady) revealRecordingIndicator()
    else if (isPlayerInitialized) updateRecordingIndicator()
    if (isPlayerInitialized && !player.isPlaying) scheduleHideStatus(integerMs(R.integer.tv_paused_info_refresh_ms))
    else hideStatusAfterDelay()
}

private fun MainActivity.resetStatusPanelTransform() {
    status.alpha = 1f
    status.scaleX = 1f
    status.scaleY = 1f
    status.translationY = 0f
}
internal fun MainActivity.hideStatusAfterDelay() {
    scheduleHideStatus(panelTimeoutSeconds() * 1_000L)
}

internal fun MainActivity.resetStallCounts() {
    frozenSamples = 0
    bufferingSamples = 0
    lastPositionMs = -1L
}

internal fun MainActivity.runStatusTimeout() {
    val playerPaused = isPlayerInitialized && !player.isPlaying
    if (playbackPanelVisible && isPlayerInfoInitialized) {
        if (playerPaused) {
            refreshPlayerInfo()
            scheduleHideStatus(integerMs(R.integer.tv_paused_info_refresh_ms))
        } else {
            playbackPanelVisible = false
            playerInfo.hideInfo()
        }
        return
    }
    when {
        diagnosticsVisible && isPlayerInitialized -> {
            setStatusText(channelDetails())
            scheduleHideStatus(STATUS_DIAGNOSTICS_REFRESH_MS)
        }
        playbackPanelVisible && playerPaused -> {
            refreshPlayerInfo()
            scheduleHideStatus(integerMs(R.integer.tv_paused_info_refresh_ms))
        }
        else -> {
            playbackPanelVisible = false
            if (isPlayerInfoInitialized) playerInfo.hideInfo()
            fadeOutStatusPanel()
        }
    }
}

internal fun MainActivity.handlePlayerNetworkLost() {
    if (networkWasLost) return
    hotspotStabilizer.onNetworkLost()
    networkWasLost = true
    networkLostAtElapsedMs = SystemClock.elapsedRealtime()
    SessionReport.onNetworkLost()
    recoveryReason = "sin conexión de red"
    DiagnosticTracker.log("NETWORK_LOST", "Pérdida de conectividad de red detectada por el sistema")
    handler.post {
        if (isFinishing || isDestroyed) return@post
        // Una reanudación pendiente ya no aplica: la red volvió a caer.
        handler.removeCallbacks(networkResumeTask)
        networkResumePending = false
        reconnectTask?.let(handler::removeCallbacks)
        reconnectTask = null
        handler.removeCallbacks(retryCountdown)
        reconnectStatusTask?.let(handler::removeCallbacks)
        reconnectStatusTask = null
        retryPending = false
        resetStallCounts()
        renderClassifiedConnectionFailure()
    }
}

/**
 * Descarta el estado de "red perdida" cuando la app vuelve a iniciar el stream por decisión
 * propia (reintento manual, reanudación desde el menú o restauración tras `onStop`).
 *
 * Sin esto, un `networkWasLost` que Android nunca cierra deja `recoverStream()` bloqueado
 * para siempre, incluso después de un reintento manual correcto.
 */
internal fun MainActivity.clearNetworkLostState(reason: String) {
    if (!networkWasLost) return
    networkWasLost = false
    // Cierra la ventana de corte del estabilizador sin dejarla abierta para siempre.
    hotspotStabilizer.onNetworkAvailable()
    DiagnosticTracker.log("NETWORK_LOST_CLEARED", "Se descarta el estado de red perdida ($reason)")
}

/**
 * Comprueba la red por cuenta propia cuando Android no avisa del regreso.
 *
 * El hotspot puede volver a funcionar sin un `onCapabilitiesChanged` (el Wi-Fi nunca perdió la
 * validación). Mientras `networkWasLost` siga activo, `recoverStream()` no programa nada, así
 * que la app se quedaría mostrando "sin conexión" indefinidamente.
 */
internal fun MainActivity.recheckNetworkLoss() {
    if (!networkWasLost) return
    val now = SystemClock.elapsedRealtime()
    val lostForMs = (now - networkLostAtElapsedMs).coerceAtLeast(0L)
    val validated = hasValidatedInternet()
    if (NetworkTuning.shouldRecheckLostState(lostForMs, validated)) {
        DiagnosticTracker.log(
            "NETWORK_RECHECK",
            "Internet validado tras ${lostForMs}ms sin evento del sistema; se reevalúa la conexión",
        )
        handlePlayerNetworkAvailable()
        return
    }
    // Bloque 1.6: la validación del sistema puede fallar aunque el stream funcione. Con
    // transporte e INTERNET se usa el reproductor como sonda, espaciada, sin ICMP.
    val transport = hasInternetTransport()
    if (PlayerTuning.unvalidatedRetry && NetworkTuning.shouldProbeUnvalidated(
            lostForMs = lostForMs,
            transportConnected = transport,
            internetCapability = transport,
            msSinceLastProbe = now - lastUnvalidatedProbeAtMs,
        )
    ) {
        lastUnvalidatedProbeAtMs = now
        DiagnosticTracker.log(
            "NETWORK_RECHECK_UNVALIDATED",
            "Transporte e INTERNET sin VALIDATED tras ${lostForMs}ms; sonda de reproducción",
        )
        recoverStream("red sin validar por el sistema")
    }
}

internal fun MainActivity.handlePlayerNetworkAvailable(networkChanged: Boolean = false) {
    val wasLost = networkWasLost
    hotspotStabilizer.onNetworkAvailable()
    SessionReport.onNetworkAvailable()
    if (networkChanged && !wasLost) {
        networkWasLost = true
        networkLostAtElapsedMs = SystemClock.elapsedRealtime()
        recoveryReason = "cambió la red de reproducción"
        DiagnosticTracker.log("NETWORK_CHANGED", "La red predeterminada validada cambió durante la reproducción")
    }
    val shouldReconnect = wasLost || networkChanged
    val elapsed = if (wasLost) (SystemClock.elapsedRealtime() - networkLostAtElapsedMs).coerceAtLeast(0L) else 0L
    DiagnosticTracker.log(
        "NETWORK_AVAILABLE",
        "Conectividad de red restablecida" + if (wasLost) " tras ${elapsed}ms" else "",
    )
    if (!shouldReconnect) return
    handler.post {
        if (isFinishing || isDestroyed) return@post
        if (selectedPlayerStatus is PlayerStatus.Offline || selectedPlayerStatus is PlayerStatus.NoSignal) renderPlayerStatus(null)
        reconnectOnNetworkReturn()
    }
}

