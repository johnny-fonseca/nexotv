@file:Suppress("PackageName")
@file:androidx.media3.common.util.UnstableApi

package Nexotv.iptv.player.tv
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale

// ----------------------------------------------------------------------------
// Diálogos: estilo y animaciones
// ----------------------------------------------------------------------------

/**
 * Aplica el estilo de TV y la animación de entrada a un [AlertDialog] ya creado.
 * Fuerza pantalla completa, estiliza título, mensaje y botones.
 * No hace nada si el diálogo aún no tiene ventana asociada.
 */
internal fun MainActivity.animateDialogEntrance(dialog: AlertDialog) {
    val window = dialog.window ?: return
    // Garantiza pantalla completa en cada llamada (protege ante redimensionados del sistema).
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    window.decorView.setPadding(0, 0, 0, 0)
    val surface = window.decorView
    if (surface.background == null || surface.background is android.graphics.drawable.ColorDrawable) {
        surface.background = menuBackdrop(this)
    }
    @SuppressLint("DiscouragedApi")
    val titleId = resources.getIdentifier("alertTitle", "id", "android")
    val titleView: TextView? = (if (titleId != 0) dialog.findViewById(titleId) else null)
        ?: dialog.findViewById(android.R.id.title)
    titleView?.apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(32), dp(24), dp(32), dp(10))
    }

    val messageView = dialog.findViewById<TextView>(android.R.id.message)
    messageView?.apply {
        setTextColor(getColor(R.color.tv_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(32), dp(6), dp(32), dp(16))
        setLineSpacing(dp(4).toFloat(), 1.05f)
    }

    styleTvDialogButtons(dialog)
    animateMenuSurface(surface)
}

/**
 * Aplica el estilo de TV a los botones estándar (positivo, negativo y neutro) de
 * un diálogo: fondo, icono, tamaño y animación de foco.
 */
internal fun MainActivity.styleTvDialogButtons(dialog: AlertDialog) {
    for (buttonId in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
        val button = dialog.getButton(buttonId) ?: continue
        val primary = buttonId == AlertDialog.BUTTON_POSITIVE
        val action = button.text?.toString().orEmpty().lowercase(Locale.ROOT)
        val isRed = "eliminar" in action || "cancelar" in action || "borrar" in action
        button.background = menuActionButton(this, isRed = isRed, isCyanAccent = primary && !isRed)
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.isAllCaps = false
        button.textSize = 15f
        button.minimumHeight = dp(50)
        button.minimumWidth = dp(96)
        button.setPadding(dp(18), dp(8), dp(18), dp(8))
        button.setTextColor(Color.WHITE)
        val iconResource = when {
            "buscar" in action || "voz" in action -> R.drawable.tv_icon_search
            "guardar" in action -> R.drawable.tv_icon_bookmark
            "actualizar" in action || "reintentar" in action -> R.drawable.tv_icon_tuning
            "continuar" in action || "listo" in action -> R.drawable.tv_icon_success
            "importar" in action || "predetermin" in action -> R.drawable.tv_icon_settings
            "abrir guía" in action || "abrir guia" in action -> R.drawable.tv_icon_guide
            "eliminar" in action || "cancelar" in action -> R.drawable.tv_icon_warning
            else -> null
        }
        val icon = iconResource?.let { runCatching { getDrawable(it)?.mutate() }.getOrNull() }
        icon?.let {
            it.setTint(Color.WHITE)
            val size = dp(18)
            it.setBounds(0, 0, size, size)
        }
        button.setCompoundDrawablesRelative(icon, null, null, null)
        button.compoundDrawablePadding = dp(8)
        button.setOnFocusChangeListener { view, focused ->
            animarFoco(view, focused, 4)
        }
    }
}
