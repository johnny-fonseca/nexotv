package Nexotv.iptv.player.diagnostics

import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.network.NetworkLoadStats
import Nexotv.iptv.player.player.BufferSizes
import android.content.Context
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * KPIs de una sesión de reproducción.
 *
 * Solo datos agregados: no incluye SSID, BSSID, ubicación ni URL completa. El host se guarda
 * como `host:puerto`, sin ruta ni query, para poder comparar 7050/40000 con 443.
 */
internal data class SessionKpis(
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val timeToFirstFrameMs: Long?,
    val rebufferCount: Int,
    val rebufferDurationMs: Long,
    val rebufferRatioPct: Int,
    val stallsPerHour: Int,
    val behindLiveWindowCount: Int,
    val fullReloadCount: Int,
    val mttrMaxMs: Long?,
    val mttrSamples: Int,
    val longestCutSurvivedMs: Long,
    val bytesConsumed: Long,
    val profile: String,
    val hostKey: String,
    val variantCount: Int,
    val capacityBps: Long?,
    val rttP95Ms: Long?,
    val headroom: Double?,
    val appVersion: String,
    val device: String,
)

/**
 * Calcula los KPIs de una sesión a partir de contadores ya medidos.
 *
 * Es una función pura para poder probarla con reloj falso y valores conocidos: no toca
 * Android ni el reproductor.
 */
internal fun buildSessionKpis(
    startedAtEpochMs: Long,
    durationMs: Long,
    firstFrameAtElapsedMs: Long?,
    startedAtElapsedMs: Long,
    rebufferCount: Int,
    rebufferDurationMs: Long,
    behindLiveWindowCount: Int,
    fullReloadCount: Int,
    mttrMaxMs: Long?,
    mttrSamples: Int,
    longestCutSurvivedMs: Long,
    bytesConsumed: Long,
    profile: String,
    hostKey: String,
    variantCount: Int,
    capacityBps: Long?,
    rttP95Ms: Long?,
    headroom: Double?,
    appVersion: String,
    device: String,
): SessionKpis {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val rebuffer = rebufferDurationMs.coerceIn(0L, safeDuration.coerceAtLeast(0L))
    val ratio = if (safeDuration > 0L) {
        (rebuffer * 100.0 / safeDuration).roundToInt().coerceIn(0, 100)
    } else {
        0
    }
    val stallsPerHour = if (safeDuration >= 60_000L) {
        (rebufferCount * 3_600_000.0 / safeDuration).roundToInt()
    } else {
        0
    }
    val firstFrame = firstFrameAtElapsedMs
        ?.takeIf { it >= startedAtElapsedMs }
        ?.let { it - startedAtElapsedMs }
    return SessionKpis(
        startedAtEpochMs = startedAtEpochMs,
        durationMs = safeDuration,
        timeToFirstFrameMs = firstFrame,
        rebufferCount = rebufferCount.coerceAtLeast(0),
        rebufferDurationMs = rebuffer,
        rebufferRatioPct = ratio,
        stallsPerHour = stallsPerHour,
        behindLiveWindowCount = behindLiveWindowCount.coerceAtLeast(0),
        fullReloadCount = fullReloadCount.coerceAtLeast(0),
        mttrMaxMs = mttrMaxMs?.takeIf { it >= 0L },
        mttrSamples = mttrSamples.coerceAtLeast(0),
        longestCutSurvivedMs = longestCutSurvivedMs.coerceAtLeast(0L),
        bytesConsumed = bytesConsumed.coerceAtLeast(0L),
        profile = profile,
        hostKey = hostKey,
        variantCount = variantCount.coerceAtLeast(0),
        capacityBps = capacityBps?.takeIf { it > 0L },
        rttP95Ms = rttP95Ms?.takeIf { it > 0L },
        headroom = headroom?.takeIf { it.isFinite() && it > 0.0 },
        appVersion = appVersion,
        device = device,
    )
}

