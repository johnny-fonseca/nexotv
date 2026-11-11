// ─── PlayerTuning.kt ───
@file:Suppress("unused", "ApplySharedPref")

package Nexotv.iptv.player.player

import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.Player
import kotlin.random.Random

// Lectura y escritura segura de SharedPreferences

private inline fun <T> SharedPreferences.safeRead(default: T, block: SharedPreferences.() -> T): T =
    try {
        block()
    } catch (_: Exception) {
        default
    }

internal fun SharedPreferences.safeString(key: String, default: String? = null): String? =
    safeRead(default) { getString(key, default) }

internal fun SharedPreferences.safeBoolean(key: String, default: Boolean = false): Boolean =
    safeRead(default) { getBoolean(key, default) }

internal fun SharedPreferences.safeInt(key: String, default: Int = 0): Int =
    safeRead(default) { getInt(key, default) }

internal fun SharedPreferences.safeLong(key: String, default: Long = 0L): Long =
    safeRead(default) { getLong(key, default) }

internal fun SharedPreferences.safeFloat(key: String, default: Float = 0f): Float =
    safeRead(default) { getFloat(key, default) }

/** Copia el conjunto devuelto: el de SharedPreferences no se debe modificar. */
internal fun SharedPreferences.safeStringSet(key: String, default: Set<String> = emptySet()): Set<String> =
    safeRead(default) { getStringSet(key, default)?.toSet() ?: default }

internal fun SharedPreferences.safeIntIn(key: String, default: Int, range: IntRange): Int {
    if (range.isEmpty()) return default
    return safeInt(key, default).coerceIn(range)
}

internal fun SharedPreferences.safeLongIn(key: String, default: Long, range: LongRange): Long {
    if (range.isEmpty()) return default
    return safeLong(key, default).coerceIn(range)
}

internal fun SharedPreferences.safeFloatIn(key: String, default: Float, min: Float, max: Float): Float {
    if (!min.isFinite() || !max.isFinite() || min > max) return default
    val value = safeFloat(key, default).takeIf { it.isFinite() }
        ?: default.takeIf { it.isFinite() }
        ?: min
    return value.coerceIn(min, max)
}

/** Lee un enum guardado por nombre; ignora mayúsculas y espacios. */
internal inline fun <reified E : Enum<E>> SharedPreferences.safeEnum(key: String, default: E): E {
    val raw = safeString(key)?.trim() ?: return default
    return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
}

/** Aplica los cambios en un solo editor; devuelve false si algo falla. */
internal fun SharedPreferences.safeEdit(block: SharedPreferences.Editor.() -> Unit): Boolean =
    try {
        edit { block() }
        true
    } catch (_: Exception) {
        false
    }

// ─── Valores por defecto compartidos ───

private const val DEFAULT_BACK_BUFFER_MS = 10_000
private const val DEFAULT_LIVE_TARGET_OFFSET_MS = 6_000L
private const val DEFAULT_LIVE_MIN_OFFSET_MS = 3_000L
private const val DEFAULT_LIVE_MAX_OFFSET_MS = 9_500L
private const val DEFAULT_LIVE_MIN_SPEED = 0.97f
private const val DEFAULT_LIVE_MAX_SPEED = 1.03f

internal data class BufferDurations(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int = DEFAULT_BACK_BUFFER_MS,
    val liveTargetOffsetMs: Long = DEFAULT_LIVE_TARGET_OFFSET_MS,
    val liveMinOffsetMs: Long = DEFAULT_LIVE_MIN_OFFSET_MS,
    // Límite superior de offset anclado a la ventana real del servidor.
    // Si supera la ventana disponible, ExoPlayer provoca BEHIND_LIVE_WINDOW.
    val liveMaxOffsetMs: Long = DEFAULT_LIVE_MAX_OFFSET_MS,
    val liveMinPlaybackSpeed: Float = DEFAULT_LIVE_MIN_SPEED,
    val liveMaxPlaybackSpeed: Float = DEFAULT_LIVE_MAX_SPEED,
) {
    /** true si los valores ya cumplen las reglas de Media3 sin necesidad de sanearlos. */
    val isValid: Boolean get() = BufferSizes.validationIssues(this).isEmpty()
}

internal data class LiveOffsets(val minMs: Long, val targetMs: Long, val maxMs: Long) {
    val isConsistent: Boolean get() = minMs in 0L..targetMs && targetMs <= maxMs
}

