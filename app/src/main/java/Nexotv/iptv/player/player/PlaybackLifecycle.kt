
package Nexotv.iptv.player.player



import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.guide.*
import Nexotv.iptv.player.common.DisplayModeMatcher
import Nexotv.iptv.player.network.hostKeyOf

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.net.Uri
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

internal const val CAUSE_CHAIN_LIMIT = 16
internal const val HEALTH_SAMPLE_SECONDS = 2

internal inline fun playbackGuarded(event: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        DiagnosticTracker.log(event, "${e.javaClass.simpleName}: ${e.message}")
    }
}

internal fun MainActivity.cancelPlaybackTask(task: Runnable?) {
    if (task != null) handler.removeCallbacks(task)
}

internal fun MainActivity.cancelScheduledPlaybackRecovery() {
    recoveryController.cancelPendingReconnects()
}

internal fun MainActivity.cancelRetryAttemptReset() {
    recoveryController.cancelRetryAttemptReset()
}

internal fun MainActivity.scheduleRetryAttemptReset() {
    recoveryController.scheduleRetryAttemptReset()
}

internal fun MainActivity.setKeepScreenOn(enabled: Boolean) {
    if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/**
 * Mantiene el Wi-Fi despierto mientras la reproducción puede seguir consumiendo red
 * (reproduciendo, conectando o esperando un reintento) y lo libera cuando no.
 *
 * No cambia a qué red se conecta la TV ni la potencia de la señal: solo evita que la interfaz
 * Wi-Fi entre en ahorro de energía durante la reproducción.
 */
internal fun MainActivity.syncWifiPlaybackLock() {
    val active = try {
        isPlayerInitialized && player.playWhenReady && !recordingPlayback && !isFinishing
    } catch (_: Exception) {
        false
    }
    playbackGuarded("WIFI_LOCK") { wifiPlaybackLock.setActive(active) }
}

internal fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this) { it.cause }.take(CAUSE_CHAIN_LIMIT).toList()

internal fun shouldResumeAfterRestart(playWhenReady: Boolean, resumeAfterLifecycle: Boolean): Boolean =
    playWhenReady || resumeAfterLifecycle

private fun MainActivity.resumePlayback() {
    player.play()
    if (networkWasLost) reconnectOnNetworkReturn()
    showPlaybackPanel()
    hideStatusAfterDelay()
}

@UnstableApi
@Suppress("DEPRECATION")
internal fun MainActivity.startStream(autoPlay: Boolean = true) {
    if (!isPlayerInitialized || isFinishing) return
    // Un inicio explícito del stream vuelve a habilitar la recuperación automática.
    playbackGuarded("NETWORK_LOST_CLEAR") { clearNetworkLostState("inicio de stream") }
    val streamUrl = currentStreamUrl()
    if (streamUrl.isBlank()) {
        DiagnosticTracker.log("STREAM_URL", "URL de stream vacía; no se inicia la reproducción")
        showStatus(getString(R.string.player_status_stream_not_configured))
        return
    }
    if (recordingPlayback) saveRecordingProgress()
    handler.removeCallbacks(recordingProgressTicker)
    recordingPlayback = false
    clearActiveRecording()
    clearActiveRecordingPoster()
    videoSize = VideoSize.UNKNOWN
    frameRate.reset()
    fps = 0f
    droppedVideoFrames = 0L
    videoDecoderName = "Auto"
    liveWindowAdjustedForItem = false
    softRecoveryAttempted = false
    cancelRetryAttemptReset()
    manualAudioSelection = false
    surroundOverrideActive = false
    val bufferProfile = playbackBufferProfile()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .setMaxVideoBitrate(maxVideoBitrateForNetwork(bufferProfile))
        .build()
    hotspotStabilizer.refresh()
    val durations = playbackDurations(bufferProfile)
    lastProgressAtElapsedMs = SystemClock.elapsedRealtime()
    SessionReport.start(
        hostKey = hostKeyOf(Uri.parse(streamUrl)),
        profile = SessionReport.profileLabel(bufferProfile),
        variantCount = DiagnosticTracker.variantCount,
    )
    // maxOffset debe ajustarse a la ventana HLS real; si la excede, ExoPlayer
    // lanza ERROR_CODE_BEHIND_LIVE_WINDOW al buscar un segmento ya expirado.
    val mediaItem = MediaItem.Builder()
        .setUri(streamUrl)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(durations.liveTargetOffsetMs)
                .setMinOffsetMs(durations.liveMinOffsetMs)
                .setMaxOffsetMs(durations.liveMaxOffsetMs)
                .setMinPlaybackSpeed(durations.liveMinPlaybackSpeed)
                .setMaxPlaybackSpeed(durations.liveMaxPlaybackSpeed)
                .build(),
        )
        .build()
    try {
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = autoPlay
    } catch (e: IllegalStateException) {
        DiagnosticTracker.log("START_STREAM", "No se pudo iniciar el stream: ${e.message}")
        showStatus(getString(R.string.player_status_start_failed))
    }
}

