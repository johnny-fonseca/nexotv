package Nexotv.iptv.player.status

import Nexotv.iptv.player.*
import androidx.media3.common.Player

internal enum class ConnectionIssue {
    NO_VALIDATED_INTERNET,
    NETWORK_LOST,
    SIGNAL_LOAD_FAILURE,
    HTTP_LOAD_FAILURE,
    CLEARTEXT_BLOCKED,
    PLAYBACK_INTERRUPTED,
}

internal enum class ConnectionAction {
    RETRY,
    SETTINGS,
}

internal data class ConnectionStatusCopy(
    val titleResId: Int,
    val helpResId: Int,
    val actions: List<ConnectionAction> = listOf(ConnectionAction.RETRY, ConnectionAction.SETTINGS),
)

internal fun classifyConnectionIssue(
    networkLost: Boolean,
    internetValidated: Boolean,
    cleartextBlocked: Boolean,
    httpResponseError: Boolean,
    playbackFailure: Boolean,
): ConnectionIssue = when {
    cleartextBlocked -> ConnectionIssue.CLEARTEXT_BLOCKED
    networkLost -> ConnectionIssue.NETWORK_LOST
    !internetValidated -> ConnectionIssue.NO_VALIDATED_INTERNET
    httpResponseError -> ConnectionIssue.HTTP_LOAD_FAILURE
    playbackFailure -> ConnectionIssue.SIGNAL_LOAD_FAILURE
    else -> ConnectionIssue.PLAYBACK_INTERRUPTED
}

internal fun connectionStatusCopy(
    issue: ConnectionIssue,
    cleartextHelpResId: Int? = null,
): ConnectionStatusCopy = when (issue) {
    ConnectionIssue.NO_VALIDATED_INTERNET -> ConnectionStatusCopy(
        R.string.player_status_offline_title,
        R.string.player_status_offline_help,
    )
    ConnectionIssue.NETWORK_LOST -> ConnectionStatusCopy(
        R.string.player_status_network_lost_title,
        R.string.player_status_network_lost_help,
    )
    ConnectionIssue.SIGNAL_LOAD_FAILURE -> ConnectionStatusCopy(
        R.string.player_status_signal_title,
        R.string.player_status_signal_help,
    )
    ConnectionIssue.HTTP_LOAD_FAILURE -> ConnectionStatusCopy(
        R.string.player_status_http_title,
        R.string.player_status_http_help,
    )
    ConnectionIssue.CLEARTEXT_BLOCKED -> ConnectionStatusCopy(
        R.string.player_status_cleartext_title,
        cleartextHelpResId ?: R.string.cleartext_blocked,
    )
    ConnectionIssue.PLAYBACK_INTERRUPTED -> ConnectionStatusCopy(
        R.string.player_status_interrupted_title,
        R.string.player_status_interrupted_help,
    )
}

internal fun playbackIsReadyAndLive(playbackState: Int, isCurrentMediaItemLive: Boolean): Boolean =
    playbackState == Player.STATE_READY && isCurrentMediaItemLive
