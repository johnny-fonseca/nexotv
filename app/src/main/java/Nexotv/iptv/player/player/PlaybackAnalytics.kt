
package Nexotv.iptv.player.player
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.network.NetworkLoadStats
import Nexotv.iptv.player.network.hostKeyOf
import Nexotv.iptv.player.*
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

private const val DROPPED_FRAMES_REFRESH_INTERVAL_MS = 1_000L

internal fun MainActivity.configurePlaybackAnalytics(player: ExoPlayer, durations: BufferDurations) {
    var lastDroppedFramesRefreshMs = 0L
    player.addAnalyticsListener(object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    droppedVideoFrames += droppedFrames
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDroppedFramesRefreshMs >= DROPPED_FRAMES_REFRESH_INTERVAL_MS) {
                        lastDroppedFramesRefreshMs = now
                        handler.post { refreshVisiblePanel() }
                    }
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    videoDecoderName = decoderName
                    handler.post { refreshVisiblePanel() }
                }

                /**
                 * Primer fotograma real: es el único instante válido para el KPI "tiempo
                 * hasta el primer frame". STATE_READY no sirve porque puede ocurrir antes
                 * de que el decodificador entregue imagen.
                 */
                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long,
                ) {
                    DiagnosticTracker.onFirstFrame()
                    SessionReport.onFirstFrame()
                }

                /**
                 * Inventario de variantes: cada grupo de video del HLS es una variante
                 * seleccionable. Con un solo grupo no hay nada a lo que bajar y el HUD lo
                 * dice explícitamente en lugar de aparentar ABR.
                 */
                override fun onTracksChanged(
                    eventTime: AnalyticsListener.EventTime,
                    tracks: androidx.media3.common.Tracks,
                ) {
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val bitrates = videoGroups.mapNotNull { group ->
                        (0 until group.length)
                            .map { group.getTrackFormat(it).bitrate }
                            .filter { it > 0 }
                            .minOrNull()
                    }
                    DiagnosticTracker.onVariants(videoGroups.size, bitrates)
                }

                override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> DiagnosticTracker.onPlaybackStateBuffering()
                        Player.STATE_READY -> DiagnosticTracker.onPlaybackStateReady()
                        else -> Unit
                    }
                }

                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: java.io.IOException,
                    wasCanceled: Boolean,
                ) {
                    if (wasCanceled) return
                    playbackGuarded("LOAD_ERROR_REPORT") {
                        val httpError = error.causeChain()
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()
                        DiagnosticTracker.onLoadError(
                            dataType = mediaLoadData.dataType.toString(),
                            errorCode = httpError?.let { "HTTP ${it.responseCode}" } ?: error.javaClass.simpleName,
                            url = loadEventInfo.dataSpec.uri.toString(),
                        )
                        reportMtuPatternIfAny()
                    }
                }

                override fun onLoadCompleted(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                ) {
                    DiagnosticTracker.onLoadCompleted(
                        durationMs = loadEventInfo.loadDurationMs,
                        bytes = loadEventInfo.bytesLoaded,
                    )
                    recordDownloadRatio(loadEventInfo, mediaLoadData)
                }

                override fun onBandwidthEstimate(
                    eventTime: AnalyticsListener.EventTime,
                    totalLoadTimeMs: Int,
                    totalBytesLoaded: Long,
                    bitrateEstimate: Long,
                ) {
                    DiagnosticTracker.onBandwidthEstimate(bitrateEstimate)
                }

                override fun onPlaybackParametersChanged(
                    eventTime: AnalyticsListener.EventTime,
                    playbackParameters: PlaybackParameters,
                ) {
                    DiagnosticTracker.onSpeedChanged(playbackParameters.speed)
                }

                override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
                    val timeline = eventTime.timeline
                    if (timeline.isEmpty || liveWindowAdjustedForItem) return
                    playbackGuarded("LIVE_WINDOW_ADJUST") {
                        val liveWindow = timeline.getWindow(0, Timeline.Window())
                        if (!liveWindow.isLive) return
                        val windowMs = liveWindow.durationMs
                            .takeIf { it != C.TIME_UNSET && it > 0 } ?: return
                        liveWindowAdjustedForItem = true
                        val offsets = BufferSizes.offsetsForWindow(durations, windowMs)
                        DiagnosticTracker.log(
                            "CHANNEL_START",
                            "Inicio de canal: ventana HLS=${windowMs}ms; offsets min=${offsets.minMs}ms, " +
                                    "target=${offsets.targetMs}ms, max=${offsets.maxMs}ms",
                        )
                        val item = player.currentMediaItem ?: return
                        val live = item.liveConfiguration
                        val unchanged = live.targetOffsetMs == offsets.targetMs &&
                                live.maxOffsetMs == offsets.maxMs &&
                                live.minOffsetMs == offsets.minMs
                        if (unchanged) return
                        val adjusted = item.buildUpon().setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(offsets.targetMs)
                                .setMinOffsetMs(offsets.minMs)
                                .setMaxOffsetMs(offsets.maxMs)
                                .setMinPlaybackSpeed(durations.liveMinPlaybackSpeed)
                                .setMaxPlaybackSpeed(durations.liveMaxPlaybackSpeed)
                                .build(),
                        ).build()
                        player.replaceMediaItem(player.currentMediaItemIndex, adjusted)
                    }
                }
    })
}

/**
 * Relación tiempo de descarga / duración del segmento (indicador temprano de congestión).
 * Solo se registra para cargas de medios con duración conocida; las playlists no cuentan.
 */
private fun recordDownloadRatio(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
    if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
    val mediaDurationMs = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
    if (mediaDurationMs <= 0L || loadEventInfo.loadDurationMs <= 0L) return
    val uri = loadEventInfo.dataSpec.uri
    NetworkLoadStats.recordRatio(
        hostKey = hostKeyOf(uri),
        ratio = loadEventInfo.loadDurationMs.toDouble() / mediaDurationMs.toDouble(),
    )
}

/**
 * Comprueba el patrón "playlist OK / segmento colgado tras pocos KB" en el último fallo
 * medido. No cambia la reproducción: solo deja la evidencia para el HUD y el informe.
 */
private fun reportMtuPatternIfAny() {
    val failure = NetworkLoadStats.lastSegmentFailure() ?: return
    val now = SystemClock.elapsedRealtime()
    if (now - failure.atMs > 5_000L) return
    val playlistOk = NetworkLoadStats.playlistSucceededRecently(failure.hostKey, 30_000L, now)
    if (isMtuSuspect(failure.bytesBeforeFailure, playlistOk, failure.elapsedMs)) {
        DiagnosticTracker.onMtuSuspected(failure.hostKey, failure.bytesBeforeFailure, failure.elapsedMs)
    }
}

