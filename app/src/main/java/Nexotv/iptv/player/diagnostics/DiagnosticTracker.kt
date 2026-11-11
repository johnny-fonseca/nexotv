// DiagnosticTracker.kt
@file:UnstableApi
@file:Suppress("PackageName")

package Nexotv.iptv.player.diagnostics

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.common.formatByteSize
import Nexotv.iptv.player.network.NetworkLoadStats
import Nexotv.iptv.player.network.NetworkStatsSnapshot
import Nexotv.iptv.player.network.WifiLinkInfo
import Nexotv.iptv.player.network.WifiLinkSnapshot
import Nexotv.iptv.player.player.NetworkTuning
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

internal data class DiagnosticEvent(
    val time: String,
    val tag: String,
    val message: String,
)

internal object DiagnosticTracker {
    private const val MAX_EVENTS = 200
    private const val MAX_TAG_LENGTH = 40
    private const val MAX_MESSAGE_LENGTH = 500
    private const val MAX_URL_FALLBACK_LENGTH = 30
    private const val MAX_RESOURCE_LENGTH = 120
    private const val DEFAULT_TAG = "GENERAL"
    private const val DEFAULT_TIME = "--:--:--"
    private const val DEFAULT_ERROR_CODE = "Ninguno"
    private const val DEFAULT_ERROR_URL = "—"
    private const val DEFAULT_ERROR_TIME = "—"
    private const val DEFAULT_FAILURE_LABEL = "—"