/**
 * Banderas de la ronda 2. Los valores por defecto son los que se validaron con pruebas
 * unitarias y con el análisis de la ventana HLS medida; el laboratorio de depuración puede
 * cambiarlos en caliente para comparar A/B sin recompilar.
 *
 * Ninguna bandera se puede activar desde el APK release: el receptor que las modifica solo
 * existe en el source set `debug` (ver `DebugFaultReceiver`). En release estas variables
 * conservan siempre el valor por defecto.
 */
internal object PlayerTuning {
    // ─── Bloque 1.1: timeouts adaptativos (solo Hotspot) ───
    // Se activa porque 20 s de conexión y 25 s de lectura permiten que un segmento colgado
    // consuma más que la ventana completa del directo (~11,5 s). El mínimo de 4 s cubre el
    // RTT medido en reposo (~114 ms) con margen; el máximo de 5,5 s cubre los picos de ~3,4 s.
    const val ADAPTIVE_TIMEOUTS_DEFAULT = true

    // ─── Bloque 1.4: reintentos de segmento dentro de la ventana ───
    const val IN_WINDOW_RETRIES_DEFAULT = true

    // ─── Bloque 1.5: reconexión no destructiva y antirrebote ───
    const val NON_DESTRUCTIVE_RESUME_DEFAULT = true

    // ─── Bloque 1.6: sondeo con transporte e INTERNET aunque no haya VALIDATED ───
    const val UNVALIDATED_RETRY_DEFAULT = true

    @Volatile var adaptiveTimeouts: Boolean = ADAPTIVE_TIMEOUTS_DEFAULT
    @Volatile var inWindowRetries: Boolean = IN_WINDOW_RETRIES_DEFAULT
    @Volatile var nonDestructiveResume: Boolean = NON_DESTRUCTIVE_RESUME_DEFAULT
    @Volatile var unvalidatedRetry: Boolean = UNVALIDATED_RETRY_DEFAULT

    /** Nombres aceptados por el laboratorio de depuración. */
    val FLAG_NAMES: List<String> = listOf(
        "adaptiveTimeouts",
        "inWindowRetries",
        "nonDestructiveResume",
        "unvalidatedRetry",
    )

    /**
     * Cambia una bandera en caliente. Devuelve false si el nombre no existe.
     *
     * Solo la llama el receptor de depuración; en release nadie la invoca.
     */
    fun setFlag(name: String, enabled: Boolean): Boolean = when (name.trim()) {
        "adaptiveTimeouts" -> { adaptiveTimeouts = enabled; true }
        "inWindowRetries" -> { inWindowRetries = enabled; true }
        "nonDestructiveResume" -> { nonDestructiveResume = enabled; true }
        "unvalidatedRetry" -> { unvalidatedRetry = enabled; true }
        else -> false
    }

    /** Restaura los valores por defecto (pruebas y reinicio del laboratorio). */
    fun resetFlags() {
        adaptiveTimeouts = ADAPTIVE_TIMEOUTS_DEFAULT
        inWindowRetries = IN_WINDOW_RETRIES_DEFAULT
        nonDestructiveResume = NON_DESTRUCTIVE_RESUME_DEFAULT
        unvalidatedRetry = UNVALIDATED_RETRY_DEFAULT
    }

    // ─── Timeouts adaptativos ───

    /**
     * RTT de referencia cuando todavía no hay muestras.
     *
     * Es un valor intermedio entre los ~114 ms medidos en reposo y los ~1,9 s de media bajo
     * descarga: ni tan corto que dé falsos positivos en el arranque, ni tan largo que un
     * segmento colgado supere la ventana.
     */
    const val DEFAULT_RTT_P95_MS = 250L

    const val TIMEOUT_RTT_FACTOR = 1.5
    const val READ_TIMEOUT_MARGIN_MS = 2_500L
    const val CONNECT_TIMEOUT_MARGIN_MS = 1_500L
    const val MIN_READ_TIMEOUT_MS = 4_000
    const val MAX_READ_TIMEOUT_MS = 5_500
    const val MIN_CONNECT_TIMEOUT_MS = 3_500
    const val MAX_CONNECT_TIMEOUT_MS = 5_000

    // ─── Bloque 1.5: reconexión ───

    /** Antirrebote de los eventos de red que oscilan (hotspot que cae y vuelve). */
    const val NETWORK_RESUME_DEBOUNCE_MS = 1_500L

    /** Con este buffer en READY no se toca el reproductor al volver la red. */
    const val NETWORK_RESUME_READY_BUFFER_MS = 4_000L

    /** Sin progreso de posición durante este tiempo, la vuelta de red hace recarga completa. */
    const val NETWORK_RESUME_FULL_RELOAD_AFTER_MS = 8_000L

