package Nexotv.iptv.player

import Nexotv.iptv.player.player.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloque 0 y 1: reglas puras de timeouts, reintentos de segmento, reanudación de red y
 * sondeo sin VALIDATED. Todas se prueban con valores y relojes controlados.
 */
class Round2NetworkPolicyTest {

    // ─── 1.1 Timeouts adaptativos ───

    @Test
    fun adaptiveTimeoutsShrinkWhenThereAreNoSamplesButStayAboveFourSeconds() {
        val timeouts = PlayerTuning.adaptiveTimeouts(null)
        assertEquals(3_500, timeouts.connectMs)
        assertEquals(4_000, timeouts.readMs)
    }

    @Test
    fun adaptiveTimeoutsGrowWithMeasuredRttButNeverExceedWindowHalf() {
        val idle = PlayerTuning.adaptiveTimeouts(114L)
        assertEquals(3_500, idle.connectMs)
        assertEquals(4_000, idle.readMs)

        val loaded = PlayerTuning.adaptiveTimeouts(1_900L)
        assertEquals(4_350, loaded.connectMs)
        assertEquals(5_350, loaded.readMs)

        val peak = PlayerTuning.adaptiveTimeouts(3_400L)
        assertEquals(5_000, peak.connectMs)
        assertEquals(5_500, peak.readMs)

        val absurd = PlayerTuning.adaptiveTimeouts(60_000L)
        assertEquals(PlayerTuning.MAX_CONNECT_TIMEOUT_MS, absurd.connectMs)
        assertEquals(PlayerTuning.MAX_READ_TIMEOUT_MS, absurd.readMs)
    }

    @Test
    fun onlyHotspotUsesAdaptiveTimeouts() {
        val hotspot = BufferSizes.connectTimeoutMs(BufferSizes.PROFILE_HOTSPOT, 3_400L)
        assertEquals(5_000, hotspot)
        assertEquals(5_500, BufferSizes.readTimeoutMs(BufferSizes.PROFILE_HOTSPOT, 3_400L))

        listOf(BufferSizes.PROFILE_LARGE, BufferSizes.PROFILE_MEDIUM, BufferSizes.PROFILE_SMALL).forEach {
            assertEquals(8_000, BufferSizes.connectTimeoutMs(it, 3_400L))
            assertEquals(8_000, BufferSizes.readTimeoutMs(it, 3_400L))
        }
    }

    @Test
    fun adaptiveTimeoutsCanBeDisabledForAB() {
        PlayerTuning.resetFlags()
        assertTrue(PlayerTuning.adaptiveTimeouts)
        PlayerTuning.setFlag("adaptiveTimeouts", false)
        try {
            assertEquals(20_000, BufferSizes.connectTimeoutMs(BufferSizes.PROFILE_HOTSPOT, 3_400L))
            assertEquals(25_000, BufferSizes.readTimeoutMs(BufferSizes.PROFILE_HOTSPOT, 3_400L))
        } finally {
            PlayerTuning.resetFlags()
        }
    }

    // ─── 1.4 Reintentos de segmento ───

    @Test
    fun definitiveHttpErrorsAreNotRetried() {
        assertEquals(
            SegmentRetryKind.DEFINITIVE,
            SegmentRetryPolicy.classify(httpStatus = 404, timedOut = false, causeNames = emptyList()),
        )
        assertEquals(
            SegmentRetryKind.DEFINITIVE,
            SegmentRetryPolicy.classify(httpStatus = 410, timedOut = false, causeNames = emptyList()),
        )
        val decision = SegmentRetryPolicy.decision(SegmentRetryKind.DEFINITIVE, errorCount = 1)
        assertNull(decision.delayMs)
    }

    @Test
    fun authGetsOneSparseRetryToTolerateAnExitIpChange() {
        assertEquals(
            SegmentRetryKind.AUTH,
            SegmentRetryPolicy.classify(httpStatus = 403, timedOut = false, causeNames = emptyList()),
        )
        val first = SegmentRetryPolicy.decision(
            SegmentRetryKind.AUTH,
            errorCount = 1,
            random = kotlin.random.Random(1),
        )
        assertTrue(first.delayMs != null && first.delayMs!! in 3_200L..4_800L)
        assertNull(SegmentRetryPolicy.decision(SegmentRetryKind.AUTH, errorCount = 2).delayMs)
    }

