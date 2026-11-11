package Nexotv.iptv.player

import Nexotv.iptv.player.player.StabilityConfig
import Nexotv.iptv.player.player.StabilityGovernor
import Nexotv.iptv.player.player.StabilityLevel
import Nexotv.iptv.player.player.heightCapApplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotStabilitySupportTest {
    @Test
    fun escalatesOneLevelPerEvaluationAndRespectsCooldown() {
        var now = 100_000L
        val governor = StabilityGovernor(
            config = StabilityConfig(escalationCooldownMs = 10_000L),
            clock = { now },
        )

        governor.record(7)
        assertEquals(StabilityLevel.CAUTIOUS, governor.evaluate())
        assertEquals(null, governor.evaluate())

        now += 10_000L
        assertEquals(StabilityLevel.DEGRADED, governor.evaluate())
        now += 10_000L
        assertEquals(StabilityLevel.SURVIVAL, governor.evaluate())
    }

    @Test
    fun decaysOldEventsAndRelaxesOneLevelAfterCalmPeriod() {
        var now = 100_000L
        val governor = StabilityGovernor(
            config = StabilityConfig(
                windowMs = 20_000L,
                escalationCooldownMs = 0L,
                calmPeriodMs = 10_000L,
            ),
            clock = { now },
        )

        governor.record(7)
        assertEquals(StabilityLevel.CAUTIOUS, governor.evaluate())
        assertEquals(StabilityLevel.DEGRADED, governor.evaluate())
        assertEquals(StabilityLevel.SURVIVAL, governor.evaluate())

        now += 21_000L
        assertEquals(0, governor.score())
        assertEquals(StabilityLevel.DEGRADED, governor.evaluate())
        now += 10_000L
        assertEquals(StabilityLevel.CAUTIOUS, governor.evaluate())
        now += 10_000L
        assertEquals(StabilityLevel.NORMAL, governor.evaluate())
    }

    @Test
    fun forcedEscalationSkipsCooldownAndStopsAtTheLastLevel() {
        var now = 100_000L
        val governor = StabilityGovernor(
            // Cooldown alto: escalateNow() debe ignorarlo por completo.
            config = StabilityConfig(escalationCooldownMs = 60_000L),
            clock = { now },
        )
        // Sin puntuación previa ni espera: un fallo de decodificador es concluyente.
        assertEquals(StabilityLevel.CAUTIOUS, governor.escalateNow())
        assertEquals(StabilityLevel.DEGRADED, governor.escalateNow())
        assertEquals(StabilityLevel.SURVIVAL, governor.escalateNow())
        assertEquals(null, governor.escalateNow())
        assertEquals(StabilityLevel.SURVIVAL, governor.level)
        // El cambio reinicia el reloj de calma: no se relaja de inmediato.
        assertEquals(null, governor.evaluate())
        now += 1_000L
        assertEquals(null, governor.evaluate())
    }

    @Test
    fun levelsDegradeBitrateMonotonicallyAndKeepAtLeast480p() {
        val levels = StabilityLevel.entries
        // De NORMAL a SURVIVAL: ni el bitrate ni la altura pueden subir.
        levels.zipWithNext().forEach { (masEstable, menosEstable) ->
            assertTrue(
                "$masEstable -> $menosEstable: bitrate",
                masEstable.maxVideoBitrateBps >= menosEstable.maxVideoBitrateBps,
            )
            assertTrue(
                "$masEstable -> $menosEstable: altura",
                masEstable.maxVideoHeightPx >= menosEstable.maxVideoHeightPx,
            )
        }
        // En 43" a 3 m, 360p se ve peor de lo que ahorra: el último nivel mantiene 480p.
        assertEquals(480, StabilityLevel.SURVIVAL.maxVideoHeightPx)
        assertTrue(levels.all { it.maxVideoHeightPx >= 480 })
    }

    @Test
    fun normalLevelCapsResolutionAt1080pWithoutCappingBitrate() {
        // El panel es 4K y reescala: no hace falta decodificar 4K en un A55 con 2 GB de RAM.
        assertEquals(1080, StabilityLevel.NORMAL.maxVideoHeightPx)
        // El bitrate sigue gobernado por el perfil de red y por el tope medido.
        assertEquals(Int.MAX_VALUE, StabilityLevel.NORMAL.maxVideoBitrateBps)
    }

    @Test
    fun heightCapIsSkippedWhenNoPublishedVariantFits() {
        // Sin pistas todavía se aplica; al llegar las pistas se reevalúa.
        assertTrue(heightCapApplies(emptyList(), 1080))
        assertTrue(heightCapApplies(listOf(720, 1080, 2160), 1080))
        assertTrue(heightCapApplies(listOf(720), 1080))
        // Solo 4K: imponer el tope dejaría el video sin pista seleccionable.
        assertFalse(heightCapApplies(listOf(2160), 1080))
        assertFalse(heightCapApplies(listOf(1080, 2160), 480))
    }
}