@UnstableApi
internal fun MainActivity.handlePlaybackStateChanged(playbackState: Int) {
    when (playbackState) {
        Player.STATE_BUFFERING -> {
            cancelRetryAttemptReset()
            cancelPlaybackTask(connectingStatusTask)
            val task = Runnable {
                if (isPlayerInitialized && player.playbackState == Player.STATE_BUFFERING && !retryPending) {
                    renderPlayerStatus(PlayerStatus.Connecting)
                }
            }
            connectingStatusTask = task
            handler.postDelayed(task, resources.getInteger(R.integer.tv_status_connecting_delay_ms).toLong())
        }
        Player.STATE_READY -> {
            if (networkWasLost && hasInternetTransport()) {
                // La sonda fue el propio reproductor (bloque 1.6): hay imagen y audio aunque
                // Android no haya validado la red. Se descarta la pérdida para no bloquear
                // las recuperaciones siguientes.
                DiagnosticTracker.log(
                    "NETWORK_RESUME_UNVALIDATED",
                    "Reproducción recuperada sin VALIDATED; se descarta el estado de red perdida",
                )
                clearNetworkLostState("reproducción recuperada sin validación del sistema")
            }
            if (networkResumeStartedAtElapsedMs > 0L) {
                val resumeMs = (SystemClock.elapsedRealtime() - networkResumeStartedAtElapsedMs).coerceAtLeast(0L)
                DiagnosticTracker.log(
                    "NETWORK_PLAYBACK_RESUMED",
                    "Reproducción reanudada ${resumeMs}ms después del regreso de la red",
                )
                networkResumeStartedAtElapsedMs = 0L
            }
            cancelPlaybackTask(connectingStatusTask)
            cancelScheduledPlaybackRecovery()
            renderPlayerStatus(null)
            clearRetryStarting()
            retryPending = false
            bufferingSamples = 0
            softRecoveryAttempted = false
            showPlaybackPanel()
            hideStatusAfterDelay()
        }
        Player.STATE_ENDED ->
            if (recordingPlayback) finishRecordingPlayback() else recoverStream("stream terminó")
        else -> Unit
    }
    syncWifiPlaybackLock()
    requestMainMenuRefresh()
}

@UnstableApi
internal fun MainActivity.handleVideoSizeChanged(size: VideoSize) {
    videoSize = size
    if (isPlayerInitialized && player.playbackState == Player.STATE_READY) refreshVisiblePanel()
    requestMainMenuRefresh()
}

internal fun MainActivity.handleIsPlayingChanged(isPlaying: Boolean) {
    setKeepScreenOn(isPlaying)
    syncWifiPlaybackLock()
    if (isPlaying) {
        lastProgressAtElapsedMs = SystemClock.elapsedRealtime()
        scheduleRetryAttemptReset()
    } else {
        cancelRetryAttemptReset()
    }
    if (!isPlaying && !player.playWhenReady) refreshDeferredGuideIfSafe()
    if (playbackPanelVisible) refreshPlayerInfo()
    if (status.isVisible) refreshVisiblePanel()
    requestMainMenuRefresh()
}

internal fun MainActivity.requestMainMenuRefresh() {
    handler.post {
        if (!isFinishing && !isDestroyed) refreshVisibleMainMenu?.invoke()
    }
}