/**
 * Graba las últimas sesiones en un JSON rotativo y las resume para el diálogo de diagnóstico.
 *
 * Escribe en el almacenamiento externo privado de la app (`Android/data/<paquete>/files/
 * diagnostics/`), que se puede extraer con `adb pull` en el APK de depuración. Si no está
 * disponible, usa el almacenamiento interno.
 *
 * Límites: 20 sesiones y 1 MiB. Al superarlos se descartan las más antiguas.
 */
internal object SessionReport {
    private const val MAX_SESSIONS = 20
    private const val MAX_FILE_BYTES = 1_048_576L
    private const val FILE_NAME = "sessions.json"
    private const val DIR_NAME = "diagnostics"

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NexoTV-SessionReport").apply { isDaemon = true }
    }

    private data class Active(
        val startedAtEpochMs: Long,
        val startedAtElapsedMs: Long,
        val hostKey: String,
        val profile: String,
        val variantCount: Int,
        val startRebufferCount: Int,
        val startRebufferMs: Long,
        val startBytes: Long,
        var firstFrameAtElapsedMs: Long = 0L,
        var behindLiveWindowCount: Int = 0,
        var fullReloadCount: Int = 0,
        var mttrMaxMs: Long = -1L,
        var mttrSamples: Int = 0,
        var recoveryStartedAtMs: Long = 0L,
        var lastCutStartMs: Long = 0L,
        var rebufferedDuringCut: Boolean = false,
        var longestCutSurvivedMs: Long = 0L,
    )

    private var active: Active? = null

    /** Empieza una sesión si no hay una activa. Los reintentos no crean sesiones nuevas. */
    fun start(
        hostKey: String,
        profile: String,
        variantCount: Int,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (active != null) return
        active = Active(
            startedAtEpochMs = System.currentTimeMillis(),
            startedAtElapsedMs = nowElapsedMs,
            hostKey = hostKey,
            profile = profile,
            variantCount = variantCount,
            startRebufferCount = DiagnosticTracker.rebufferCount,
            startRebufferMs = DiagnosticTracker.totalRebufferDurationMs,
            startBytes = uidRxBytes(),
        )
    }

    fun onFirstFrame(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val session = active ?: return
        if (session.firstFrameAtElapsedMs == 0L) session.firstFrameAtElapsedMs = nowElapsedMs
    }

    fun onBehindLiveWindow() {
        active?.let { it.behindLiveWindowCount++ }
    }

    fun onFullReload() {
        active?.let { it.fullReloadCount++ }
    }

    fun onRecoveryStarted(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val session = active ?: return
        if (session.recoveryStartedAtMs == 0L) session.recoveryStartedAtMs = nowElapsedMs
    }

    /** Se llama cuando la reproducción lleva 15 s estable tras una recuperación. */
    fun onRecoveryStable(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val session = active ?: return
        val started = session.recoveryStartedAtMs
        if (started <= 0L) return
        val mttr = (nowElapsedMs - started).coerceAtLeast(0L)
        session.mttrMaxMs = maxOf(session.mttrMaxMs, mttr)
        session.mttrSamples++
        session.recoveryStartedAtMs = 0L
    }

    fun onNetworkLost(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val session = active ?: return
        session.lastCutStartMs = nowElapsedMs
        session.rebufferedDuringCut = false
    }

    fun onRebufferStarted() {
        active?.let { it.rebufferedDuringCut = true }
    }

    fun onNetworkAvailable(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val session = active ?: return
        if (session.lastCutStartMs <= 0L) return
        val cut = (nowElapsedMs - session.lastCutStartMs).coerceAtLeast(0L)
        if (!session.rebufferedDuringCut) {
            session.longestCutSurvivedMs = maxOf(session.longestCutSurvivedMs, cut)
        }
        session.lastCutStartMs = 0L
    }

    /**
     * Cierra la sesión y la persiste. Es idempotente: si no hay sesión activa no hace nada.
     */
    fun finish(
        context: Context,
        reason: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val session = active ?: return
        active = null
        val duration = (nowElapsedMs - session.startedAtElapsedMs).coerceAtLeast(0L)
        if (duration < 5_000L) return
        val snapshot = NetworkLoadStats.snapshot()
        val declared = DiagnosticTracker.variantBitratesKbps.maxOrNull()?.toLong()?.times(1_000L)
        val kpis = buildSessionKpis(
            startedAtEpochMs = session.startedAtEpochMs,
            durationMs = duration,
            firstFrameAtElapsedMs = session.firstFrameAtElapsedMs.takeIf { it > 0L },
            startedAtElapsedMs = session.startedAtElapsedMs,
            rebufferCount = DiagnosticTracker.rebufferCount - session.startRebufferCount,
            rebufferDurationMs = DiagnosticTracker.totalRebufferDurationMs - session.startRebufferMs,
            behindLiveWindowCount = session.behindLiveWindowCount,
            fullReloadCount = session.fullReloadCount,
            mttrMaxMs = session.mttrMaxMs.takeIf { it >= 0L },
            mttrSamples = session.mttrSamples,
            longestCutSurvivedMs = session.longestCutSurvivedMs,
            bytesConsumed = (uidRxBytes() - session.startBytes).coerceAtLeast(0L),
            profile = session.profile,
            hostKey = session.hostKey,
            variantCount = maxOf(session.variantCount, DiagnosticTracker.variantCount),
            capacityBps = snapshot.sustainedCapacityBps,
            rttP95Ms = snapshot.rttP95Ms,
            headroom = snapshot.headroom ?: headroomOf(snapshot.sustainedCapacityBps, declared),
            appVersion = BuildConfig.VERSION_NAME,
            device = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})",
        )
        ukiLog(kpis, reason)
        val appContext = context.applicationContext
        writer.execute { runCatching { append(appContext, kpis) } }
    }

    /** Texto corto con las últimas sesiones, para el diálogo de diagnóstico. */
    fun summaryText(context: Context, limit: Int = 5): String = try {
        val array = readArray(context)
        if (array.length() == 0) {
            "Sin sesiones registradas todavía."
        } else {
            buildString {
                appendLine("Sesiones guardadas: ${array.length()} (máx. $MAX_SESSIONS)")
                for (index in (array.length() - 1) downTo maxOf(0, array.length() - limit)) {
                    val item = array.optJSONObject(index) ?: continue
                    appendLine(formatKpiLine(item))
                }
                append("Archivo: ${reportFile(context).absolutePath}")
            }
        }
    } catch (error: Exception) {
        "Informe de sesiones no disponible (${error.javaClass.simpleName})"
    }

    fun reportFile(context: Context): File =
        File(reportDir(context), FILE_NAME)

    private fun append(context: Context, kpis: SessionKpis) {
        val array = readArray(context)
        array.put(toJson(kpis))
        while (array.length() > MAX_SESSIONS) array.remove(0)
        var text = array.toString()
        while (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES && array.length() > 1) {
            array.remove(0)
            text = array.toString()
        }
        val file = reportFile(context)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun readArray(context: Context): JSONArray {
        val file = reportFile(context)
        if (!file.isFile) return JSONArray()
        return runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrElse { JSONArray() }
    }

    private fun reportDir(context: Context): File {
        val base = context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)
        if (!base.isDirectory) base.mkdirs()
        return base
    }

    private fun toJson(kpis: SessionKpis): JSONObject = JSONObject().apply {
        put("startedAt", kpis.startedAtEpochMs)
        put("durationMs", kpis.durationMs)
        put("timeToFirstFrameMs", kpis.timeToFirstFrameMs ?: JSONObject.NULL)
        put("rebufferCount", kpis.rebufferCount)
        put("rebufferDurationMs", kpis.rebufferDurationMs)
        put("rebufferRatioPct", kpis.rebufferRatioPct)
        put("stallsPerHour", kpis.stallsPerHour)
        put("behindLiveWindowCount", kpis.behindLiveWindowCount)
        put("fullReloadCount", kpis.fullReloadCount)
        put("mttrMaxMs", kpis.mttrMaxMs ?: JSONObject.NULL)
        put("mttrSamples", kpis.mttrSamples)
        put("longestCutSurvivedMs", kpis.longestCutSurvivedMs)
        put("bytesConsumed", kpis.bytesConsumed)
        put("profile", kpis.profile)
        put("host", kpis.hostKey)
        put("variantCount", kpis.variantCount)
        put("capacityBps", kpis.capacityBps ?: JSONObject.NULL)
        put("rttP95Ms", kpis.rttP95Ms ?: JSONObject.NULL)
        put("headroom", kpis.headroom ?: JSONObject.NULL)
        put("appVersion", kpis.appVersion)
        put("device", kpis.device)
    }

    private fun formatKpiLine(item: JSONObject): String {
        val minutes = item.optLong("durationMs") / 60_000L
        val firstFrame = item.optLong("timeToFirstFrameMs", -1L)
        val mttr = item.optLong("mttrMaxMs", -1L)
        return String.format(
            Locale.US,
            "· %s min · 1er frame %s · rebuffers %d (%d%%) · stalls/h %d · BEHIND %d · recargas %d · MTTR %s · corte superado %ss",
            minutes,
            if (firstFrame >= 0L) "${firstFrame}ms" else "n/d",
            item.optInt("rebufferCount"),
            item.optInt("rebufferRatioPct"),
            item.optInt("stallsPerHour"),
            item.optInt("behindLiveWindowCount"),
            item.optInt("fullReloadCount"),
            if (mttr >= 0L) "${mttr / 1_000}s" else "n/d",
            item.optLong("longestCutSurvivedMs") / 1_000L,
        )
    }

    private fun ukiLog(kpis: SessionKpis, reason: String) {
        DiagnosticTracker.log(
            "SESSION_KPI",
            "fin=$reason; ${kpis.durationMs / 1_000}s; 1erFrame=${kpis.timeToFirstFrameMs ?: -1}ms; " +
                "rebuffers=${kpis.rebufferCount} (${kpis.rebufferRatioPct}%), stalls/h=${kpis.stallsPerHour}; " +
                "BEHIND=${kpis.behindLiveWindowCount}; recargas=${kpis.fullReloadCount}; " +
                "MTTR=${kpis.mttrMaxMs ?: -1}ms; corteSuperado=${kpis.longestCutSurvivedMs}ms; " +
                "bytes=${kpis.bytesConsumed}; host=${kpis.hostKey}; variantes=${kpis.variantCount}; " +
                "capacidad=${kpis.capacityBps ?: -1}; rttP95=${kpis.rttP95Ms ?: -1}",
        )
    }

    private fun uidRxBytes(): Long = try {
        TrafficStats.getUidRxBytes(Process.myUid())
    } catch (_: Exception) {
        -1L
    }

    private fun headroomOf(capacityBps: Long?, declaredBps: Long?): Double? =
        if (capacityBps != null && capacityBps > 0L && declaredBps != null && declaredBps > 0L) {
            capacityBps.toDouble() / declaredBps.toDouble()
        } else {
            null
        }

    /** Perfil legible para el informe (evita guardar la preferencia cruda). */
    fun profileLabel(profile: String): String = when (profile) {
        BufferSizes.PROFILE_HOTSPOT -> "Hotspot"
        BufferSizes.PROFILE_LARGE -> "Large"
        BufferSizes.PROFILE_MEDIUM -> "Medium"
        BufferSizes.PROFILE_SMALL -> "Small"
        else -> profile
    }
}
