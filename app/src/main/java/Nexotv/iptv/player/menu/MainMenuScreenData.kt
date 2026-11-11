// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenData.kt
// Estado de reproducción, instantánea de datos, agenda y almacenamiento para el menú principal.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.status.*
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Environment
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Player
import Nexotv.iptv.player.MainActivity.*
import java.lang.ref.WeakReference

// Tiempos y formatos
private const val MENU_STORAGE_TTL_MS = 5000L
private const val MENU_DATA_TTL_MS = 5000L

@androidx.media3.common.util.UnstableApi
private fun MainActivity.menuScreenPlayState(): MenuScreenPlayState = when {
    recordingPlayback -> MenuScreenPlayState.RECORDING
    retryPending -> MenuScreenPlayState.RECONNECTING
    player.playbackState == Player.STATE_BUFFERING -> MenuScreenPlayState.CONNECTING
    player.playbackState == Player.STATE_READY && player.isPlaying -> MenuScreenPlayState.LIVE
    player.playbackState == Player.STATE_READY -> MenuScreenPlayState.PAUSED
    player.playbackState == Player.STATE_ENDED -> MenuScreenPlayState.ENDED
    else -> MenuScreenPlayState.WAITING
}

@androidx.media3.common.util.UnstableApi
internal fun MainActivity.menuScreenSnapshot(state: MenuScreenState): MenuScreenSnapshot {
    val now = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    val programs = guidePrograms
    if (state.guideProgramsSnapshot !== programs || elapsed - state.guideCheckedAt >= MENU_DATA_TTL_MS) {
        var airing: TvProgram? = null
        var next: TvProgram? = null
        var searchableCount = 0
        for (program in programs) {
            if (program.endMs > now) searchableCount++
            if (program.startMs <= now && program.endMs > now &&
                (airing == null || program.startMs > airing.startMs)
            ) {
                airing = program
            }
            if (program.startMs > now && (next == null || program.startMs < next.startMs)) {
                next = program
            }
        }
        state.guideProgramsSnapshot = programs
        state.guideCheckedAt = elapsed
        state.cachedOnline = menuScreenGuardOr("internet", false) { hasValidatedInternet() }
        state.cachedProgramCount = programs.size
        state.cachedSearchableCount = searchableCount
        state.cachedAiring = airing
        state.cachedNext = next
    }
    return MenuScreenSnapshot(
        now = now,
        online = state.cachedOnline,
        programCount = state.cachedProgramCount,
        searchableCount = state.cachedSearchableCount,
        airing = state.cachedAiring,
        next = state.cachedNext,
        playState = menuScreenGuardOr("estadoReproduccion", MenuScreenPlayState.WAITING) { menuScreenPlayState() },
        guideLoading = guideLoading,
        loadError = guideLoadError,
        epgUpdatedAt = menuScreenGuardOr("epgActualizada", 0L) {
            menuScreenPrefs().getLong(MainActivity.KEY_EPG_UPDATED, 0L)
        },
        nextBoundaryMs = listOfNotNull(state.cachedAiring?.endMs, state.cachedNext?.startMs)
            .filter { it > now }
            .minOrNull(),
    )
}

internal fun MainActivity.menuScreenScheduleSummary(
    now: Long,
    next: TvProgram?,
    state: MenuScreenState
): MenuScreenScheduleSummary {
    val elapsed = SystemClock.elapsedRealtime()
    val nextKey = next?.let { "${it.channelId}:${it.startMs}" }
    if (state.scheduleCheckedAt > 0L && state.scheduleNextKey == nextKey &&
        elapsed - state.scheduleCheckedAt < MENU_DATA_TTL_MS
    ) return state.cachedSchedule

    val summary = try {
        var activeRecordings = 0
        var upcomingRecordings = 0
        var activeReminders = 0
        var upcomingReminders = 0
        var nextRecordingStartMs: Long? = null
        var nextReminderStartMs: Long? = null
        var nextHasRecording = false
        var nextHasReminder = false
        for (item in ProgramSchedule.all(this)) {
            val program = item.program
            if (program.endMs <= now) continue
            val active = program.startMs <= now
            if (item.recording) {
                if (active) activeRecordings++ else {
                    upcomingRecordings++
                    val nextRecordingCandidate = nextRecordingStartMs
                    if (nextRecordingCandidate == null || program.startMs < nextRecordingCandidate) {
                        nextRecordingStartMs = program.startMs
                    }
                }
            }
            if (item.reminder) {
                if (active) activeReminders++ else {
                    upcomingReminders++
                    val nextReminderCandidate = nextReminderStartMs
                    if (nextReminderCandidate == null || program.startMs < nextReminderCandidate) {
                        nextReminderStartMs = program.startMs
                    }
                }
            }
            if (next != null && program.channelId == next.channelId && program.startMs == next.startMs) {
                nextHasRecording = item.recording
                nextHasReminder = item.reminder
            }
        }
        MenuScreenScheduleSummary(
            available = true,
            activeRecordings = activeRecordings,
            upcomingRecordings = upcomingRecordings,
            activeReminders = activeReminders,
            upcomingReminders = upcomingReminders,
            nextRecordingStartMs = nextRecordingStartMs,
            nextReminderStartMs = nextReminderStartMs,
            nextHasRecording = nextHasRecording,
            nextHasReminder = nextHasReminder
        )
    } catch (e: Exception) {
        menuScreenReport("agenda", e)
        MenuScreenScheduleSummary()
    }
    state.scheduleCheckedAt = elapsed
    state.scheduleNextKey = nextKey
    state.cachedSchedule = summary
    return summary
}

