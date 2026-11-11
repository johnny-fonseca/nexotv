// Archivo: Nexotv/iptv/player/activity/PermissionResultHandler.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*



import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.status.*
import android.content.pm.PackageManager
import androidx.core.content.edit
import Nexotv.iptv.player.MainActivity.Companion.KEY_AUTO_BACKUP
import Nexotv.iptv.player.MainActivity.Companion.REQUEST_BACKUP_STORAGE
import Nexotv.iptv.player.MainActivity.Companion.REQUEST_NOTIFICATIONS

internal fun MainActivity.handlePermissionResult(requestCode: Int, grantResults: IntArray) {
    if (isFinishing || isDestroyed) return
    // Solicitud interrumpida o cancelada: el sistema entrega un arreglo vacío
    if (grantResults.isEmpty()) return

    val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED
    when (requestCode) {
        REQUEST_BACKUP_STORAGE -> if (granted) {
            activarRespaldoAutomatico()
        } else {
            mostrarToastSeguro(
                "Se requiere permiso para guardar el respaldo fuera de la app",
                long = true,
            )
        }
        REQUEST_NOTIFICATIONS -> if (granted) {
            mostrarEstadoSeguro("Notificaciones activadas")
        } else {
            mostrarToastSeguro(
                "Sin notificaciones, ${BuildConfig.BRAND_NAME} no podrá avisarte de recordatorios ni mostrar el estado de grabación en el panel",
                long = true,
            )
        }
        else -> Unit
    }
}

private fun MainActivity.activarRespaldoAutomatico() {
    try {
        mainPrefs.edit { putBoolean(KEY_AUTO_BACKUP, true) }
        saveAutomaticBackup()
    } catch (e: Exception) {
        // Si el respaldo falla, se revierte la preferencia para no dejar un estado inconsistente
        try {
            mainPrefs.edit { putBoolean(KEY_AUTO_BACKUP, false) }
        } catch (_: Exception) {
        }
        mostrarToastSeguro("No se pudo activar el respaldo automático", long = true)
        return
    }
    mostrarEstadoSeguro("Respaldo automático en Documents/${BuildConfig.BRAND_NAME} activado")
}

private fun MainActivity.mostrarEstadoSeguro(mensaje: String) {
    if (isFinishing || isDestroyed) return
    try {
        showStatus(mensaje)
    } catch (_: Exception) {
    }
}

private fun MainActivity.mostrarToastSeguro(mensaje: String, long: Boolean) {
    if (isFinishing || isDestroyed) return
    try {
        showTvToast(mensaje, long = long)
    } catch (_: Exception) {
    }
}