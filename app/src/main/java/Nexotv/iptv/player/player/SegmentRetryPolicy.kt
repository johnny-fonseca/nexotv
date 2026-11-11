@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package Nexotv.iptv.player.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Tipo de fallo de una carga HLS desde el punto de vista de "¿conviene reintentar este
 * segmento dentro de la ventana del directo?".
 */
internal enum class SegmentRetryKind {
    /** 404/410 u otro 4xx sin recuperación posible: escalar de inmediato. */
    DEFINITIVE,

    /** 401/403: puede ser un cambio de IP de salida tras reconectar el túnel. */
    AUTH,

    /** Connect/read timeout: caro en tiempo; un solo reintento. */
    TIMEOUT,

    /** Reset, DNS, red: dos reintentos cortos. */
    TRANSIENT,

    /** 5xx del proveedor: un reintento espaciado. */
    SERVER,
}

/**
 * Decisión de reintento de un segmento o lista.
 *
 * `delayMs == null` significa "no reintentar": Media3 debe abandonar esa carga y escalar a
 * `recoverStream()`, que reposiciona en el borde del directo.
 */
internal data class SegmentRetryDecision(
    val delayMs: Long?,
    val kind: SegmentRetryKind,
    val label: String,
)

/**
 * Política de reintentos pensada para directo, no para VOD.
 *
 * La política por defecto de Media3 usa 1→2→4→8 s (hasta 30 s) por segmento; con una
 * ventana de ~11,5 s eso garantiza salir de la ventana. Aquí el presupuesto se mide en
 * segundos: primer reintento casi inmediato, segundo corto con jitter, y rendición para
 * que `recoverStream()` reposicione. Los backoff largos quedan solo en la recuperación
 * completa del stream.
 */
internal object SegmentRetryPolicy {
    /**
     * Intentos totales que Media3 puede hacer de una carga (1 inicial + 2 reintentos).
     * El límite real lo impone [decision]: tras el presupuesto devuelve null.
     */
    const val MIN_LOADABLE_RETRY_COUNT = 3

    /** Calcula el tipo de fallo a partir de lo que expone Media3. */
    fun classify(
        httpStatus: Int?,
        timedOut: Boolean,
        causeNames: List<String>,
    ): SegmentRetryKind {
        val names = causeNames.joinToString(" ")
        fun anyName(fragment: String): Boolean = names.contains(fragment, ignoreCase = true)
        return when {
            httpStatus == 404 || httpStatus == 410 -> SegmentRetryKind.DEFINITIVE
            httpStatus == 401 || httpStatus == 403 -> SegmentRetryKind.AUTH
            httpStatus != null && httpStatus in 400..499 -> SegmentRetryKind.DEFINITIVE
            httpStatus != null && httpStatus in 500..599 -> SegmentRetryKind.SERVER
            timedOut || anyName("SocketTimeout") -> SegmentRetryKind.TIMEOUT
            anyName("UnknownHost") || anyName("NoRouteToHost") -> SegmentRetryKind.TRANSIENT
            else -> SegmentRetryKind.TRANSIENT
        }
    }

    /**
     * Espera antes del siguiente intento, o null si ya se agotó el presupuesto.
     *
     * `errorCount` es el contador de Media3: 1 en el primer fallo de la carga.
     */
    fun decision(
        kind: SegmentRetryKind,
        errorCount: Int,
        random: Random = Random.Default,
        jitterRatio: Float = BufferSizes.RETRY_JITTER_RATIO,
    ): SegmentRetryDecision {
        if (errorCount <= 0) {
            return SegmentRetryDecision(null, kind, "sin intento")
        }
        val delay = when (kind) {
            SegmentRetryKind.DEFINITIVE -> null
            SegmentRetryKind.AUTH ->
                if (errorCount == 1) PlayerTuning.AUTH_RETRY_DELAY_MS else null
            SegmentRetryKind.TIMEOUT ->
                if (errorCount == 1) PlayerTuning.FIRST_RETRY_DELAY_MS else null
            SegmentRetryKind.SERVER ->
                if (errorCount == 1) PlayerTuning.SERVER_RETRY_DELAY_MS else null
            SegmentRetryKind.TRANSIENT -> when (errorCount) {
                1 -> PlayerTuning.FIRST_RETRY_DELAY_MS
                2 -> PlayerTuning.SECOND_RETRY_DELAY_MS
                else -> null
            }
        }
        return SegmentRetryDecision(
            delayMs = delay?.let { BufferSizes.jitteredMs(it, jitterRatio, random) },
            kind = kind,
            label = when (kind) {
                SegmentRetryKind.DEFINITIVE -> "definitivo"
                SegmentRetryKind.AUTH -> "autorización"
                SegmentRetryKind.TIMEOUT -> "timeout"
                SegmentRetryKind.TRANSIENT -> "red"
                SegmentRetryKind.SERVER -> "5xx"
            },
        )
    }
}

/**
 * Adaptador de [SegmentRetryPolicy] para la política de errores de carga de Media3.
 *
 * Se instala en `DefaultMediaSourceFactory` en lugar de `DefaultLoadErrorHandlingPolicy(4)`
 * cuando la bandera [PlayerTuning.inWindowRetries] está activa y el perfil es Hotspot.
 * Hereda el resto del comportamiento de `DefaultLoadErrorHandlingPolicy` (fallback de
 * pistas, etc.) para no cambiar nada que no sea la espera entre reintentos.
 */
internal class LiveLoadErrorHandlingPolicy(
    private val onDecision: (SegmentRetryDecision, Int) -> Unit = { _, _ -> },
) : DefaultLoadErrorHandlingPolicy(SegmentRetryPolicy.MIN_LOADABLE_RETRY_COUNT) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
        val httpStatus = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        val timedOut = causes.any { it is SocketTimeoutException }
        val decision = SegmentRetryPolicy.decision(
            kind = SegmentRetryPolicy.classify(
                httpStatus = httpStatus,
                timedOut = timedOut,
                causeNames = causes.map { it.javaClass.simpleName },
            ),
            errorCount = loadErrorInfo.errorCount,
        )
        onDecision(decision, loadErrorInfo.errorCount)
        return decision.delayMs ?: C.TIME_UNSET
    }
}
