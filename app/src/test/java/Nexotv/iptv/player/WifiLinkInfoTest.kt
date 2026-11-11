package Nexotv.iptv.player

import Nexotv.iptv.player.network.WifiLinkSnapshot
import Nexotv.iptv.player.network.isKnownRssi
import Nexotv.iptv.player.network.wifiBandLabel
import Nexotv.iptv.player.network.wifiRssiQuality
import Nexotv.iptv.player.network.wifiStandardLabel
import Nexotv.iptv.player.player.NetworkTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLinkInfoTest {

    @Test
    fun bandsAreDerivedFromChannelFrequency() {
        assertEquals("2,4 GHz", wifiBandLabel(2412))
        assertEquals("2,4 GHz", wifiBandLabel(2472))
        assertEquals("5 GHz", wifiBandLabel(5180))
        assertEquals("5 GHz", wifiBandLabel(5745))
        // 6 GHz no debe etiquetarse como 5 GHz: son rangos distintos y el usuario lo vería mal.
        assertEquals("6 GHz", wifiBandLabel(5955))
        assertNull(wifiBandLabel(0))
        assertNull(wifiBandLabel(3500))
    }

    @Test
    fun rssiIsClassifiedWithTheConfiguredThresholds() {
        assertEquals("buena", wifiRssiQuality(NetworkTuning.WIFI_RSSI_GOOD_DBM))
        assertEquals("buena", wifiRssiQuality(-42))
        assertEquals("regular", wifiRssiQuality(NetworkTuning.WIFI_RSSI_GOOD_DBM - 1))
        assertEquals("regular", wifiRssiQuality(NetworkTuning.WIFI_RSSI_FAIR_DBM))
        assertEquals("débil", wifiRssiQuality(NetworkTuning.WIFI_RSSI_FAIR_DBM - 1))
        assertEquals("débil", wifiRssiQuality(NetworkTuning.WIFI_RSSI_WEAK_DBM))
        assertEquals("muy débil", wifiRssiQuality(NetworkTuning.WIFI_RSSI_WEAK_DBM - 1))
    }

    @Test
    fun driverPlaceholdersAreNotTreatedAsMeasurements() {
        assertTrue(isKnownRssi(-58))
        assertTrue(isKnownRssi(-105))
        // 0 y -127 son los rellenos habituales cuando el dato no está disponible.
        assertFalse(isKnownRssi(0))
        assertFalse(isKnownRssi(-127))
        assertFalse(isKnownRssi(20))
    }

    @Test
    fun wifiStandardsAreTranslatedForTheHud() {
        assertEquals("802.11a/b/g", wifiStandardLabel(1))
        assertEquals("Wi-Fi 4 (11n)", wifiStandardLabel(4))
        assertEquals("Wi-Fi 5 (11ac)", wifiStandardLabel(5))
        assertEquals("Wi-Fi 6 (11ax)", wifiStandardLabel(6))
        assertEquals("WiGig (11ad)", wifiStandardLabel(7))
        assertEquals("Wi-Fi 7 (11be)", wifiStandardLabel(8))
        assertNull(wifiStandardLabel(WifiLinkSnapshot.UNKNOWN))
    }

    @Test
    fun describeShowsTheWholeLinkWhenEverythingIsKnown() {
        val snapshot = WifiLinkSnapshot(
            frequencyMhz = 5180,
            rssiDbm = -58,
            linkSpeedMbps = 390,
            standard = 5,
        )
        assertEquals(
            "5 GHz · 5180 MHz · -58 dBm (buena) · 390 Mbps · Wi-Fi 5 (11ac)",
            snapshot.describe(),
        )
    }

    @Test
    fun describeOmitsUnknownFieldsAndFallsBackWhenNothingIsKnown() {
        // Frecuencia sin banda reconocida: se muestra el MHz crudo, sin inventar la banda.
        assertEquals("2,4 GHz · 2412 MHz", WifiLinkSnapshot(frequencyMhz = 2412).describe())
        // Restricción de Android 12+: puede faltar todo menos la frecuencia.
        assertEquals(
            "5 GHz · 5180 MHz · 200 Mbps",
            WifiLinkSnapshot(frequencyMhz = 5180, linkSpeedMbps = 200).describe(),
        )
        assertEquals(WifiLinkSnapshot.NO_DATA, WifiLinkSnapshot().describe())
    }
}
