
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduledProgram(
    val program: TvProgram,
    val reminder: Boolean,
    val recording: Boolean,
)

internal data class ScheduledProgramsSnapshot(
    val programs: List<ScheduledProgram>,
    val revision: Long,
)

/** Persiste recordatorios y ventanas de grabación de programas y restaura sus alarmas tras reiniciar. */
internal object ProgramSchedule {
    private val PREFS = "${BuildConfig.BRAND_KEY}_player"
    private const val KEY_SCHEDULES = "program_schedules"
    private const val KEY_REMINDER_LEAD = "reminder_lead_minutes"
    private const val DEFAULT_REMINDER_LEAD_MINUTES = 5
    private const val MAX_PADDING_MINUTES = 15
    private const val MIN_ALARM_DELAY_MS = 1_000L
    private const val MINUTE_MS = 60_000L
    private const val KEY_ALARM_ERROR_PREFIX = "scheduled_recording_alarm_error_"

    internal const val REMINDER = "Nexotv.iptv.player.PROGRAM_REMINDER"
    internal const val RECORD_START = "Nexotv.iptv.player.PROGRAM_RECORD_START"
    internal const val RECORD_STOP = "Nexotv.iptv.player.PROGRAM_RECORD_STOP"
    internal const val EXTRA_PROGRAM_ID = "scheduled_program_id"
    internal const val EXTRA_TITLE = "scheduled_program_title"

    private val lock = Any()
    private var cachedSchedules: List<ScheduledProgram>? = null
    private var schedulesRevision = 0L

    fun uiStateSnapshot(context: Context): ScheduledProgramsSnapshot = synchronized(lock) {
        if (cachedSchedules == null) cachedSchedules = readAll(context).toList()
        ScheduledProgramsSnapshot(requireNotNull(cachedSchedules), schedulesRevision)
    }

    fun preloadUiState(context: Context) {
        uiStateSnapshot(context)
    }

    fun all(context: Context): List<ScheduledProgram> = uiStateSnapshot(context).programs

    @Suppress("unused")
    fun isScheduled(context: Context, program: TvProgram): Boolean =
        all(context).any { sameProgram(it.program, program) }

    fun canScheduleExactAlarms(context: Context): Boolean =
        runCatching { PermissionFlows.canScheduleExactAlarms(context) }.getOrDefault(false)

    fun save(context: Context, program: TvProgram, reminder: Boolean, recording: Boolean): Boolean {
        if (!isValid(program)) return false
        if ((reminder || recording) && !canScheduleExactAlarms(context)) return false
        synchronized(lock) {
            val current = readAll(context)
            val window = recordingWindow(context, program)
            val conflicts = recording && current.any { item ->
                item.recording && !sameProgram(item.program, program) &&
                        recordingWindow(context, item.program).overlaps(window)
            }
            if (conflicts) return false
            val entries = current.filterNot { sameProgram(it.program, program) }.toMutableList()
            if (reminder || recording) entries += ScheduledProgram(program, reminder, recording)
            if (!persist(context, entries)) return false
            cancelAlarms(context, program)
            val now = System.currentTimeMillis()
            var registered = true
            if (reminder && program.startMs > now) {
                registered = schedule(context, program, REMINDER, reminderTriggerMs(context, program, now)) && registered
            }
            if (recording && window.endMs > now) {
                registered = schedule(context, program, RECORD_START, window.startMs.coerceAtLeast(now + MIN_ALARM_DELAY_MS)) && registered
                registered = schedule(context, program, RECORD_STOP, window.endMs) && registered
            }
            if (!registered) {
                cancelAlarms(context, program)
                persist(context, current)
                return false
            }
            return true
        }
    }

