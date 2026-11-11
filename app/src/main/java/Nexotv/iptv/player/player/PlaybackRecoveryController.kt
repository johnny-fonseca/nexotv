package Nexotv.iptv.player.player


import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.status.*
import android.os.Handler
import android.os.SystemClock
import android.view.View
import androidx.media3.common.util.UnstableApi

internal interface PlaybackRecoveryHost {
    val recoveryStatusInitialized: Boolean
    val recoveryStatusView: View
    val recoveryActivityUnavailable: Boolean
    val recoveryPlayerInitialized: Boolean
    val recoveryPlayerPlaying: Boolean
    val recoveryRecordingPlayback: Boolean
    val retryStartTimeoutMs: Long
    fun setRecoveryStatusText(message: String)
    fun recoveryString(resId: Int, attemptText: String): String
    fun clearRecoveryStatus()
    fun startRecoveryStream()
    fun logRecoveryFailure(error: Exception)
    fun logStableRecovery()
}

/**
 * Owns the mutable retry state and scheduled recovery tasks for live playback.
 * Activity-facing properties remain as compatibility delegates during the
 * incremental migration of the existing playback extensions.
 */
@UnstableApi
internal class PlaybackRecoveryController(
    private val host: PlaybackRecoveryHost,
    private val handler: Handler,
) {
    var retryAttempts = 0
    var retryPending = false
    var retryAtMs = 0L
    var recoveryReason = "—"

    var retryResetTask: Runnable? = null
    var retryStarting = false
    var retryStartingTimeout: Runnable? = null
    var reconnectTask: Runnable? = null
    var reconnectStatusTask: Runnable? = null

    val retryCountdown = object : Runnable {
        override fun run() {
            if (!retryPending || !host.recoveryStatusInitialized || host.recoveryStatusView.visibility != View.VISIBLE) return
            val attemptText = if (retryAttempts <= MainActivity.MAX_RETRIES) {
                "$retryAttempts/${MainActivity.MAX_RETRIES}"
            } else {
                "$retryAttempts"
            }
            host.setRecoveryStatusText(host.recoveryString(R.string.player_status_retrying_attempt, attemptText))
            handler.postDelayed(this, RETRY_COUNTDOWN_TICK_MS)
        }
    }

    fun scheduleAt(delayMs: Long): Long = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(0L)

    /**
     * Espera antes del siguiente reinicio completo del stream: backoff exponencial mientras
     * queden intentos rápidos y reintento lento espaciado después, para que un corte
     * prolongado del hotspot se recupere solo sin martillar el enlace móvil.
     */
    fun backoffDelayMs(attempt: Int): Long =
        BufferSizes.streamRetryDelayMs(attempt, MainActivity.MAX_RETRIES).coerceAtLeast(0L)

    fun cancelRetryAttemptReset() {
        retryResetTask?.let(handler::removeCallbacks)
        retryResetTask = null
    }

    fun scheduleRetryAttemptReset() {
        cancelRetryAttemptReset()
        if (retryAttempts == 0 || host.recoveryRecordingPlayback) return
        val task = Runnable {
            if (host.recoveryPlayerInitialized && host.recoveryPlayerPlaying &&
                !retryPending && !host.recoveryRecordingPlayback
            ) {
                retryAttempts = 0
                host.logStableRecovery()
            }
            retryResetTask = null
        }
        retryResetTask = task
        handler.postDelayed(task, MainActivity.RETRY_STABLE_PLAYBACK_MS)
    }

    /**
     * Cancels recovery work before the player is released temporarily by onStop.
     * Retry counters and the last recovery reason intentionally remain available
     * for the player restoration path in onStart.
     */
    fun prepareForPlayerStop() {
        clearScheduledTasks()
    }

    fun clearScheduledTasks() {
        retryResetTask?.let(handler::removeCallbacks)
        retryResetTask = null
        retryStartingTimeout?.let(handler::removeCallbacks)
        retryStartingTimeout = null
        reconnectTask?.let(handler::removeCallbacks)
        reconnectTask = null
        reconnectStatusTask?.let(handler::removeCallbacks)
        reconnectStatusTask = null
        handler.removeCallbacks(retryCountdown)
        retryPending = false
        retryStarting = false
    }

    fun cancelPendingReconnects() {
        reconnectTask?.let(handler::removeCallbacks)
        reconnectTask = null
        handler.removeCallbacks(retryCountdown)
        reconnectStatusTask?.let(handler::removeCallbacks)
        reconnectStatusTask = null
        retryPending = false
    }

    fun retryFromStatus() {
        if (retryStarting || host.recoveryActivityUnavailable) return
        retryStarting = true
        retryStartingTimeout?.let(handler::removeCallbacks)
        retryStartingTimeout = Runnable {
            retryStarting = false
            retryStartingTimeout = null
        }.also { handler.postDelayed(it, host.retryStartTimeoutMs) }
        cancelPendingReconnects()
        host.clearRecoveryStatus()
        try {
            host.startRecoveryStream()
        } catch (error: Exception) {
            host.logRecoveryFailure(error)
            clearRetryStarting()
        }
    }

    fun clearRetryStarting() {
        retryStartingTimeout?.let(handler::removeCallbacks)
        retryStartingTimeout = null
        retryStarting = false
    }

    companion object {
        private const val RETRY_COUNTDOWN_TICK_MS = 500L
    }
}