    private val controlChars = Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]+")

    private val eventQueue = ArrayDeque<DiagnosticEvent>()

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    @Volatile var rebufferCount: Int = 0
    @Volatile var totalRebufferDurationMs: Long = 0L
    @Volatile var lastRebufferStartMs: Long = 0L
    @Volatile var isRebuffering: Boolean = false

    @Volatile var lastLoadErrorCode: String = DEFAULT_ERROR_CODE
    @Volatile var lastLoadErrorUrl: String = DEFAULT_ERROR_URL
    @Volatile var lastLoadErrorTime: String = DEFAULT_ERROR_TIME

    @Volatile var estimatedBandwidthBps: Long = 0L
    @Volatile var currentPlaybackSpeed: Float = 1.0f

    /** Etiqueta de la última causa clasificada de fallo (ver `PlaybackFailureKind`). */
    @Volatile var lastFailureLabel: String = DEFAULT_FAILURE_LABEL

    /** Duración de la última carga de segmento/lista completada y sus bytes. */
    @Volatile var lastLoadDurationMs: Long = 0L
    @Volatile var lastLoadBytes: Long = 0L

    // ─── Inventario de variantes y contadores de la ronda 2 ───

    /** Número de grupos de video publicados (aproximación al número de variantes HLS). */
    @Volatile var variantCount: Int = 0
    @Volatile var variantBitratesKbps: List<Int> = emptyList()

    /** Veces que Media3 informó ERROR_CODE_BEHIND_LIVE_WINDOW en esta sesión. */
    @Volatile var behindLiveWindowCount: Int = 0

    /** Recargas completas del stream (no los reintentos livianos). */
    @Volatile var fullReloadCount: Int = 0

    /** Instante del primer fotograma renderizado, para el tiempo hasta el primer frame. */
    @Volatile var firstFrameAtElapsedMs: Long = 0L

    /** Timeouts HTTP aplicados al reproductor y RTT p95 con el que se calcularon. */
    @Volatile var appliedConnectTimeoutMs: Int = 0
    @Volatile var appliedReadTimeoutMs: Int = 0
    @Volatile var appliedRttP95Ms: Long? = null
    @Volatile var timeoutsAreAdaptive: Boolean = false

    /** Instante de la última sospecha de MTU/PMTUD (patrón playlist OK / segmento corto). */
    private var mtuSuspectedAtMs = 0L

    private var rxBytesLast = -1L
    private var rxBytesAtMs = 0L
    @Volatile var receivedBytesPerSecond: Long = 0L

    /** Última lectura del enlace Wi-Fi y su marca de tiempo (se muestrea cada 2 s). */
    private var wifiSampledAtMs = 0L
    private var wifiSnapshot: WifiLinkSnapshot? = null

    private data class HudSnapshot(
        val rebufferCount: Int,
        val totalRebufferDurationMs: Long,
        val lastErrorCode: String,
        val lastErrorUrl: String,
        val lastErrorTime: String,
        val bandwidthBps: Long,
        val speed: Float,
        val failureLabel: String,
        val loadDurationMs: Long,
        val loadBytes: Long,
        val receivedBps: Long,
        val variantCount: Int,
        val variantBitratesKbps: List<Int>,
        val behindLiveWindowCount: Int,
        val fullReloadCount: Int,
    )

    private fun String.safeTake(limit: Int): String {
        if (limit <= 0) return ""
        if (length <= limit) return this
        var end = limit
        if (Character.isHighSurrogate(this[end - 1])) end--
        return substring(0, end)
    }

    private fun String.sanitized(): String = replace(controlChars, " ")

    @Synchronized
    fun log(tag: String, message: String) {
        try {
            val event = DiagnosticEvent(
                time = timestamp(),
                tag = tag.sanitized().trim().ifEmpty { DEFAULT_TAG }.safeTake(MAX_TAG_LENGTH),
                message = message.sanitized().safeTake(MAX_MESSAGE_LENGTH),
            )
            while (eventQueue.size >= MAX_EVENTS) {
                eventQueue.pollFirst()
            }
            eventQueue.addLast(event)
        } catch (_: Exception) {
        }
    }

    @Suppress("unused")
    @Synchronized
    fun getEvents(): List<DiagnosticEvent> = eventQueue.toList()

    @Synchronized
    fun exportLog(): String = try {
        buildString {
            appendLine("=== REGISTRO DE EVENTOS ${BuildConfig.BRAND_NAME.uppercase()} (Últimos ${eventQueue.size}) ===")
            for (event in eventQueue) {
                appendLine("[${event.time}] [${event.tag}] ${event.message}")
            }
        }
    } catch (e: Exception) {
        "=== REGISTRO DE EVENTOS (no disponible: ${e.javaClass.simpleName}) ==="
    }

    @Suppress("unused")
    @Synchronized
    fun reset() {
        eventQueue.clear()
        rebufferCount = 0
        totalRebufferDurationMs = 0L
        lastRebufferStartMs = 0L
        isRebuffering = false
        lastLoadErrorCode = DEFAULT_ERROR_CODE
        lastLoadErrorUrl = DEFAULT_ERROR_URL
        lastLoadErrorTime = DEFAULT_ERROR_TIME
        estimatedBandwidthBps = 0L
        currentPlaybackSpeed = 1.0f
        lastFailureLabel = DEFAULT_FAILURE_LABEL
        lastLoadDurationMs = 0L
        lastLoadBytes = 0L
        variantCount = 0
        variantBitratesKbps = emptyList()
        behindLiveWindowCount = 0
        fullReloadCount = 0
        firstFrameAtElapsedMs = 0L
        appliedConnectTimeoutMs = 0
        appliedReadTimeoutMs = 0
        appliedRttP95Ms = null
        timeoutsAreAdaptive = false
        mtuSuspectedAtMs = 0L
        rxBytesLast = -1L
        rxBytesAtMs = 0L
        receivedBytesPerSecond = 0L
        wifiSampledAtMs = 0L
        wifiSnapshot = null
    }

    @Synchronized
    fun onPlaybackStateBuffering() {
        // La carga inicial antes del primer fotograma no es un rebuffer: es el tiempo hasta
        // el primer frame y se mide aparte. Contarla inflaría el ratio de rebuffer.
        if (firstFrameAtElapsedMs == 0L) return
        if (isRebuffering) return
        isRebuffering = true
        if (rebufferCount < Int.MAX_VALUE) rebufferCount++
        lastRebufferStartMs = SystemClock.elapsedRealtime()
        SessionReport.onRebufferStarted()
        log("REBUFFER_START", "Buffering #$rebufferCount iniciado")
    }

    @Synchronized
    fun onPlaybackStateReady() {
        if (!isRebuffering) return
        isRebuffering = false
        val start = lastRebufferStartMs
        val duration = if (start > 0L) {
            (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        } else {
            0L
        }
        val current = totalRebufferDurationMs
        totalRebufferDurationMs = if (current > Long.MAX_VALUE - duration) Long.MAX_VALUE else current + duration
        lastRebufferStartMs = 0L
        log(
            "REBUFFER_END",
            "Buffering finalizado en ${duration}ms (Total detenido: ${totalRebufferDurationMs / 1000}s)",
        )
    }

    /** Registra la causa clasificada del último fallo para mostrarla en el HUD. */
    fun onFailureClassified(kind: PlaybackFailureKind) {
        lastFailureLabel = kind.label
    }

    /** Timeouts HTTP aplicados al crear el reproductor y el RTT con el que se calcularon. */
    fun onTimeouts(connectMs: Int, readMs: Int, rttP95Ms: Long?, adaptive: Boolean) {
        appliedConnectTimeoutMs = connectMs
        appliedReadTimeoutMs = readMs
        appliedRttP95Ms = rttP95Ms
        timeoutsAreAdaptive = adaptive
    }

    /** Inventario aproximado de variantes publicado por el manifiesto. */
    fun onVariants(count: Int, bitratesKbps: List<Int>) {
        variantCount = count.coerceAtLeast(0)
        variantBitratesKbps = bitratesKbps.sorted()
    }

    fun onFirstFrame(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (firstFrameAtElapsedMs == 0L) firstFrameAtElapsedMs = nowMs
    }

    /** Cuenta un BEHIND_LIVE_WINDOW; el llamador ya lo registra en el evento. */
    fun onBehindLiveWindow() {
        if (behindLiveWindowCount < Int.MAX_VALUE) behindLiveWindowCount++
    }

    /** Cuenta una recarga completa del stream, no un reintento liviano. */
    fun onFullReload() {
        if (fullReloadCount < Int.MAX_VALUE) fullReloadCount++
    }

    /**
     * Registra la decisión de reintento de un segmento.
     *
     * Se escribe en el registro para poder reconstruir por qué se escaló a recuperación:
     * `espera=null` significa que se agotó el presupuesto dentro de la ventana.
     */
    fun onSegmentRetry(decision: SegmentRetryDecision, errorCount: Int) {
        val delay = decision.delayMs?.let { "${it}ms" } ?: "ninguna (escala a recuperación)"
        log(
            "SEGMENT_RETRY",
            "Tipo=${decision.label}, intento=$errorCount, espera=$delay",
        )
    }

    /**
     * Marca una sospecha de MTU/PMTUD. La clasificación de fallos la usa durante 30 s.
     *
     * No afirma la causa: solo deja evidencia (bytes recibidos y host) para que el HUD y el
     * informe permitan decidir si conviene probar un clamp de MSS en el túnel.
     */
    fun onMtuSuspected(hostKey: String, bytesBeforeFailure: Long, elapsedMs: Long) {
        mtuSuspectedAtMs = SystemClock.elapsedRealtime()
        log(
            "MTU_SUSPECT",
            "Lista OK y segmento colgado tras ${bytesBeforeFailure}B en ${elapsedMs}ms ($hostKey); " +
                "compatible con MTU/PMTUD, no concluyente",
        )
    }

    fun mtuSuspectedRecently(
        windowMs: Long = 30_000L,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = mtuSuspectedAtMs > 0L && nowMs - mtuSuspectedAtMs <= windowMs

    /** Duración y tamaño de la última carga completada (segmento o lista HLS). */
    @Synchronized
    fun onLoadCompleted(durationMs: Long, bytes: Long) {
        if (durationMs <= 0L) return
        lastLoadDurationMs = durationMs
        lastLoadBytes = bytes.coerceAtLeast(0L)
    }

    @Synchronized
    fun onLoadError(dataType: String, errorCode: String, url: String) {
        val safeCode = errorCode.sanitized().trim().ifBlank { "Desconocido" }.safeTake(MAX_TAG_LENGTH)
        val safeType = dataType.sanitized().trim().ifBlank { "Desconocido" }.safeTake(MAX_TAG_LENGTH)
        val cleanUrl = url.substringAfterLast('/').substringBefore('?').substringBefore('#').sanitized().trim()
        val resource = when {
            cleanUrl.isNotBlank() -> cleanUrl.safeTake(MAX_RESOURCE_LENGTH)
            url.isNotBlank() -> (runCatching { java.net.URI(url.trim()).host }.getOrNull()?.sanitized()?.takeIf { it.isNotBlank() }
                ?: DEFAULT_ERROR_URL).safeTake(MAX_URL_FALLBACK_LENGTH)
            else -> DEFAULT_ERROR_URL
        }
        lastLoadErrorCode = safeCode
        lastLoadErrorUrl = resource
        lastLoadErrorTime = timestamp()
        log("LOAD_ERROR", "Tipo: $safeType, Error: $safeCode, Recurso: $resource")
    }

    fun onBandwidthEstimate(estimateBps: Long) {
        estimatedBandwidthBps = estimateBps.coerceAtLeast(0L)
    }

    fun onSpeedChanged(speed: Float) {
        if (speed.isNaN() || speed.isInfinite() || speed <= 0f) return
        currentPlaybackSpeed = speed
    }

    /**
     * Bytes/s recibidos por la app, medidos entre dos muestras del HUD.
     *
     * Solo consulta el tráfico del propio UID, así que no necesita permisos. Sirve para separar
     * "el enlace no entrega datos" de "el enlace entrega datos pero el proveedor no publica".
     */
    private fun sampleReceivedBytesPerSecond(nowMs: Long): Long {
        val received = try {
            TrafficStats.getUidRxBytes(Process.myUid())
        } catch (_: Exception) {
            -1L
        }
        if (received < 0L) return receivedBytesPerSecond
        val previousBytes = rxBytesLast
        val previousAtMs = rxBytesAtMs
        rxBytesLast = received
        rxBytesAtMs = nowMs
        val elapsedMs = nowMs - previousAtMs
        if (previousBytes < 0L || previousAtMs <= 0L || elapsedMs <= 0L) return receivedBytesPerSecond
        receivedBytesPerSecond = (received - previousBytes).coerceAtLeast(0L) * 1_000L / elapsedMs
        return receivedBytesPerSecond
    }

    /**
     * Estado del enlace Wi-Fi para el HUD, reutilizando la última lectura si aún es reciente.
     *
     * Devuelve null cuando la red activa no es Wi-Fi (por ejemplo, cable), para no mostrar una
     * línea que no aplica al escenario.
     */
    private fun sampleWifiLink(context: Context, nowMs: Long): WifiLinkSnapshot? {
        if (nowMs - wifiSampledAtMs < NetworkTuning.WIFI_SAMPLE_INTERVAL_MS) return wifiSnapshot
        wifiSampledAtMs = nowMs
        wifiSnapshot = WifiLinkInfo.read(context)
        return wifiSnapshot
    }

    fun formatHudText(activity: MainActivity): CharSequence {
        return try {
            buildHudText(activity)
        } catch (e: Exception) {
            "Diagnóstico: no disponible (${e.javaClass.simpleName})"
        }
    }

    @Synchronized
    private fun snapshot(): HudSnapshot = HudSnapshot(
        rebufferCount = rebufferCount,
        totalRebufferDurationMs = totalRebufferDurationMs,
        lastErrorCode = lastLoadErrorCode,
        lastErrorUrl = lastLoadErrorUrl,
        lastErrorTime = lastLoadErrorTime,
        bandwidthBps = estimatedBandwidthBps,
        speed = currentPlaybackSpeed,
        failureLabel = lastFailureLabel,
        loadDurationMs = lastLoadDurationMs,
        loadBytes = lastLoadBytes,
        receivedBps = receivedBytesPerSecond,
        variantCount = variantCount,
        variantBitratesKbps = variantBitratesKbps,
        behindLiveWindowCount = behindLiveWindowCount,
        fullReloadCount = fullReloadCount,
    )

    private fun buildHudText(activity: MainActivity): CharSequence {
        if (activity.isFinishing || activity.isDestroyed) return "Diagnóstico: pantalla no disponible"
        if (!activity.isPlayerInitialized) return "Diagnóstico: reproductor no inicializado"
        val player = activity.player
        val nowMs = SystemClock.elapsedRealtime()
        sampleReceivedBytesPerSecond(nowMs)
        val wifiLink = sampleWifiLink(activity.applicationContext, nowMs)
        val snap = snapshot()
        val bufferSec = format1(player.totalBufferedDuration.coerceAtLeast(0L) / 1000.0)
        val liveOffsetMs = player.currentLiveOffset
        val liveOffsetSec = if (player.isCurrentMediaItemLive && liveOffsetMs > 0L) {
            format1(liveOffsetMs / 1000.0)
        } else {
            "—"
        }
        val speedStr = String.format(Locale.US, "%.2f", snap.speed)
        val bwStr = if (snap.bandwidthBps > 0L) {
            String.format(Locale.US, "%.2f Mbps", snap.bandwidthBps / 1_000_000.0)
        } else {
            "estimando…"
        }
        val stoppedSec = format1(snap.totalRebufferDurationMs / 1000.0)
        val networkOk = runCatching { activity.hasValidatedInternet() }.getOrDefault(false)
        val networkStr = if (networkOk) {
            "Conectado (Internet OK)"
        } else {
            "Sin validación de internet"
        }
        val receivedStr = formatRate(snap.receivedBps)
        val segmentStr = if (snap.loadDurationMs > 0L) {
            "${snap.loadDurationMs} ms / ${formatByteSize(snap.loadBytes)}"
        } else {
            "—"
        }
        val levelStr = runCatching { activity.hotspotStabilizer.level.name }.getOrDefault("—")
        val network = runCatching {
            NetworkLoadStats.snapshot(declaredBitrateBps = player.videoFormat?.bitrate?.toLong())
        }.getOrNull()
        val linkStr = buildLinkLine(network)
        val variantStr = when {
            snap.variantCount <= 0 -> "n/d"
            snap.variantCount == 1 -> "1 (sin variantes inferiores)"
            else -> "${snap.variantCount} · ${snap.variantBitratesKbps.joinToString("/") { "${it / 1_000}" }} Mbps"
        }

        return buildString {
            append("• DIAGNÓSTICO EN VIVO\n")
            append("Buffer: ${bufferSec}s  ·  Retraso vivo: ${liveOffsetSec}s  ·  Vel: ${speedStr}x\n")
            append("Rebuffers: ${snap.rebufferCount} (${stoppedSec}s detenido)  ·  Bw est: $bwStr\n")
            append("Red: $networkStr  ·  Recibido: $receivedStr\n")
            wifiLink?.let { append("Wi-Fi: ${it.describe()}\n") }
            append("$linkStr\n")
            append("Variantes: $variantStr  ·  Estabilidad: $levelStr\n")
            append("Segmento: $segmentStr  ·  Recarga completa: ${snap.fullReloadCount}  ·  BEHIND: ${snap.behindLiveWindowCount}\n")
            append("Causa: ${snap.failureLabel}  ·  Último error: ${snap.lastErrorCode} (${snap.lastErrorUrl} a las ${snap.lastErrorTime})")
        }
    }

    /**
     * Línea de capacidad y RTT medida por la app.
     *
     * Sin muestras escribe "sin muestras" y los timeouts aplicados; nunca inventa una
     * capacidad. La holgura solo aparece si hay bitrate declarado y capacidad medida.
     */
    private fun buildLinkLine(snapshot: NetworkStatsSnapshot?): String {
        val timeouts = when {
            appliedConnectTimeoutMs <= 0 -> "t/o n/d"
            timeoutsAreAdaptive ->
                "t/o ${formatSeconds(appliedConnectTimeoutMs)}/${formatSeconds(appliedReadTimeoutMs)}"
            else -> "t/o fijos ${formatSeconds(appliedConnectTimeoutMs)}/${formatSeconds(appliedReadTimeoutMs)}"
        }
        if (snapshot == null || !snapshot.hasSamples) {
            return "Enlace: sin muestras · $timeouts"
        }
        val capacity = snapshot.sustainedCapacityBps?.let { formatRate(it / 8L) } ?: "cap n/d"
        val rtt = snapshot.rttP95Ms?.let { formatMillis(it) } ?: "n/d"
        val headroom = snapshot.headroom?.let { String.format(Locale.US, "%.1fx", it) } ?: "n/d"
        val ratio = snapshot.ratioP95?.let { String.format(Locale.US, "%.2f", it) } ?: "n/d"
        val failures = snapshot.failureRatePct?.let { "$it%" } ?: "n/d"
        return "Enlace: cap $capacity · RTT p95 $rtt · holgura $headroom · desc/seg $ratio · fallos $failures · $timeouts"
    }

    private fun formatMillis(value: Long): String =
        if (value >= 1_000L) String.format(Locale.US, "%.1fs", value / 1_000.0) else "${value}ms"

    private fun formatSeconds(ms: Int): String =
        String.format(Locale.US, "%.1fs", ms / 1_000.0)


    /** Velocidad de recepción en bits/s, el mismo orden de magnitud que el bitrate del video. */
    private fun formatRate(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "—"
        val bitsPerSecond = bytesPerSecond * 8L
        return if (bitsPerSecond >= 1_000_000L) {
            String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
        } else {
            "${bitsPerSecond / 1_000L} kbps"
        }
    }

    private fun format1(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "—" else String.format(Locale.US, "%.1f", value)

    private fun timestamp(): String = try {
        val formatter = timeFormat.get() ?: SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        formatter.format(Date())
    } catch (_: Exception) {
        DEFAULT_TIME
    }
}

private fun MainActivity.isHudEnabled(): Boolean = try {
    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        .safeBoolean(MainActivity.KEY_DIAGNOSTIC_HUD, default = false)
} catch (_: Exception) {
    false
}

private fun MainActivity.isUiUnavailable(): Boolean = isFinishing || isDestroyed

private fun MainActivity.ensureDiagnosticHudView(): TextView {
    val existing = diagnosticHudView
    if (existing != null) {
        val parent = existing.parent
        if (parent === statusHost) return existing
        if (parent is ViewGroup) parent.removeView(existing)
    }

    val hud = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.WHITE)
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        background = TvStyle.playerOverlaySurface(this@ensureDiagnosticHudView, dp(12).toFloat())
        elevation = dp(8).toFloat()
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isFocusable = false
        isClickable = false
    }
    val params = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        topMargin = dp(16)
        marginStart = dp(16)
    }
    statusHost.addView(hud, params)
    diagnosticHudView = hud
    return hud
}

