package Nexotv.iptv.player.player

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.ArrayDeque

/** Niveles de adaptación. Siempre se combinan con el tope de bitrate existente de la app. */
internal enum class StabilityLevel(
    val maxVideoBitrateBps: Int,
    val maxVideoHeightPx: Int,
) {
    /**
     * Tope de 1080p incluso sin degradación.
     *
     * El panel es 4K y reescala, pero decodificar 4K en un Cortex-A55 con 2 GB de RAM no
     * aporta nada al stream ni al espectador. Además, un variante 4K sin `BANDWIDTH` declarado
     * se escapa del tope de bitrate porque Media3 no puede comparar su tasa.
     */
    NORMAL(Int.MAX_VALUE, 1080),
    CAUTIOUS(1_500_000, 540),
    DEGRADED(1_000_000, 480),
    /**
     * Último recurso: se baja el bitrate, no la resolución.
     *
     * En un panel de 43" visto a 3 m, 360p se nota mucho más que la pérdida de detalle de
     * 480p a 600 kbps. El ahorro de datos lo da el tope de bitrate; bajar además la altura
     * solo empeora la imagen.
     */
    SURVIVAL(600_000, 480);

    fun escalated(): StabilityLevel = entries.getOrElse(ordinal + 1) { this }
    fun relaxed(): StabilityLevel = entries.getOrElse(ordinal - 1) { this }
}

/** Alturas de video publicadas por el stream; ignora formatos sin altura declarada. */
internal fun videoHeights(tracks: androidx.media3.common.Tracks): List<Int> =
    tracks.groups
        .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
        .filter { it > 0 }

/**
 * true si conviene aplicar el tope de altura.
 *
 * Sin pistas todavía se aplica (se reevalúa cuando llegan). Si ninguna variante publicada cabe
 * dentro del tope, no se aplica: imponerlo dejaría el video sin pista seleccionable, que es peor
 * que reproducir una variante demasiado alta.
 */
internal fun heightCapApplies(availableHeights: List<Int>, cap: Int): Boolean =
    availableHeights.isEmpty() || availableHeights.any { it <= cap }

/** Parámetros del gobernador; se mantienen separados para permitir pruebas deterministas. */
internal data class StabilityConfig(
    val windowMs: Long = 90_000L,
    val cautiousScore: Int = 2,
    val degradedScore: Int = 4,
    val survivalScore: Int = 7,
    val escalationCooldownMs: Long = 15_000L,
    val calmPeriodMs: Long = 90_000L,
    val longStallMs: Long = 5_000L,
    val tickMs: Long = 5_000L,
    val stallWeight: Int = 1,
    val longStallWeight: Int = 1,
    val networkErrorWeight: Int = 2,
    val otherErrorWeight: Int = 1,
    val outageWeight: Int = 1,
    val minOutageForPenaltyMs: Long = 3_000L,
)

