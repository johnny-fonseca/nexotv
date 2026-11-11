@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/activity/TvHomeEntry.kt
package Nexotv.iptv.player.activity

import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.R
import Nexotv.iptv.player.guide.refreshGuide
import Nexotv.iptv.player.guide.showGuide
import Nexotv.iptv.player.guide.showGuideSearch
import Nexotv.iptv.player.guide.showProgramActions
import Nexotv.iptv.player.menu.menuScreenEnsureGuideLoaded
import Nexotv.iptv.player.menu.MenuScreenState
import Nexotv.iptv.player.menu.menuScreenScheduleSummary
import Nexotv.iptv.player.menu.menuScreenStorageReader
import Nexotv.iptv.player.menu.menuScreenToggleLivePlayback
import Nexotv.iptv.player.menu.recordingElapsedMs
import Nexotv.iptv.player.menu.showMainMenu
import Nexotv.iptv.player.player.safeBoolean
import Nexotv.iptv.player.player.togglePlayback
import Nexotv.iptv.player.recordings.isRecording
import Nexotv.iptv.player.recordings.formatBytes
import Nexotv.iptv.player.recordings.recordingLibrarySummary
import Nexotv.iptv.player.recordings.showRecordingLibrary
import Nexotv.iptv.player.recordings.toggleRecording
import Nexotv.iptv.player.settings.showMoreSettings
import Nexotv.iptv.player.status.showTvToast
import Nexotv.iptv.player.tv.TvProgram
import Nexotv.iptv.player.ui.TvChannelUi
import Nexotv.iptv.player.ui.TvHomeActions
import Nexotv.iptv.player.ui.TvHomeDestination
import Nexotv.iptv.player.ui.selectHomeGuidePrograms
import Nexotv.iptv.player.ui.TvHomeDialog
import Nexotv.iptv.player.ui.TvPlaybackState
import Nexotv.iptv.player.ui.TvProgramUi
import Nexotv.iptv.player.ui.TvRecordingUi
import Nexotv.iptv.player.ui.TvUiState
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import java.lang.ref.WeakReference
import java.util.Date

/**
 * Single entry point and switch for the rebuilt home. The legacy menu keeps running while
 * [KEY_HOME_V2] is false (the default). Enabling it is an operator decision, not a user
 * preference exposed in P1.
 *
 * Switch: SharedPreferences key [KEY_HOME_V2], boolean, default false.
 * Debug builds can toggle it with:
 *   adb shell am broadcast -a Nexotv.iptv.player.DEBUG.HOME_V2 --ez enabled true
 */
internal object TvHomeEntry {

    const val KEY_HOME_V2 = "home_v2_enabled"

