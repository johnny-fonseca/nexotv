// Archivo: Nexotv/iptv/player/activity/DisplayLifecycleHandler.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*



import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.player.*
import android.content.res.Configuration

internal fun MainActivity.handleUserLeaveForDisplay() {
    if (isDestroyed) return
    // Cada paso es independiente: si falla uno, el otro debe ejecutarse igual
    ejecutarSeguro { updatePipParams() }
    ejecutarSeguro { handlePlayerUserLeaveHint() }
}

internal fun MainActivity.handlePictureInPictureChanged(isInPictureInPictureMode: Boolean) {
    if (isDestroyed) return
    ejecutarSeguro { updatePlayerPictureInPicture(isInPictureInPictureMode) }
}

@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.handleDisplayConfigurationChanged(newConfig: Configuration) {
    if (isDestroyed) return
    ejecutarSeguro { handleConfigurationChanged() }
}

private inline fun ejecutarSeguro(bloque: () -> Unit) {
    try {
        bloque()
    } catch (_: Exception) {
        // Un fallo de visualización (PiP/configuración) nunca debe cerrar la app
    }
}