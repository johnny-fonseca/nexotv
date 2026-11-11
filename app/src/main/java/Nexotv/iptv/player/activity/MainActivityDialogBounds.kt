@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*


import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.MainActivity.Companion.DIALOG_BLUR_RADIUS_DP
import Nexotv.iptv.player.MainActivity.Companion.DIALOG_DIM_AMOUNT
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
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

private const val TV_DIALOG_TAG = "TvDialogBounds"

/**
 * Diálogos que ya tienen un listener de ciclo de vida de ventana instalado.
 * WeakHashMap: no retiene el diálogo si la Activity lo suelta (evita fugas de memoria).
 * Solo se toca desde el hilo principal.
 */
private val observedDialogs: MutableSet<AlertDialog> =
    Collections.newSetFromMap(WeakHashMap<AlertDialog, Boolean>())

/** La Activity todavía puede tocar ventanas/vistas. */
private fun MainActivity.isUiAlive(): Boolean = !isFinishing && !isDestroyed

// ---------------------------------------------------------------------------
// Registro y limpieza
// ---------------------------------------------------------------------------

/** Quita de la lista los diálogos que ya no se muestran. */
internal fun MainActivity.pruneDialogBounds() {
    dialogBounds.removeAll { !it.dialog.isShowing }
}

internal fun MainActivity.pruneAndApplyDialogBounds() {
    if (!isUiAlive()) {
        dialogBounds.clear()
        return
    }
    pruneDialogBounds()
    dialogBounds.toList().forEach(::applyDialogBounds)
}

/** Registra el diálogo (sin duplicados), descarta los que ya no se ven y aplica los límites. */
private fun MainActivity.trackAndApply(dialog: AlertDialog) {
    dialogBounds.removeAll { it.dialog === dialog || !it.dialog.isShowing }
    val bounds = MainActivity.DialogBounds(dialog)
    dialogBounds += bounds
    applyDialogBounds(bounds)
}

/**
 * Engancha el ciclo de vida de la ventana del diálogo SIN pisar el OnDismissListener/OnShowListener
 * que haya puesto quien creó el diálogo:
 *  - al adjuntarse la ventana (show) se vuelve a registrar y aplicar, así funciona aunque
 *    setTvDialogBounds se haya llamado antes de show();
 *  - al soltarse (dismiss) se elimina de la lista, sin esperar al siguiente prune.
 */
private fun MainActivity.observeDialogLifecycle(dialog: AlertDialog) {
    val decor = dialog.window?.decorView ?: return
    if (!observedDialogs.add(dialog)) return
    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.post {
                if (isUiAlive() && dialog.isShowing) trackAndApply(dialog)
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            dialogBounds.removeAll { it.dialog === dialog }
        }
    })
}

// ---------------------------------------------------------------------------
// Aplicación de límites
// ---------------------------------------------------------------------------

internal fun MainActivity.applyDialogBounds(bounds: MainActivity.DialogBounds) {
    if (!isUiAlive()) return
    guarded("ajuste de diálogo") {
        val dialogWindow = bounds.dialog.window ?: return@guarded

        dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.setDimAmount(DIALOG_DIM_AMOUNT.coerceIn(0f, 1f))
        dialogWindow.setWindowAnimations(R.style.TvDialogAnimations)

        // El desenfoque es decorativo: algunas versiones de Android TV declaran API 31 pero lo rechazan al ejecutar.
        val blurPx = dp(DIALOG_BLUR_RADIUS_DP)
        if (blurPx > 0) {
            guarded("desenfoque de ventana") { applyWindowBlur(dialogWindow, blurPx) }
        }

        dialogWindow.setGravity(Gravity.CENTER)
        dialogWindow.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialogWindow.attributes = dialogWindow.attributes.apply { x = 0; y = 0 }

        val decor = dialogWindow.decorView
        decor.setPadding(0, 0, 0, 0)
        decor.findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            content.setPadding(0, 0, 0, 0)
            content.forceMatchParent()
            content.getChildAt(0)?.forceMatchParent()
        }
    }
}

internal fun View.forceMatchParent() {
    val params = layoutParams ?: return
    if (params.width != ViewGroup.LayoutParams.MATCH_PARENT || params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams = params
    }
}

/**
 * Los diálogos de TV siempre ocupan toda la pantalla; los parámetros de fracción/gravedad/posición
 * se conservan solo por compatibilidad con los llamadores existentes y se ignoran.
 */
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.setTvDialogBounds(
    dialog: AlertDialog,
    widthFraction: Float,
    heightFraction: Float? = null,
    gravity: Int = Gravity.CENTER,
    x: Int = 0,
    y: Int = 0,
) {
    if (!isUiAlive()) {
        Log.w(TV_DIALOG_TAG, "setTvDialogBounds ignorado: la Activity ya no está activa")
        return
    }
    guarded("registro de diálogo") {
        trackAndApply(dialog)
        observeDialogLifecycle(dialog)
    }
}

// ---------------------------------------------------------------------------
// Consultas y utilidades
// ---------------------------------------------------------------------------

internal fun MainActivity.hasVisibleTvDialog(): Boolean = dialogBounds.any { it.dialog.isShowing }

/** Diálogo visible más reciente (el que está encima), o null si no hay ninguno. */
internal fun MainActivity.topTvDialog(): AlertDialog? =
    dialogBounds.lastOrNull { it.dialog.isShowing }?.dialog

/** Cierra todos los diálogos de TV visibles; un fallo en uno no impide cerrar los demás. */
internal fun MainActivity.dismissAllTvDialogs() {
    dialogBounds.toList().forEach { bounds ->
        guarded("cierre de diálogo") {
            if (bounds.dialog.isShowing) bounds.dialog.dismiss()
        }
    }
    dialogBounds.clear()
}

/** Llamar desde onDestroy: suelta las referencias a los diálogos. */
internal fun MainActivity.clearTvDialogBounds() {
    dialogBounds.clear()
}

// ---------------------------------------------------------------------------
// Configuración
// ---------------------------------------------------------------------------

internal fun MainActivity.handleConfigurationChanged() {
    if (!isUiAlive()) return

    guarded("límites de diálogos tras cambio de configuración") {
        pruneAndApplyDialogBounds()
    }
    // Un fallo en el overlay no debe impedir que los diálogos queden bien ajustados (ni al revés).
    guarded("overlay tras cambio de configuración") {
        overlayController.applyConfiguration()
    }
    // Segunda pasada tras el siguiente layout: la ventana ya tiene el tamaño final.
    window?.decorView?.post {
        if (isUiAlive() && hasVisibleTvDialog()) pruneAndApplyDialogBounds()
    }
}