    /** Tras un BEHIND_LIVE_WINDOW se intenta un reposicionamiento liviano como máximo cada 30 s. */
    const val BEHIND_LIVE_WINDOW_LIGHT_RETRY_MS = 30_000L

    // ─── Bloque 1.4: esperas de reintento por segmento ───

    const val FIRST_RETRY_DELAY_MS = 250L
    const val SECOND_RETRY_DELAY_MS = 900L
    const val AUTH_RETRY_DELAY_MS = 4_000L
    const val SERVER_RETRY_DELAY_MS = 900L

    // ─── Bloque 1.6: red sin VALIDATED ───

    /** Margen desde la pérdida antes de sondear con transporte e INTERNET sin validación. */
    const val UNVALIDATED_PROBE_GRACE_MS = 12_000L

    /** Frecuencia mínima de los reintentos cuando Android no valida la red. */
    const val UNVALIDATED_PROBE_INTERVAL_MS = 8_000L

    /**
     * Timeouts para el perfil Hotspot a partir del RTT p95 medido por la app.
     *
     * `timeout = clamp(factor × RTT_p95 + margen, mínimo, máximo)`. El máximo queda por
     * debajo de la mitad de la ventana medida (~11,3–11,7 s) para que un solo intento
     * colgado no agote el buffer de 8 s. `rttP95Ms` nulo o no positivo usa el valor
     * intermedio [DEFAULT_RTT_P95_MS].
     */
    fun adaptiveTimeouts(rttP95Ms: Long?): HttpTimeouts {
        val rtt = rttP95Ms?.takeIf { it > 0L } ?: DEFAULT_RTT_P95_MS
        val read = (TIMEOUT_RTT_FACTOR * rtt + READ_TIMEOUT_MARGIN_MS).toLong()
            .coerceIn(MIN_READ_TIMEOUT_MS.toLong(), MAX_READ_TIMEOUT_MS.toLong())
        val connect = (TIMEOUT_RTT_FACTOR * rtt + CONNECT_TIMEOUT_MARGIN_MS).toLong()
            .coerceIn(MIN_CONNECT_TIMEOUT_MS.toLong(), MAX_CONNECT_TIMEOUT_MS.toLong())
        return HttpTimeouts(connectMs = connect.toInt(), readMs = read.toInt())
    }
}

internal data class HttpTimeouts(val connectMs: Int, val readMs: Int)

/** Acción al volver una red que el sistema ya no marca como perdida. */
internal enum class NetworkResumeAction { KEEP_PLAYING, LIGHT_RESTART, FULL_RELOAD }

/**
 * Decide cómo retomar la reproducción cuando vuelve la red.
 *
 * - `KEEP_PLAYING`: el reproductor sigue en READY con buffer suficiente; no se toca.
 * - `LIGHT_RESTART`: está en BUFFERING/READY pero con poco buffer y hubo progreso
 *   reciente; basta `prepare()` + `seekToDefaultPosition()` sobre la misma fuente.
 * - `FULL_RELOAD`: no hay progreso desde hace [fullReloadAfterMs] o el reproductor está
 *   en IDLE; se reconstruye la carga completa.
 *
 * La regla evita el `stop()` + recarga completa que la ronda 1 hacía siempre.
 */
internal fun decideNetworkResume(
    playbackState: Int,
    bufferedMs: Long,
    msSinceProgress: Long,
    readyBufferThresholdMs: Long = PlayerTuning.NETWORK_RESUME_READY_BUFFER_MS,
    fullReloadAfterMs: Long = PlayerTuning.NETWORK_RESUME_FULL_RELOAD_AFTER_MS,
): NetworkResumeAction = when {
    playbackState == Player.STATE_READY && bufferedMs >= readyBufferThresholdMs ->
        NetworkResumeAction.KEEP_PLAYING
    (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) &&
        msSinceProgress in 0L..fullReloadAfterMs -> NetworkResumeAction.LIGHT_RESTART
    else -> NetworkResumeAction.FULL_RELOAD
}

/**
 * Arbitraje entre los tres niveles que pueden pedir una recuperación: la política de carga
 * de Media3 (que ya reintenta por su cuenta), `recoverStream()` y el watchdog de salud.
 *
 * Solo uno puede programar un reinicio a la vez. Si hay una reanudación de red en curso o ya
 * hay un reintento programado, la petición se descarta en lugar de duplicar tareas.
 */
internal fun shouldScheduleRecovery(
    networkResumePending: Boolean,
    retryPending: Boolean,
    networkBlocked: Boolean,
): Boolean = !networkResumePending && !retryPending && !networkBlocked