    fun setReminder(context: Context, program: TvProgram, reminder: Boolean): Boolean {
        if (!isValid(program)) return false
        if (reminder && !canScheduleExactAlarms(context)) return false
        synchronized(lock) {
            val current = readAll(context)
            val existing = current.firstOrNull { sameProgram(it.program, program) }
            val recording = existing?.recording == true
            val entries = current.filterNot { sameProgram(it.program, program) }.toMutableList()
            if (reminder || recording) entries += ScheduledProgram(program, reminder, recording)
            if (!persist(context, entries)) return false
            cancelAlarm(context, program, REMINDER)
            val now = System.currentTimeMillis()
            if (reminder && program.startMs > now) {
                schedule(context, program, REMINDER, reminderTriggerMs(context, program, now))
            }
            return true
        }
    }

    fun restore(context: Context) {
        if (!canScheduleExactAlarms(context)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            readAll(context).filter { recordingEndMs(context, it.program) > now }.forEach { item ->
                val program = item.program
                if (item.reminder && program.startMs > now) {
                    schedule(context, program, REMINDER, reminderTriggerMs(context, program, now))
                }
                if (item.recording) {
                    schedule(context, program, RECORD_START, recordingStartMs(context, program).coerceAtLeast(now + MIN_ALARM_DELAY_MS))
                    schedule(context, program, RECORD_STOP, recordingEndMs(context, program))
                }
            }
        }
    }

    /** Reconstruye las alarmas de grabación pendientes cuando el usuario cambia los márgenes. */
    fun rescheduleRecordings(context: Context) {
        if (!canScheduleExactAlarms(context)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            readAll(context).filter { it.recording }.forEach { item ->
                val program = item.program
                cancelAlarm(context, program, RECORD_START)
                cancelAlarm(context, program, RECORD_STOP)
                val recordingEnd = recordingEndMs(context, program)
                if (recordingEnd > now) {
                    schedule(context, program, RECORD_START, recordingStartMs(context, program).coerceAtLeast(now + MIN_ALARM_DELAY_MS))
                    schedule(context, program, RECORD_STOP, recordingEnd)
                } else {
                    // Reducir el margen final puede acortar una captura que está en su margen.
                    schedule(context, program, RECORD_STOP, now + MIN_ALARM_DELAY_MS)
                }
            }
        }
    }

    fun rescheduleReminders(context: Context) {
        if (!canScheduleExactAlarms(context)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            readAll(context).filter { it.reminder && it.program.startMs > now }.forEach { item ->
                cancelAlarm(context, item.program, REMINDER)
                schedule(context, item.program, REMINDER, reminderTriggerMs(context, item.program, now))
            }
        }
    }

    fun remove(context: Context, program: TvProgram) {
        synchronized(lock) {
            val current = readAll(context)
            val existing = current.firstOrNull { sameProgram(it.program, program) }
            if (!persist(context, current.filterNot { sameProgram(it.program, program) })) return
            cancelAlarms(context, program)
            val now = System.currentTimeMillis()
            val capturing = existing?.recording == true &&
                    recordingStartMs(context, program) <= now && recordingEndMs(context, program) > now
            if (capturing) {
                runCatching {
                    context.startService(
                        Intent(context, HlsRecordingService::class.java)
                            .setAction(HlsRecordingService.ACTION_STOP)
                            .putExtra(HlsRecordingService.EXTRA_SCHEDULE_ID, key(program)),
                    )
                }
            }
        }
    }

    /** Completa una parada confirmada y conserva el recordatorio si existía. */
    internal fun completeRecording(context: Context, scheduleId: String) {
        if (scheduleId.isBlank()) return
        synchronized(lock) {
            val current = readAll(context)
            val existing = current.firstOrNull { key(it.program) == scheduleId } ?: return
            val remaining = current.filterNot { key(it.program) == scheduleId }.toMutableList()
            if (existing.reminder) remaining += existing.copy(recording = false)
            if (persist(context, remaining)) {
                cancelAlarm(context, existing.program, RECORD_START)
                cancelAlarm(context, existing.program, RECORD_STOP)
                clearAlarmFailure(context, scheduleId)
            }
        }
    }

