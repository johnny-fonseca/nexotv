@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package Nexotv.iptv.player.network

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.ArrayDeque

/** Tipo de recurso HLS medido por el enlace. */
internal enum class NetworkSampleKind { PLAYLIST, SEGMENT, OTHER }

/** Una carga HTTP medida de principio a fin. */
internal data class NetworkSample(
    val hostKey: String,
    val kind: NetworkSampleKind,
    val ttfbMs: Long,
    val totalMs: Long,
    val bytes: Long,
    val atMs: Long,
    val success: Boolean,
)

/** Relación tiempo de descarga / duración del segmento, medida por Media3. */
internal data class SegmentRatioSample(
    val hostKey: String,
    val ratio: Double,
    val atMs: Long,
)

/** Fallo de carga con los bytes que llegaron antes del error. */
internal data class NetworkFailureSample(
    val hostKey: String,
    val kind: NetworkSampleKind,
    val bytesBeforeFailure: Long,
    val elapsedMs: Long,
    val atMs: Long,
    val cause: String,
)

/** Resumen de una ventana móvil para el HUD y el informe. */
internal data class NetworkStatsSnapshot(
    val samples: Int,
    val rttP50Ms: Long?,
    val rttP95Ms: Long?,
    val rttP99Ms: Long?,
    val ttfbP95Ms: Long?,
    val sustainedCapacityBps: Long?,
    val ratioP95: Double?,
    val failureRatePct: Int?,
    val headroom: Double?,
    val hosts: List<HostStats>,
) {
    val hasSamples: Boolean get() = samples > 0
}

internal data class HostStats(
    val hostKey: String,
    val samples: Int,
    val rttP95Ms: Long?,
    val capacityBps: Long?,
    val ratioP95: Double?,
    val failures: Int,
)

/**
 * Acumula mediciones del enlace hechas desde la app, por `host:puerto`.
 *
 * No guarda URLs completas ni query strings: solo el host y el puerto, para poder comparar
 * los puertos no estándar (7050/40000) con 443 sin filtrar tokens.
 *
 * Los datos viven en memoria y se recortan por tiempo (15 min) y por número (600 muestras),
 * así que el objeto no crece sin límite durante una sesión larga.
 */
internal object NetworkLoadStats {
    const val WINDOW_SHORT_MS = 2 * 60_000L
    const val WINDOW_LONG_MS = 15 * 60_000L
    private const val MAX_SAMPLES = 600
    private const val MAX_RATIOS = 300
    private const val MAX_FAILURES = 120
    private const val MIN_SEGMENT_BYTES_FOR_CAPACITY = 32_000L

    private val samples = ArrayDeque<NetworkSample>()
    private val ratios = ArrayDeque<SegmentRatioSample>()
    private val failures = ArrayDeque<NetworkFailureSample>()

    private var lastPlaylistOkAtMs = 0L
    private var lastPlaylistHost: String? = null

    fun reset() {
        synchronized(this) {
            samples.clear()
            ratios.clear()
            failures.clear()
            lastPlaylistOkAtMs = 0L
            lastPlaylistHost = null
        }
    }

    fun recordSample(sample: NetworkSample) {
        synchronized(this) {
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            prune(sample.atMs)
            if (sample.success && sample.kind == NetworkSampleKind.PLAYLIST) {
                lastPlaylistOkAtMs = sample.atMs
                lastPlaylistHost = sample.hostKey
            }
        }
    }

    fun recordRatio(hostKey: String, ratio: Double, atMs: Long = SystemClock.elapsedRealtime()) {
        if (!ratio.isFinite() || ratio <= 0.0 || ratio > 100.0) return
        synchronized(this) {
            ratios.addLast(SegmentRatioSample(hostKey, ratio, atMs))
            while (ratios.size > MAX_RATIOS) ratios.removeFirst()
            prune(atMs)
        }
    }

    fun recordFailure(failure: NetworkFailureSample) {
        synchronized(this) {
            failures.addLast(failure)
            while (failures.size > MAX_FAILURES) failures.removeFirst()
            prune(failure.atMs)
        }
    }

    /**
     * RTT p95 de las muestras retenidas (15 min). Es la entrada de los timeouts adaptativos.
     * Devuelve null sin muestras para que el llamador use el valor intermedio.
     */
    fun rttP95Ms(): Long? = synchronized(this) {
        val now = SystemClock.elapsedRealtime()
        val values = samples.asSequence()
            .filter { it.success && now - it.atMs <= WINDOW_LONG_MS }
            .map { it.ttfbMs }
            .filter { it > 0L }
            .sorted()
            .toList()
        percentile(values, 95.0)
    }