internal class MenuScreenStorageReader(
    private val context: Context,
    private val treeConfigured: Boolean,
    private val treeUri: Uri?,
) {
    @Suppress("NewApi", "StorageManagerLeak", "DiscUsage")
    fun read(): String? {
        if (!treeConfigured) {
            val directory = runCatching {
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            }.onFailure { menuScreenReport("espacioTv", it) }.getOrNull() ?: return null
            val target = directory.takeIf { it.exists() && it.isDirectory && it.canRead() } ?: return null
            val usableSpace = runCatching { target.usableSpace }
                .onFailure { menuScreenReport("espacioTv", it) }
                .getOrNull()
                ?: return null
            return context.getString(R.string.tv_menu_storage_tv_free, formatBytes(usableSpace))
        }
        val parsedTree = treeUri ?: return null
        val usbRoot = try {
            DocumentFile.fromTreeUri(context, parsedTree)?.takeIf { it.exists() && it.canRead() }
        } catch (e: Exception) {
            menuScreenReport("accesoUsb", e)
            null
        }
        if (usbRoot == null) return null
        val usbFreeBytes = try {
            UsbCaptureOutput.freeSpace(context, parsedTree)
        } catch (e: Exception) {
            menuScreenReport("espacioUsb", e)
            null
        }
        return usbFreeBytes?.let { context.getString(R.string.tv_menu_storage_usb_free, formatBytes(it)) }
    }
}

internal fun MainActivity.menuScreenStorageReader(): MenuScreenStorageReader {
    val tree = menuScreenGuardOr("uriArbol", null) {
        menuScreenPrefs().getString(MainActivity.KEY_RECORDING_TREE, null)
    }
    if (tree.isNullOrBlank()) {
        return MenuScreenStorageReader(applicationContext, false, null)
    }
    val parsedTree = try {
        tree.toUri()
    } catch (e: Exception) {
        menuScreenReport("parseUri", e)
        null
    }
    return MenuScreenStorageReader(applicationContext, true, parsedTree)
}

internal fun MainActivity.menuScreenStorageText(state: MenuScreenState): String? {
    val elapsed = SystemClock.elapsedRealtime()
    val shouldStart = synchronized(state) {
        val cacheValid = state.storageCheckedAt > 0L && elapsed - state.storageCheckedAt < MENU_STORAGE_TTL_MS
        if (cacheValid || state.storageLookupPending) {
            false
        } else {
            state.storageLookupPending = true
            true
        }
    }
    if (!shouldStart) return synchronized(state) { state.storageText }

    val reader = try {
        menuScreenStorageReader()
    } catch (e: Exception) {
        menuScreenReport("espacioAlmacenamiento", e)
        return synchronized(state) {
            state.storageLookupPending = false
            state.storageCheckedAt = elapsed.coerceAtLeast(1L)
            state.storageText
        }
    }
    val stateRef = WeakReference(state)
    val worker = Thread(
        {
            val result = runCatching { reader.read() }.getOrNull()
            stateRef.get()?.let { cachedState ->
                synchronized(cachedState) {
                    cachedState.storageText = result
                    cachedState.storageCheckedAt = SystemClock.elapsedRealtime().coerceAtLeast(1L)
                    cachedState.storageLookupPending = false
                }
            }
        },
        "menu-screen-storage",
    ).apply { isDaemon = true }
    try {
        worker.start()
    } catch (e: Exception) {
        menuScreenReport("espacioAlmacenamiento", e)
        return synchronized(state) {
            state.storageLookupPending = false
            state.storageCheckedAt = elapsed.coerceAtLeast(1L)
            state.storageText
        }
    }
    return synchronized(state) { state.storageText }
}