    internal fun recordAlarmFailure(context: Context, scheduleId: String, action: String, error: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(alarmErrorKey(scheduleId, action), error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun clearAlarmFailure(context: Context, scheduleId: String) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        listOf(RECORD_START, RECORD_STOP).forEach { action ->
            val key = alarmErrorKey(scheduleId, action)
            if (!preferences.contains(key)) return@forEach
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(alarmErrorNotificationId(scheduleId, action))
            }
            preferences.edit { remove(key) }
        }
    }

    private fun alarmErrorKey(scheduleId: String, action: String) = "${KEY_ALARM_ERROR_PREFIX}${scheduleId}|${action}"
    internal fun key(program: TvProgram) = "${program.channelId}:${program.startMs}"

    internal fun alarmErrorNotificationId(scheduleId: String, action: String): Int =
        (scheduleId + action).hashCode()

    private fun readAll(context: Context): List<ScheduledProgram> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCHEDULES, null)
            ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val program = TvProgram(
                item.optString("channel"), item.optString("title"), item.optString("description"),
                item.optLong("start"), item.optLong("end"),
            )
            if (!isValid(program)) return@mapNotNull null
            val reminder = item.optBoolean("reminder")
            val recording = item.optBoolean("recording")
            if (!reminder && !recording) null else ScheduledProgram(program, reminder, recording)
        }
    }.getOrDefault(emptyList())

    private fun persist(context: Context, values: List<ScheduledProgram>): Boolean = runCatching {
        val array = JSONArray()
        values.forEach { item ->
            array.put(JSONObject().apply {
                put("channel", item.program.channelId)
                put("title", item.program.title)
                put("description", item.program.description)
                put("start", item.program.startMs)
                put("end", item.program.endMs)
                put("reminder", item.reminder)
                put("recording", item.recording)
            })
        }
        val persisted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCHEDULES, array.toString())
            .commit()
        if (!persisted) return@runCatching false
        if (cachedSchedules != values) {
            cachedSchedules = values.toList()
            schedulesRevision++
        }
        true
    }.getOrDefault(false)

    private fun isValid(program: TvProgram) =
        program.channelId.isNotBlank() && program.startMs > 0L && program.endMs > program.startMs

    private fun alarmManager(context: Context): AlarmManager? =
        runCatching { context.getSystemService(AlarmManager::class.java) }.getOrNull()

    private fun schedule(context: Context, program: TvProgram, action: String, atMs: Long): Boolean {
        if (!canScheduleExactAlarms(context)) return false
        val alarm = alarmManager(context) ?: return false
        val pending = pending(context, program, action, create = true) ?: return false
        return runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending) }.isSuccess
    }

    private fun cancelAlarm(context: Context, program: TvProgram, action: String) {
        val pending = pending(context, program, action, create = false) ?: return
        runCatching { alarmManager(context)?.cancel(pending) }
        runCatching { pending.cancel() }
    }

    private fun cancelAlarms(context: Context, program: TvProgram) {
        listOf(REMINDER, RECORD_START, RECORD_STOP).forEach { cancelAlarm(context, program, it) }
    }

    private fun pending(context: Context, program: TvProgram, action: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ProgramAlarmReceiver::class.java).setAction(action)
            .putExtra(EXTRA_PROGRAM_ID, key(program))
            .putExtra(EXTRA_TITLE, program.title)
        val id = (key(program) + action).hashCode()
        val base = if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        val flags = base or PendingIntent.FLAG_IMMUTABLE
        return runCatching { PendingIntent.getBroadcast(context, id, intent, flags) }.getOrNull()
    }

    private fun recordingWindow(context: Context, program: TvProgram): RecordingWindow {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RecordingWindow.fromProgram(
            program.startMs,
            program.endMs,
            preferences.getInt(MainActivity.KEY_RECORDING_START_LEAD, 0).coerceIn(0, MAX_PADDING_MINUTES),
            preferences.getInt(MainActivity.KEY_RECORDING_END_LEAD, 0).coerceIn(0, MAX_PADDING_MINUTES),
        )
    }

    private fun recordingStartMs(context: Context, program: TvProgram) = recordingWindow(context, program).startMs
    private fun recordingEndMs(context: Context, program: TvProgram) = recordingWindow(context, program).endMs

    private fun reminderLeadMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_REMINDER_LEAD, DEFAULT_REMINDER_LEAD_MINUTES)
            .coerceAtLeast(0) * MINUTE_MS

    private fun reminderTriggerMs(context: Context, program: TvProgram, now: Long) =
        (program.startMs - reminderLeadMs(context)).coerceAtLeast(now + MIN_ALARM_DELAY_MS)

    private fun sameProgram(first: TvProgram, second: TvProgram) =
        first.channelId == second.channelId && first.startMs == second.startMs
}

class ProgramAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val title = intent.getStringExtra(ProgramSchedule.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "${BuildConfig.BRAND_NAME}"
        val id = intent.getStringExtra(ProgramSchedule.EXTRA_PROGRAM_ID).orEmpty()
        when (intent.action) {
            ProgramSchedule.REMINDER -> showReminder(context, title, id)
            ProgramSchedule.RECORD_START -> {
                runCatching {
                    val service = Intent(context, HlsRecordingService::class.java)
                        .setAction(HlsRecordingService.ACTION_START)
                        .putExtra(HlsRecordingService.EXTRA_SCHEDULE_ID, id)
                    ContextCompat.startForegroundService(context, service)
                }.onSuccess {
                    ProgramSchedule.clearAlarmFailure(context, id)
                }.onFailure { failure ->
                    ProgramSchedule.recordAlarmFailure(context, id, ProgramSchedule.RECORD_START, failure)
                    showRecordingAlarmError(context, id, title, ProgramSchedule.RECORD_START, "No se pudo iniciar la grabación programada", failure)
                }
            }
            ProgramSchedule.RECORD_STOP -> {
                runCatching {
                    context.startService(
                        Intent(context, HlsRecordingService::class.java)
                            .setAction(HlsRecordingService.ACTION_STOP)
                            .putExtra(HlsRecordingService.EXTRA_SCHEDULE_ID, id),
                    )
                }.onSuccess {
                    ProgramSchedule.clearAlarmFailure(context, id)
                }.onFailure { failure ->
                    ProgramSchedule.recordAlarmFailure(context, id, ProgramSchedule.RECORD_STOP, failure)
                    showRecordingAlarmError(context, id, title, ProgramSchedule.RECORD_STOP, "No se pudo detener la grabación programada", failure)
                }
            }
        }
    }

    private fun showRecordingAlarmError(context: Context, id: String, title: String, action: String, message: String, failure: Throwable) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_RECORDING_ERRORS, "Grabaciones de ${BuildConfig.BRAND_NAME}", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                (title + message).hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                ProgramSchedule.alarmErrorNotificationId(id, action),
                NotificationCompat.Builder(context, CHANNEL_RECORDING_ERRORS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("${BuildConfig.BRAND_NAME}: error de grabación")
                    .setContentText("$message: ${failure.message ?: "servicio no disponible"}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$message para «$title». La programación se conservó para poder recuperarla."))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build(),
            )
        }
    }

    private fun showReminder(context: Context, title: String, programId: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Recordatorios de ${BuildConfig.BRAND_NAME}", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                (programId.ifBlank { title }).hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                (programId.ifBlank { title }).hashCode(),
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("${BuildConfig.BRAND_NAME}: programa próximo")
                    .setContentText(title)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    companion object {
        private val CHANNEL = "${BuildConfig.BRAND_KEY}_program_reminders"
        private val CHANNEL_RECORDING_ERRORS = "${BuildConfig.BRAND_KEY}_recording_alarm_errors"
    }
}
