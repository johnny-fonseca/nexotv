
package Nexotv.iptv.player.player
import Nexotv.iptv.player.diagnostics.*
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


internal fun MainActivity.playbackPrefs(): SharedPreferences =
    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

internal fun MainActivity.playbackBufferProfile() =
    BufferSizes.normalizedProfile(
        playbackPrefs().safeString(MainActivity.KEY_BUFFER, BufferSizes.PROFILE_HOTSPOT),
    )

internal fun playbackDurations(profile: String) =
    BufferSizes.validatedDurations(BufferSizes.durations(profile))

internal fun MainActivity.maxVideoBitrateForNetwork(profile: String): Int =
    if (isMeteredNetwork) BufferSizes.HOTSPOT_MAX_VIDEO_BITRATE
    else BufferSizes.maxVideoBitrateFor(profile) ?: Int.MAX_VALUE

internal fun MainActivity.applyMeteredNetworkPolicy(metered: Boolean) {
    if (!isPlayerInitialized) return
    val maxBitrate = if (metered) {
        BufferSizes.HOTSPOT_MAX_VIDEO_BITRATE
    } else {
        BufferSizes.maxVideoBitrateFor(playbackBufferProfile()) ?: Int.MAX_VALUE
    }
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setMaxVideoBitrate(maxBitrate)
        .build()
    hotspotStabilizer.refresh()
    DiagnosticTracker.log(
        "METERED_NETWORK",
        if (metered) "Red medida: límite de video=${maxBitrate}bps" else "Red no medida: límite de video=${maxBitrate}bps",
    )
}