/**
 * Perfiles de buffer para IPTV en vivo, sintonizados para conexiones Hotspot LTE inestables.
 *
 * Ventana confirmada para a07t: 11.26–11.51 s (4 segmentos). Timeline recorta
 * los offsets si el servidor publica una ventana distinta.
 */
internal object BufferSizes {
    const val DEFAULT_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_SHIFT = 30

    /** Variación aleatoria (±) de todas las esperas de reintento, para no sincronizar intentos. */
    const val RETRY_JITTER_RATIO = 0.2f

    /**
     * Espera del reintento lento, que se usa después de agotar los intentos rápidos.
     * Un corte del hotspot puede durar minutos: se sigue reintentando, pero espaciado
     * para no consumir datos móviles ni inundar el registro de diagnóstico.
     */
    const val SLOW_RETRY_DELAY_MS = 60_000L
    private const val MIN_VALID_LIVE_SPEED = 0.1f
    private const val MAX_VALID_LIVE_SPEED = 2.0f
    const val PROFILE_HOTSPOT = "Hotspot"
    const val PROFILE_LARGE = "Large"
    const val PROFILE_MEDIUM = "Medium"
    const val PROFILE_SMALL = "Small"
    const val DEFAULT_PROFILE = PROFILE_HOTSPOT

    /** Perfiles conocidos, en el orden en que se muestran. */
    val ALL_PROFILES: List<String> = listOf(PROFILE_HOTSPOT, PROFILE_LARGE, PROFILE_MEDIUM, PROFILE_SMALL)

    fun isKnownProfile(profile: String?): Boolean = profile != null && profile in ALL_PROFILES

    /** Acepta nulos, espacios y mayúsculas distintas; lo desconocido cae en Hotspot. */
    fun normalizedProfile(profile: String?): String {
        val key = profile?.trim().orEmpty()
        return ALL_PROFILES.firstOrNull { it.equals(key, ignoreCase = true) } ?: DEFAULT_PROFILE
    }

    // ─── Timeouts HTTP ───
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 120_000

    /** Mismos valores por defecto de DefaultHttpDataSource (8 s). */
    const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
    const val DEFAULT_READ_TIMEOUT_MS = 8_000

    // ─── Hotspot LTE / Móvil (Claro, ~4.3 Mbps bajada, 218 ms ping, bufferbloat 4.8 s) ───
    // Stream a07t: 1 variante ~1.89 Mbps y segmentos ~2.9 s. La ventana se lee por canal.
    const val HOTSPOT_SEGMENT_DURATION_MS = 2_900L
    const val HOTSPOT_WINDOW_MIN_MEASURED_MS = 11_260L
    const val HOTSPOT_LIVE_EDGE_GUARD_MS = 1_750L

    /** Bitrate máximo de video: 2.2 Mbps (protección de ancho de banda). */
    const val HOTSPOT_MAX_VIDEO_BITRATE = 2_200_000

    /** Estimación inicial de ancho de banda: conservadora para arrancar sin cortes. */
    const val HOTSPOT_INITIAL_BITRATE_ESTIMATE = 800_000L

    /** Dos segmentos y medio de reserva, dentro de los ~11.4 s medidos. */
    const val HOTSPOT_MIN_BUFFER_MS = 8_000

    /** Tope de buffer por debajo de la ventana mínima medida. */
    const val HOTSPOT_MAX_BUFFER_MS = 10_000

    /** Buffer para iniciar la reproducción: 3 s (1 segmento, inicio rápido). */
    const val HOTSPOT_BUFFER_FOR_PLAYBACK_MS = 3_000

    /** Recupera con cerca de dos segmentos cargados y siempre ≤ minBufferMs. */
    const val HOTSPOT_BUFFER_AFTER_REBUFFER_MS = 5_000

    /** Back-buffer para seek hacia atrás en vivo. */
    const val HOTSPOT_BACK_BUFFER_MS = 10_000

    /** Retraso objetivo confirmado dentro de la ventana real de a07t. */
    const val HOTSPOT_LIVE_TARGET_OFFSET_MS = 8_000L

    /** Offset mínimo permitido: 1 segmento de distancia del borde. */
    const val HOTSPOT_MIN_OFFSET_MS = 3_000L

    /**
     * Offset máximo permitido: 9.5 s.
     * Anclado a la ventana real para que ExoPlayer no intente posicionarse
     * más allá del segmento más antiguo y provoque BEHIND_LIVE_WINDOW.
     * Si el servidor acorta la ventana, startStream() recorta este valor dinámicamente.
     */
    const val HOTSPOT_MAX_OFFSET_MS = 9_500L

