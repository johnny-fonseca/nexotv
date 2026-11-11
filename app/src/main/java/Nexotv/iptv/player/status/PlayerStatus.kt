// app/src/main/java/Nexotv/iptv/player/status/PlayerStatus.kt
package Nexotv.iptv.player.status

import Nexotv.iptv.player.*
/** Estados de solo presentación, traducidos a partir de eventos ya expuestos por el reproductor y el SO. */
internal sealed class PlayerStatus {

    /** Indica si el estado ofrece una acción de reintento al usuario. */
    open val isRetryable: Boolean get() = false

    /** Indica si el estado es transitorio y se resuelve sin intervención del usuario. */
    open val isTransient: Boolean get() = false

    /** Indica si el estado debe bloquear la reproducción hasta que el usuario actúe. */
    open val requiresUserAction: Boolean get() = false

    /** Ejecuta la acción de reintento si existe; devuelve true si se ejecutó sin errores. */
    open fun retry(): Boolean = false

    /** Ejecuta la acción de ajustes si existe; devuelve true si se ejecutó sin errores. */
    open fun openSettings(): Boolean = false

    /** Ejecuta la acción de grabaciones si existe; devuelve true si se ejecutó sin errores. */
    open fun openRecordings(): Boolean = false

    data object Connecting : PlayerStatus() {
        override val isTransient: Boolean get() = true
    }

    data object Reconnecting : PlayerStatus() {
        override val isTransient: Boolean get() = true
    }

    data class Offline(
        val onRetry: () -> Unit,
        val onSettings: () -> Unit,
        val issue: ConnectionIssue = ConnectionIssue.NO_VALIDATED_INTERNET,
    ) : PlayerStatus() {
        override val isRetryable: Boolean get() = true
        override val requiresUserAction: Boolean get() = true
        override fun retry(): Boolean = safeInvoke(onRetry)
        override fun openSettings(): Boolean = safeInvoke(onSettings)
    }

    data class NoSignal(
        val onRetry: () -> Unit,
        val onSettings: () -> Unit,
        val issue: ConnectionIssue = ConnectionIssue.SIGNAL_LOAD_FAILURE,
        val helpResId: Int? = null,
    ) : PlayerStatus() {
        override val isRetryable: Boolean get() = true
        override val requiresUserAction: Boolean get() = true
        override fun retry(): Boolean = safeInvoke(onRetry)
        override fun openSettings(): Boolean = safeInvoke(onSettings)
    }

    data class NoSpace(
        val onRecordings: () -> Unit
    ) : PlayerStatus() {
        override val requiresUserAction: Boolean get() = true
        override fun openRecordings(): Boolean = safeInvoke(onRecordings)
    }

    protected fun safeInvoke(action: () -> Unit): Boolean =
        try {
            action()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "Fallo al ejecutar la acción del estado ${this::class.simpleName}", e)
            false
        }

    private companion object {
        const val LOG_TAG = "PlayerStatus"
    }
}
