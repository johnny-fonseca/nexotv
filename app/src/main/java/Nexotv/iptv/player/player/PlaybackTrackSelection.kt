
package Nexotv.iptv.player.player
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
internal fun MainActivity.handleTracksChanged(tracks: Tracks) {
    if (!isPlayerInitialized) return
    restoreSavedAudioTrack(tracks)
    val surroundEnabled = playbackPrefs().safeBoolean(MainActivity.KEY_SURROUND, false)
    if (!manualAudioSelection && surroundEnabled) {
        SurroundAudio.pick(tracks)?.let { selection ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(selection)
                .build()
            surroundOverrideActive = true
        }
    }
    if (status.isVisible && player.playbackState == Player.STATE_READY) refreshVisiblePanel()
    requestMainMenuRefresh()
}

@UnstableApi
internal fun MainActivity.restoreSavedAudioTrack(tracks: Tracks) {
    if (!isPlayerInitialized) return
    val saved = playbackPrefs().safeString(MainActivity.KEY_AUDIO_TRACK, null) ?: return
    val choices = tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
    val match = choices.firstOrNull { (group, index) ->
        audioSignature(group.getTrackFormat(index)) == saved
    } ?: return
    val alreadySelected = choices.any { (group, index) ->
        group.isTrackSelected(index) && audioSignature(group.getTrackFormat(index)) == saved
    }
    if (alreadySelected) return
    manualAudioSelection = true
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
        .build()
}

@UnstableApi
internal fun audioSignature(format: Format): String =
    "${format.language.orEmpty()}|${format.channelCount}|${format.sampleMimeType.orEmpty()}"


