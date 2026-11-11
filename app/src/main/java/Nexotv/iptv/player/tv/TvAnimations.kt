@file:Suppress("PackageName")
@file:UnstableApi

package Nexotv.iptv.player.tv
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.home.*
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.transition.TransitionManager
import androidx.media3.common.util.UnstableApi

private const val DURACION_ANIMACION_ENTRADA_MS = 240L
private const val DURACION_TRANSICION_MENU_MS = 190L
private const val DURACION_ENTRADA_ELEMENTO_MS = 220L
private const val RETARDO_ENTRADA_ELEMENTO_MS = 36L
private const val MAX_ELEMENTOS_ENTRADA_ESCALONADA = 8

internal fun MainActivity.animarFoco(view: View, enfocado: Boolean, elevacionDp: Int) {
    animateTvFocus(view, enfocado, focusedScale = 1.025f, elevationDp = elevacionDp.toFloat())
}

/** Anima la aparición de una superficie de menú (desvanecido, escala y desplazamiento). */
internal fun MainActivity.animateMenuSurface(surface: View) {
    surface.animate().cancel()
    surface.alpha = 0f
    surface.scaleX = 0.97f
    surface.scaleY = 0.97f
    surface.translationX = 0f
    surface.translationY = dp(8).toFloat()
    surface.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .translationY(0f)
        .setDuration(DURACION_ANIMACION_ENTRADA_MS)
        .setInterpolator(DecelerateInterpolator(2.2f))
        .withLayer()
        .start()
}

/**
 * Anima el contenido reconstruido de un menú para que los cambios de día o pestaña
 * de la guía no reemplacen las filas de forma abrupta.
 */
internal fun animateMenuLayoutChange(container: ViewGroup) {
    val transition = android.transition.AutoTransition().apply {
        duration = DURACION_TRANSICION_MENU_MS
        interpolator = DecelerateInterpolator(1.4f)
    }
    TransitionManager.beginDelayedTransition(container, transition)
}

/** Presenta las primeras filas de una lista con un ascenso breve y escalonado. */
internal fun View.animateListItemEntrance(index: Int) {
    if (index !in 0 until MAX_ELEMENTOS_ENTRADA_ESCALONADA || !isAttachedToWindow) return
    animate().cancel()
    alpha = 0f
    translationY = resources.displayMetrics.density * 8f
    animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(index * RETARDO_ENTRADA_ELEMENTO_MS)
        .setDuration(DURACION_ENTRADA_ELEMENTO_MS)
        .setInterpolator(DecelerateInterpolator(1.8f))
        .withLayer()
        .start()
}

/**
 * Aplica desenfoque de fondo a una ventana (Android 12 o superior). Si el
 * dispositivo no soporta el efecto, se limpia la bandera y el diálogo se muestra
 * igualmente sin desenfoque.
 *
 * @param window ventana sobre la que aplicar el efecto.
 * @param radiusPx radio de desenfoque en píxeles.
 */