    @Test
    fun timeoutBudgetStaysWithinTwoAttempts() {
        assertEquals(
            SegmentRetryKind.TIMEOUT,
            SegmentRetryPolicy.classify(httpStatus = null, timedOut = true, causeNames = emptyList()),
        )
        val first = SegmentRetryPolicy.decision(
            SegmentRetryKind.TIMEOUT,
            errorCount = 1,
            random = kotlin.random.Random(2),
        )
        assertTrue(first.delayMs != null && first.delayMs!! in 200L..300L)
        assertNull(SegmentRetryPolicy.decision(SegmentRetryKind.TIMEOUT, errorCount = 2).delayMs)
    }

    @Test
    fun transientNetworkErrorsAllowTwoShortRetries() {
        val first = SegmentRetryPolicy.decision(
            SegmentRetryKind.TRANSIENT,
            errorCount = 1,
            random = kotlin.random.Random(3),
        )
        val second = SegmentRetryPolicy.decision(
            SegmentRetryKind.TRANSIENT,
            errorCount = 2,
            random = kotlin.random.Random(3),
        )
        assertTrue(first.delayMs != null && first.delayMs!! in 200L..300L)
        assertTrue(second.delayMs != null && second.delayMs!! in 720L..1_080L)
        assertNull(SegmentRetryPolicy.decision(SegmentRetryKind.TRANSIENT, errorCount = 3).delayMs)
    }

    @Test
    fun serverErrorsGetOneSpacedRetry() {
        assertEquals(
            SegmentRetryKind.SERVER,
            SegmentRetryPolicy.classify(httpStatus = 503, timedOut = false, causeNames = emptyList()),
        )
        val first = SegmentRetryPolicy.decision(
            SegmentRetryKind.SERVER,
            errorCount = 1,
            random = kotlin.random.Random(4),
        )
        assertTrue(first.delayMs != null && first.delayMs!! in 720L..1_080L)
        assertNull(SegmentRetryPolicy.decision(SegmentRetryKind.SERVER, errorCount = 2).delayMs)
    }

    @Test
    fun retryBudgetNeverExceedsMedia3MinimumAttempts() {
        // 3 intentos totales = 2 reintentos; el presupuesto real lo recorta decision().
        assertEquals(3, SegmentRetryPolicy.MIN_LOADABLE_RETRY_COUNT)
        assertFalse(SegmentRetryPolicy.decision(SegmentRetryKind.TRANSIENT, 0).delayMs != null)
    }

    // ─── 1.5 Reanudación no destructiva ───

    @Test
    fun networkResumeKeepsPlayingWhenBufferIsHealthy() {
        val action = decideNetworkResume(
            playbackState = androidx.media3.common.Player.STATE_READY,
            bufferedMs = 6_000L,
            msSinceProgress = 0L,
        )
        assertEquals(NetworkResumeAction.KEEP_PLAYING, action)
    }

    @Test
    fun networkResumeDoesLightRestartWhenBufferingButProgressIsRecent() {
        val action = decideNetworkResume(
            playbackState = androidx.media3.common.Player.STATE_BUFFERING,
            bufferedMs = 500L,
            msSinceProgress = 3_000L,
        )
        assertEquals(NetworkResumeAction.LIGHT_RESTART, action)
    }

    @Test
    fun networkResumeFallsBackToFullReloadWithoutProgress() {
        val action = decideNetworkResume(
            playbackState = androidx.media3.common.Player.STATE_BUFFERING,
            bufferedMs = 500L,
            msSinceProgress = PlayerTuning.NETWORK_RESUME_FULL_RELOAD_AFTER_MS + 1,
        )
        assertEquals(NetworkResumeAction.FULL_RELOAD, action)

        val idle = decideNetworkResume(
            playbackState = androidx.media3.common.Player.STATE_IDLE,
            bufferedMs = 0L,
            msSinceProgress = 0L,
        )
        assertEquals(NetworkResumeAction.FULL_RELOAD, idle)
    }

    // ─── 1.6 Red sin VALIDATED ───

    @Test
    fun unvalidatedProbeNeedsTransportInternetAndAFloorInterval() {
        val grace = PlayerTuning.UNVALIDATED_PROBE_GRACE_MS
        val interval = PlayerTuning.UNVALIDATED_PROBE_INTERVAL_MS

        assertFalse(NetworkTuning.shouldProbeUnvalidated(0L, false, false, Long.MAX_VALUE))
        assertFalse(NetworkTuning.shouldProbeUnvalidated(grace, true, false, Long.MAX_VALUE))
        assertFalse(NetworkTuning.shouldProbeUnvalidated(grace - 1, true, true, Long.MAX_VALUE))
        assertFalse(NetworkTuning.shouldProbeUnvalidated(grace, true, true, interval - 1))
        assertTrue(NetworkTuning.shouldProbeUnvalidated(grace, true, true, interval))
        assertTrue(NetworkTuning.shouldProbeUnvalidated(grace * 10, true, true, interval * 3))
    }

