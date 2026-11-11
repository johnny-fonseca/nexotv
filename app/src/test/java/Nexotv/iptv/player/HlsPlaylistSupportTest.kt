package Nexotv.iptv.player

import Nexotv.iptv.player.player.HlsPlaylistSupport
import Nexotv.iptv.player.player.UnsupportedHlsFeatureException
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistSupportTest {
    @Test
    fun acceptsPlainTransportStreamAndFmp4Playlists() {
        HlsPlaylistSupport.validate("""
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            segment-1.ts
        """.trimIndent())
        HlsPlaylistSupport.validate("""
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            segment-1.m4s
        """.trimIndent())
    }

    @Test
    fun allowsExplicitUnencryptedKeyReset() {
        HlsPlaylistSupport.validate("#EXT-X-KEY:METHOD=NONE")
    }

    @Test
    fun rejectsEncryptedPlaylistsWithActionableReason() {
        assertRejected("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"", "cifrada")
        assertRejected("#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"key.bin\"", "cifrada")
    }

    @Test
    fun rejectsPlaylistFeaturesTheRecorderCannotPreserve() {
        assertRejected("#EXT-X-BYTERANGE:512@0", "rangos de bytes")
        assertRejected("#EXT-X-DISCONTINUITY", "cambia de formato")
        assertRejected("#EXT-X-PART:DURATION=0.5,URI=\"part.m4s\"", "baja latencia")
        assertRejected("#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"next.m4s\"", "baja latencia")
        assertRejected("#EXT-X-SKIP:SKIPPED-SEGMENTS=2", "parciales")
    }

    private fun assertRejected(playlist: String, expectedText: String) {
        val failure = try {
            HlsPlaylistSupport.validate(playlist)
            null
        } catch (expected: UnsupportedHlsFeatureException) {
            expected
        }
        assertTrue("Expected unsupported HLS feature: $playlist", failure != null)
        assertTrue(failure!!.message.orEmpty().contains(expectedText, ignoreCase = true))
    }
}
