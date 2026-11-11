@file:Suppress("PackageName")
@file:androidx.media3.common.util.UnstableApi

package Nexotv.iptv.player.tv
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.home.*
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

private const val MAX_INTENTOS_FOCO = 8
private const val RETARDO_REINTENTO_FOCO_MS = 40L

/**
 * Solicita el foco para el primer elemento enfocable de [container] una vez que la
 * ventana esté lista. Reintenta un número limitado de veces y se detiene si otro
 * elemento ya tomó el foco o el contenedor se desvinculó de la ventana.
 *
 * @param container contenedor donde buscar el elemento a enfocar.
 * @param fallback vista alternativa si ningún hijo es enfocable.
 * @param preferred vista preferida; se usa solo si es enfocable.
 */
internal fun focusFirstWhenReady(container: ViewGroup, fallback: View? = null, preferred: View? = null) {
    var attempts = 0
    val target = preferred?.takeIf { it.isFocusable } ?: (0 until container.childCount)
        .asSequence()
        .map(container::getChildAt)
        .firstOrNull { it.isFocusable } ?: fallback
    if (target == null) return
    val request = object : Runnable {
        override fun run() {
            if (!container.isAttachedToWindow) return
            if ((target.hasFocus()) || (attempts >= MAX_INTENTOS_FOCO)) return
            if ((attempts > 0) && (container.rootView.findFocus() != null)) return
            attempts++
            if (!container.rootView.hasWindowFocus() || !target.requestFocus()) {
                container.postDelayed(this, RETARDO_REINTENTO_FOCO_MS)
            }
        }
    }
    container.post(request)
}

/**
 * Mantiene visible la fila enfocada dentro de un diálogo con desplazamiento vertical.
 * El listener se elimina automáticamente cuando la vista se desvincula de la ventana
 * para evitar fugas de memoria.
 */
internal fun ScrollView.scrollFocusedDescendantIntoView() {
    val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        if (focused == null) return@OnGlobalFocusChangeListener
        var parent = focused.parent
        while ((parent != null) && (parent !== this)) parent = parent.parent
        if (parent === this) {
            focused.post {
                if (focused.isFocused && isAttachedToWindow) {
                    focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), true)
                }
            }
        }
    }
    viewTreeObserver.addOnGlobalFocusChangeListener(listener)
    addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                }
                removeOnAttachStateChangeListener(this)
            }
        },
    )
}
