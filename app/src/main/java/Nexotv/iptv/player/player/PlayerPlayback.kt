

package Nexotv.iptv.player.player
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.status.*
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@UnstableApi
internal fun MainActivity.recoverStream(
    reason: String = "error de reproducción",
    restartPlayer: Boolean = false,
    connectionFailure: Throwable? = null,
) {
    if (recordingPlayback || isFinishing || !isPlayerInitialized) return
    val networkBlocked = networkWasLost && !networkAllowsPlaybackRetry()
    if (!shouldScheduleRecovery(networkResumePending, retryPending, networkBlocked)) {
        when {
            networkResumePending -> DiagnosticTracker.log(
                "RECOVERY_DEFERRED",
                "Hay una reanudación de red en curso; no se duplica: $reason",
            )
            networkBlocked -> {
                recoveryReason = reason
                DiagnosticTracker.log(
                    "RECOVERY_WAIT_NETWORK",
                    "Recuperación pausada hasta recuperar internet: $reason",
                )
            }
        }
        return
    }
    DiagnosticTracker.log("RECOVER", "Iniciando recuperación: $reason (intento ${retryAttempts + 1})")
    SessionReport.onRecoveryStarted()
    resetStallCounts()
    recoveryReason = reason
    renderClassifiedConnectionFailure(connectionFailure)
    retryPending = true
    retryAttempts++
    var delayMs = recoveryController.backoffDelayMs(retryAttempts)
    if (networkWasLost) {
        // Red con transporte pero sin VALIDATED: el reintento es la sonda, pero espaciado
        // para no convertir una validación lenta en una tormenta de reinicios.
        lastUnvalidatedProbeAtMs = SystemClock.elapsedRealtime()
        delayMs = maxOf(delayMs, PlayerTuning.UNVALIDATED_PROBE_INTERVAL_MS)
        DiagnosticTracker.log(
            "RECOVERY_UNVALIDATED",
            "Red con transporte pero sin validar; sonda de reproducción en ${delayMs}ms",
        )
    }
    DiagnosticTracker.log("RETRY_SCHEDULED", "Intento $retryAttempts en ${delayMs}ms; causa=$reason")
    retryAtMs = recoveryController.scheduleAt(delayMs)

    cancelPlaybackTask(reconnectStatusTask)
    val statusTask = Runnable { if (retryPending) renderPlayerStatus(PlayerStatus.Reconnecting) }
    reconnectStatusTask = statusTask
    handler.postDelayed(statusTask, resources.getInteger(R.integer.tv_status_reconnecting_delay_ms).toLong())
    handler.post(retryCountdown)

    cancelPlaybackTask(reconnectTask)
    val retryTask = Runnable {
        if (!retryPending) return@Runnable
        if (isFinishing || !isPlayerInitialized) {
            cancelScheduledPlaybackRecovery()
            return@Runnable
        }
        if (networkWasLost && !networkAllowsPlaybackRetry()) {
            cancelScheduledPlaybackRecovery()
            return@Runnable
        }
        cancelScheduledPlaybackRecovery()
        playbackGuarded("RETRY_EXECUTION") {
            if (restartPlayer) {
                rebuildPlayer("Recuperación de decodificador", resetRetryState = false)
            } else {
                val shouldResume = shouldResumeAfterRestart(player.playWhenReady, resumeAfterLifecycle)
                SessionReport.onFullReload()
                DiagnosticTracker.onFullReload()
                player.stop()
                player.seekToDefaultPosition()
                startStream(autoPlay = shouldResume)
            }
        }
    }
    reconnectTask = retryTask
    handler.postDelayed(retryTask, delayMs)
}

internal fun MainActivity.channelDetails(): String {
    if (!isPlayerInitialized) return ""
    val prefs = playbackPrefs()
    val now = System.currentTimeMillis()
    val dimensions = if (videoSize.width > 0) "${videoSize.width}×${videoSize.height}" else "Video"
    val rate = if (fps > 0f) " · ${fps.toInt()} FPS" else ""
    val videoFormat = player.videoFormat
    val bitrate = videoFormat?.bitrate?.takeIf { it > 0 }?.let { "${it / 1_000} kb/s declarado" } ?: "bitrate n/d"
    val videoCodec = videoFormat?.sampleMimeType?.substringAfter('/') ?: "video n/d"
    val audio = player.audioFormat?.let { format ->
        listOfNotNull(
            format.sampleMimeType?.substringAfter('/'),
            format.channelCount.takeIf { it > 0 }?.let { "$it ch" },
            format.sampleRate.takeIf { it > 0 }?.let { "${it}Hz" },
        ).joinToString(" ")
    }?.ifBlank { null } ?: "audio n/d"
    val buffer = "${player.totalBufferedDuration.coerceAtLeast(0) / 1_000}s buffer"
    val retry = when {
        retryPending -> {
            val remainingSeconds = ((retryAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1_000
            " · intento $retryAttempts/${MainActivity.MAX_RETRIES} en ${remainingSeconds}s: $recoveryReason"
        }
        recoveryReason == "—" -> ""
        else -> " · último fallo: $recoveryReason"
    }
    val bufferProfileName = when (playbackBufferProfile()) {
        BufferSizes.PROFILE_LARGE -> "Grande"
        BufferSizes.PROFILE_MEDIUM -> "Medio"
        BufferSizes.PROFILE_SMALL -> "Pequeño"
        else -> "Hotspot LTE"
    }
    val recordingError = prefs.safeString(MainActivity.KEY_RECORDING_ERROR, null)
    val recordingStatus = when {
        recordingPlayback -> " · reproduciendo grabación"
        prefs.safeBoolean(MainActivity.KEY_RECORDING, false) -> " · grabando HLS"
        recordingError != null -> " · error grabación: $recordingError"
        else -> ""
    }
    val state = when (player.playbackState) {
        Player.STATE_BUFFERING -> "Conectando"
        Player.STATE_READY ->
            if (playbackIsReadyAndLive(player.playbackState, player.isCurrentMediaItemLive)) "HLS en vivo${if (player.isPlaying) "" else " · pausa"}"
            else "Reproduciendo"
        Player.STATE_IDLE -> "Esperando"
        else -> "Reproduciendo"
    }
    val airing = guideProgramTimeIndex.at(now)
    val next = guideProgramTimeIndex.nextAfter(now)
    val programmeRows = listOfNotNull(
        airing?.let { "AL AIRE  ·  ${it.title}" },
        next?.let { "SIGUIENTE  ·  ${it.title}" },
    )
    return (
            listOf("${BuildConfig.BRAND_NAME.uppercase()}  ·  $state$recordingStatus") + programmeRows + listOfNotNull(
                "VIDEO  ·  $dimensions$rate · $videoCodec · $bitrate",
                "AUDIO  ·  $audio · decodificador $videoDecoderName",
                "BUFFER  ·  $buffer ($bufferProfileName) · cuadros perdidos $droppedVideoFrames",
                retry.takeIf(String::isNotBlank)?.removePrefix(" · "),
            )
            ).joinToString("\n")
}


