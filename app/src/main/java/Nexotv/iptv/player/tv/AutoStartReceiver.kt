// app/src/main/java/Nexotv/iptv/player/tv/AutoStartReceiver.kt
package Nexotv.iptv.player.tv

import Nexotv.iptv.player.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Opens the single live channel after Android finishes booting, when enabled. */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: context ?: return
        val action = intent?.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runSafely("restaurar programación") { ProgramSchedule.restore(appContext) }
            runSafely("actualizar programa actual") { HomeScreenChannel.refreshCurrentProgram(appContext) }
        }

        val preferenceKey = preferenceKeyFor(action) ?: return
        if (!isEnabled(appContext, preferenceKey)) return

        launchMainActivity(appContext)
    }

    private fun preferenceKeyFor(action: String): String? = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> KEY_AUTOSTART_ON_BOOT
        Intent.ACTION_SCREEN_ON -> KEY_AUTOSTART_ON_WAKE
        else -> null
    }

    private fun isEnabled(context: Context, key: String): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, false)
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer la preferencia $key", e)
        false
    }

    private fun launchMainActivity(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        try {
            context.startActivity(launchIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "MainActivity no encontrada", e)
        } catch (e: SecurityException) {
            // Android 10+ restringe el inicio de actividades desde segundo plano
            Log.e(TAG, "Inicio de actividad bloqueado por el sistema", e)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Fallo inesperado al iniciar MainActivity", e)
        }
    }

    private inline fun runSafely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error al $step", e)
        }
    }

    private companion object {
        const val TAG = "AutoStartReceiver"
        val PREFS_NAME = "${BuildConfig.BRAND_KEY}_player"
        const val KEY_AUTOSTART_ON_BOOT = "autostart_on_boot"
        const val KEY_AUTOSTART_ON_WAKE = "autostart_on_wake"
    }
}
