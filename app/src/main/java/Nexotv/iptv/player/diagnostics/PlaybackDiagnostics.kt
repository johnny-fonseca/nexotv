package Nexotv.iptv.player.diagnostics

import Nexotv.iptv.player.*
import Nexotv.iptv.player.common.FrameRateEstimator

import android.os.Handler
import android.view.View
import android.widget.TextView
import androidx.media3.common.VideoSize

/**
 * Estado mutable de diagnóstico de la reproducción y del HUD en pantalla.
 *
 * El controlador no conoce la Activity ni crea hilos propios. Las extensiones
 * existentes siguen leyendo las propiedades delegadas de MainActivity, por lo
 * que el movimiento no altera callbacks, timers ni la presentación actual.
 */
internal class PlaybackDiagnostics {
    private companion object {
        const val DEFAULT_DECODER_NAME = "Auto"
        const val UNSET_POSITION_MS = -1L
    }

    internal var diagnosticsVisible = false
    internal var hudView: TextView? = null
    internal var hudTicker: Runnable? = null

    internal var lastPositionMs = UNSET_POSITION_MS
        set(value) {
            field = if (value < 0L) UNSET_POSITION_MS else value
        }

    internal var frozenSamples = 0
        set(value) {
            field = value.coerceAtLeast(0)
        }

    internal var bufferingSamples = 0
        set(value) {
            field = value.coerceAtLeast(0)
        }

    internal var videoSize: VideoSize = VideoSize.UNKNOWN
    internal var frameRate = FrameRateEstimator()

    internal var fps = 0f
        set(value) {
            field = if (value.isNaN() || value.isInfinite() || value < 0f) 0f else value
        }

    internal var droppedVideoFrames = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    internal var videoDecoderName = DEFAULT_DECODER_NAME
        set(value) {
            field = value.trim().ifEmpty { DEFAULT_DECODER_NAME }
        }

    fun clearHud(handler: Handler) {
        val ticker = hudTicker
        hudTicker = null
        if (ticker != null) {
            try {
                handler.removeCallbacks(ticker)
            } catch (_: Exception) {
            }
        }
        try {
            hudView?.visibility = View.GONE
        } catch (_: Exception) {
        }
    }
}