
package Nexotv.iptv.player.playerimport Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.network.MeasuredDataSourceFactory
import Nexotv.iptv.player.network.NetworkLoadStats
import Nexotv.iptv.player.settings.*
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

@UnstableApi
internal fun MainActivity.createPlayer(): ExoPlayer {
    val prefs = playbackPrefs()
    val bufferProfile = playbackBufferProfile()
    val initialStreamIsHttp = isCleartextUrl(currentStreamUrl())
    val requestHeaders = currentStreamHeaders()
    val hotspot = bufferProfile == BufferSizes.PROFILE_HOTSPOT
    val rttP95 = if (hotspot) NetworkLoadStats.rttP95Ms() else null
    val connectTimeoutMs = BufferSizes.validatedTimeoutMs(
        BufferSizes.connectTimeoutMs(bufferProfile, rttP95),
        BufferSizes.DEFAULT_CONNECT_TIMEOUT_MS,
    )
    val readTimeoutMs = BufferSizes.validatedTimeoutMs(
        BufferSizes.readTimeoutMs(bufferProfile, rttP95),
        BufferSizes.DEFAULT_READ_TIMEOUT_MS,
    )
    DiagnosticTracker.onTimeouts(connectTimeoutMs, readTimeoutMs, rttP95, hotspot)
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(requestHeaders.isEmpty() || initialStreamIsHttp)
        .setConnectTimeoutMs(connectTimeoutMs)
        .setReadTimeoutMs(readTimeoutMs)
        .setKeepPostFor302Redirects(true)
        .setUserAgent(currentUserAgent())
        .setDefaultRequestProperties(requestHeaders)
    val guardedHttp = ResolvingDataSource.Factory(http) { dataSpec ->
        if (requestHeaders.isNotEmpty() && isCleartextUrl(dataSpec.uri.toString())) {
            throw CleartextHeadersOnHttpException()
        }
        dataSpec
    }
    val loadErrorPolicy: LoadErrorHandlingPolicy = if (hotspot && PlayerTuning.inWindowRetries) {
        LiveLoadErrorHandlingPolicy { decision, errorCount ->
            DiagnosticTracker.onSegmentRetry(decision, errorCount)
        }
    } else {
        object : DefaultLoadErrorHandlingPolicy(4) {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                BufferSizes.backoffWithJitterMs(loadErrorInfo.errorCount)
        }
    }
    val measuredHttp = MeasuredDataSourceFactory(guardedHttp)
    val mediaSource = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, measuredHttp))
        .setLoadErrorHandlingPolicy(loadErrorPolicy)
    val durations = playbackDurations(bufferProfile)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            durations.minBufferMs,
            durations.maxBufferMs,
            durations.bufferForPlaybackMs,
            durations.bufferForPlaybackAfterRebufferMs,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(durations.backBufferMs, true)
        .build()
    val bandwidthMeterBuilder = DefaultBandwidthMeter.Builder(this)
    BufferSizes.initialBitrateEstimateFor(bufferProfile)?.let { estimate ->
        bandwidthMeterBuilder.setInitialBitrateEstimate(estimate)
    }
    val bandwidthMeter = bandwidthMeterBuilder.build()
    val passthrough = prefs.safeBoolean(MainActivity.KEY_PASSTHROUGH, false)
    val renderers = OffsetRenderersFactory(this, audioOffset, passthrough, decoderPreferences())
        .setEnableDecoderFallback(true)
    val muted = prefs.safeBoolean(MainActivity.KEY_MUTED, false)

    return ExoPlayer.Builder(this, renderers)
        .setMediaSourceFactory(mediaSource)
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidthMeter)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            addListener(this@createPlayer)
            val trackParams = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            trackParams.setMaxVideoBitrate(maxVideoBitrateForNetwork(bufferProfile))
            trackSelectionParameters = trackParams.build()
            volume = if (muted) 0f else 1f
            this@createPlayer.configurePlaybackAnalytics(this, durations)
            setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                frameRate.onFrame(presentationTimeUs)?.let { measured ->
                    fps = measured
                    handler.post { applyFrameRate(measured) }
                }
            }
            this@createPlayer.hotspotStabilizer.attach(this)
        }
}

