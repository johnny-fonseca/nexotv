package Nexotv.iptv.player

import Nexotv.iptv.player.player.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTuningTest {
    @Test
    fun everyProfileSatisfiesLoadControlAndLiveRanges() {
        listOf(BufferSizes.PROFILE_HOTSPOT, BufferSizes.PROFILE_LARGE, BufferSizes.PROFILE_MEDIUM, BufferSizes.PROFILE_SMALL)
            .forEach { profile ->
                val d = BufferSizes.validatedDurations(BufferSizes.durations(profile))
                assertTrue("$profile playback", d.bufferForPlaybackMs <= d.minBufferMs)
                assertTrue("$profile rebuffer", d.bufferForPlaybackAfterRebufferMs <= d.minBufferMs)
                assertTrue("$profile min/max", d.minBufferMs <= d.maxBufferMs)
                assertTrue("$profile offsets", d.liveMinOffsetMs <= d.liveTargetOffsetMs && d.liveTargetOffsetMs <= d.liveMaxOffsetMs)
                assertTrue("$profile speeds", d.liveMinPlaybackSpeed <= d.liveMaxPlaybackSpeed)
            }
    }

    @Test
    fun corruptDurationsAreClampedBeforeLoadControl() {
        val d = BufferSizes.validatedDurations(BufferDurations(
            minBufferMs = -1,
            maxBufferMs = -4,
            bufferForPlaybackMs = 900,
            bufferForPlaybackAfterRebufferMs = 1_000,
            liveMinOffsetMs = 8_000L,
            liveTargetOffsetMs = 2_000L,
            liveMaxOffsetMs = 1_000L,
            liveMinPlaybackSpeed = Float.NaN,
            liveMaxPlaybackSpeed = 0.5f,
        ))
        assertEquals(1, d.minBufferMs)
        assertEquals(1, d.maxBufferMs)
        assertEquals(1, d.bufferForPlaybackMs)
        assertEquals(1, d.bufferForPlaybackAfterRebufferMs)
        assertTrue(d.liveMinOffsetMs <= d.liveTargetOffsetMs && d.liveTargetOffsetMs <= d.liveMaxOffsetMs)
        assertTrue(d.liveMinPlaybackSpeed <= d.liveMaxPlaybackSpeed)
    }

    @Test
    fun liveOffsetsAreClampedForShortNormalAndUnknownWindows() {
        val profile = BufferSizes.durations(BufferSizes.PROFILE_HOTSPOT)
        val short = BufferSizes.offsetsForWindow(profile, 1_000L)
        assertEquals(LiveOffsets(0L, 0L, 0L), short)

        val normal = BufferSizes.offsetsForWindow(profile, 11_400L)
        assertEquals(LiveOffsets(3_000L, 8_000L, 9_500L), normal)

        val unknown = BufferSizes.offsetsForWindow(profile, 0L)
        assertEquals(LiveOffsets(3_000L, 8_000L, 9_500L), unknown)
    }

    @Test
    fun backoffIsExponentialAndCapped() {
        assertEquals(0L, BufferSizes.backoffMs(0))
        assertEquals(1_000L, BufferSizes.backoffMs(1))
        assertEquals(2_000L, BufferSizes.backoffMs(2))
        assertEquals(4_000L, BufferSizes.backoffMs(3))
        assertEquals(BufferSizes.MAX_BACKOFF_MS, BufferSizes.backoffMs(100))
    }

    @Test
    fun jitteredBackoffStaysWithinBoundsAndCap() {
        val firstAttempt = BufferSizes.backoffWithJitterMs(1, random = kotlin.random.Random(42))
        assertTrue(firstAttempt in 800L..1_200L)

        val cappedAttempt = BufferSizes.backoffWithJitterMs(100, random = kotlin.random.Random(42))
        assertTrue(cappedAttempt in 24_000L..BufferSizes.MAX_BACKOFF_MS)
    }

    @Test
    fun streamRetryFallsBackToSlowRetryAfterFastAttempts() {
        val random = { kotlin.random.Random(7) }
        assertEquals(0L, BufferSizes.streamRetryDelayMs(0, MainActivity.MAX_RETRIES, random()))

        // Intentos rápidos: backoff exponencial con el tope de 30 s.
        assertTrue(BufferSizes.streamRetryDelayMs(1, MainActivity.MAX_RETRIES, random()) in 800L..1_200L)
        assertTrue(
            BufferSizes.streamRetryDelayMs(MainActivity.MAX_RETRIES, MainActivity.MAX_RETRIES, random()) in
                24_000L..BufferSizes.MAX_BACKOFF_MS,
        )

        // Agotados los rápidos: espera lenta de 60 s ±20 %, sin recortarse al tope del backoff.
        val slow = BufferSizes.streamRetryDelayMs(MainActivity.MAX_RETRIES + 1, MainActivity.MAX_RETRIES, random())
        assertTrue("lento=$slow", slow in 48_000L..72_000L)
        val verySlow = BufferSizes.streamRetryDelayMs(100, MainActivity.MAX_RETRIES, random())
        assertTrue("lento=$verySlow", verySlow in 48_000L..72_000L)

        // Valor nominal y jitter desactivable (lo usa el reintento lento).
        assertEquals(BufferSizes.SLOW_RETRY_DELAY_MS, BufferSizes.jitteredMs(BufferSizes.SLOW_RETRY_DELAY_MS, 0f))
        assertTrue(BufferSizes.SLOW_RETRY_DELAY_MS > BufferSizes.MAX_BACKOFF_MS)
    }

    @Test
    fun lostNetworkIsRecheckedOnlyWithValidatedInternetAfterGracePeriod() {
        // Sin internet validado nunca se descarta la pérdida: el corte puede ser real.
        assertTrue(!NetworkTuning.shouldRecheckLostState(0L, false))
        assertTrue(!NetworkTuning.shouldRecheckLostState(600_000L, false))

        // Con internet validado se espera el margen antes de dudar del evento del sistema.
        assertTrue(!NetworkTuning.shouldRecheckLostState(0L, true))
        assertTrue(
            !NetworkTuning.shouldRecheckLostState(
                NetworkTuning.LOST_RECOVERY_RECHECK_MS - 1L,
                true,
            ),
        )
        assertTrue(NetworkTuning.shouldRecheckLostState(NetworkTuning.LOST_RECOVERY_RECHECK_MS, true))
        assertTrue(NetworkTuning.shouldRecheckLostState(NetworkTuning.LOST_RECOVERY_RECHECK_MS * 3, true))
    }

    @Test
    fun hotspotUsesTimeoutsAndBandwidthEstimateForMobileNetworks() {
        // Ronda 2: sin muestras se usa un RTT intermedio y timeouts adaptativos (bloque 1.1).
        // Los valores fijos de la ronda 1 quedan disponibles con la bandera en OFF.
        PlayerTuning.resetFlags()
        assertEquals(3_500, BufferSizes.connectTimeoutMs(BufferSizes.PROFILE_HOTSPOT))
        assertEquals(4_000, BufferSizes.readTimeoutMs(BufferSizes.PROFILE_HOTSPOT))
        assertEquals(800_000L, BufferSizes.initialBitrateEstimateFor(BufferSizes.PROFILE_HOTSPOT))
        assertEquals(null, BufferSizes.initialBitrateEstimateFor(BufferSizes.PROFILE_MEDIUM))
    }

    @Test
    fun videoBitrateLimitFollowsNetworkProfile() {
        assertEquals(
            BufferSizes.HOTSPOT_MAX_VIDEO_BITRATE,
            BufferSizes.maxVideoBitrateFor(BufferSizes.PROFILE_HOTSPOT),
        )
        assertEquals(null, BufferSizes.maxVideoBitrateFor(BufferSizes.PROFILE_LARGE))
        assertEquals(null, BufferSizes.maxVideoBitrateFor(BufferSizes.PROFILE_MEDIUM))
        assertEquals(null, BufferSizes.maxVideoBitrateFor(BufferSizes.PROFILE_SMALL))
    }
}
