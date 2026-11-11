// GuideSettingsDialog.kt
package Nexotv.iptv.player.settings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Diálogos de ajustes de actualización, retención, anticipación de recordatorios y margen de grabación. */
internal object GuideSettingsDialog {

    private const val DIALOG_TAG_GUIDE = "GUÍA"
    private const val DIALOG_TAG_RECORDINGS = "GRABACIONES"

    private const val DEFAULT_INTERVAL_HOURS = 6
    private const val DEFAULT_PAST_DAYS = 7
    private const val DEFAULT_REMINDER_LEAD = 5
    private const val DEFAULT_RECORDING_LEAD = 0

    private val REFRESH_LABELS = arrayOf(
        "Siempre al iniciar", "Cada 3 horas", "Cada 6 horas", "Cada 12 horas", "Cada 24 horas", "Solo manual",
    )
    private val REFRESH_VALUES = intArrayOf(-1, 3, 6, 12, 24, 0)

    private val RETENTION_LABELS = arrayOf("Solo próximos programas", "3 días", "7 días", "14 días")
    private val RETENTION_VALUES = intArrayOf(0, 3, 7, 14)

    private val REMINDER_LABELS = arrayOf(
        "Al inicio", "5 minutos antes", "10 minutos antes", "15 minutos antes", "30 minutos antes",
    )
    private val REMINDER_VALUES = intArrayOf(0, 5, 10, 15, 30)

    private val PADDING_LABELS = arrayOf(
        "Sin margen", "1 minuto", "2 minutos", "3 minutos", "5 minutos", "10 minutos", "15 minutos",
    )
    private val PADDING_VALUES = intArrayOf(0, 1, 2, 3, 5, 10, 15)