internal fun MainActivity.setupDiagnosticHud() {
    if (isUiUnavailable()) return
    if (!isHudEnabled()) {
        diagnosticHudView?.visibility = View.GONE
        diagnosticHudTicker?.let(handler::removeCallbacks)
        diagnosticHudTicker = null
        return
    }
    ensureDiagnosticHudView().visibility = View.VISIBLE
    positionDiagnosticHud()
    startDiagnosticHudTicker()
}

internal fun MainActivity.positionDiagnosticHud() {
    val hud = diagnosticHudView ?: return
    val params = hud.layoutParams as? FrameLayout.LayoutParams ?: return
    val indicator = recordingIndicator
    val recordingVisible = indicator?.parent === statusHost && indicator.isVisible
    params.topMargin = if (recordingVisible) safeMarginVertical() + dp(64) else dp(16)
    hud.layoutParams = params
}

internal fun MainActivity.startDiagnosticHudTicker() {
    diagnosticHudTicker?.let(handler::removeCallbacks)
    val ticker = object : Runnable {
        override fun run() {
            if (isUiUnavailable()) return
            if (!isHudEnabled()) {
                diagnosticHudView?.visibility = View.GONE
                diagnosticHudTicker = null
                return
            }
            diagnosticHudView?.text = DiagnosticTracker.formatHudText(this@startDiagnosticHudTicker)
            handler.postDelayed(this, 1_000L)
        }
    }
    diagnosticHudTicker = ticker
    handler.post(ticker)
}