/** Lógica pura: puntúa eventos recientes y cambia como máximo un nivel por evaluación. */
internal class StabilityGovernor(
    private val config: StabilityConfig = StabilityConfig(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private data class Event(val atMs: Long, val weight: Int)
    private val events = ArrayDeque<Event>()
    private var lastEventMs = NEVER
    private var lastChangeMs = NEVER

    var level: StabilityLevel = StabilityLevel.NORMAL
        private set

    fun record(weight: Int) {
        if (weight <= 0) return
        val now = clock()
        events.addLast(Event(now, weight))
        lastEventMs = now
    }

    fun score(): Int {
        prune(clock())
        return events.sumOf(Event::weight)
    }

    fun evaluate(): StabilityLevel? {
        val now = clock()
        prune(now)
        val score = events.sumOf(Event::weight)
        val target = when {
            score >= config.survivalScore -> StabilityLevel.SURVIVAL
            score >= config.degradedScore -> StabilityLevel.DEGRADED
            score >= config.cautiousScore -> StabilityLevel.CAUTIOUS
            else -> StabilityLevel.NORMAL
        }
        val next = when {
            target.ordinal > level.ordinal && now - lastChangeMs >= config.escalationCooldownMs -> level.escalated()
            target.ordinal < level.ordinal && now - maxOf(lastEventMs, lastChangeMs) >= config.calmPeriodMs -> level.relaxed()
            else -> level
        }
        if (next == level) return null
        level = next
        lastChangeMs = now
        return next
    }

    fun reset() {
        events.clear()
        level = StabilityLevel.NORMAL
        lastEventMs = NEVER
        lastChangeMs = NEVER
    }

    /**
     * Sube un nivel sin esperar a la puntuación ni al periodo de calma.
     *
     * Se usa cuando el fallo ya es concluyente por sí mismo (por ejemplo, el decodificador no
     * puede con la pista): seguir probando la misma calidad solo repite el error.
     * Devuelve el nivel nuevo, o `null` si ya estaba en el último.
     */
    fun escalateNow(): StabilityLevel? {
        val next = level.escalated()
        if (next == level) return null
        level = next
        lastChangeMs = clock()
        return next
    }

    private fun prune(now: Long) {
        while (events.isNotEmpty() && now - events.first().atMs > config.windowMs) events.removeFirst()
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
    }
}

/**
 * Observa rebuffers y errores del reproductor. Solo modifica los topes de selección de video;
 * no llama a prepare/stop/seek ni sustituye la recuperación existente.
 */
@UnstableApi
internal class HotspotStabilizer(
    private val config: StabilityConfig = StabilityConfig(),
    private val baseMaxVideoBitrateBps: () -> Int,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val onLevelChanged: (StabilityLevel, StabilityLevel, String) -> Unit = { _, _, _ -> },
) : Player.Listener {
    private val governor = StabilityGovernor(config, clock)
    private var player: ExoPlayer? = null
    private var handler: Handler? = null
    private var baselineOwner: ExoPlayer? = null
    private var baselineWidth = Int.MAX_VALUE
    private var baselineHeight = Int.MAX_VALUE
    private var hasPlayed = false
    private var stallStartMs = NO_TIME
    private var longStallCounted = false
    private var outageStartMs = NO_TIME
    private var warnedAboutHeight = false

    val level: StabilityLevel get() = governor.level
    val score: Int get() = governor.score()

    private val tick = object : Runnable {
        override fun run() {
            val now = clock()
            if (stallStartMs != NO_TIME && !longStallCounted && outageStartMs == NO_TIME &&
                now - stallStartMs >= config.longStallMs
            ) {
                longStallCounted = true
                governor.record(config.longStallWeight)
            }
            evaluate("tick")
            handler?.postDelayed(this, config.tickMs)
        }
    }

    fun attach(newPlayer: ExoPlayer) {
        detach()
        if (baselineOwner !== newPlayer) {
            val params = newPlayer.trackSelectionParameters
            baselineWidth = params.maxVideoWidth
            baselineHeight = params.maxVideoHeight
            baselineOwner = newPlayer
        }
        player = newPlayer
        handler = Handler(newPlayer.applicationLooper)
        warnedAboutHeight = false
        newPlayer.addListener(this)
        applyConstraints()
        handler?.postDelayed(tick, config.tickMs)
    }

    fun detach() {
        handler?.removeCallbacks(tick)
        player?.removeListener(this)
        player = null
        handler = null
        hasPlayed = false
        closeStall()
    }

    /** Llamar cuando cambie el tope base por cambio de perfil o red medida. */
    fun refresh() = applyConstraints()

    /**
     * Baja la calidad un nivel de inmediato y reaplica los topes.
     *
     * Un fallo de decodificador se repite si se reconstruye el reproductor con la misma pista.
     * Bajar la calidad antes del reinicio da al decodificador nuevo una variante más ligera, en
     * lugar de caer al decodificador por software en un A55 con 2 GB de RAM.
     */
    fun forceEscalate(reason: String): StabilityLevel? {
        val previous = governor.level
        val current = governor.escalateNow() ?: return null
        applyConstraints()
        onLevelChanged(previous, current, reason)
        return current
    }

    fun onNetworkLost() {
        if (outageStartMs == NO_TIME) outageStartMs = clock()
    }

    fun onNetworkAvailable() {
        val started = outageStartMs
        if (started == NO_TIME) return
        outageStartMs = NO_TIME
        if (clock() - started >= config.minOutageForPenaltyMs) {
            governor.record(config.outageWeight)
            evaluate("outage")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                val current = player ?: return
                if (hasPlayed && stallStartMs == NO_TIME && current.playWhenReady) {
                    stallStartMs = clock()
                    longStallCounted = false
                    if (outageStartMs == NO_TIME) {
                        governor.record(config.stallWeight)
                        evaluate("rebuffer")
                    }
                }
            }
            Player.STATE_READY -> {
                hasPlayed = true
                closeStall()
            }
            else -> {
                hasPlayed = false
                closeStall()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        hasPlayed = false
        closeStall()
    }

    /** Las alturas publicadas solo se conocen al preparar las pistas: hay que reevaluar el tope. */
    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
        applyConstraints()
    }

    override fun onPlayerError(error: PlaybackException) {
        val code = error.errorCode
        val weight = when {
            code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> 0
            code in IO_ERROR_RANGE -> config.networkErrorWeight
            code in DECODER_ERROR_RANGE -> 0
            else -> config.otherErrorWeight
        }
        if (weight > 0 && outageStartMs == NO_TIME) {
            governor.record(weight)
            evaluate("error:$code")
        }
    }

    private fun closeStall() {
        stallStartMs = NO_TIME
        longStallCounted = false
    }

    private fun evaluate(reason: String) {
        val previous = governor.level
        val current = governor.evaluate() ?: return
        applyConstraints()
        onLevelChanged(previous, current, reason)
    }

    private fun applyConstraints() {
        val current = player ?: return
        val level = governor.level
        val cap = minOf(baseMaxVideoBitrateBps().coerceAtLeast(1), level.maxVideoBitrateBps)
        val height = heightLimitFor(current, level)
        val params = current.trackSelectionParameters
        if (params.maxVideoBitrate == cap && params.maxVideoHeight == height && params.maxVideoWidth == baselineWidth) return
        current.trackSelectionParameters = params.buildUpon()
            .setMaxVideoBitrate(cap)
            .setMaxVideoSize(baselineWidth, height)
            .build()
    }

    /**
     * Tope de altura del nivel, salvo que ninguna variante publicada quepa en él. En ese caso
     * se deja sin tope para no quedarse sin pista de video y se registra una sola vez.
     */
    private fun heightLimitFor(current: ExoPlayer, level: StabilityLevel): Int {
        val desired = minOf(baselineHeight, level.maxVideoHeightPx)
        if (desired == Int.MAX_VALUE) return Int.MAX_VALUE
        val available = videoHeights(current.currentTracks)
        if (heightCapApplies(available, desired)) return desired
        if (!warnedAboutHeight) {
            warnedAboutHeight = true
            Log.w(
                TAG,
                "Ninguna variante cabe en ${desired}p (disponibles: $available); se omite el tope de altura",
            )
        }
        return Int.MAX_VALUE
    }

    private companion object {
        const val TAG = "HotspotStabilizer"
        const val NO_TIME = -1L
        val IO_ERROR_RANGE = 2000..2999
        val DECODER_ERROR_RANGE = 4000..4999
    }
}
