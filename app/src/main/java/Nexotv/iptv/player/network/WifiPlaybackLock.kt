package Nexotv.iptv.player.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Modo de bloqueo de Wi-Fi según la versión de Android.
 *
 * `WIFI_MODE_FULL_HIGH_PERF` quedó obsoleto en Android 10, donde se sustituye por
 * `WIFI_MODE_FULL_LOW_LATENCY`. Ambos solo piden el permiso `WAKE_LOCK`, que el manifiesto
 * ya declara, así que no hace falta ningún permiso nuevo.
 */
@Suppress("DEPRECATION")
internal fun wifiLockModeFor(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }

/**
 * Mantiene el Wi-Fi a plena potencia mientras hay reproducción activa.
 *
 * `C.WAKE_MODE_NETWORK` de Media3 mantiene la CPU despierta, pero no impide que el firmware
 * del Wi-Fi entre en ahorro de energía. En un televisor ese ahorro aparece sobre todo en modo
 * ambiente o con la pantalla apagada, y se traduce en más latencia y microcortes justo cuando
 * el enlace LTE ya es inestable.
 *
 * La app no controla a qué red se conecta la TV: solo evita que la interfaz Wi-Fi se duerma
 * mientras se reproduce. Se libera al pausar, al detener y al destruir la actividad.
 */
internal class WifiPlaybackLock(context: Context) {
    private val appContext = context.applicationContext
    private var lock: WifiManager.WifiLock? = null

    /** Adquiere o libera el bloqueo según el estado de reproducción; es idempotente. */
    fun setActive(active: Boolean) {
        if (active) acquire() else release()
    }

    fun release() {
        val current = lock ?: return
        lock = null
        runCatching {
            if (current.isHeld) current.release()
        }.onFailure { Log.w(TAG, "No se pudo liberar el bloqueo de Wi-Fi", it) }
    }

    private fun acquire() {
        if (lock != null) return
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (manager == null) {
            Log.w(TAG, "Sin WifiManager: no se puede mantener el Wi-Fi despierto")
            return
        }
        runCatching {
            manager.createWifiLock(wifiLockModeFor(Build.VERSION.SDK_INT), LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess { lock = it }
            .onFailure { Log.w(TAG, "No se pudo adquirir el bloqueo de Wi-Fi", it) }
    }

    private companion object {
        const val TAG = "WifiPlaybackLock"
        const val LOCK_TAG = "NexoTV:playback"
    }
}
