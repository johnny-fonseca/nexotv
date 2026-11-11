@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package Nexotv.iptv.player.network

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque

/** Fallos que el laboratorio de depuración puede inyectar en el enlace de medios. */
internal enum class FaultMode {
    NONE,
    BLACKHOLE,
    RESET,
    LATENCY,
    THROTTLE,
    DNS,
    LOAD_LATENCY,
}

/**
 * Estado del laboratorio. Solo existe en el source set `debug`.
 *
 * El APK de release no contiene esta clase, el receptor que la modifica ni las cadenas de
 * los fallos simulados. Los valores se pierden al cerrar la app, que es lo deseable para
 * una prueba de campo.
 */
internal object DebugFaultState {
    @Volatile var mode: FaultMode = FaultMode.NONE
    @Volatile var value: Long = 0L
    @Volatile var hostFilter: String? = null

    fun set(newMode: String?, newValue: Long, newHost: String?): Boolean {
        val parsed = FaultMode.entries.firstOrNull {
            it.name.equals(newMode?.trim(), ignoreCase = true)
        } ?: return false
        mode = parsed
        value = newValue.coerceAtLeast(0L)
        hostFilter = newHost?.trim()?.takeIf { it.isNotBlank() }
        return true
    }

    fun reset() {
        mode = FaultMode.NONE
        value = 0L
        hostFilter = null
    }

    fun matches(hostKey: String): Boolean {
        val filter = hostFilter ?: return true
        return hostKey.contains(filter, ignoreCase = true)
    }
}

/** Envuelve la fuente medida con el inyector cuando el laboratorio está activo. */
internal fun decorateWithFaults(source: DataSource): DataSource =
    if (DebugFaultState.mode == FaultMode.NONE) source else FaultInjectingDataSource(source)

/**
 * Inyector de fallos del laboratorio.
 *
 * - `BLACKHOLE <ms>`: espera y lanza timeout de lectura.
 * - `RESET`: lanza un reset de conexión en la primera lectura.
 * - `LATENCY <ms>`: añade latencia fija a la apertura.
 * - `THROTTLE <bytes/s>`: limita la velocidad de lectura.
 * - `DNS`: hace fallar la resolución del host.
 * - `LOAD_LATENCY`: RTT que crece con la carga reciente (0,1 → 2 → 3,5 s).
 *
 * Se aplica solo a peticiones HTTP(S) y solo cuando el host coincide con el filtro.
 */
internal class FaultInjectingDataSource(
    private val upstream: DataSource,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : DataSource {
    private var activePlan: FaultMode = FaultMode.NONE
    private var planValue = 0L
    private var hostKey = ""
    private var throttleStartedAt = 0L
    private var throttledBytes = 0L
    private var resetSent = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        hostKey = hostKeyOf(dataSpec.uri)
        resetSent = false
        throttledBytes = 0L
        throttleStartedAt = clock()
        activePlan = if (dataSpec.uri.scheme?.startsWith("http", ignoreCase = true) == true &&
            DebugFaultState.matches(hostKey)
        ) {
            DebugFaultState.mode
        } else {
            FaultMode.NONE
        }
        planValue = DebugFaultState.value
        when (activePlan) {
            FaultMode.DNS -> throw UnknownHostException(hostKey)
            FaultMode.LATENCY -> sleepQuietly(planValue)
            FaultMode.LOAD_LATENCY -> sleepQuietly(loadSensitiveLatencyMs())
            else -> Unit
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        when (activePlan) {
            FaultMode.BLACKHOLE -> {
                sleepQuietly(planValue.coerceAtMost(120_000L))
                throw SocketTimeoutException("blackhole de laboratorio")
            }
            FaultMode.RESET -> if (!resetSent) {
                resetSent = true
                throw SocketException("reset de laboratorio")
            }
            FaultMode.LOAD_LATENCY -> if (throttledBytes == 0L) {
                sleepQuietly(loadSensitiveLatencyMs())
            }
            else -> Unit
        }
        val read = upstream.read(buffer, offset, length)
        if (read > 0) {
            if (activePlan == FaultMode.LOAD_LATENCY) LabLoadMonitor.note(read, clock())
            if (activePlan == FaultMode.THROTTLE && planValue > 0L) {
                throttledBytes += read
                val targetMs = throttledBytes * 1_000L / planValue
                val elapsed = clock() - throttleStartedAt
                val sleep = targetMs - elapsed
                if (sleep > 0L) sleepQuietly(sleep)
            }
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    private fun loadSensitiveLatencyMs(): Long {
        val bytesPerSecond = LabLoadMonitor.bytesInLastSecond(clock())
        return when {
            bytesPerSecond >= 600_000L -> 3_500L
            bytesPerSecond >= 200_000L -> 2_000L
            else -> 100L
        }
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** Bytes leídos en el último segundo, para simular latencia que crece bajo carga. */
internal object LabLoadMonitor {
    private val events = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun note(bytes: Int, nowMs: Long) {
        events.addLast(nowMs to bytes.toLong())
        while (events.isNotEmpty() && nowMs - events.first().first > 5_000L) events.removeFirst()
    }

    @Synchronized
    fun bytesInLastSecond(nowMs: Long): Long =
        events.asSequence()
            .filter { nowMs - it.first <= 1_000L }
            .sumOf { it.second }
}