internal fun MainActivity.toggleDiagnosticHud() {
    if (isUiUnavailable()) return
    val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    val next = !prefs.safeBoolean(MainActivity.KEY_DIAGNOSTIC_HUD, default = false)
    prefs.edit { putBoolean(MainActivity.KEY_DIAGNOSTIC_HUD, next) }
    setupDiagnosticHud()
    showStatus(if (next) "Panel de diagnóstico en pantalla activado" else "Panel de diagnóstico desactivado")
    hideStatusAfterDelay()
}

@Suppress("UsePropertyAccessSyntax")
@SuppressLint("SetTextI18n")
internal fun MainActivity.showDiagnosticLogDialog() {
    if (isUiUnavailable()) return
    val logText = buildString {
        append(DiagnosticTracker.exportLog())
        appendLine()
        appendLine("=== INFORME DE SESIONES (KPIs) ===")
        append(SessionReport.summaryText(this@showDiagnosticLogDialog))
    }
    val tv = TextView(this).apply {
        text = logText.ifBlank { "No hay eventos registrados aún." }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        setTextColor(getColor(R.color.tv_text_primary))
        setTextIsSelectable(true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            TextView(this@showDiagnosticLogDialog).apply {
                text = "Cortes, rebuffers y errores recientes de la reproducción."
                setTextColor(getColor(R.color.tv_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(4), dp(8), dp(10))
            },
        )
        addView(tv, LinearLayout.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(this)
        .setView(createStyledFormPanel("Registro de eventos de red y reproducción", "DIAGNÓSTICO", content))
        .setPositiveButton("Copiar registro") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                showStatus("No se pudo acceder al portapapeles")
            } else {
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Registro ${BuildConfig.BRAND_NAME}", logText))
                    showStatus("Registro copiado al portapapeles")
                } catch (_: RuntimeException) {
                    showStatus("No se pudo copiar el registro")
                }
            }
            hideStatusAfterDelay()
        }
        .setNegativeButton("Cerrar", null)
        .showForTv()
}