// app/src/main/java/Nexotv/iptv/player/tv/TvIptvApplication.kt
package Nexotv.iptv.player.tv

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.TimeZone

/** SCREEN_ON solo se puede registrar en tiempo de ejecución; funciona mientras el proceso siga activo. */
class TvIptvApplication : Application() {

    private var autoStartReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        configurarZonaHoraria()
        registrarReceptorEncendidoPantalla()
    }

    // La guía y los relojes usan la hora de Costa Rica, aunque el dispositivo tenga otra zona configurada.
    private fun configurarZonaHoraria() {
        runCatching {
            val zona = TimeZone.getTimeZone(ZONA_HORARIA_ID)
            if (zona.id == ZONA_HORARIA_ID) {
                TimeZone.setDefault(zona)
            } else {
                Log.w(TAG, "Zona horaria no reconocida: $ZONA_HORARIA_ID; se conserva la del sistema")
            }
        }.onFailure { error ->
            Log.e(TAG, "No se pudo establecer la zona horaria", error)
        }
    }

    private fun registrarReceptorEncendidoPantalla() {
        if (autoStartReceiver != null) return

        val receptor = runCatching { AutoStartReceiver() }
            .onFailure { Log.e(TAG, "No se pudo crear AutoStartReceiver", it) }
            .getOrNull() ?: return

        val filtro = IntentFilter(Intent.ACTION_SCREEN_ON)

        val registrado = runCatching {
            ContextCompat.registerReceiver(this, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { error ->
            Log.e(TAG, "No se pudo registrar el receptor de ACTION_SCREEN_ON", error)
        }.isSuccess

        if (registrado) autoStartReceiver = receptor
    }

    private companion object {
        const val TAG = "TvIptvApplication"
        const val ZONA_HORARIA_ID = "America/Costa_Rica"
    }
}