    /**
     * Velocidad mínima de reproducción: 0.90x.
     * Permite frenar suavemente en bajones de red sin congelar el video.
     */
    const val HOTSPOT_MIN_PLAYBACK_SPEED = 0.90f

    /**
     * Velocidad máxima de reproducción: 1.00x.
     * Nunca acelera por encima de la normal para no consumir
     * el colchón acumulado buscando el borde en vivo.
     */
    const val HOTSPOT_MAX_PLAYBACK_SPEED = 1.00f

    const val HOTSPOT_CONNECT_TIMEOUT_MS = 20_000
    const val HOTSPOT_READ_TIMEOUT_MS = 25_000

    /**
     * Muestras de BUFFERING (a 2 s/muestra) antes de intentar un reposicionamiento suave.
     * 4 muestras = 8 s sin progreso → seekToDefaultPosition antes del recover completo.
     */
    const val HOTSPOT_BUFFERING_SOFT_RECOVERY_SAMPLES = 4
    const val HOTSPOT_BUFFERING_SAMPLE_INTERVAL_MS = 2_000L
    const val HOTSPOT_BUFFERING_SOFT_RECOVERY_MS =
        HOTSPOT_BUFFERING_SOFT_RECOVERY_SAMPLES * HOTSPOT_BUFFERING_SAMPLE_INTERVAL_MS

    fun durations(profile: String): BufferDurations = when (profile) {
        PROFILE_HOTSPOT -> hotspotDurations()
        PROFILE_LARGE -> BufferDurations(
            minBufferMs = 150_000,
            maxBufferMs = 200_000,
            bufferForPlaybackMs = 3_000,
            bufferForPlaybackAfterRebufferMs = 8_000,
            backBufferMs = 30_000,
            liveTargetOffsetMs = 20_000L,
            liveMinOffsetMs = 5_000L,
            liveMaxOffsetMs = 40_000L,
        )
        PROFILE_MEDIUM -> BufferDurations(
            minBufferMs = 80_000,
            maxBufferMs = 120_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 6_000,
            backBufferMs = 20_000,
            liveTargetOffsetMs = 14_000L,
            liveMinOffsetMs = 4_000L,
            liveMaxOffsetMs = 30_000L,
        )
        PROFILE_SMALL -> BufferDurations(
            minBufferMs = 40_000,
            maxBufferMs = 60_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 5_000,
            backBufferMs = 10_000,
            liveTargetOffsetMs = 8_000L,
            liveMinOffsetMs = 3_000L,
            liveMaxOffsetMs = 18_000L,
        )
        else -> hotspotDurations()
    }

    /** Atajo seguro: normaliza el nombre, obtiene el perfil y lo sanea. */
    fun resolvedDurations(profile: String?): BufferDurations =
        validatedDurations(durations(normalizedProfile(profile)))

    private fun hotspotDurations() = BufferDurations(
        minBufferMs = HOTSPOT_MIN_BUFFER_MS,
        maxBufferMs = HOTSPOT_MAX_BUFFER_MS,
        bufferForPlaybackMs = HOTSPOT_BUFFER_FOR_PLAYBACK_MS,
        bufferForPlaybackAfterRebufferMs = HOTSPOT_BUFFER_AFTER_REBUFFER_MS,
        backBufferMs = HOTSPOT_BACK_BUFFER_MS,
        liveTargetOffsetMs = HOTSPOT_LIVE_TARGET_OFFSET_MS,
        liveMinOffsetMs = HOTSPOT_MIN_OFFSET_MS,
        liveMaxOffsetMs = HOTSPOT_MAX_OFFSET_MS,
        liveMinPlaybackSpeed = HOTSPOT_MIN_PLAYBACK_SPEED,
        liveMaxPlaybackSpeed = HOTSPOT_MAX_PLAYBACK_SPEED,
    )

    // ─── Red por perfil (null = sin límite ni estimación propia) ───

    fun maxVideoBitrateFor(profile: String?): Int? =
        if (normalizedProfile(profile) == PROFILE_HOTSPOT) HOTSPOT_MAX_VIDEO_BITRATE else null

    fun initialBitrateEstimateFor(profile: String?): Long? =
        if (normalizedProfile(profile) == PROFILE_HOTSPOT) HOTSPOT_INITIAL_BITRATE_ESTIMATE else null

