package Nexotv.iptv.player


import Nexotv.iptv.player.guide.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlTvParserChannelTest {
    @Test
    fun exactChannelSevenIsAcceptedOnlyWhenConfiguredByTheVariant() {
        assertTrue(XmlTvParser.isConfiguredChannelId("7", listOf("teletica", "7")))
        assertFalse(XmlTvParser.isConfiguredChannelId("7", listOf("futv")))
        assertFalse(XmlTvParser.isConfiguredChannelId("7", listOf("opa", "canal.38", "canal 38", "canal38")))
    }

    @Test
    fun configuredMarkersRemainCaseInsensitive() {
        assertTrue(XmlTvParser.isConfiguredChannelId("FUTV-HD", listOf("futv")))
        assertFalse(XmlTvParser.isConfiguredChannelId("canal-38", listOf("futv")))
    }
}

