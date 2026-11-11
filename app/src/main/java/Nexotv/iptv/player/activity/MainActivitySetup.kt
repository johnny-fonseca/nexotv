// Archivo: Nexotv/iptv/player/activity/MainActivityWindow.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*




import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.player.*
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import Nexotv.iptv.player.MainActivity.Companion.KEY_RESIZE
import Nexotv.iptv.player.MainActivity.Companion.SUBTITLE_TEXT_FRACTION
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val VENTANA_TAG = "MainActivityWindow"

private inline fun ejecutarVentana(paso: String, bloque: () -> Unit) {
    try {
        bloque()
    } catch (e: Exception) {
        try {
            Log.w(VENTANA_TAG, "Falló el paso de ventana: $paso", e)
        } catch (_: RuntimeException) {
        }
    }
}

internal fun MainActivity.configureWindow() {
    ejecutarVentana("márgenes del sistema") {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
    ejecutarVentana("barras del sistema") {
        val controlador = WindowCompat.getInsetsController(window, window.decorView)
        controlador.hide(WindowInsetsCompat.Type.systemBars())
        controlador.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    ejecutarVentana("pantalla siempre encendida") {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

internal fun MainActivity.buildContentView(): FrameLayout {
    val root = FrameLayout(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
    }
    val vistaReproductor = buildPlayerView()
    playerView = vistaReproductor
    root.addView(
        vistaReproductor,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    // Si los overlays fallan, el video debe seguir mostrándose
    ejecutarVentana("overlays") { overlayController.attach(root) }
    return root
}

@OptIn(UnstableApi::class)
internal fun MainActivity.buildPlayerView(): PlayerView {
    val actividad = this
    return PlayerView(actividad).apply {
        useController = false
        isFocusable = false

        ejecutarVentana("estilo de subtítulos") {
            subtitleView?.apply {
                setFractionalTextSize(SUBTITLE_TEXT_FRACTION)
                setStyle(
                    CaptionStyleCompat(
                        Color.WHITE,
                        0x99000000.toInt(),
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK,
                        Typeface.DEFAULT_BOLD,
                    ),
                )
            }
        }

        val modoGuardado = try {
            actividad.mainPrefs.safeInt(KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
        } catch (_: Exception) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        resizeMode = if (modoGuardado in AspectRatioFrameLayout.RESIZE_MODE_FIT..AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            modoGuardado
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        ejecutarVentana("conservar contenido al reiniciar") { setKeepContentOnPlayerReset(true) }

        setOnClickListener {
            if (actividad.isFinishing || actividad.isDestroyed) return@setOnClickListener
            ejecutarVentana("alternar reproducción") { actividad.togglePlayback() }
        }
        setOnLongClickListener {
            if (!actividad.isFinishing && !actividad.isDestroyed) {
                ejecutarVentana("mostrar opciones") { actividad.showOptions() }
            }
            true
        }
    }
}