    fun connectTimeoutMs(profile: String?, rttP95Ms: Long? = null): Int =
        if (normalizedProfile(profile) == PROFILE_HOTSPOT) {
            if (PlayerTuning.adaptiveTimeouts) PlayerTuning.adaptiveTimeouts(rttP95Ms).connectMs
            else HOTSPOT_CONNECT_TIMEOUT_MS
        } else {
            DEFAULT_CONNECT_TIMEOUT_MS
        }

    fun readTimeoutMs(profile: String?, rttP95Ms: Long? = null): Int =
        if (normalizedProfile(profile) == PROFILE_HOTSPOT) {
            if (PlayerTuning.adaptiveTimeouts) PlayerTuning.adaptiveTimeouts(rttP95Ms).readMs
            else HOTSPOT_READ_TIMEOUT_MS
        } else {
            DEFAULT_READ_TIMEOUT_MS
        }

    /** Devuelve el timeout si está en rango; si no, el valor por defecto indicado. */
    fun validatedTimeoutMs(value: Int, default: Int): Int =
        if (value in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) value else default

    // ─── Validación ───

    /** Sanea los valores del perfil antes de que lleguen a los builders de Media3. */
    fun validatedDurations(value: BufferDurations): BufferDurations {
        val min = value.minBufferMs.coerceAtLeast(1)
        val max = value.maxBufferMs.coerceAtLeast(min)
        return value.copy(
            minBufferMs = min,
            maxBufferMs = max,
            bufferForPlaybackMs = value.bufferForPlaybackMs.coerceIn(0, min),
            bufferForPlaybackAfterRebufferMs = value.bufferForPlaybackAfterRebufferMs.coerceIn(0, min),
            backBufferMs = value.backBufferMs.coerceAtLeast(0),
        ).validatedLiveValues()
    }

    /** Lista los problemas que validatedDurations corregiría; vacía si el perfil es correcto. */
    fun validationIssues(value: BufferDurations): List<String> = buildList {
        if (value.minBufferMs < 1) add("minBufferMs debe ser ≥ 1")
        if (value.maxBufferMs < value.minBufferMs) add("maxBufferMs es menor que minBufferMs")
        if (value.bufferForPlaybackMs !in 0..value.minBufferMs) {
            add("bufferForPlaybackMs fuera de 0..minBufferMs")
        }
        if (value.bufferForPlaybackAfterRebufferMs !in 0..value.minBufferMs) {
            add("bufferForPlaybackAfterRebufferMs fuera de 0..minBufferMs")
        }
        if (value.backBufferMs < 0) add("backBufferMs es negativo")
        if (value.liveTargetOffsetMs < 0L) add("liveTargetOffsetMs es negativo")
        if (value.liveMinOffsetMs !in 0L..value.liveTargetOffsetMs) {
            add("liveMinOffsetMs fuera de 0..liveTargetOffsetMs")
        }
        if (value.liveMaxOffsetMs < value.liveTargetOffsetMs) add("liveMaxOffsetMs es menor que el objetivo")
        val minSpeed = value.liveMinPlaybackSpeed
        if (!minSpeed.isFinite() || minSpeed !in MIN_VALID_LIVE_SPEED..MAX_VALID_LIVE_SPEED) {
            add("liveMinPlaybackSpeed fuera de rango")
        }
        val maxSpeed = value.liveMaxPlaybackSpeed
        if (!maxSpeed.isFinite() || maxSpeed !in minSpeed..MAX_VALID_LIVE_SPEED) {
            add("liveMaxPlaybackSpeed fuera de rango")
        }
    }

    private fun BufferDurations.validatedLiveValues(): BufferDurations {
        val minSpeed = liveMinPlaybackSpeed
            .takeIf { it.isFinite() && it in MIN_VALID_LIVE_SPEED..MAX_VALID_LIVE_SPEED }
            ?: DEFAULT_LIVE_MIN_SPEED
        val maxSpeed = liveMaxPlaybackSpeed
            .takeIf { it.isFinite() && it in minSpeed..MAX_VALID_LIVE_SPEED }
            ?: maxOf(minSpeed, DEFAULT_LIVE_MAX_SPEED)
        val target = liveTargetOffsetMs.takeIf { it >= 0L } ?: DEFAULT_LIVE_TARGET_OFFSET_MS
        val maxOffset = liveMaxOffsetMs.takeIf { it >= target } ?: maxOf(target, DEFAULT_LIVE_MAX_OFFSET_MS)
        val minOffset = liveMinOffsetMs.takeIf { it in 0L..target } ?: minOf(DEFAULT_LIVE_MIN_OFFSET_MS, target)
        return copy(
            liveMinOffsetMs = minOffset,
            liveTargetOffsetMs = target,
            liveMaxOffsetMs = maxOffset,
            liveMinPlaybackSpeed = minSpeed,
            liveMaxPlaybackSpeed = maxSpeed,
        )
    }