@UnstableApi
internal fun MainActivity.handlePlayerError(error: PlaybackException) {
    cancelRetryAttemptReset()
    clearRetryStarting()
    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
        DiagnosticTracker.onBehindLiveWindow()
        SessionReport.onBehindLiveWindow()
        DiagnosticTracker.log("BEHIND_LIVE_WINDOW", "Ventana HLS expirada; reposicionamiento en el siguiente reintento")
        // Antes de reconstruir el reproductor se prueba un reposicionamiento liviano sobre
        // la misma fuente: es más rápido y no vuelve a descargar el manifiesto.
        if (tryLightBehindLiveWindowRecovery()) {
            requestMainMenuRefresh()
            return
        }
        recoverStream("BEHIND_LIVE_WINDOW")
        requestMainMenuRefresh()
        return
    }
    val failureKind = classifyPlaybackFailure(
        failureInputOf(error),
        mtuSuspected = DiagnosticTracker.mtuSuspectedRecently(),
    )
    val cleartextMessageResId = cleartextErrorMessageResId(error, hasCustomStreamHeaders())
    val tlsMessageResId = tlsErrorMessageResId(error)
    val category = when {
        failureKind == PlaybackFailureKind.CLEARTEXT -> "HTTP sin cifrar bloqueado: usar HTTPS"
        failureKind == PlaybackFailureKind.DECODER -> "decodificador: reiniciar player"
        failureKind == PlaybackFailureKind.PARSING -> "parsing: reintentar stream"
        failureKind == PlaybackFailureKind.HTTP_CLIENT -> "HTTP 4xx: revisar URL, ruta o credenciales"
        failureKind == PlaybackFailureKind.HTTP_SERVER -> "HTTP 5xx: fallo del proveedor"
        failureKind == PlaybackFailureKind.DNS -> "DNS: reintentar con backoff"
        failureKind == PlaybackFailureKind.TLS -> "TLS: revisar certificado o reloj del equipo"
        failureKind == PlaybackFailureKind.MTU -> "posible MTU/PMTUD: lista OK y segmento corto colgado"
        failureKind == PlaybackFailureKind.CONNECT_TIMEOUT ||
            failureKind == PlaybackFailureKind.READ_TIMEOUT -> "timeout: reintentar con backoff"
        failureKind == PlaybackFailureKind.NETWORK -> "red/HTTP: reintentar con backoff"
        else -> "Media3: reintentar stream"
    }
    DiagnosticTracker.onFailureClassified(failureKind)
    DiagnosticTracker.log(
        "PLAYER_ERROR",
        "${error.errorCodeName} (${error.errorCode}) [${failureKind.label}]: ${error.message}; acción=$category",
    )
    cleartextMessageResId?.let { showTvToast(getString(it), long = true) }
    tlsMessageResId?.let { showTvToast(getString(it), long = true) }
    if (failureKind == PlaybackFailureKind.DECODER) {
        // Reconstruir con la misma pista repite el fallo; bajar un nivel antes del reinicio
        // evita empujar al decodificador por software en resoluciones altas.
        hotspotStabilizer.forceEscalate("fallo de decodificador")
    }
    recoverStream(
        "${error.errorCodeName}: $category",
        restartPlayer = failureKind == PlaybackFailureKind.DECODER,
        connectionFailure = error,
    )
    requestMainMenuRefresh()
}
internal fun MainActivity.refreshVisiblePanel() {
    if (playbackPanelVisible) refreshPlayerInfo()
    if (status.isVisible && diagnosticsVisible) setStatusText(channelDetails())
}

@UnstableApi
internal fun MainActivity.refreshPlayerInfo() {
    if (!isPlayerInfoInitialized || !isPlayerInitialized) return
    playerInfo.showInfo(
        guideProgramTimeIndex,
        isPlaying = player.isPlaying,
        isRecording = recordingPlayback,
        textSizeSp = playbackPrefs().safeInt(
            MainActivity.KEY_PANEL_TEXT_SIZE,
            resources.getInteger(R.integer.tv_panel_text_max_sp),
        ),
        isConnecting = player.playbackState != Player.STATE_READY && player.playWhenReady,
    )
}

@Suppress("DEPRECATION")
@UnstableApi
internal fun MainActivity.applyFrameRate(contentFps: Float) {
    if (!contentFps.isFinite() || contentFps <= 0f || isFinishing || isDestroyed) return
    if (frameRatePreference() == MainActivity.AFR_OFF) return
    val display: Display = windowManager.defaultDisplay ?: return
    playbackGuarded("FRAME_RATE") {
        val modeId = DisplayModeMatcher.bestModeId(display, contentFps) ?: return
        if (originalDisplayModeId == null) originalDisplayModeId = display.mode.modeId
        window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
    }
}

