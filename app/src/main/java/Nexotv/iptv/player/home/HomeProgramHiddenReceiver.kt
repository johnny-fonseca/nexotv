// HomeProgramHiddenReceiver.kt
package Nexotv.iptv.player.home

import Nexotv.iptv.player.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

/** Atiende la solicitud de quitar una tarjeta de grabación de la fila de inicio de la TV. */
class HomeProgramHiddenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action: String = try {
            intent.action
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la acción del intent", e)
            null
        } ?: return

        val appContext: Context = try {
            context.applicationContext ?: context
        } catch (e: Exception) {
            context
        }

        try {
            when (action) {
                TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                    val id = readProgramId(intent, TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID)
                    if (isValidId(id)) {
                        HomeScreenChannel.onProgramHidden(appContext, id)
                    } else {
                        Log.w(TAG, "ID de programa inválido para $action: $id")
                    }
                }
                TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                    val id = readProgramId(intent, TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID)
                    if (isValidId(id)) {
                        HomeScreenChannel.onWatchNextHidden(appContext, id)
                    } else {
                        Log.w(TAG, "ID de programa inválido para $action: $id")
                    }
                }
                else -> Log.d(TAG, "Acción ignorada: ${sanitizeForLog(action)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo procesar la acción ${sanitizeForLog(action)}", e)
        }
    }

    private fun readProgramId(intent: Intent, extraName: String): Long =
        try {
            if (intent.hasExtra(extraName)) intent.getLongExtra(extraName, INVALID_ID) else INVALID_ID
        } catch (e: Exception) {
            Log.w(TAG, "Extra inválido: $extraName", e)
            INVALID_ID
        }

    private fun isValidId(id: Long): Boolean = id > 0L && id != Long.MAX_VALUE

    private fun sanitizeForLog(value: String): String {
        val trimmed = if (value.length > MAX_LOG_LENGTH) value.substring(0, MAX_LOG_LENGTH) else value
        return trimmed.replace('\n', ' ').replace('\r', ' ')
    }

    private companion object {
        const val TAG = "HomeProgramHidden"
        const val INVALID_ID = -1L
        const val MAX_LOG_LENGTH = 128
    }
}