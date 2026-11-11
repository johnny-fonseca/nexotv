// HlsRecordingService.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.common.formatByteSize
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class HlsRecordingService : Service() {
    @Volatile private var capture: HlsCapture? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var scheduledRecordingId: String? = null
    private val outputSizes = ConcurrentHashMap<String, Long>()
    @Volatile private var freeBytes: Long? = null
    @Volatile private var startedAt = 0L
    @Volatile private var lastProgressPublishAtMs = 0L
    @Volatile private var lastPublishedBytes = 0L
    private val lock = Any()

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private val notificationManager: NotificationManager?
        get() = getSystemService(NotificationManager::class.java)

    private val stopPendingIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        PendingIntent.getService(
            this,
            1,
            Intent(this, HlsRecordingService::class.java).setAction(ACTION_STOP),
            flags,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            if (intent?.action == ACTION_STOP) {
                handleStop(intent.getStringExtra(EXTRA_SCHEDULE_ID))
                return START_NOT_STICKY
            }
            if (worker?.isAlive == true) {
                handleActiveRecordingConflict(intent?.getStringExtra(EXTRA_SCHEDULE_ID))
                return START_NOT_STICKY
            }
            try {
                startRecording(intent?.getStringExtra(EXTRA_SCHEDULE_ID))
            } catch (failure: Exception) {
                abortStart(failure)
            }
            return START_NOT_STICKY
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        synchronized(lock) {
            prefs.edit {
                putString(KEY_ERROR, "Grabación detenida: Android alcanzó el límite de tiempo del servicio en segundo plano. La captura parcial se conservó.")
            }
            capture?.stop()
            worker?.interrupt()
            if (worker?.isAlive != true) stopSelf()
        }
    }

    private fun handleStop(requestedId: String?) {
        val active = worker?.isAlive == true
        if (active && ((requestedId == null) || (requestedId == scheduledRecordingId))) {
            capture?.stop()
            worker?.interrupt()
        } else if (!active) {
            ProgramSchedule.completeRecording(this, requestedId.orEmpty())
            stopSelf()
        }
    }

    private fun handleActiveRecordingConflict(requestedScheduleId: String?) {
        if (!scheduledStartConflictsWithActiveCapture(workerAlive = true, requestedScheduleId, scheduledRecordingId)) return
        val title = runCatching {
            ProgramSchedule.all(this).firstOrNull { (program) ->
                "${program.channelId}:${program.startMs}" == requestedScheduleId
            }?.program?.title
        }.getOrNull() ?: "el programa programado"
        prefs.edit {
            putString(MainActivity.KEY_RECORDING_ERROR, "No se grabó «$title»: ya había otra grabación activa.")
        }
        updateNotification()
    }

    private fun startRecording(scheduleId: String?) {
        scheduledRecordingId = scheduleId
        val preferences = prefs
        val stem = "${BuildConfig.BRAND_NAME}-${System.currentTimeMillis()}"
        val localDirectory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!localDirectory.exists() && !localDirectory.mkdirs() && !localDirectory.isDirectory) {
            throw CaptureDestinationException("No se pudo crear el directorio de grabación", null)
        }
        val localOutput = File(localDirectory, "$stem.ts")
        val tree = preferences.getString(KEY_RECORDING_TREE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.toUri() }.getOrNull() }
        val userAgent = preferences.getString("stream_user_agent", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: TV_USER_AGENT

        val recorder = HlsCapture(
            currentStreamUrl(),
            localOutput,
            outputFactory = tree?.let { root ->
                { isMp4, part ->
                    val partName = if (part == 1) stem else "$stem-part${part.toString().padStart(2, '0')}"
                    runCatching { UsbCaptureOutput.create(this, root, if (isMp4) "mp4" else "ts", partName) }
                        .getOrElse { throw CaptureDestinationException("No se pudo preparar la USB: ${it.message}", it) }
                }
            },
            onOutput = { actual ->
                outputSizes.putIfAbsent(actual.id, actual.bytes)
                prefs.edit {
                    putString("recording_file", actual.id)
                    putString(KEY_RECORDING_NAME, actual.displayName)
                }
            },
            onDestinationFailure = { message ->
                prefs.edit {
                    putString(KEY_ERROR, "$message. Las partes completadas se conservaron; revisa la USB antes de continuar.")
                }
            },
            userAgent = userAgent,
            requestHeaders = currentStreamHeaders(),
            onStorageLimit = {
                val message = if (tree == null) {
                    "Grabación detenida: quedan menos de 512 MB en el dispositivo. La captura parcial se conservó."
                } else {
                    "Grabación detenida: quedan menos de 512 MB en la USB. Las partes completadas se conservaron; libera espacio antes de continuar."
                }
                prefs.edit {
                    putString(KEY_ERROR, message)
                }
            },
            onProgress = { actual ->
                outputSizes[actual.id] = actual.bytes
                val total = outputSizes.values.sum()
                val now = System.currentTimeMillis()
                val shouldPublish = now - lastProgressPublishAtMs >= PROGRESS_PUBLISH_INTERVAL_MS ||
                        total - lastPublishedBytes >= PROGRESS_PUBLISH_BYTES
                if (shouldPublish) {
                    freeBytes = actual.freeSpaceBytes()
                    prefs.edit {
                        putLong(KEY_RECORDING_BYTES, total)
                        putLong(KEY_RECORDING_FREE, freeBytes ?: -1L)
                    }
                    lastProgressPublishAtMs = now
                    lastPublishedBytes = total
                    updateNotification()
                }
            },
        )

        capture = recorder
        startedAt = System.currentTimeMillis()
        outputSizes.clear()
        freeBytes = null
        lastProgressPublishAtMs = 0L
        lastPublishedBytes = 0L
        preferences.edit {
            remove(MainActivity.KEY_RECORDING_ERROR)
            putLong(KEY_RECORDING_STARTED, startedAt)
            putLong(KEY_RECORDING_BYTES, 0L)
            putLong(KEY_RECORDING_FREE, -1L)
        }
        startForeground(NOTIFICATION_ID, notification())
        preferences.edit {
            putBoolean(KEY_ACTIVE, true)
        }

        worker = Thread(
            {
                var termination = CaptureTermination.FAILED
                try {
                    termination = recorder.run()
                } catch (failure: Exception) {
                    prefs.edit {
                        putString(KEY_ERROR, failure.message ?: "error de captura")
                    }
                } finally {
                    finishRecording(termination)
                }
            },
            "${BuildConfig.BRAND_NAME}-HLS-Recorder",
        ).apply { start() }
    }

    private fun finishRecording(termination: CaptureTermination) {
        val existingError = prefs.getString(KEY_ERROR, null)
        runCatching {
            prefs.edit {
                putBoolean(KEY_ACTIVE, false)
                if (termination == CaptureTermination.FAILED && existingError.isNullOrBlank()) {
                    putString(KEY_ERROR, "La grabación terminó por un fallo; la programación se conservó.")
                }
            }
        }
        if (termination != CaptureTermination.FAILED) {
            scheduledRecordingId?.let { ProgramSchedule.completeRecording(this, it) }
        }
        runCatching { HomeScreenChannel.syncRecordings(this) }
        removeForeground()
        stopSelf()
    }

    private fun abortStart(failure: Exception) {
        capture = null
        worker = null
        scheduledRecordingId = null
        runCatching {
            prefs.edit {
                putString(KEY_ERROR, failure.message ?: "No se pudo iniciar la grabación")
                putBoolean(KEY_ACTIVE, false)
            }
        }
        // Android exige startForeground tras startForegroundService; se cumple antes de cerrar.
        runCatching { startForeground(NOTIFICATION_ID, notification()) }
        removeForeground()
        stopSelf()
    }

    private fun removeForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun updateNotification() {
        runCatching { notificationManager?.notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        val manager = notificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Grabación ${BuildConfig.BRAND_NAME}", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val preferences = prefs
        val external = preferences.getString(KEY_RECORDING_TREE, null) != null
        val elapsed = ((System.currentTimeMillis() - startedAt.coerceAtLeast(1L)) / 1000L).coerceAtLeast(0L)
        val duration = String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, (elapsed / 60) % 60, elapsed % 60)
        val bytes = preferences.getLong(KEY_RECORDING_BYTES, 0L)
        val savedFree = preferences.getLong(KEY_RECORDING_FREE, -1L).takeIf { it >= 0L }
        val free = (freeBytes ?: savedFree)?.let { " · Libre ${formatByteSize(it)}" }
            ?: if (external) " · espacio libre desconocido" else ""
        val destination = if (external) "USB" else "Dispositivo"
        val conflict = preferences.getString(MainActivity.KEY_RECORDING_ERROR, null)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Grabando ${BuildConfig.BRAND_NAME} · $destination")
            .setContentText(conflict ?: "$duration · ${formatByteSize(bytes)}$free")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { capture?.stop() }
        worker?.interrupt()
        runCatching { prefs.edit { putBoolean(KEY_ACTIVE, false) } }
        super.onDestroy()
    }


    companion object {
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 2_000L
        private const val PROGRESS_PUBLISH_BYTES = 2L * 1024 * 1024
        const val ACTION_START = "Nexotv.iptv.player.START_RECORDING"
        const val ACTION_STOP = "Nexotv.iptv.player.STOP_RECORDING"
        const val EXTRA_SCHEDULE_ID = "scheduled_program_id"
        private val PREFS_NAME = "${BuildConfig.BRAND_KEY}_player"
        private const val KEY_ACTIVE = "recording_active"
        private const val KEY_ERROR = "recording_error"
        private const val KEY_RECORDING_TREE = "recording_tree_uri"
        private const val KEY_RECORDING_NAME = "recording_name"
        private const val KEY_RECORDING_STARTED = "recording_started_at"
        private const val KEY_RECORDING_BYTES = "recording_bytes"
        private const val KEY_RECORDING_FREE = "recording_free_bytes"
        private val CHANNEL = "${BuildConfig.BRAND_KEY}_recording"
        private const val NOTIFICATION_ID = 7050
    }
}