@UnstableApi
internal fun MainActivity.rebuildPlayer(message: String, resetRetryState: Boolean = true) {
    val shouldResume = isPlayerInitialized && shouldResumeAfterRestart(player.playWhenReady, resumeAfterLifecycle)
    cancelScheduledPlaybackRecovery()
    if (resetRetryState) retryAttempts = 0
    if (isPlayerInitialized) {
        playerView.player = null
        hotspotStabilizer.detach()
        playbackGuarded("PLAYER_RELEASE") { player.release() }
    }
    manualAudioSelection = false
    surroundOverrideActive = false
    player = createPlayer()
    playerView.player = player
    startStream(autoPlay = shouldResume)
    showStatus(getString(R.string.player_status_rebuilding, message))
}

@UnstableApi
internal fun MainActivity.reconnectOnNetworkReturn() {
    val inPip = isInPipMode
    if (recordingPlayback || !networkWasLost || isFinishing || (!activityResumed && !inPip)) return
    if (!isPlayerInitialized) {
        networkWasLost = false
        return
    }
    val shouldResume = shouldResumeAfterRestart(player.playWhenReady, resumeAfterLifecycle)
    if (!shouldResume) return
    networkWasLost = false
    val outageDurationMs = (SystemClock.elapsedRealtime() - networkLostAtElapsedMs).coerceAtLeast(0L)
    DiagnosticTracker.log("NETWORK_RESUME", "Vuelta de red tras ${outageDurationMs}ms sin red")
    SessionReport.onNetworkAvailable()
    SessionReport.onRecoveryStarted()
    networkResumeStartedAtElapsedMs = SystemClock.elapsedRealtime()
    cancelScheduledPlaybackRecovery()
    resetStallCounts()
    showStatus(getString(R.string.player_status_network_restored, getString(R.string.app_name)))
    if (!PlayerTuning.nonDestructiveResume) {
        // A/B con el comportamiento de la ronda 1: parar y recargar siempre.
        retryAttempts = 0
        player.stop()
        startStream(autoPlay = shouldResume)
        return
    }
    // Antirrebote: los eventos de red del hotspot oscilan y una recarga por cada uno
    // multiplica el tráfico justo cuando el enlace acaba de volver.
    networkResumePending = true
    handler.removeCallbacks(networkResumeTask)
    handler.postDelayed(networkResumeTask, PlayerTuning.NETWORK_RESUME_DEBOUNCE_MS)
    DiagnosticTracker.log(
        "NETWORK_RESUME_SCHEDULED",
        "Se decide cómo reanudar en ${PlayerTuning.NETWORK_RESUME_DEBOUNCE_MS}ms (antirrebote)",
    )
}

/**
 * Ejecuta la reanudación tras el antirrebote.
 *
 * No destruye el reproductor si sigue en READY con buffer suficiente, y usa un reintento
 * liviano (`prepare` + `seekToDefaultPosition`) si está en BUFFERING con progreso reciente.
 * La recarga completa queda para el caso en que no hubo progreso.
 */
@UnstableApi
internal fun MainActivity.executeNetworkResume() {
    if (!networkResumePending) return
    networkResumePending = false
    if (!isPlayerInitialized || isFinishing) return
    val now = SystemClock.elapsedRealtime()
    val progressBase = maxOf(lastProgressAtElapsedMs, networkLostAtElapsedMs)
    val msSinceProgress = (now - progressBase).coerceAtLeast(0L)
    val action = decideNetworkResume(
        playbackState = player.playbackState,
        bufferedMs = player.totalBufferedDuration.coerceAtLeast(0L),
        msSinceProgress = msSinceProgress,
    )
    DiagnosticTracker.log(
        "NETWORK_RESUME_DECISION",
        "estado=${player.playbackState}, buffer=${player.totalBufferedDuration}ms, " +
            "sinProgreso=${msSinceProgress}ms, acción=$action",
    )
    when (action) {
        NetworkResumeAction.KEEP_PLAYING -> Unit
        NetworkResumeAction.LIGHT_RESTART -> {
            retryAttempts = 0
            lastProgressAtElapsedMs = now
            player.prepare()
            player.seekToDefaultPosition()
            player.play()
            scheduleNetworkResumeStableCheck()
        }
        NetworkResumeAction.FULL_RELOAD -> {
            retryAttempts = 0
            player.stop()
            startStream(autoPlay = true)
            scheduleNetworkResumeStableCheck()
        }
    }
}

/** Cierra el MTTR de la reanudación si la reproducción se mantiene 15 s. */
private fun MainActivity.scheduleNetworkResumeStableCheck() {
    handler.removeCallbacks(networkResumeStableTask)
    handler.postDelayed(networkResumeStableTask, MainActivity.RETRY_STABLE_PLAYBACK_MS)
}

