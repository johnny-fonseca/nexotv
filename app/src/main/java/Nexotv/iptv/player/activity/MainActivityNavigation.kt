// app/src/main/java/Nexotv/iptv/player/activity/MainActivityNavigation.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*





import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.status.*
import android.app.Dialog
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi

private const val NAV_TAG = "ActivityNavigation"

private const val EXIT_CONFIRM_MESSAGE = "Pulsa Atrás otra vez para salir"

/**
 * Ejecuta [block] y devuelve [fallback] si lanza una excepción.
 * Los errores fatales de la JVM (Error) no se capturan y se propagan siempre.
 */
private inline fun <T> navProtegido(paso: String, fallback: T, block: () -> T): T {
    return try {
        block()
    } catch (e: Exception) {
        try {
            Log.w(NAV_TAG, "Falló el paso de navegación: $paso", e)
        } catch (_: RuntimeException) {
            // El registro nunca debe romper la navegación
        }
        fallback
    }
}

/** Variante de [navProtegido] para bloques sin valor de retorno. */
private inline fun navEjecutar(paso: String, block: () -> Unit) {
    navProtegido(paso, Unit) { block() }
}

/** Si falla la lectura devuelve `true`, para evitar salidas por una pulsación accidental. */
@UnstableApi
private fun MainActivity.isExitConfirmationEnabled(): Boolean =
    navProtegido("leer preferencia de confirmar salida", true) {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_CONFIRM_EXIT, true)
    }

/** Si no se puede leer el estado se asume la actividad no disponible, para no operar sobre una ventana inválida. */
@UnstableApi
private fun MainActivity.isActivityUnavailable(): Boolean =
    navProtegido("leer estado del ciclo de vida", true) { isFinishing || isDestroyed }

@UnstableApi
private fun MainActivity.finishSafely() {
    navEjecutar("cerrar actividad") {
        if (!isFinishing && !isDestroyed) {
            finish()
        }
    }
}

/** Anula la ventana de confirmación de salida para que no sobreviva a otras acciones de Atrás. */
@UnstableApi
private fun MainActivity.disarmExitConfirmation() {
    navEjecutar("anular confirmación de salida") {
        backArmedUntilMs = 0L
    }
}

/**
 * Gestiona Atrás con el diálogo de búsqueda de guía visible.
 * Primero limpia los resultados; si no hay, cierra el diálogo y retrocede
 * (o abre el menú principal en SEARCH si la pila está vacía).
 *
 * @return `true` si el diálogo estaba visible y el evento fue consumido.
 */
@UnstableApi
private fun MainActivity.handleGuideSearchBack(): Boolean {
    val dialog = navProtegido<Dialog?>("inspeccionar diálogo de búsqueda de guía", null) {
        guideSearchDialog
    } ?: return false

    val isShowing = navProtegido("consultar visibilidad del diálogo de búsqueda", false) { dialog.isShowing }
    if (!isShowing) return false

    val hasVisibleResults = navProtegido("contar resultados de búsqueda", 0) {
        guideSearchResultsContainer?.childCount ?: 0
    } > 0

    if (hasVisibleResults) {
        navEjecutar("limpiar resultados de búsqueda") {
            guideSearchResultsContainer?.removeAllViews()
        }
        navEjecutar("devolver foco al campo de búsqueda") {
            guideSearchInput?.requestFocus()
        }
        return true
    }

    navEjecutar("cerrar diálogo de búsqueda") {
        dialog.dismiss()
    }
    val poppedBack = navProtegido("retroceder pila tras la búsqueda", false) {
        popScreenBackStack()
    }
    if (!poppedBack) {
        navEjecutar("abrir menú principal en SEARCH") {
            showMainMenu(MainActivity.MenuDestination.SEARCH) {}
        }
    }
    return true
}

/**
 * Descarta la tarjeta de estado accionable.
 * Si no se pudo limpiar devuelve `false` para que Atrás continúe con la siguiente etapa y no quede bloqueado.
 *
 * @return `true` si se descartó la tarjeta.
 */
@UnstableApi
private fun MainActivity.handleActionableStatusCardBack(): Boolean {
    val actionable = navProtegido("leer indicador de tarjeta accionable", false) { statusCardIsActionable }
    if (!actionable) return false
    return navProtegido("limpiar tarjeta de estado del reproductor", false) {
        renderPlayerStatus(null)
        true
    }
}

/**
 * Flujo "pulsa Atrás dos veces para salir" basado en [SystemClock.elapsedRealtime],
 * que es monótono e inmune a cambios del reloj del sistema.
 */
@UnstableApi
private fun MainActivity.handleExitConfirmation() {
    if (!isExitConfirmationEnabled()) {
        disarmExitConfirmation()
        finishSafely()
        return
    }

    val now = SystemClock.elapsedRealtime()
    if (now <= backArmedUntilMs) {
        disarmExitConfirmation()
        finishSafely()
    } else {
        backArmedUntilMs = now + MainActivity.EXIT_CONFIRM_WINDOW_MS
        navEjecutar("mostrar aviso de confirmación de salida") {
            showTvToast(EXIT_CONFIRM_MESSAGE)
        }
    }
}

/**
 * Manejador central de la tecla Atrás. Orden de evaluación:
 * 1. Diálogo de búsqueda de guía.
 * 2. Tarjeta de estado accionable del reproductor.
 * 3. Retroceso en la pila de pantallas.
 * 4. Salida, con confirmación opcional por doble pulsación.
 *
 * Cada etapa está aislada: un fallo se registra y se pasa a la siguiente.
 * Si una etapa consume el evento, se anula la confirmación de salida pendiente.
 */
@UnstableApi
internal fun MainActivity.handleBackKey() {
    if (isActivityUnavailable()) return

    val consumed =
        navProtegido("manejo de Atrás en búsqueda de guía", false) { handleGuideSearchBack() } ||
                navProtegido("manejo de Atrás en tarjeta de estado", false) { handleActionableStatusCardBack() } ||
                navProtegido("retroceso en pila de pantallas", false) { popScreenBackStack() }

    if (consumed) {
        disarmExitConfirmation()
        return
    }

    navEjecutar("manejo de confirmación de salida") { handleExitConfirmation() }
}