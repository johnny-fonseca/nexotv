package Nexotv.iptv.player.home

import Nexotv.iptv.player.*
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Owns the timed refresh alarm for the home-row live program. */
internal class HomeProgramRefreshScheduler {
    private val tag = "${BuildConfig.BRAND_NAME}HomeScheduler".take(MAX_TAG_LENGTH)
    private val requestCode = 7315
    private val lock = Any()

    fun cancel(context: Context) {
        synchronized(lock) {
            try {
                val appContext = appContext(context)
                val pending = existingPendingIntent(appContext) ?: return
                try {
                    alarmManager(appContext)?.cancel(pending)
                } catch (error: Exception) {
                    Log.w(tag, "No se pudo cancelar la alarma de actualización", error)
                }
                try {
                    pending.cancel()
                } catch (error: Exception) {
                    Log.w(tag, "No se pudo cancelar el intent pendiente", error)
                }
            } catch (error: Exception) {
                Log.w(tag, "No se pudo cancelar la actualización del programa", error)
            }
        }
    }

    fun schedule(context: Context, atMs: Long) {
        if (atMs <= 0L || atMs <= System.currentTimeMillis()) return
        synchronized(lock) {
            try {
                val appContext = appContext(context)
                val alarm = alarmManager(appContext) ?: return
                val pending = pendingIntent(appContext) ?: return
                if (!scheduleExact(alarm, atMs, pending)) scheduleInexact(alarm, atMs, pending)
            } catch (error: Exception) {
                Log.w(tag, "No se pudo programar la actualización del programa", error)
            }
        }
    }

    private fun scheduleExact(alarm: AlarmManager, atMs: Long, pending: PendingIntent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact(alarm)) return false
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
                else -> alarm.setExact(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
            true
        } catch (error: SecurityException) {
            Log.w(tag, "Alarma exacta no permitida; se usa una alarma aproximada", error)
            false
        } catch (error: Exception) {
            Log.w(tag, "Falló la alarma exacta; se usa una alarma aproximada", error)
            false
        }
    }

    private fun scheduleInexact(alarm: AlarmManager, atMs: Long, pending: PendingIntent) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
                else -> alarm.set(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        } catch (error: Exception) {
            Log.w(tag, "No se pudo programar la actualización del programa", error)
        }
    }

    private fun canScheduleExact(alarm: AlarmManager): Boolean =
        try {
            alarm.canScheduleExactAlarms()
        } catch (error: Exception) {
            Log.w(tag, "No se pudo comprobar el permiso de alarmas exactas", error)
            false
        }

    private fun alarmManager(context: Context): AlarmManager? =
        try {
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        } catch (error: Exception) {
            Log.w(tag, "AlarmManager no disponible", error)
            null
        }

    private fun pendingIntent(context: Context): PendingIntent? =
        buildPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)

    private fun existingPendingIntent(context: Context): PendingIntent? =
        buildPendingIntent(context, PendingIntent.FLAG_NO_CREATE)

    private fun buildPendingIntent(context: Context, baseFlag: Int): PendingIntent? =
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                baseFlag or PendingIntent.FLAG_IMMUTABLE
            } else {
                baseFlag
            }
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, HomeProgramUpdateReceiver::class.java),
                flags,
            )
        } catch (error: Exception) {
            Log.w(tag, "No se pudo crear el intent pendiente", error)
            null
        }

    private fun appContext(context: Context): Context = context.applicationContext ?: context

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}