    // ─── Ventana en vivo ───

    /** Duración de la ventana a partir del número de segmentos publicados; 0 si algún dato no es válido. */
    fun windowMsFromSegments(segmentCount: Int, segmentDurationMs: Long): Long {
        if (segmentCount <= 0 || segmentDurationMs <= 0L) return 0L
        return segmentCount.toLong() * segmentDurationMs
    }

    /** Toma la lectura positiva más pequeña de varias mediciones; 0 si no hay ninguna válida. */
    fun conservativeWindowMs(readingsMs: Iterable<Long>): Long =
        readingsMs.filter { it > 0L }.minOrNull() ?: 0L

    /** Ajusta los offsets en vivo a una ventana medida; si es desconocida conserva los valores validados del perfil. */
    fun offsetsForWindow(
        value: BufferDurations,
        windowMs: Long,
        edgeGuardMs: Long = HOTSPOT_LIVE_EDGE_GUARD_MS,
        segmentDurationMs: Long = HOTSPOT_SEGMENT_DURATION_MS,
    ): LiveOffsets {
        val d = validatedDurations(value)
        if (windowMs <= 0L) return LiveOffsets(d.liveMinOffsetMs, d.liveTargetOffsetMs, d.liveMaxOffsetMs)
        val guard = edgeGuardMs.coerceAtLeast(0L)
        val segment = segmentDurationMs.coerceAtLeast(0L)
        val maxOffset = minOf(d.liveMaxOffsetMs, (windowMs - guard).coerceAtLeast(0L))
        val target = minOf(
            d.liveTargetOffsetMs,
            maxOffset,
            (windowMs - segment).coerceAtLeast(0L),
        )
        return LiveOffsets(minOf(d.liveMinOffsetMs, target), target, maxOffset)
    }

    // ─── Reintentos ───

    /**
     * Espera exponencial: base, 2·base, 4·base… con tope en maxMs.
     * La comparación previa al desplazamiento evita desbordar el Long.
     */
    fun backoffMs(
        attempt: Int,
        baseMs: Long = DEFAULT_BACKOFF_MS,
        maxMs: Long = MAX_BACKOFF_MS,
    ): Long {
        if (attempt <= 0) return 0L
        val base = baseMs.coerceAtLeast(0L)
        if (base == 0L) return 0L
        val cap = maxMs.coerceAtLeast(base)
        val shift = (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        if (base > (cap shr shift)) return cap
        return base shl shift
    }

    /** Aplica una variación aleatoria ±[jitterRatio] sin recortar contra el tope del backoff. */
    fun jitteredMs(
        delayMs: Long,
        jitterRatio: Float = RETRY_JITTER_RATIO,
        random: Random = Random.Default,
    ): Long {
        if (delayMs <= 0L) return 0L
        val ratio = if (jitterRatio.isFinite()) jitterRatio.coerceIn(0f, 1f) else 0f
        val spread = (delayMs * ratio).toLong()
        if (spread <= 0L) return delayMs
        return delayMs + random.nextLong(-spread, spread + 1)
    }

    /** Igual que backoffMs con variación aleatoria ±jitterRatio para no sincronizar reintentos. */
    fun backoffWithJitterMs(
        attempt: Int,
        jitterRatio: Float = RETRY_JITTER_RATIO,
        random: Random = Random.Default,
    ): Long {
        val delay = backoffMs(attempt)
        if (delay == 0L) return 0L
        return jitteredMs(delay, jitterRatio, random).coerceIn(0L, MAX_BACKOFF_MS)
    }

    /**
     * Espera antes de reiniciar el stream completo.
     *
     * Mientras queden intentos rápidos se usa el backoff exponencial con jitter. Agotados
     * (por ejemplo un corte prolongado del teléfono), se pasa a un reintento lento fijo con
     * jitter: la recuperación sigue siendo automática, pero deja de reintentar cada 30 s.
     */
    fun streamRetryDelayMs(
        attempt: Int,
        fastAttempts: Int,
        random: Random = Random.Default,
    ): Long {
        if (attempt <= 0) return 0L
        if (attempt <= fastAttempts.coerceAtLeast(0)) return backoffWithJitterMs(attempt, random = random)
        return jitteredMs(SLOW_RETRY_DELAY_MS, random = random).coerceAtLeast(DEFAULT_BACKOFF_MS)
    }
}

// ─── Red ───

/**
 * Reglas para decidir cuándo revisar la conectividad sin esperar un evento del sistema.
 *
 * Un hotspot LTE puede volver a estar operativo sin que Android emita un
 * `onCapabilitiesChanged`: el Wi-Fi nunca perdió la validación. Si además la app quedó con
 * el estado de "red perdida", la recuperación automática no se reintenta, así que hace falta
 * un margen propio para volver a comprobarlo.
 */
internal object NetworkTuning {
    /** Margen desde la pérdida antes de comprobar la red por cuenta propia. */
    const val LOST_RECOVERY_RECHECK_MS = 20_000L