/**
 * Reposicionamiento liviano tras BEHIND_LIVE_WINDOW, antes de reconstruir.
 *
 * Limitado a uno cada [PlayerTuning.BEHIND_LIVE_WINDOW_LIGHT_RETRY_MS] para no entrar en un
 * bucle si el manifiesto sigue sin ofrecer una posición válida.
 */
@UnstableApi
internal fun MainActivity.tryLightBehindLiveWindowRecovery(): Boolean {
    if (!isPlayerInitialized) return false
    val now = SystemClock.elapsedRealtime()
    if (now - lastBehindLiveWindowLightAtMs < PlayerTuning.BEHIND_LIVE_WINDOW_LIGHT_RETRY_MS) return false
    if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return false
    lastBehindLiveWindowLightAtMs = now
    DiagnosticTracker.log(
        "BEHIND_LIVE_WINDOW_LIGHT",
        "Reposicionamiento liviano en el borde del directo antes de reconstruir",
    )
    handler.post {
        if (!isPlayerInitialized || isFinishing) return@post
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }
    return true
}

internal fun MainActivity.togglePlayback() {
    if (!isPlayerInitialized) return
    if (retryPending) {
        retryNow()
        return
    }
    if (player.playbackState == Player.STATE_IDLE || retryAttempts >= MainActivity.MAX_RETRIES) {
        retryAttempts = 0
        retryPending = false
        startStream()
    } else if (player.isPlaying) {
        player.pause()
        showPlaybackPanel()
    } else {
        resumePlayback()
    }
    hideStatusAfterDelay()
}

internal fun MainActivity.retryNow() {
    cancelScheduledPlaybackRecovery()
    retryAttempts = 0
    recoveryReason = "reintento manual"
    if (isPlayerInitialized) {
        player.stop()
        player.seekToDefaultPosition()
        startStream()
        showStatus("Reintentando conexión…")
    }
}

internal fun MainActivity.playFromRemote() {
    if (!isPlayerInitialized) return
    if (retryPending || retryAttempts >= MainActivity.MAX_RETRIES) retryNow() else resumePlayback()
}

internal fun MainActivity.pauseFromRemote() {
    if (!isPlayerInitialized) return
    player.pause()
    showPlaybackPanel()
    hideStatusAfterDelay()
}

internal fun MainActivity.seekInLive(forward: Boolean) {
    if (!isPlayerInitialized) return
    if (player.isCurrentMediaItemSeekable) {
        seekByConfiguredStep(forward)
    } else {
        showStatus("Este stream no ofrece ventana de retroceso en vivo")
        hideStatusAfterDelay()
    }
}

@UnstableApi
internal fun MainActivity.handlePlayerUserLeaveHint() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (!isPlayerInitialized || isInPipMode || !player.isPlaying) return
    if (!playbackPrefs().safeBoolean(MainActivity.KEY_PIP_ON_HOME, false)) return
    playbackGuarded("PIP_ENTER") { enterPip() }
}

internal fun MainActivity.preparePlayerForPause() {
    playbackGuarded("PAUSE_RETRY_STATE") { clearRetryStarting() }
    // En PiP la reproducción continúa: el bloqueo de Wi-Fi se mantiene.
    if (!isInPipMode) playbackGuarded("PAUSE_WIFI_LOCK") { wifiPlaybackLock.release() }
    handler.removeCallbacks(recordingStatusTicker)
    recordingStatusTickerScheduled = false
    handler.removeCallbacks(networkResumeTask)
    networkResumePending = false
    cancelPlaybackTask(connectingStatusTask)
    connectingStatusTask = null
    cancelPlaybackTask(reconnectStatusTask)
    reconnectStatusTask = null
    playbackGuarded("PAUSE_NETWORK_CALLBACK") { unregisterNetworkCallback() }
    playbackGuarded("PAUSE_SAVE_PROGRESS") { saveRecordingProgress() }
    playbackGuarded("PAUSE_AUTO_BACKUP") { saveAutomaticBackup() }
}

internal fun MainActivity.finishPlayerPause() {
    activityResumed = false
    playbackGuarded("PAUSE_DISPLAY_MODE") { restoreDisplayMode() }
    if (isPlayerInitialized && !isInPipMode) {
        resumeAfterLifecycle = player.playWhenReady
        player.pause()
    }
}