    /** ¿Las playlists (pequeñas) funcionaron en este host en los últimos [withinMs]? */
    fun playlistSucceededRecently(hostKey: String, withinMs: Long, nowMs: Long): Boolean =
        synchronized(this) {
            lastPlaylistHost == hostKey && lastPlaylistOkAtMs > 0L &&
                nowMs - lastPlaylistOkAtMs <= withinMs
        }

    /** Último fallo de segmento, para el patrón MTU/PMTUD. */
    fun lastSegmentFailure(): NetworkFailureSample? = synchronized(this) {
        failures.lastOrNull { it.kind == NetworkSampleKind.SEGMENT }
    }

    fun snapshot(
        declaredBitrateBps: Long? = null,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): NetworkStatsSnapshot = synchronized(this) {
        prune(nowMs)
        val recent = samples.filter { nowMs - it.atMs <= WINDOW_LONG_MS && it.success }
        val rtts = recent.map { it.ttfbMs }.filter { it > 0L }.sorted()
        val segmentThroughput = recent.asSequence()
            .filter { it.kind == NetworkSampleKind.SEGMENT }
            .filter { it.bytes >= MIN_SEGMENT_BYTES_FOR_CAPACITY && it.totalMs >= 200L }
            .map { it.bytes * 8_000L / it.totalMs }
            .sorted()
            .toList()
        val recentRatios = ratios.filter { nowMs - it.atMs <= WINDOW_LONG_MS }.map { it.ratio }.sorted()
        val recentFailures = failures.count { nowMs - it.atMs <= WINDOW_LONG_MS }
        val totalAttempts = recent.size + recentFailures
        val capacity = percentile(segmentThroughput, 50.0)
        val headroom = if (capacity != null && declaredBitrateBps != null && declaredBitrateBps > 0L) {
            capacity.toDouble() / declaredBitrateBps.toDouble()
        } else {
            null
        }
        val hosts = recent.groupBy { it.hostKey }.map { (host, hostSamples) ->
            val hostRtts = hostSamples.map { it.ttfbMs }.filter { it > 0L }.sorted()
            val hostThroughput = hostSamples.asSequence()
                .filter { it.kind == NetworkSampleKind.SEGMENT }
                .filter { it.bytes >= MIN_SEGMENT_BYTES_FOR_CAPACITY && it.totalMs >= 200L }
                .map { it.bytes * 8_000L / it.totalMs }
                .sorted()
                .toList()
            HostStats(
                hostKey = host,
                samples = hostSamples.size,
                rttP95Ms = percentile(hostRtts, 95.0),
                capacityBps = percentile(hostThroughput, 50.0),
                ratioP95 = percentile(
                    ratios.filter { it.hostKey == host && nowMs - it.atMs <= WINDOW_LONG_MS }
                        .map { it.ratio }.sorted(),
                    95.0,
                ),
                failures = failures.count { it.hostKey == host && nowMs - it.atMs <= WINDOW_LONG_MS },
            )
        }.sortedByDescending { it.samples }
        NetworkStatsSnapshot(
            samples = recent.size,
            rttP50Ms = percentile(rtts, 50.0),
            rttP95Ms = percentile(rtts, 95.0),
            rttP99Ms = percentile(rtts, 99.0),
            ttfbP95Ms = percentile(rtts, 95.0),
            sustainedCapacityBps = capacity,
            ratioP95 = percentile(recentRatios, 95.0),
            failureRatePct = if (totalAttempts > 0) {
                (recentFailures * 100.0 / totalAttempts).toInt().coerceIn(0, 100)
            } else {
                null
            },
            headroom = headroom,
            hosts = hosts,
        )
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > WINDOW_LONG_MS) samples.removeFirst()
        while (ratios.isNotEmpty() && nowMs - ratios.first().atMs > WINDOW_LONG_MS) ratios.removeFirst()
        while (failures.isNotEmpty() && nowMs - failures.first().atMs > WINDOW_LONG_MS) failures.removeFirst()
    }
}

/** Percentil por rango más cercano; null si la lista está vacía. */
internal fun percentile(sortedValues: List<Long>, p: Double): Long? {
    if (sortedValues.isEmpty()) return null
    if (sortedValues.size == 1) return sortedValues.first()
    val rank = (p.coerceIn(0.0, 100.0) / 100.0) * (sortedValues.size - 1)
    val low = rank.toInt()
    val high = (low + 1).coerceAtMost(sortedValues.lastIndex)
    val fraction = rank - low
    val value = sortedValues[low] + (sortedValues[high] - sortedValues[low]) * fraction
    return value.toLong()
}

