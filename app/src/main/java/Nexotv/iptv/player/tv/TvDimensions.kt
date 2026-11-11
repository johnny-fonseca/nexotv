@file:Suppress("PackageName")
@file:UnstableApi

package Nexotv.iptv.player.tv
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi

// ----------------------------------------------------------------------------
// Utilidades de contexto
// ----------------------------------------------------------------------------

/**
 * Recorre la cadena de [ContextWrapper] hasta encontrar la [MainActivity] contenedora.
 * Devuelve `null` si el contexto no pertenece a ninguna instancia de [MainActivity].
 */
@UnstableApi
internal tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext?.findMainActivity()
    else -> null
}

/** Margen horizontal seguro para TV (overscan), calculado a partir de los recursos. */
internal fun Context.safeMarginHorizontal(): Int =
    (resources.displayMetrics.widthPixels * resources.getFraction(R.fraction.tv_safe_margin_horizontal, 1, 1)).toInt()

/** Margen vertical seguro para TV (overscan), calculado a partir de los recursos. */
internal fun Context.safeMarginVertical(): Int =
    (resources.displayMetrics.heightPixels * resources.getFraction(R.fraction.tv_safe_margin_vertical, 1, 1)).toInt()

/** Convierte un valor en dp a píxeles según la densidad de la pantalla actual. */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