    @Test
    fun validatedRecheckKeepsItsTwentySecondGrace() {
        assertFalse(NetworkTuning.shouldRecheckLostState(19_999L, true))
        assertTrue(NetworkTuning.shouldRecheckLostState(20_000L, true))
        assertFalse(NetworkTuning.shouldRecheckLostState(60_000L, false))
    }

    // ─── Carrera entre los tres niveles de recuperación ───

    @Test
    fun onlyOneRecoveryCanBeScheduledAtATime() {
        // Un reintento ya programado bloquea al watchdog y a la política de carga.
        assertFalse(shouldScheduleRecovery(networkResumePending = false, retryPending = true, networkBlocked = false))
        // Una reanudación de red en curso bloquea cualquier recuperación duplicada.
        assertFalse(shouldScheduleRecovery(networkResumePending = true, retryPending = false, networkBlocked = false))
        // Sin red disponible no se programa nada hasta que la sonda pueda reproducir.
        assertFalse(shouldScheduleRecovery(networkResumePending = false, retryPending = false, networkBlocked = true))
        // Solo con los tres libres se programa un reinicio.
        assertTrue(shouldScheduleRecovery(networkResumePending = false, retryPending = false, networkBlocked = false))
    }

    // ─── 0.3 Reloj falso: cadencia de reintentos del stream completo ───

    /**
     * Simula cortes de 5/10/15/30/120 s con el reloj avanzando solo por las esperas
     * programadas. Comprueba que el plan de reintentos no se rinde y que tras los seis
     * rápidos empieza el reintento lento de 60 s ±20 %.
     */
    @Test
    fun streamRetryCadenceSurvivesCutsOfFiveToTwoMinutes() {
        listOf(5_000L, 10_000L, 15_000L, 30_000L, 120_000L).forEach { cutMs ->
            var clock = 0L
            var attempts = 0
            val delays = mutableListOf<Long>()
            val random = kotlin.random.Random(11)
            while (clock < cutMs && attempts < 12) {
                attempts++
                val delay = BufferSizes.streamRetryDelayMs(attempts, MainActivity.MAX_RETRIES, random)
                delays += delay
                clock += delay
            }
            assertTrue("corte=$cutMs sin intentos", attempts > 0)
            assertTrue("corte=$cutMs no recuperó en 12 intentos: $delays", clock >= cutMs)
            assertTrue("espera inicial no positiva: $delays", delays.first() > 0L)
            if (attempts > MainActivity.MAX_RETRIES) {
                assertTrue(
                    "tras los rápidos debe venir el reintento lento: $delays",
                    delays.last() >= 48_000L,
                )
            }
        }
    }

    /**
     * Presupuesto de un segmento colgado, antes y después.
     *
     * Antes: 5 intentos × (20 s conexión + 25 s lectura) + backoff 1+2+4+8 s ≈ 240 s.
     * Después (timeout): 2 intentos × 5,5 s + 250 ms ≈ 11,3 s, y el fallo escala a
     * `recoverStream()` en lugar de seguir consumiendo la ventana.
     */
    @Test
    fun oneStuckSegmentBurnsMuchLessThanTheLegacyPolicy() {
        val legacyAttempts = 5
        val legacyPerAttemptMs = 20_000L + 25_000L
        val legacyBackoffMs = 1_000L + 2_000L + 4_000L + 8_000L
        val legacyWorstMs = legacyAttempts * legacyPerAttemptMs + legacyBackoffMs

        val newAttempts = 2
        val newWorstMs = newAttempts * PlayerTuning.MAX_READ_TIMEOUT_MS + PlayerTuning.FIRST_RETRY_DELAY_MS

        assertTrue("legado=$legacyWorstMs", legacyWorstMs >= 240_000L)
        assertTrue("nuevo=$newWorstMs", newWorstMs <= 12_000L)
        assertTrue("nuevo=$newWorstMs legado=$legacyWorstMs", newWorstMs < legacyWorstMs / 10)
    }
}