internal fun percentile(sortedValues: List<Double>, p: Double): Double? {
    if (sortedValues.isEmpty()) return null
    if (sortedValues.size == 1) return sortedValues.first()
    val rank = (p.coerceIn(0.0, 100.0) / 100.0) * (sortedValues.size - 1)
    val low = rank.toInt()
    val high = (low + 1).coerceAtMost(sortedValues.lastIndex)
    val fraction = rank - low
    return sortedValues[low] + (sortedValues[high] - sortedValues[low]) * fraction
}

/** Clave estable `host:puerto` sin ruta, esquema ni query. */
internal fun hostKeyOf(uri: Uri): String {
    val host = uri.host?.takeIf { it.isNotBlank() } ?: "sin-host"
    val port = when {
        uri.port > 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    return "$host:$port"
}

/** Clasifica una URL HLS sin mirar tokens ni parámetros. */
internal fun sampleKindOf(path: String?): NetworkSampleKind {
    val clean = path?.lowercase().orEmpty()
    return when {
        clean.endsWith(".m3u8") || clean.endsWith(".m3u") -> NetworkSampleKind.PLAYLIST
        clean.endsWith(".ts") || clean.endsWith(".m4s") || clean.endsWith(".mp4") ||
            clean.endsWith(".aac") || clean.endsWith(".m4a") || clean.endsWith(".m4v") ->
            NetworkSampleKind.SEGMENT
        else -> NetworkSampleKind.OTHER
    }
}

/**
 * Envuelve la fuente HTTP real para medir TTFB, bytes y duración por carga.
 *
 * No altera el comportamiento normal: solo cuenta bytes y tiempos. La inyección de fallos
 * del laboratorio se añade por fuera, desde el source set `debug`, con
 * [decorateWithFaults]; en release esa función es la identidad y no existe código de fallos.
 */
internal class MeasuredDataSource(
    private val upstream: DataSource,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : DataSource {
    private var startedAt = 0L
    private var openAt = 0L
    private var firstByteAt = 0L
    private var bytes = 0L
    private var opened = false
    private var failed = false
    private var reachedEof = false
    private var currentHost = ""
    private var currentKind = NetworkSampleKind.OTHER

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        startedAt = clock()
        openAt = 0L
        firstByteAt = 0L
        bytes = 0L
        opened = false
        failed = false
        reachedEof = false
        currentHost = hostKeyOf(dataSpec.uri)
        currentKind = sampleKindOf(dataSpec.uri.path)
        try {
            val length = upstream.open(dataSpec)
            openAt = clock()
            opened = true
            return length
        } catch (error: IOException) {
            failed = true
            recordFailure(error, startedAt)
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = try {
            upstream.read(buffer, offset, length)
        } catch (error: IOException) {
            failed = true
            recordFailure(error, clock())
            throw error
        }
        if (read > 0) {
            if (firstByteAt == 0L) firstByteAt = clock()
            bytes += read
        } else if (read < 0 && !failed) {
            reachedEof = true
            finish(success = true)
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        // Solo se contabiliza una carga completa. Un cierre anticipado (seek, stop o
        // cancelación) dejaría una muestra con bytes parciales y subestimaría la capacidad.
        if (opened && !failed && reachedEof) finish(success = true)
        upstream.close()
    }

    private fun finish(success: Boolean) {
        if (!opened) return
        opened = false
        val end = clock()
        val total = (end - startedAt).coerceAtLeast(0L)
        val ttfb = when {
            firstByteAt > 0L -> (firstByteAt - startedAt).coerceAtLeast(0L)
            openAt > 0L -> (openAt - startedAt).coerceAtLeast(0L)
            else -> total
        }
        NetworkLoadStats.recordSample(
            NetworkSample(
                hostKey = currentHost,
                kind = currentKind,
                ttfbMs = ttfb,
                totalMs = total,
                bytes = bytes,
                atMs = end,
                success = success,
            ),
        )
    }

    private fun recordFailure(error: IOException, atMs: Long) {
        val elapsed = (atMs - startedAt).coerceAtLeast(0L)
        NetworkLoadStats.recordFailure(
            NetworkFailureSample(
                hostKey = currentHost,
                kind = currentKind,
                bytesBeforeFailure = bytes,
                elapsedMs = elapsed,
                atMs = atMs,
                cause = error.javaClass.simpleName,
            ),
        )
    }
}

/**
 * Fábrica que inserta [MeasuredDataSource] delante de la fuente HTTP.
 *
 * [decorateWithFaults] se resuelve en el source set de la variante: en debug envuelve la
 * fuente con el inyector de fallos; en release devuelve la misma instancia.
 */
internal class MeasuredDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        decorateWithFaults(MeasuredDataSource(upstreamFactory.createDataSource()))
}
