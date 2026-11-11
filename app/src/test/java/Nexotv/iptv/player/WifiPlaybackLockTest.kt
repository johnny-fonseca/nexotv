package Nexotv.iptv.player

import android.net.wifi.WifiManager
import Nexotv.iptv.player.network.wifiLockModeFor
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("DEPRECATION")
class WifiPlaybackLockTest {
    @Test
    fun usesLowLatencyModeFromAndroidTen() {
        // Android 10 (API 29) y posteriores: modo de baja latencia, que sustituye al obsoleto.
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, wifiLockModeFor(29))
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, wifiLockModeFor(34))
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, wifiLockModeFor(37))
    }

    @Test
    fun usesHighPerformanceModeBeforeAndroidTen() {
        // minSdk 23: en versiones anteriores el único modo de plena potencia es HIGH_PERF.
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, wifiLockModeFor(23))
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, wifiLockModeFor(24))
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, wifiLockModeFor(28))
    }
}