    /** true si conviene revisar el estado de pérdida aunque no llegue ningún evento de red. */
    fun shouldRecheckLostState(lostForMs: Long, validatedInternet: Boolean): Boolean =
        validatedInternet && lostForMs >= LOST_RECOVERY_RECHECK_MS

    /**
     * Sondeo de baja frecuencia cuando Android informa transporte e INTERNET pero no VALIDATED.
     *
     * La sonda de validación del sistema puede fallar en un enlace lento o con un proxy DNS
     * propio aunque el stream funcione. El propio reproductor se usa como sonda: no se
     * reintroduce `StreamReachabilityProbe` ni se usa ICMP.
     */
    fun shouldProbeUnvalidated(
        lostForMs: Long,
        transportConnected: Boolean,
        internetCapability: Boolean,
        msSinceLastProbe: Long,
    ): Boolean =
        transportConnected && internetCapability &&
            lostForMs >= PlayerTuning.UNVALIDATED_PROBE_GRACE_MS &&
            msSinceLastProbe >= PlayerTuning.UNVALIDATED_PROBE_INTERVAL_MS

    // ─── Datos de Wi-Fi para el HUD ───

    /**
     * Separación mínima entre lecturas del estado del Wi-Fi.
     *
     * `getConnectionInfo()`, `getNetworkCapabilities()` y `getLinkProperties()` son llamadas al
     * sistema; el HUD se refresca cada segundo y no necesita consultarlas con esa frecuencia.
     */
    const val WIFI_SAMPLE_INTERVAL_MS = 2_000L

    /** Umbrales de calidad de la señal, en dBm. Sirven para separar "TV↔teléfono" de "LTE". */
    const val WIFI_RSSI_GOOD_DBM = -60
    const val WIFI_RSSI_FAIR_DBM = -70
    const val WIFI_RSSI_WEAK_DBM = -80

    /** Fuera de este rango el valor no es una medición real (drivers que informan 0 o -127). */
    val WIFI_RSSI_VALID_RANGE = -110..-15

    /** Bandas según la frecuencia del canal, en MHz. 6 GHz se incluye para no etiquetarla mal. */
    val WIFI_2GHZ_RANGE = 2_400..2_500
    val WIFI_5GHZ_RANGE = 4_900..5_895
    val WIFI_6GHZ_RANGE = 5_925..7_125
}

// ─── Decodificadores ───
internal enum class DecoderMode {
    HARDWARE, SOFTWARE;

    fun toggled(): DecoderMode = if (this == HARDWARE) SOFTWARE else HARDWARE

    companion object {
        /** Acepta nulos, espacios y mayúsculas distintas; lo desconocido devuelve el valor por defecto. */
        fun fromName(name: String?, default: DecoderMode = HARDWARE): DecoderMode {
            val key = name?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: default
        }
    }
}

internal class DecoderPreferences(var audio: DecoderMode, var video: DecoderMode) {
    fun forMimeType(mimeType: String?): DecoderMode =
        if (mimeType?.trim()?.startsWith("audio/", ignoreCase = true) == true) audio else video

    fun copy(audio: DecoderMode = this.audio, video: DecoderMode = this.video) =
        DecoderPreferences(audio, video)

    override fun toString(): String = "DecoderPreferences(audio=$audio, video=$video)"

    companion object {
        /** Lee ambos modos por nombre desde las claves indicadas. */
        fun fromPreferences(
            prefs: SharedPreferences,
            audioKey: String,
            videoKey: String,
            default: DecoderMode = DecoderMode.HARDWARE,
        ) = DecoderPreferences(
            audio = prefs.safeEnum(audioKey, default),
            video = prefs.safeEnum(videoKey, default),
        )
    }

    /** Guarda ambos modos por nombre; devuelve false si la escritura falla. */
    fun writeTo(prefs: SharedPreferences, audioKey: String, videoKey: String): Boolean =
        prefs.safeEdit {
            putString(audioKey, audio.name)
            putString(videoKey, video.name)
        }
}