    fun show(
        activity: Activity,
        onRefresh: (silent: Boolean) -> Unit,
        onRetentionChanged: () -> Unit,
        onStatus: (String) -> Unit,
        hideStatus: () -> Unit,
    ) {
        val host = hostActivity(activity) ?: return
        val preferences = preferencesOf(activity)

        val interval = preferences.getInt(MainActivity.KEY_EPG_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
        val intervalLabel = when (interval) {
            -1 -> "siempre al iniciar"
            0 -> "solo manual"
            else -> "cada $interval h"
        }
        val days = preferences.getInt(MainActivity.KEY_EPG_PAST_DAYS, DEFAULT_PAST_DAYS)
        val retentionLabel = if (days == 0) "solo próximos" else "$days días"
        val lead = preferences.getInt(MainActivity.KEY_REMINDER_LEAD, DEFAULT_REMINDER_LEAD)
        val reminderLabel = if (lead == 0) "al inicio" else "$lead min antes"
        val recordingStartLead = preferences.getInt(MainActivity.KEY_RECORDING_START_LEAD, DEFAULT_RECORDING_LEAD)
        val recordingEndLead = preferences.getInt(MainActivity.KEY_RECORDING_END_LEAD, DEFAULT_RECORDING_LEAD)
        val recordingPaddingLabel = "$recordingStartLead min antes · $recordingEndLead min después"

        val choices = listOf(
            "Frecuencia" to intervalLabel,
            "Retención" to retentionLabel,
            "Avisos" to reminderLabel,
            "Margen" to recordingPaddingLabel,
        )

        host.showStyledChoiceDialog("Guía y recordatorios", DIALOG_TAG_GUIDE, choices, -1) { index ->
            when (index) {
                0 -> showRefreshPicker(activity, onRefresh, onStatus)
                1 -> showRetentionPicker(activity, onRetentionChanged, onStatus)
                2 -> showReminderLeadPicker(activity, onStatus)
                else -> showRecordingPaddingPicker(activity, onStatus)
            }
            hideStatus()
        }
    }

    private fun showRefreshPicker(activity: Activity, onRefresh: (Boolean) -> Unit, onStatus: (String) -> Unit) {
        val host = hostActivity(activity) ?: return
        val preferences = preferencesOf(activity)
        val selected = selectedIndex(
            REFRESH_VALUES,
            preferences.getInt(MainActivity.KEY_EPG_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS),
        )
        host.showStyledChoiceDialog(
            "Actualizar guía", DIALOG_TAG_GUIDE, REFRESH_LABELS.map { it to "" }, selected,
        ) { index ->
            if (index !in REFRESH_VALUES.indices) return@showStyledChoiceDialog
            val hours = REFRESH_VALUES[index]
            preferences.edit { putInt(MainActivity.KEY_EPG_INTERVAL_HOURS, hours) }
            if (hours == -1 || hours > 0) onRefresh(true)
            onStatus("Actualización de guía: ${REFRESH_LABELS[index].lowercase()}")
        }
    }

    private fun showRetentionPicker(activity: Activity, onChanged: () -> Unit, onStatus: (String) -> Unit) {
        val host = hostActivity(activity) ?: return
        val preferences = preferencesOf(activity)
        val selected = selectedIndex(
            RETENTION_VALUES,
            preferences.getInt(MainActivity.KEY_EPG_PAST_DAYS, DEFAULT_PAST_DAYS),
        )
        host.showStyledChoiceDialog(
            "Días de guía", DIALOG_TAG_GUIDE, RETENTION_LABELS.map { it to "" }, selected,
        ) { index ->
            if (index !in RETENTION_VALUES.indices) return@showStyledChoiceDialog
            val days = RETENTION_VALUES[index]
            preferences.edit { putInt(MainActivity.KEY_EPG_PAST_DAYS, days) }
            onChanged()
            onStatus(if (days == 0) "Se conservarán solo los próximos programas" else "Retención de guía: $days días")
        }
    }

    private fun showReminderLeadPicker(activity: Activity, onStatus: (String) -> Unit) {
        val host = hostActivity(activity) ?: return
        val preferences = preferencesOf(activity)
        val selected = selectedIndex(
            REMINDER_VALUES,
            preferences.getInt(MainActivity.KEY_REMINDER_LEAD, DEFAULT_REMINDER_LEAD),
        )
        host.showStyledChoiceDialog(
            "Aviso previo", DIALOG_TAG_GUIDE, REMINDER_LABELS.map { it to "" }, selected,
        ) { index ->
            if (index !in REMINDER_VALUES.indices) return@showStyledChoiceDialog
            if (hasPendingReminders(activity) && !PermissionFlows.requestExactAlarmAccess(activity)) {
                showReminderLeadPicker(activity, onStatus)
                return@showStyledChoiceDialog
            }
            preferences.edit { putInt(MainActivity.KEY_REMINDER_LEAD, REMINDER_VALUES[index]) }
            ProgramSchedule.rescheduleReminders(activity)
            onStatus("Recordatorio: ${REMINDER_LABELS[index].lowercase()}")
        }
    }

    private fun showRecordingPaddingPicker(activity: Activity, onStatus: (String) -> Unit) {
        val host = hostActivity(activity) ?: return
        val preferences = preferencesOf(activity)
        val beforeSelected = selectedIndex(
            PADDING_VALUES,
            preferences.getInt(MainActivity.KEY_RECORDING_START_LEAD, DEFAULT_RECORDING_LEAD),
        )
        val afterSelected = selectedIndex(
            PADDING_VALUES,
            preferences.getInt(MainActivity.KEY_RECORDING_END_LEAD, DEFAULT_RECORDING_LEAD),
        )
        host.showStyledChoiceDialog(
            "Margen inicial", DIALOG_TAG_RECORDINGS, PADDING_LABELS.map { it to "" }, beforeSelected,
        ) { index ->
            if (index !in PADDING_VALUES.indices) return@showStyledChoiceDialog
            if (hasScheduledRecordings(activity) && !PermissionFlows.requestExactAlarmAccess(activity)) {
                showRecordingPaddingPicker(activity, onStatus)
                return@showStyledChoiceDialog
            }
            preferences.edit { putInt(MainActivity.KEY_RECORDING_START_LEAD, PADDING_VALUES[index]) }
            ProgramSchedule.rescheduleRecordings(activity)
            showRecordingEndPaddingPicker(activity, onStatus, PADDING_VALUES[index], afterSelected)
        }
    }

    private fun showRecordingEndPaddingPicker(
        activity: Activity,
        onStatus: (String) -> Unit,
        beforeMinutes: Int,
        selected: Int,
    ) {
        val host = hostActivity(activity) ?: return
        val preferences = preferencesOf(activity)
        host.showStyledChoiceDialog(
            "Margen final", DIALOG_TAG_RECORDINGS, PADDING_LABELS.map { it to "" }, selected,
        ) { index ->
            if (index !in PADDING_VALUES.indices) return@showStyledChoiceDialog
            if (hasScheduledRecordings(activity) && !PermissionFlows.requestExactAlarmAccess(activity)) {
                showRecordingEndPaddingPicker(activity, onStatus, beforeMinutes, selected)
                return@showStyledChoiceDialog
            }
            val afterMinutes = PADDING_VALUES[index]
            preferences.edit { putInt(MainActivity.KEY_RECORDING_END_LEAD, afterMinutes) }
            ProgramSchedule.rescheduleRecordings(activity)
            onStatus("Grabación: $beforeMinutes min antes y $afterMinutes min después")
        }
    }

    /** Devuelve la actividad principal solo si es del tipo esperado y sigue activa. */
    private fun hostActivity(activity: Activity): MainActivity? {
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity as? MainActivity
    }

    private fun preferencesOf(activity: Activity): SharedPreferences =
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    private fun selectedIndex(values: IntArray, current: Int): Int =
        values.indexOf(current).coerceAtLeast(0)

    private fun hasPendingReminders(activity: Activity): Boolean =
        runCatching {
            val now = System.currentTimeMillis()
            ProgramSchedule.all(activity).any { it.reminder && it.program.startMs > now }
        }.getOrDefault(false)

    private fun hasScheduledRecordings(activity: Activity): Boolean =
        runCatching { ProgramSchedule.all(activity).any { it.recording } }.getOrDefault(false)
}


