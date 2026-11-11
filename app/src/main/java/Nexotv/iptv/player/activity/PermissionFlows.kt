// File: Nexotv/iptv/player/activity/PermissionFlows.kt
package Nexotv.iptv.player.activity

import Nexotv.iptv.player.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

internal object PermissionFlows {

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
            alarmManager.canScheduleExactAlarms()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun requestExactAlarmAccess(activity: Activity, onSettingsLaunched: (() -> Unit)? = null): Boolean {
        if (canScheduleExactAlarms(activity)) return true
        if (!activity.canShowDialogs()) return false

        try {
            AlertDialog.Builder(activity)
                .setTitle("Permitir programaciones exactas")
                .setMessage("${BuildConfig.BRAND_NAME} necesita permiso de Alarmas y recordatorios para iniciar grabaciones y avisos a la hora elegida. Actívalo y vuelve a guardar la programación.")
                .setNegativeButton("Ahora no", null)
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    val request = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                packageUri(activity),
                            )
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity))
                        }
                    } catch (_: RuntimeException) {
                        Intent(Settings.ACTION_SETTINGS)
                    }
                    if (startWithAppDetailsFallback(activity, request)) onSettingsLaunched?.invoke()
                }
                .showForTv()
        } catch (_: RuntimeException) {
            return false
        }
        return false
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: RuntimeException) {
            false
        }
    }

    fun openNotificationSettings(activity: Activity) {
        val appSettings = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity))
            }
        } catch (_: RuntimeException) {
            Intent(Settings.ACTION_SETTINGS)
        }
        startWithAppDetailsFallback(activity, appSettings)
    }

    internal fun Activity.canShowDialogs(): Boolean =
        try {
            !isFinishing && !isDestroyed
        } catch (_: RuntimeException) {
            false
        }

    private fun packageUri(activity: Activity): Uri =
        Uri.fromParts("package", activity.packageName.orEmpty(), null)

    private fun tryStart(activity: Activity, intent: Intent): Boolean {
        if (!activity.canShowDialogs()) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    // Intenta la pantalla específica; si el dispositivo no la soporta, abre los detalles de la app
    private fun startWithAppDetailsFallback(activity: Activity, primary: Intent): Boolean {
        if (tryStart(activity, primary)) return true
        val details = try {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity))
        } catch (_: RuntimeException) {
            null
        }
        if (details != null && tryStart(activity, details)) return true
        return tryStart(activity, Intent(Settings.ACTION_SETTINGS))
    }
}

internal fun MainActivity.requestNotificationsForRecording() {
    if (!isUiAvailable()) return
    if (PermissionFlows.hasNotificationPermission(this)) return

    try {
        val preferences = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val askedBefore = preferences.getBoolean(MainActivity.KEY_NOTIFICATIONS_ASKED, false)

        if (!askedBefore) {
            preferences.edit().putBoolean(MainActivity.KEY_NOTIFICATIONS_ASKED, true).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isUiAvailable()) {
                requestAppPermission(Manifest.permission.POST_NOTIFICATIONS, MainActivity.REQUEST_NOTIFICATIONS)
            }
            return
        }

        if (!isUiAvailable()) return

        AlertDialog.Builder(this)
            .setTitle("Notificaciones desactivadas")
            .setMessage("Activa las notificaciones para ver el estado de grabación y recibir recordatorios de programas.")
            .setNegativeButton("Continuar sin notificaciones", null)
            .setPositiveButton("Abrir ajustes") { _, _ -> PermissionFlows.openNotificationSettings(this) }
            .showForTv()
    } catch (_: RuntimeException) {
        return
    }
}
