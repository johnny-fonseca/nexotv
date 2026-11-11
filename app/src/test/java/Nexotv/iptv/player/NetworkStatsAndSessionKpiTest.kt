package Nexotv.iptv.player

import Nexotv.iptv.player.diagnostics.buildSessionKpis
import Nexotv.iptv.player.network.NetworkFailureSample
import Nexotv.iptv.player.network.NetworkLoadStats
import Nexotv.iptv.player.network.NetworkSample
import Nexotv.iptv.player.network.NetworkSampleKind
import Nexotv.iptv.player.network.percentile
import Nexotv.iptv.player.player.isMtuSuspect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkStatsAndSessionKpiTest {
    @Before
    fun setUp() {
        NetworkLoadStats.reset()
    }

    @After
    fun tearDown() {
        NetworkLoadStats.reset()
    }

    @Test
    fun percentilesUseNearestRankInterpolation() {
        assertEquals(1L, percentile(listOf(1L, 2L, 3L, 4L), 0.0))
        assertEquals(4L, percentile(listOf(1L, 2L, 3L, 4L), 100.0))
        assertEquals(2L, percentile(listOf(1L, 2L, 3L, 4L), 50.0))
        assertEquals(3L, percentile(listOf(1L, 2L, 3L, 4L), 95.0))
        assertNull(percentile(emptyList<Long>(), 95.0))
    }

    @Test
    fun sustainedCapacityComesFromSegmentThroughputAndHeadroomUsesDeclaredBitrate() {
        val now = 10_000_000L
        repeat(4) { index ->
            NetworkLoadStats.recordSample(
                NetworkSample(
                    hostKey = "190.61.101.11:7050",
                    kind = NetworkSampleKind.SEGMENT,
                    ttfbMs = 300L + index * 10L,
                    totalMs = 1_000L,
                    bytes = 600_000L,
                    atMs = now - index * 1_000L,
                    success = true,
                ),
            )
        }
        val snapshot = NetworkLoadStats.snapshot(declaredBitrateBps = 1_960_000L, nowMs = now)
        assertEquals(4, snapshot.samples)
        assertEquals(600_000L * 8L, snapshot.sustainedCapacityBps)
        assertNotNull(snapshot.headroom)
        assertEquals(4_800_000.0 / 1_960_000.0, snapshot.headroom!!, 0.001)
        assertEquals(1, snapshot.hosts.size)
    }

    @Test
    fun capacityIgnoresTinyPlaylistBursts() {
        val now = 5_000_000L
        NetworkLoadStats.recordSample(
            NetworkSample("h:443", NetworkSampleKind.PLAYLIST, 120L, 130L, 1_200L, now, true),
        )
        val snapshot = NetworkLoadStats.snapshot(declaredBitrateBps = 1_000_000L, nowMs = now)
        assertNull(snapshot.sustainedCapacityBps)
        assertNull(snapshot.headroom)
    }

    @Test
    fun failureRateCountsFailuresAndSamplesInTheWindow() {
        val now = 7_000_000L
        repeat(3) { index ->
            NetworkLoadStats.recordSample(
                NetworkSample("h:7050", NetworkSampleKind.SEGMENT, 200L, 900L, 100_000L, now - index, true),
            )
        }
        NetworkLoadStats.recordFailure(
            NetworkFailureSample("h:7050", NetworkSampleKind.SEGMENT, 12_000L, 4_000L, now, "SocketTimeoutException"),
        )
        val snapshot = NetworkLoadStats.snapshot(nowMs = now)
        assertEquals(25, snapshot.failureRatePct)
    }

    @Test
    fun mtuPatternNeedsPlaylistOkShortBodyAndAHang() {
        assertTrue(isMtuSuspect(bytesBeforeFailure = 12_000L, playlistSucceededRecently = true, elapsedMs = 4_000L))
        assertFalse(isMtuSuspect(0L, true, 4_000L))
        assertFalse(isMtuSuspect(80_000L, true, 4_000L))
        assertFalse(isMtuSuspect(12_000L, false, 4_000L))
        assertFalse(isMtuSuspect(12_000L, true, 500L))
    }

    @Test
    fun sessionKpisComputeRatioStallsAndMttr() {
        val kpis = buildSessionKpis(
            startedAtEpochMs = 1_000L,
            durationMs = 600_000L,
            firstFrameAtElapsedMs = 3_500L,
            startedAtElapsedMs = 1_000L,
            rebufferCount = 4,
            rebufferDurationMs = 60_000L,
            behindLiveWindowCount = 2,
            fullReloadCount = 3,
            mttrMaxMs = 12_000L,
            mttrSamples = 2,
            longestCutSurvivedMs = 9_000L,
            bytesConsumed = 42_000_000L,
            profile = "Hotspot",
            hostKey = "190.61.101.11:7050",
            variantCount = 1,
            capacityBps = 5_800_000L,
            rttP95Ms = 1_900L,
            headroom = 2.95,
            appVersion = "1.0.6",
            device = "Xiaomi TV",
        )
        assertEquals(2_500L, kpis.timeToFirstFrameMs)
        assertEquals(10, kpis.rebufferRatioPct)
        assertEquals(24, kpis.stallsPerHour)
        assertEquals(12_000L, kpis.mttrMaxMs)
        assertEquals(9_000L, kpis.longestCutSurvivedMs)
        assertEquals(42_000_000L, kpis.bytesConsumed)
        assertEquals(1, kpis.variantCount)
    }

    @Test
    fun sessionKpisDoNotDivideByZeroOnVeryShortSessions() {
        val kpis = buildSessionKpis(
            startedAtEpochMs = 0L,
            durationMs = 0L,
            firstFrameAtElapsedMs = null,
            startedAtElapsedMs = 0L,
            rebufferCount = 0,
            rebufferDurationMs = 0L,
            behindLiveWindowCount = 0,
            fullReloadCount = 0,
            mttrMaxMs = null,
            mttrSamples = 0,
            longestCutSurvivedMs = 0L,
            bytesConsumed = 0L,
            profile = "Hotspot",
            hostKey = "h:1",
            variantCount = 0,
            capacityBps = null,
            rttP95Ms = null,
            headroom = null,
            appVersion = "x",
            device = "y",
        )
        assertEquals(0, kpis.rebufferRatioPct)
        assertEquals(0, kpis.stallsPerHour)
        assertNull(kpis.timeToFirstFrameMs)
    }
}

