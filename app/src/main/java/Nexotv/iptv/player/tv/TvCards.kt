@file:Suppress("PackageName")
@file:androidx.media3.common.util.UnstableApi

package Nexotv.iptv.player.tv
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.recordings.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

private val PALABRAS_ADVERTENCIA = listOf("error", "fallo", "insuficiente", "conflicto", "eliminar")

// ----------------------------------------------------------------------------
// Tarjetas enfocables
// ----------------------------------------------------------------------------

/** Crea una tarjeta enfocable con el icono deducido automáticamente del título. */
internal fun MainActivity.focusCard(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
    focusCard(title, subtitle, cardIcon(title), onClick)

/**
 * Crea una tarjeta enfocable (fila de menú) con icono, título y subtítulo.
 * Las acciones de grabación se resaltan con una paleta roja.
 *
 * @param title título principal de la tarjeta.
 * @param subtitle texto secundario; si está en blanco se oculta.
 * @param iconResource recurso drawable del icono.
 * @param onClick acción a ejecutar al seleccionar la tarjeta.
 */
internal fun MainActivity.focusCard(title: String, subtitle: String, iconResource: Int, onClick: () -> Unit): LinearLayout {
    val recordingAction = (title == "Grabar ahora") || (title == "Detener grabación")
    val titleView = TextView(this).apply {
        text = title
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    val subtitleView = TextView(this).apply {
        text = subtitle
        textSize = 15f
        setTextColor(0xFF8899AA.toInt())
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(4), 0, 0)
        visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
    }
    val iconBox = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (recordingAction) 0x33EF4444.toInt() else 0xFF132034.toInt())
        }
    }
    val iconView = ImageView(this).apply {
        setImageResource(iconResource)
        imageTintList = ColorStateList.valueOf(if (recordingAction) 0xFFFF8B91.toInt() else getColor(R.color.tv_turquoise))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    iconBox.addView(iconView, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))

    val textColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(titleView)
        addView(subtitleView)
    }

    return LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        isFocusable = true
        isClickable = true
        isFocusableInTouchMode = true
        minimumHeight = dp(100)
        contentDescription = if (subtitle.isBlank()) title else "$title. $subtitle"
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = if (recordingAction) menuRedCardBackground(this@focusCard) else menuRowBackground(this@focusCard)
        addView(iconBox, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
        addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        clipChildren = true
        clipToPadding = false
        setOnFocusChangeListener { view, focused ->
            titleView.setTextColor(Color.WHITE)
            subtitleView.setTextColor(if (focused) 0xFFD8ECF8.toInt() else 0xFF8899AA.toInt())
            (iconBox.background as? GradientDrawable)?.setColor(
                if (focused) (if (recordingAction) 0xFFEF4444.toInt() else getColor(R.color.tv_turquoise))
                else (if (recordingAction) 0x33EF4444.toInt() else 0xFF132034.toInt()),
            )
            iconView.imageTintList = ColorStateList.valueOf(
                if (focused) (if (recordingAction) Color.WHITE else 0xFF07111D.toInt())
                else (if (recordingAction) 0xFFFF8B91.toInt() else getColor(R.color.tv_turquoise)),
            )
            animarFoco(view, focused, 5)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
}

/**
 * Deduce el recurso de icono más apropiado a partir de la etiqueta de una tarjeta.
 * El orden de las condiciones es significativo: la primera coincidencia gana.
 */
internal fun cardIcon(label: String): Int {
    val value = label.lowercase(Locale.ROOT)
    return when {
        PALABRAS_ADVERTENCIA.any { it in value } -> R.drawable.tv_icon_warning
        value == "guía y recordatorios" -> R.drawable.tv_icon_guide_group
        value == "guía" || value == "guia" -> R.drawable.tv_icon_guide
        value == "recordatorios y grabaciones" || value == "avisos y grabaciones" -> R.drawable.tv_icon_reminder
        "origen de la guía" in value || "fuente de la guía" in value || "fuente de guía" in value || "epg" in value -> R.drawable.tv_icon_epg_source
        value == "imagen y sonido" -> R.drawable.tv_icon_audio
        value == "decodificador" || "calidad" in value || "resolución" in value -> R.drawable.tv_icon_quality
        "compatibilidad de video" in value || "video compatible" in value || "decodificadores" in value -> R.drawable.tv_icon_decoder
        "tamaño de la imagen" in value || "tamaño de imagen" in value || "ajustar imagen predeterminada" in value || "restablecer imagen" in value -> R.drawable.tv_icon_aspect
        "inicio automático" in value -> R.drawable.tv_icon_auto_start
        "confirmar al salir" in value -> R.drawable.tv_icon_success
        "tiempo visible" in value -> R.drawable.tv_icon_timer
        "transparencia" in value -> R.drawable.tv_icon_contrast
        "tamaño de texto" in value -> R.drawable.tv_icon_text_size
        "formato del reloj" in value || "reloj" in value -> R.drawable.tv_icon_clock
        "margen después" in value || "margen inicial" in value || "margen final" in value ||
            "programaciones" in value || "conservar programación" in value -> R.drawable.tv_icon_schedule
        "retroceder" in value -> R.drawable.tv_icon_seek_back
        "avanzar" in value -> R.drawable.tv_icon_seek_forward
        "saltos" in value -> R.drawable.tv_icon_seek
        "fluidez" in value || "frecuencia" in value -> R.drawable.tv_icon_fluency
        "actualizar" in value || "refrescar" in value -> R.drawable.tv_icon_refresh
        "compatibilidad de audio" in value -> R.drawable.tv_icon_audio_decoder
        "pistas de audio" in value || "audio y subtítulos" in value -> R.drawable.tv_icon_audio_tracks
        "desfasado" in value || "sincronización de audio" in value || "sincronizar audio" in value -> R.drawable.tv_icon_audio_sync
        "subtítulo" in value -> R.drawable.tv_icon_subtitles
        "envolvente" in value || "passthrough" in value || "surround" in value -> R.drawable.tv_icon_surround
        "silenciar" in value || "activar sonido" in value || "volumen" in value -> R.drawable.tv_icon_volume_off
        "fuente de la señal" in value || "fuente de tv" in value -> R.drawable.tv_icon_stream_source
        "registro de eventos" in value || "registro de red" in value -> R.drawable.tv_icon_log
        "panel de diagnóstico" in value || "diagnóstico en pantalla" in value -> R.drawable.tv_icon_hud
        "diagnóstico" in value -> R.drawable.tv_icon_diagnostic
        "red" in value -> R.drawable.tv_icon_wifi
        "copia de ajustes" in value || "respaldo" in value -> R.drawable.tv_icon_backup
        "importar" in value -> R.drawable.tv_icon_import
        "exportar informe" in value -> R.drawable.tv_icon_report
        "exportar" in value -> R.drawable.tv_icon_database
        "abrir ahora con otra aplicación" in value || "enviar a reproductor" in value || "reproductor externo" in value || "abrir con otra aplicación" in value -> R.drawable.tv_icon_external_player
        "user-agent" in value -> R.drawable.tv_icon_user_agent
        "configurar mando" in value || "teclas configurables" in value || "configurar teclas" in value -> R.drawable.tv_icon_keymap
        "arriba / abajo" in value -> R.drawable.tv_icon_dpad_vertical
        "izquierda / derecha" in value -> R.drawable.tv_icon_dpad_horizontal
        "ok mantenido" in value -> R.drawable.tv_icon_ok_hold
        value == "ok" -> R.drawable.tv_icon_ok
        "apariencia" in value -> R.drawable.tv_icon_appearance
        value == "mi aplicación" -> R.drawable.tv_icon_settings
        "ayuda" in value -> R.drawable.tv_icon_tuning
        "búsqueda reciente" in value || "historial" in value || "⌕" in value -> R.drawable.tv_icon_history
        "buscar" in value || "búsqueda" in value -> R.drawable.tv_icon_search
        "grab" in value || "dvr" in value || "usb" in value || "almacenamiento" in value || "memoria" in value || "carpeta" in value -> R.drawable.tv_icon_recordings
        "lista" in value || "guardar" in value || "favorit" in value -> R.drawable.tv_icon_bookmark
        "recordatorios y grabaciones" in value || "recordarme" in value || "recordatorio" in value ||
            "aviso previo" in value -> R.drawable.tv_icon_reminder
        "program" in value || "guía" in value || "guia" in value -> R.drawable.tv_icon_guide
        "audio" in value || "sonido" in value -> R.drawable.tv_icon_audio
        "estabilidad de la imagen" in value || "imagen estable" in value || "buffer" in value || "sync" in value || "latencia" in value -> R.drawable.tv_icon_stability
        "imagen" in value || "fit predeterminado" in value -> R.drawable.tv_icon_quality
        "abrir ventana pequeña ahora" in value || "abrir ventana pip" in value -> R.drawable.tv_icon_pip_launch
        "pip" in value || "pantalla en pantalla" in value -> R.drawable.tv_icon_pip
        "ajuste" in value || "config" in value || "preferencia" in value || "copia" in value || "respaldo" in value || "teclas" in value || "salida" in value || "apariencia" in value -> R.drawable.tv_icon_settings
        "panel" in value || "reintentar" in value -> R.drawable.tv_icon_tuning
        else -> R.drawable.tv_icon_live
    }
}