    fun isEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOME_V2, false)
    } catch (_: RuntimeException) {
        false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HOME_V2, enabled)
            .apply()
    }

    fun show(activity: MainActivity, focusDestination: MainActivity.MenuDestination?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val returnFocus = activity.currentFocus
        val source = TvHomeDataSource(activity)
        var dialog: TvHomeDialog? = null
        var launched = false

        fun openDestination(destination: MainActivity.MenuDestination, open: () -> Unit) {
            launched = true
            activity.menuOrigin = destination
            activity.pushScreenBackStack { activity.showMainMenu(destination) {} }
            dialog?.dismiss()
            runCatching(open).onFailure {
                activity.showTvToast(
                    activity.getString(R.string.tv_home_destination_failed),
                    long = true,
                )
            }
        }

        val actions = object : TvHomeActions {
            override fun onNavigate(destination: TvHomeDestination) {
                when (destination) {
                    TvHomeDestination.LIVE -> returnToLive(activity) { dialog?.dismiss() }
                    TvHomeDestination.GUIDE -> openDestination(MainActivity.MenuDestination.GUIDE) {
                        activity.showGuide()
                    }
                    TvHomeDestination.SEARCH -> openDestination(MainActivity.MenuDestination.SEARCH) {
                        activity.showGuideSearch()
                    }
                    TvHomeDestination.CLIPS -> openDestination(MainActivity.MenuDestination.RECORDINGS) {
                        activity.showRecordingLibrary()
                    }
                    TvHomeDestination.MORE -> openDestination(MainActivity.MenuDestination.MORE) {
                        activity.showMoreSettings()
                    }
                }
            }

            override fun onWatchNow() {
                returnToLive(activity) { dialog?.dismiss() }
            }

            override fun onToggleRecording() {
                runCatching { activity.toggleRecording() }
                dialog?.refresh()
            }

            override fun onOpenProgram(program: TvProgramUi) {
                val domain = activity.guidePrograms.firstOrNull {
                    it.channelId == program.channelId && it.startMs == program.startMs
                }
                if (domain != null) {
                    runCatching { activity.showProgramActions(domain) }
                } else {
                    activity.showTvToast(
                        activity.getString(R.string.tv_home_program_unavailable),
                        long = true,
                    )
                }
            }

            override fun onRetryGuide() {
                runCatching { activity.refreshGuide(silent = false) }
                dialog?.refresh()
            }

            override fun onTogglePlayback() {
                runCatching { activity.togglePlayback() }
                dialog?.refresh()
            }
        }

        val previousRefresh = activity.refreshVisibleMainMenu
        val home = TvHomeDialog(
            context = activity,
            stateProvider = source::state,
            actions = actions,
            initialDestination = focusDestination?.toHomeDestination(),
            confirmExit = confirmExitEnabled(activity),
            onExit = {
                launched = true
                if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
            },
            onStopped = source::stopHomeWorkers,
            onClosed = {
                source.stopHomeWorkers()
                activity.refreshVisibleMainMenu = previousRefresh
                if (!launched && !activity.isFinishing && !activity.isDestroyed) {
                    runCatching {
                        val target = returnFocus?.takeIf { it.isAttachedToWindow && it.isFocusable }
                        if (target != null) target.requestFocus() else activity.statusHost.requestFocus()
                    }
                }
            },
        )
        dialog = home
        activity.refreshVisibleMainMenu = {
            if (dialog?.isShowing == true) dialog?.refresh()
        }
        try {
            home.show()
            home.window?.decorView?.let { decorView ->
                decorView.postOnAnimation {
                    decorView.post {
                        if (home.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                            runCatching { activity.menuScreenEnsureGuideLoaded() }
                            home.refresh()
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            source.stopHomeWorkers()
            activity.refreshVisibleMainMenu = previousRefresh
        }
    }

    private fun returnToLive(activity: MainActivity, dismiss: () -> Unit) {
        activity.clearScreenBackStack()
        activity.menuOrigin = null
        dismiss()
        runCatching { activity.menuScreenToggleLivePlayback() }
    }

    private fun confirmExitEnabled(activity: MainActivity): Boolean = try {
        activity.mainPrefs.getBoolean(MainActivity.KEY_CONFIRM_EXIT, true)
    } catch (_: RuntimeException) {
        true
    }
}

private fun MainActivity.MenuDestination.toHomeDestination(): TvHomeDestination = when (this) {
    MainActivity.MenuDestination.GUIDE -> TvHomeDestination.GUIDE
    MainActivity.MenuDestination.SEARCH -> TvHomeDestination.SEARCH
    MainActivity.MenuDestination.RECORDINGS -> TvHomeDestination.CLIPS
    MainActivity.MenuDestination.MORE -> TvHomeDestination.MORE
}

private data class HomeGuideUiSelection(
    val airing: TvProgramUi?,
    val next: TvProgramUi?,
    val upcoming: List<TvProgramUi>,
)

private fun selectHomeGuideUi(
    guide: List<TvProgram>,
    nowMs: Long,
    railLimit: Int,
): HomeGuideUiSelection {
    val selection = selectHomeGuidePrograms(guide, nowMs, railLimit)
    return HomeGuideUiSelection(
        airing = selection.airing?.toHomeProgramUi(),
        next = selection.next?.toHomeProgramUi(),
        upcoming = selection.upcoming.map { it.toHomeProgramUi() },
    )
}

private fun TvProgram.toHomeProgramUi(): TvProgramUi = TvProgramUi(
    channelId = channelId,
    title = title,
    startMs = startMs,
    endMs = endMs,
    description = description,
)

/**
 * Builds a bounded home snapshot from the raw guide. Only the airing, next and capped rail
 * programs are converted to UI models; the one-second tick never scans the EPG.
 */
private class TvHomeDataSource(private val activity: MainActivity) {

    private val railLimit = activity.resources
        .getInteger(R.integer.tv_ui_home_rail_max)
        .coerceAtLeast(0)
    private val channel = TvChannelUi(
        id = BuildConfig.BRAND_KEY,
        name = BuildConfig.BRAND_NAME,
        number = BuildConfig.CHANNEL_NAME,
        isLive = true,
    )
    private val channels = listOf(channel)

    @Volatile private var guideReference: List<TvProgram>? = null
    private var guideSnapshotInitialized = false
    @Volatile private var computedAtMs = 0L
    @Volatile private var guideUiSelection = HomeGuideUiSelection(null, null, emptyList())
    private val guideMappingLock = Any()
    @Volatile private var guideMappingPending = false
    private var guideMappingClosed = false
    private var guideMappingGeneration = 0L
    private var guideMappingWorker: Thread? = null
    private val clipsScheduleState = MenuScreenState()
    private val clipsStorageLock = Any()
    @Volatile private var cachedClipsStorageText: String? = null
    @Volatile private var clipsStorageLookupPending = false
    private var clipsStorageLookupClosed = false
    private var clipsStorageWorker: Thread? = null
    private var clipsStorageLookupStartedAtMs = 0L
    private var clipsSummaryCheckedAt = 0L
    private var cachedClipsSubtitle: String? = null

    fun state(): TvUiState {
        val now = System.currentTimeMillis()
        val guide = activity.guidePrograms
        val firstGuideSnapshot = !guideSnapshotInitialized
        guideSnapshotInitialized = true
        updateGuideSelection(guide, now)
        val recordingActive = runCatching { activity.isRecording() }.getOrDefault(false)
        val recordingElapsedMs = if (recordingActive) {
            runCatching { activity.recordingElapsedMs() ?: 0L }.getOrDefault(0L)
        } else {
            0L
        }
        val clipsSummaryText = clipsSubtitle(now)
        val guideUiSnapshot = guideUiSelection
        return TvUiState(
            playback = playbackState(),
            channel = channel,
            channels = channels,
            program = guideUiSnapshot.airing,
            nextProgram = guideUiSnapshot.next,
            upcoming = guideUiSnapshot.upcoming,
            guideLoading = activity.guideLoading || firstGuideSnapshot ||
                (guideMappingPending && computedAtMs == 0L),
            guideError = activity.guideLoadError,
            recording = TvRecordingUi(
                active = recordingActive,
                elapsedMs = recordingElapsedMs,
            ),
            clock = clock(),
            clipsSubtitle = clipsSummaryText,
        )
    }

    private fun updateGuideSelection(guide: List<TvProgram>, nowMs: Long) {
        val guideChanged = guide !== guideReference
        synchronized(guideMappingLock) {
            if (guideChanged) {
                guideReference = guide
                computedAtMs = 0L
                guideUiSelection = HomeGuideUiSelection(null, null, emptyList())
                guideMappingGeneration++
                guideMappingWorker?.interrupt()
            }
            if (guideMappingClosed || guideMappingWorker != null || guideMappingPending ||
                (!guideChanged && nowMs - computedAtMs < RECOMPUTE_MS)
            ) return

            val generation = ++guideMappingGeneration
            guideMappingPending = true
            val sourceRef = WeakReference(this)
            val selectionLimit = railLimit
            val worker = Thread(
                {
                    try {
                        val selection = selectHomeGuideUi(guide, System.currentTimeMillis(), selectionLimit)
                        if (!Thread.currentThread().isInterrupted) {
                            sourceRef.get()?.publishGuideSelection(generation, guide, selection)
                        }
                    } catch (_: Exception) {
                    } finally {
                        sourceRef.get()?.finishGuideSelection(Thread.currentThread())
                    }
                },
                "tv-home-guide-map",
            ).apply { isDaemon = true }
            guideMappingWorker = worker
            try {
                worker.start()
            } catch (_: RuntimeException) {
                guideMappingWorker = null
                guideMappingPending = false
            }
        }
    }

    private fun publishGuideSelection(
        generation: Long,
        guide: List<TvProgram>,
        selection: HomeGuideUiSelection,
    ) {
        synchronized(guideMappingLock) {
            if (guideMappingClosed || generation != guideMappingGeneration || guide !== guideReference) return
            guideUiSelection = selection
            computedAtMs = System.currentTimeMillis()
        }
    }

    private fun finishGuideSelection(worker: Thread) {
        synchronized(guideMappingLock) {
            if (guideMappingWorker === worker) {
                guideMappingWorker = null
                guideMappingPending = false
            }
        }
    }

    private fun clipsSubtitle(nowMs: Long): String? {
        if (clipsSummaryCheckedAt == 0L || nowMs - clipsSummaryCheckedAt >= RECOMPUTE_MS) {
            clipsSummaryCheckedAt = nowMs
            val library = runCatching { activity.recordingLibrarySummary() }.getOrNull()
            val schedule = activity.menuScreenScheduleSummary(nowMs, null, clipsScheduleState)
            requestClipsStorageRefresh()
            val storageText = cachedClipsStorageText
            val lines = buildList {
                if (!schedule.available) {
                    if (library != null) add(activity.getString(R.string.tv_home_clips_schedule_unavailable))
                } else {
                    val recordingAt = schedule.nextRecordingStartMs
                    val reminderAt = schedule.nextReminderStartMs
                    when {
                        recordingAt != null && reminderAt != null -> {
                            if (recordingAt <= reminderAt) {
                                add(activity.getString(R.string.tv_home_clips_next_recording_time, formatHomeTime(Date(recordingAt))))
                                add(activity.getString(R.string.tv_home_clips_next_reminder_time, formatHomeTime(Date(reminderAt))))
                            } else {
                                add(activity.getString(R.string.tv_home_clips_next_reminder_time, formatHomeTime(Date(reminderAt))))
                                add(activity.getString(R.string.tv_home_clips_next_recording_time, formatHomeTime(Date(recordingAt))))
                            }
                        }
                        recordingAt != null ->
                            add(activity.getString(R.string.tv_home_clips_next_recording_time, formatHomeTime(Date(recordingAt))))
                        reminderAt != null ->
                            add(activity.getString(R.string.tv_home_clips_next_reminder_time, formatHomeTime(Date(reminderAt))))
                    }
                }
                library?.let { (savedCount, savedBytes) ->
                    add(
                        if (savedCount > 0) {
                            activity.getString(R.string.tv_home_clips_library_summary, savedCount, formatBytes(savedBytes))
                        } else {
                            activity.getString(R.string.tv_home_clips_empty_library)
                        },
                    )
                }
                if (schedule.available) {
                    if (schedule.totalRecordings > 0) {
                        add(
                            activity.getString(
                                R.string.tv_home_clips_scheduled_recordings,
                                schedule.activeRecordings,
                                schedule.upcomingRecordings,
                                "",
                            ),
                        )
                    }
                    if (schedule.totalReminders > 0) {
                        add(
                            activity.getString(
                                R.string.tv_home_clips_scheduled_reminders,
                                schedule.activeReminders,
                                schedule.upcomingReminders,
                                "",
                            ),
                        )
                    }
                    if (schedule.totalRecordings == 0 && schedule.totalReminders == 0) {
                        add(activity.getString(R.string.tv_home_clips_schedule_empty))
                    }
                }
                storageText?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            cachedClipsSubtitle = lines.joinToString(" · ").takeIf(String::isNotBlank)
        }
        return cachedClipsSubtitle
    }

    private fun requestClipsStorageRefresh() {
        val elapsed = SystemClock.elapsedRealtime()
        synchronized(clipsStorageLock) {
            if (clipsStorageLookupClosed || clipsStorageLookupPending ||
                (clipsStorageLookupStartedAtMs > 0L && elapsed - clipsStorageLookupStartedAtMs < CLIPS_STORAGE_REFRESH_MS)
            ) return
            val reader = runCatching { activity.menuScreenStorageReader() }.getOrNull() ?: return
            clipsStorageLookupStartedAtMs = elapsed.coerceAtLeast(1L)
            clipsStorageLookupPending = true
            val sourceRef = WeakReference(this)
            val worker = Thread(
                {
                    try {
                        if (!Thread.currentThread().isInterrupted) {
                            val text = runCatching { reader.read() }.getOrNull()
                            if (!Thread.currentThread().isInterrupted) {
                                sourceRef.get()?.publishClipsStorageText(text)
                            }
                        }
                    } finally {
                        sourceRef.get()?.finishClipsStorageWorker(Thread.currentThread())
                    }
                },
                "tv-home-storage",
            ).apply { isDaemon = true }
            clipsStorageWorker = worker
            worker.start()
        }
    }

    fun stopClipsStorageLookup() {
        synchronized(clipsStorageLock) {
            clipsStorageLookupClosed = true
            clipsStorageWorker?.interrupt()
            clipsStorageWorker = null
            clipsStorageLookupPending = false
        }
    }

    fun stopHomeWorkers() {
        stopClipsStorageLookup()
        synchronized(guideMappingLock) {
            guideMappingClosed = true
            guideMappingGeneration++
            guideMappingWorker?.interrupt()
            guideMappingWorker = null
            guideMappingPending = false
        }
    }

    private fun publishClipsStorageText(text: String?) {
        synchronized(clipsStorageLock) {
            if (!clipsStorageLookupClosed) cachedClipsStorageText = text
        }
    }

    private fun finishClipsStorageWorker(worker: Thread) {
        synchronized(clipsStorageLock) {
            if (clipsStorageWorker === worker) {
                clipsStorageWorker = null
                clipsStorageLookupPending = false
            }
        }
    }

    private fun playbackState(): TvPlaybackState {
        if (!activity.isPlayerInitialized) return TvPlaybackState.Idle
        if (activity.retryPending) {
            val remainingMs = (activity.retryAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val seconds = ((remainingMs + 999L) / 1_000L).toInt().coerceAtLeast(0)
            return TvPlaybackState.Reconnecting(
                attempt = activity.retryAttempts.coerceAtLeast(0),
                secondsToNextRetry = seconds,
            )
        }
        if (activity.networkWasLost) return TvPlaybackState.Offline
        return when (activity.player.playbackState) {
            Player.STATE_BUFFERING -> TvPlaybackState.Buffering
            Player.STATE_READY -> TvPlaybackState.Playing
            Player.STATE_ENDED -> TvPlaybackState.Idle
            else -> TvPlaybackState.Idle
        }
    }

    private fun formatHomeTime(date: Date): String {
        val preference = runCatching {
            activity.mainPrefs.getString(
                MainActivity.KEY_CLOCK_FORMAT,
                MainActivity.CLOCK_FORMAT_12H,
            )
        }.getOrNull() ?: MainActivity.CLOCK_FORMAT_12H
        return formatClockTime(date, preference)
    }

    private fun clock(): String {
        val visible = runCatching {
            activity.mainPrefs.safeBoolean(MainActivity.KEY_SHOW_CLOCK, true)
        }.getOrDefault(true)
        return if (visible) formatHomeTime(Date()) else ""
    }

    private companion object {
        const val RECOMPUTE_MS = 5_000L
        const val CLIPS_STORAGE_REFRESH_MS = 60_000L
    }
}