internal fun MainActivity.updatePlayerPictureInPicture(isInPictureInPictureMode: Boolean) {
    if (isInPictureInPictureMode) {
        status.isVisible = false
        if (isPlayerInfoInitialized) playerInfo.isVisible = false
    } else {
        if (isPlayerInfoInitialized) playerInfo.isVisible = true
        clockTicker.run()
    }
}

internal fun MainActivity.handlePlayerResume() {
    activityResumed = true
    playbackGuarded("RESUME_NETWORK_CALLBACK") { registerNetworkCallback() }
    if (isPlayerInitialized && player.isPlaying) setKeepScreenOn(true)
    if (fps > 0f) applyFrameRate(fps)
    if (isPlayerInitialized && resumeAfterLifecycle) {
        if (!externalAlways()) {
            if (networkWasLost) reconnectOnNetworkReturn() else player.play()
        }
        resumeAfterLifecycle = false
    }
    playbackGuarded("RESUME_RECORDING_NOTICE") { showRecordingNoticeIfNeeded() }
    playbackGuarded("RESUME_HOME_CHANNEL") {
        HomeScreenChannel.refreshCurrentProgram(this)
        val appContext = applicationContext
        recordingIoExecutor.execute { runCatching { HomeScreenChannel.syncRecordings(appContext) } }
    }
    if (recordingPlayback) {
        handler.removeCallbacks(recordingProgressTicker)
        handler.postDelayed(recordingProgressTicker, MainActivity.RECORDING_PROGRESS_INTERVAL_MS)
    }
    externalHandoff = false
}

internal fun MainActivity.cleanupPlayerOnDestroy() {
    runCatching { SessionReport.finish(this, "cierre de la actividad") }
    handler.removeCallbacksAndMessages(null)
    networkResumePending = false
    guideDialog = null
    refreshVisibleGuide = null
    recordingStatusTickerScheduled = false
    connectingStatusTask = null
    playbackGuarded("DESTROY_DISPLAY_LISTENER") {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
    }
    dialogBounds.clear()
    playbackGuarded("DESTROY_NETWORK_CALLBACK") { unregisterNetworkCallback() }
    playbackGuarded("DESTROY_WIFI_LOCK") { wifiPlaybackLock.release() }
    playbackGuarded("DESTROY_DISPLAY_MODE") { restoreDisplayMode() }
    hotspotStabilizer.detach()
    if (isPlayerInitialized && !playerReleasedForStop) {
        playbackGuarded("DESTROY_PLAYER_RELEASE") { player.release() }
    }
}

@UnstableApi
internal fun MainActivity.checkPlaybackHealth() {
    // Primero la red: si el sistema no avisó del regreso, un estado de "sin red" pegado
    // dejaría el resto de esta comprobación sin efecto.
    recheckNetworkLoss()
    if (!isPlayerInitialized || !player.playWhenReady || recordingPlayback) {
        resetStallCounts()
        return
    }
    when (player.playbackState) {
        Player.STATE_BUFFERING -> {
            bufferingSamples++
            frozenSamples = 0
            // Recuperación suave: tras N muestras en BUFFERING sin progreso se intenta
            // un seek a la posición por defecto antes de escalar a recuperación completa,
            // evitando reintentos en cascada cuando solo se salió de la ventana HLS.
            val softLimit = BufferSizes.HOTSPOT_BUFFERING_SOFT_RECOVERY_SAMPLES
            if (bufferingSamples == softLimit && !retryPending && !softRecoveryAttempted &&
                !networkResumePending
            ) {
                softRecoveryAttempted = true
                DiagnosticTracker.log(
                    "SOFT_RECOVERY",
                    "${softLimit * HEALTH_SAMPLE_SECONDS}s en BUFFERING → seekToDefaultPosition suave",
                )
                player.seekToDefaultPosition()
            } else if (bufferingSamples >= MainActivity.BUFFERING_LIMIT && !networkResumePending) {
                recoverStream("buffering atascado")
            }
        }
        Player.STATE_READY -> {
            bufferingSamples = 0
            val position = player.currentPosition
            val playing = player.isPlaying
            if (playing && position != lastPositionMs) {
                lastProgressAtElapsedMs = SystemClock.elapsedRealtime()
            }
            frozenSamples = if (playing && position == lastPositionMs) frozenSamples + 1 else 0
            lastPositionMs = if (playing) position else -1L
            if (frozenSamples >= MainActivity.FROZEN_LIMIT && !networkResumePending) {
                recoverStream("video congelado")
            }
        }
        else -> resetStallCounts()
    }
}



