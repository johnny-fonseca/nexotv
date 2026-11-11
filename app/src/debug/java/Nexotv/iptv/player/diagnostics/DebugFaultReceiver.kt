package Nexotv.iptv.player.diagnostics

import Nexotv.iptv.player.activity.TvHomeEntry
import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.network.DebugFaultState
import Nexotv.iptv.player.network.FaultMode
import Nexotv.iptv.player.player.PlayerTuning
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Conmutador del laboratorio de red. **Solo se compila en el APK de depuración.**
 *
 * El manifiesto debug declara android:exported="true". applicationId por flavor:
 * teletica=teletica.iptv.player, futv=teletica.iptv.player.futv,
 * opa=teletica.iptv.player.opa. El receptor no se incluye en release.
 * Activar (sin extras) o desactivar para cada flavor:
 *
 * ```
 * adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 -p teletica.iptv.player
 * adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 -p teletica.iptv.player --ez enabled false
 * adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 -p teletica.iptv.player.futv
 * adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 -p teletica.iptv.player.futv --ez enabled false
 * adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 -p teletica.iptv.player.opa
 * adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 -p teletica.iptv.player.opa --ez enabled false
 * adb shell run-as teletica.iptv.player cat /data/data/teletica.iptv.player/shared_prefs/teletica_player.xml
 * adb shell am broadcast -p teletica.iptv.player -a Nexotv.iptv.player.DEBUG.FAULT --es mode blackhole --el value 20000
 * adb shell am broadcast -p teletica.iptv.player -a Nexotv.iptv.player.DEBUG.FLAG --es flag adaptiveTimeouts --ez enabled false
 * adb shell am broadcast -p teletica.iptv.player -a Nexotv.iptv.player.DEBUG.RESET
 * ```
 * La preferencia se guarda como ${BuildConfig.BRAND_KEY}_player.
 * HOME_V2 solo puede persistir y registrar el switch desde este receiver: abrir el diálogo
 * requeriría una referencia a la MainActivity viva, que no se expone al receiver.
 */
internal class DebugFaultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FAULT -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                val value = intent.getLongExtra(EXTRA_VALUE, 0L)
                val host = intent.getStringExtra(EXTRA_HOST)
                val ok = DebugFaultState.set(mode, value, host)
                DiagnosticTracker.log(
                    "DEBUG_FAULT",
                    if (ok) "Laboratorio: modo=${DebugFaultState.mode} valor=${DebugFaultState.value} host=${DebugFaultState.hostFilter ?: "todos"}"
                    else "Laboratorio: modo inválido '$mode'; válidos: ${FaultMode.entries.joinToString()}",
                )
            }
            ACTION_FLAG -> {
                val flag = intent.getStringExtra(EXTRA_FLAG).orEmpty()
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                val ok = PlayerTuning.setFlag(flag, enabled)
                DiagnosticTracker.log(
                    "DEBUG_FLAG",
                    if (ok) "Bandera $flag=$enabled" else "Bandera desconocida '$flag'; válidas: ${PlayerTuning.FLAG_NAMES.joinToString()}",
                )
            }
            ACTION_RESET -> {
                DebugFaultState.reset()
                PlayerTuning.resetFlags()
                DiagnosticTracker.log("DEBUG_RESET", "Laboratorio restablecido: sin fallos y banderas por defecto")
            }
            ACTION_HOME_V2 -> {
                val enabled = if (intent.hasExtra(EXTRA_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_ENABLED, false)
                } else {
                    true
                }
                TvHomeEntry.setEnabled(context, enabled)
                Log.i(
                    "DebugFaultReceiver",
                    "HOME_V2 enabled=$enabled prefs=${BuildConfig.BRAND_KEY}_player",
                )
            }
        }
    }

    private companion object {
        const val ACTION_FAULT = "Nexotv.iptv.player.DEBUG.FAULT"
        const val ACTION_FLAG = "Nexotv.iptv.player.DEBUG.FLAG"
        const val ACTION_RESET = "Nexotv.iptv.player.DEBUG.RESET"
        const val ACTION_HOME_V2 = "Nexotv.iptv.player.DEBUG.HOME_V2"
        const val EXTRA_MODE = "mode"
        const val EXTRA_VALUE = "value"
        const val EXTRA_HOST = "host"
        const val EXTRA_FLAG = "flag"
        const val EXTRA_ENABLED = "enabled"
    }
}
