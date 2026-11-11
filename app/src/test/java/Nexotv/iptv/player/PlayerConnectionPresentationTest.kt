package Nexotv.iptv.player


import Nexotv.iptv.player.status.*
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerConnectionPresentationTest {
    @Test
    fun realConnectionSignalsMapToSpecificIssues() {
        assertEquals(
            ConnectionIssue.NO_VALIDATED_INTERNET,
            classifyConnectionIssue(false, false, false, false, true),
        )
        assertEquals(
            ConnectionIssue.NETWORK_LOST,
            classifyConnectionIssue(true, true, false, false, true),
        )
        assertEquals(
            ConnectionIssue.HTTP_LOAD_FAILURE,
            classifyConnectionIssue(false, true, false, true, true),
        )
        assertEquals(
            ConnectionIssue.SIGNAL_LOAD_FAILURE,
            classifyConnectionIssue(false, true, false, false, true),
        )
        assertEquals(
            ConnectionIssue.PLAYBACK_INTERRUPTED,
            classifyConnectionIssue(false, true, false, false, false),
        )
        assertEquals(
            ConnectionIssue.CLEARTEXT_BLOCKED,
            classifyConnectionIssue(false, false, true, false, true),
        )
    }

    @Test
    fun connectionIssuesMapToHonestCopyAndRetryActions() {
        val expectedTitles = mapOf(
            ConnectionIssue.NO_VALIDATED_INTERNET to R.string.player_status_offline_title,
            ConnectionIssue.NETWORK_LOST to R.string.player_status_network_lost_title,
            ConnectionIssue.SIGNAL_LOAD_FAILURE to R.string.player_status_signal_title,
            ConnectionIssue.HTTP_LOAD_FAILURE to R.string.player_status_http_title,
            ConnectionIssue.CLEARTEXT_BLOCKED to R.string.player_status_cleartext_title,
            ConnectionIssue.PLAYBACK_INTERRUPTED to R.string.player_status_interrupted_title,
        )

        expectedTitles.forEach { (issue, titleResId) ->
            val copy = connectionStatusCopy(issue)

            assertEquals(titleResId, copy.titleResId)
            assertEquals(listOf(ConnectionAction.RETRY, ConnectionAction.SETTINGS), copy.actions)
        }
    }

    @Test
    fun cleartextClassificationKeepsItsSpecificHelpMessage() {
        val copy = connectionStatusCopy(
            ConnectionIssue.CLEARTEXT_BLOCKED,
            R.string.cleartext_blocked_with_headers,
        )

        assertEquals(R.string.cleartext_blocked_with_headers, copy.helpResId)
    }

    @Test
    fun livePlaybackClaimRequiresMedia3ReadyState() {
        assertFalse(playbackIsReadyAndLive(Player.STATE_BUFFERING, true))
        assertFalse(playbackIsReadyAndLive(Player.STATE_IDLE, true))
        assertTrue(playbackIsReadyAndLive(Player.STATE_READY, true))
        assertFalse(playbackIsReadyAndLive(Player.STATE_READY, false))
    }
}
