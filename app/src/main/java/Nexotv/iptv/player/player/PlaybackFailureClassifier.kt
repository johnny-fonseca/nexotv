package Nexotv.iptv.player.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * Causa clasificada de un fallo de reproducción.
 *
 * No pretende ser una taxonomía completa: agrupa los casos que cambian la decisión que toma la
 * app (reintentar, esperar, bajar calidad o avisar al usuario) y que permiten distinguir en el
 * HUD si el problema está en el DNS, en el enlace LTE, en el hotspot o en el proveedor.
 */
internal enum class PlaybackFailureKind(val label: String) {
    NONE("—"),
    CLEARTEXT("HTTP bloqueado"),
    DECODER("decodificador"),
    PARSING("lista o segmento inválido"),
    DNS("DNS"),
    TLS("TLS/certificado"),
    HTTP_CLIENT("HTTP 4xx"),
    HTTP_SERVER("HTTP 5xx"),
    CONNECT_TIMEOUT("timeout de conexión"),
    READ_TIMEOUT("timeout de lectura"),
    NETWORK("red inestable"),
    MTU("posible MTU/PMTUD"),
    OTHER("otro"),
}

/** Datos mínimos para clasificar un fallo sin depender del estado del reproductor. */
internal data class PlaybackFailureInput(
    val errorCode: Int,
    val httpStatus: Int? = null,
    /** El fallo ocurrió al abrir la conexión (no al leer el cuerpo). */
    val connectPhase: Boolean = false,
    val timedOut: Boolean = false,
    /** Nombres simples de las clases de la cadena de causas. */
    val causeNames: List<String> = emptyList(),
)

/**
 * Clasifica un fallo de reproducción. El orden importa: primero lo que identifica la causa de
 * forma inequívoca (HTTP sin cifrar, decodificador, formato) y al final el rango genérico de E/S.
 *
 * `mtuSuspected` lo aporta la medición del enlace (ver `isMtuSuspect`): si una lista pequeña
 * funciona mientras un segmento grande se cuelga tras pocos KB, la causa probable es MTU/PMTUD
 * del túnel, no ancho de banda.
 */
internal fun classifyPlaybackFailure(
    input: PlaybackFailureInput,
    mtuSuspected: Boolean = false,
): PlaybackFailureKind {
    val names = input.causeNames.joinToString(" ")
    fun anyName(fragment: String): Boolean = names.contains(fragment, ignoreCase = true)

    return when {
        input.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED || anyName("cleartext") ->
            PlaybackFailureKind.CLEARTEXT
        anyName("decoder") -> PlaybackFailureKind.DECODER
        anyName("parser") -> PlaybackFailureKind.PARSING
        anyName("unknownhost") || anyName("noroutetohost") -> PlaybackFailureKind.DNS
        anyName("ssl") || anyName("certificate") || anyName("certpath") -> PlaybackFailureKind.TLS
        input.httpStatus != null -> if (input.httpStatus in 400..499) {
            PlaybackFailureKind.HTTP_CLIENT
        } else {
            PlaybackFailureKind.HTTP_SERVER
        }
        mtuSuspected && (input.timedOut || input.errorCode in IO_ERROR_RANGE) -> PlaybackFailureKind.MTU
        input.timedOut && input.connectPhase -> PlaybackFailureKind.CONNECT_TIMEOUT
        input.timedOut -> PlaybackFailureKind.READ_TIMEOUT
        input.errorCode in IO_ERROR_RANGE -> PlaybackFailureKind.NETWORK
        else -> PlaybackFailureKind.OTHER
    }
}

/** Por debajo de este tamaño de cuerpo se considera "pocos KB" para el patrón MTU. */
internal const val MTU_SUSPECT_MAX_BYTES = 64_000L

/** Tiempo mínimo de un segmento colgado para que el patrón MTU tenga sentido. */
internal const val MTU_SUSPECT_MIN_ELAPSED_MS = 2_000L

/**
 * Patrón "playlist OK / segmento colgado tras pocos KB".
 *
 * Es una sospecha, no una prueba: exige que una lista pequeña del mismo host haya funcionado
 * hace poco, que el segmento haya entregado entre 1 byte y 64 KB, y que el fallo tardara al
 * menos 2 s. Un segmento que falla sin recibir nada es más compatible con un blackhole total.
 */
internal fun isMtuSuspect(
    bytesBeforeFailure: Long,
    playlistSucceededRecently: Boolean,
    elapsedMs: Long,
): Boolean = playlistSucceededRecently &&
    bytesBeforeFailure in 1L..MTU_SUSPECT_MAX_BYTES &&
    elapsedMs >= MTU_SUSPECT_MIN_ELAPSED_MS


/** Códigos de E/S de Media3 (`ERROR_CODE_IO_*`). */
private val IO_ERROR_RANGE = 2000..2999

/**
 * Extrae de una [PlaybackException] los datos que necesita [classifyPlaybackFailure]:
 * código HTTP, fase de la conexión, si hubo timeout y la cadena de causas.
 */
internal fun failureInputOf(error: PlaybackException): PlaybackFailureInput {
    val causes = error.causeChain()
    val httpError = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    val dataSourceError = causes.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull()
    return PlaybackFailureInput(
        errorCode = error.errorCode,
        httpStatus = httpError?.responseCode,
        connectPhase = dataSourceError?.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        timedOut = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any { it is java.net.SocketTimeoutException },
        causeNames = causes.map { it.javaClass.simpleName },
    )
}
