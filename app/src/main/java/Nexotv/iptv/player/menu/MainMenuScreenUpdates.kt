// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenUpdates.kt
// Actualización de la interfaz del menú principal a partir de la instantánea de datos.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.guide.*
import Nexotv.iptv.player.activity.PermissionFlows
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import java.util.Locale

/** Datos de agenda y grabación leídos para una actualización del menú. */
internal data class MenuSnapshot(
    val schedule: MenuScreenScheduleSummary,
    val recording: Boolean,
    val recordingStartedAt: Long,
    val librarySummary: Pair<Int, Long>?,
    val nextBoundaryMs: Long?,
) {
    var libraryReadAt: Long = 0L
}

// Carrusel e ilustración del hero
private const val MENU_CAROUSEL_INTERVAL_MS = 5_000L
private const val MENU_ARTWORK_KEY_EPG_PREFIX = "epg"
private const val MENU_ARTWORK_KEY_CAROUSEL = "carrusel"
private const val MENU_ARTWORK_KEY_DEFAULT = "defecto"

// Hero
private const val MENU_HERO_PROGRESS_MIN_DP = 8
private const val MENU_HERO_PROGRESS_MIN_FRACTION = 0.02f
private const val MENU_HERO_PROGRESS_MAX_FRACTION = 1f

private fun TextView.menuScreenUpdateText(value: CharSequence?) {
    setTextIfChanged(value)
}

private fun MainActivity.menuScreenUpdateArtwork(
    ui: MenuScreenViews,
    s: MenuScreenSnapshot,
    state: MenuScreenState
) {
    val image = ui.hero.studioImage
    val airingProgram = s.airing?.takeUnless { recordingPlayback }
    val artworkUrl = airingProgram?.iconUrl
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    // La clave evita recargar la misma ilustración en cada tick del refresco
    if (airingProgram != null && artworkUrl != null) {
        val key = "$MENU_ARTWORK_KEY_EPG_PREFIX|${airingProgram.channelId}|${airingProgram.startMs}|$artworkUrl"
        if (state.artworkKey == key) return
        image.menuScreenResetImageAnimation()
        loadEpgProgramArtwork(image, airingProgram, currentUserAgent(), R.drawable.guide_placeholder)
        state.artworkKey = key
        return
    }

    if (ui.guideRow.view.hasFocus() && MENU_CAROUSEL_IMAGES.isNotEmpty()) {
        state.artworkKey = MENU_ARTWORK_KEY_CAROUSEL
        val imageIndex = ((SystemClock.elapsedRealtime() / MENU_CAROUSEL_INTERVAL_MS) % MENU_CAROUSEL_IMAGES.size).toInt()
        image.showCarouselImage(imageIndex)
        return
    }

    if (state.artworkKey == MENU_ARTWORK_KEY_DEFAULT) return
    image.menuScreenShowStatic(R.drawable.guide_placeholder)
    state.artworkKey = MENU_ARTWORK_KEY_DEFAULT
}

private fun MainActivity.menuScreenUpdateRowSubtitles(
    ui: MenuScreenViews,
    s: MenuScreenSnapshot,
    fmt: MenuScreenFormats
) {
    val airing = s.airing
    val next = s.next
    val count = s.programCount

    ui.liveTitle.menuScreenUpdateText(airing?.title?.takeIf(String::isNotBlank) ?: getString(R.string.tv_menu_live_title))
    ui.liveSubtitle.menuScreenUpdateText(if (airing != null) {
        getString(R.string.tv_menu_live_program_format, fmt.time(airing.startMs), fmt.time(airing.endMs), BuildConfig.CHANNEL_NAME)
    } else {
        getString(R.string.tv_menu_channel_state_format, BuildConfig.CHANNEL_NAME, getString(s.playState.labelRes))
    })

    ui.liveRow.subtitle?.menuScreenUpdateText(when {
        recordingPlayback -> getString(R.string.tv_menu_recording_playback)
        airing != null -> listOfNotNull(
            getString(s.playState.labelRes),
            airing.title.takeIf(String::isNotBlank),
            getString(R.string.tv_menu_program_time_range, fmt.time(airing.startMs), fmt.time(airing.endMs)),
        ).joinToString(" · ")
        else -> getString(R.string.tv_menu_destination_program_format, BuildConfig.BRAND_NAME, BuildConfig.CHANNEL_NAME, getString(s.playState.labelRes))
    })

    ui.guideRow.subtitle?.menuScreenUpdateText(when {
        s.guideLoading -> getString(R.string.tv_menu_guide_loading_count, count)
        count == 0 && s.hasLoadError -> getString(R.string.tv_menu_guide_empty_error)
        count == 0 && !s.online -> getString(R.string.tv_menu_guide_empty_offline)
        count == 0 -> getString(R.string.tv_menu_guide_empty_hint)
        s.hasLoadError -> getString(R.string.tv_menu_guide_count_error, count)
        !s.online -> getString(R.string.tv_menu_guide_count_offline, count)
        next != null -> listOf(
            getString(R.string.tv_menu_guide_count, count),
            if (next.title.isNotBlank()) getString(R.string.tv_menu_guide_next_named, next.title, fmt.time(next.startMs)) else getString(R.string.tv_menu_guide_next_unnamed, fmt.time(next.startMs)),
        ).joinToString(" · ")
        else -> {
            if (s.epgUpdatedAt > 0L) getString(R.string.tv_menu_guide_no_next_updated, count, fmt.time(s.epgUpdatedAt))
            else getString(R.string.tv_menu_guide_no_next, count)
        }
    })

    val recentSearchResult = runCatching { recentGuideSearches().firstOrNull(String::isNotBlank) }
        .onFailure { menuScreenReport("busquedasRecientes", it) }
    ui.searchRow.subtitle?.menuScreenUpdateText(when {
        recentSearchResult.isFailure -> getString(R.string.tv_menu_search_unavailable)
        recentSearchResult.getOrNull() != null -> getString(R.string.tv_menu_search_recent, recentSearchResult.getOrNull())
        s.searchableCount > 0 -> getString(R.string.tv_menu_search_empty_count, s.searchableCount)
        else -> getString(R.string.tv_menu_search_empty)
    })

    ui.moreRow.subtitle?.menuScreenUpdateText(getString(
        R.string.tv_menu_connection_format,
        getString(s.playState.labelRes),
        getString(if (s.online) R.string.tv_menu_internet_available else R.string.tv_menu_internet_unavailable),
    ))
}

@androidx.media3.common.util.UnstableApi
private fun MainActivity.menuScreenUpdateStreamInfo(ui: MenuScreenViews, s: MenuScreenSnapshot) {
    val videoWidth = videoSize.width.takeIf { it > 0 } ?: (player.videoFormat?.width ?: 0)
    val videoHeight = videoSize.height.takeIf { it > 0 } ?: (player.videoFormat?.height ?: 0)
    val resolution = if (videoWidth > 0 && videoHeight > 0) "${videoWidth}×$videoHeight" else null
    val audioLanguage = menuScreenGuardOr("idiomaAudio", null as String?) {
        val selectedTrackLanguage = player.currentTracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).asSequence()
                .filter(group::isTrackSelected)
                .mapNotNull { index -> group.getTrackFormat(index).language }
            }
            .firstOrNull(String::isNotBlank)
        (selectedTrackLanguage ?: player.audioFormat?.language)
            ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
            ?.let { Locale.forLanguageTag(it).displayLanguage }
            ?.takeIf(String::isNotBlank)
            ?.menuScreenCapitalized()
    }
    val streamParts = listOfNotNull(getString(s.playState.labelRes), resolution, audioLanguage)
    ui.hero.channelInfo.menuScreenUpdateText(menuScreenDotted(
        getString(R.string.tv_menu_stream_info, streamParts.joinToString(" · ")),
        getColor(R.color.tv_live),
        MenuScreenPalette.textSoft
    ))

    var subtitleTracksAvailable = true
    val textGroups = try {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    } catch (e: Exception) {
        menuScreenReport("pistasTexto", e)
        subtitleTracksAvailable = false
        emptyList()
    }
    val subtitleLanguage = try {
        textGroups.firstOrNull()?.getTrackFormat(0)?.language?.uppercase()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        menuScreenReport("idiomaSubtitulos", e)
        null
    }
    if (subtitleTracksAvailable) {
        ui.hero.subtitlesBadge.menuScreenUpdateText(when {
            textGroups.isEmpty() -> getString(R.string.tv_menu_subtitles_missing)
            subtitleLanguage != null -> getString(R.string.tv_menu_subtitles_language, subtitleLanguage)
            else -> getString(R.string.tv_menu_subtitles_unknown)
        })
        ui.hero.subtitlesBadge.setVisibilityIfChanged(View.VISIBLE)
    } else {
        ui.hero.subtitlesBadge.setVisibilityIfChanged(View.GONE)
    }
    (ui.hero.subtitlesBadge.parent as? View)?.setVisibilityIfChanged(
        if (subtitleTracksAvailable) View.VISIBLE else View.GONE
    )
}

/** Textos cortos a propósito: la píldora vive en la fila de pestañas y comparte ancho con ellas. */
private fun MainActivity.menuScreenUpdateGuideStatus(
    ui: MenuScreenViews,
    s: MenuScreenSnapshot,
    fmt: MenuScreenFormats
) {
    val warning = getColor(R.color.tv_warning)
    val count = s.programCount
    val pill = when {
        !s.online -> MenuScreenPillState(MenuScreenPalette.error, getString(R.string.tv_menu_status_offline), "!", MenuScreenPalette.error)
        s.guideLoading -> MenuScreenPillState(warning, getString(R.string.tv_menu_status_loading), getString(R.string.tv_menu_status_loading_icon), warning)
        s.hasLoadError -> MenuScreenPillState(
            warning,
            getString(if (count > 0) R.string.tv_menu_status_guide_update_failed_count else R.string.tv_menu_status_guide_error),
            "!",
            warning
        )
        count > 0 -> MenuScreenPillState(
            MenuScreenPalette.textSoft,
            if (s.epgUpdatedAt > 0L) getString(R.string.tv_menu_status_guide_updated, fmt.time(s.epgUpdatedAt)) else getString(R.string.tv_menu_status_guide_available),
            count.toString(),
            getColor(R.color.tv_success)
        )
        else -> MenuScreenPillState(warning, getString(R.string.tv_menu_status_no_programs), "0", warning)
    }
    val statusIconTint = ColorStateList.valueOf(pill.iconTint)
    if (ui.topBar.statusIcon.imageTintList != statusIconTint) {
        ui.topBar.statusIcon.imageTintList = statusIconTint
    }
    ui.topBar.statusText.menuScreenUpdateText(pill.text)
    ui.topBar.statusBadge.menuScreenUpdateText(pill.badge)
    if (ui.topBar.statusBadge.currentTextColor != pill.badgeColor) {
        ui.topBar.statusBadge.setTextColor(pill.badgeColor)
    }
}

private fun menuScreenSetProgressWidth(hero: MenuScreenHero, widthPx: Int) {
    val safeWidth = widthPx.coerceAtLeast(0)
    val params = hero.progressFill.layoutParams
    if (params == null) {
        hero.progressFill.layoutParams = FrameLayout.LayoutParams(safeWidth, MENU_MATCH)
        return
    }
    if (params.width == safeWidth && params.height == MENU_MATCH) return
    params.width = safeWidth
    params.height = MENU_MATCH
    hero.progressFill.layoutParams = params
}

private fun MainActivity.menuScreenUpdateHero(
    ui: MenuScreenViews,
    s: MenuScreenSnapshot
) {
    val hero = ui.hero
    val airing = s.airing
    val previousLiveBadge = hero.liveBadge.text.toString()

    if (airing != null) {
        hero.liveBadge.menuScreenUpdateText(getString(s.playState.airingBadgeRes))
        hero.title.menuScreenUpdateText(airing.title.takeIf(String::isNotBlank) ?: getString(R.string.tv_menu_hero_brand_live, BuildConfig.BRAND_NAME))
    } else {
        hero.liveBadge.menuScreenUpdateText(getString(s.playState.idleBadgeRes))
        hero.title.menuScreenUpdateText(getString(if (recordingPlayback) R.string.tv_menu_hero_recording else R.string.tv_menu_hero_brand_live, BuildConfig.BRAND_NAME))
    }

    val updatedLiveBadge = hero.liveBadge.text.toString()
    if (previousLiveBadge != updatedLiveBadge && hero.liveBadge.isAttachedToWindow) {
        hero.liveBadge.post {
            if (hero.liveBadge.isAttachedToWindow && hero.liveBadge.text.toString() == updatedLiveBadge) {
                animateTvFocusShine(hero.liveBadge, cornerRadiusDp = 6f)
            }
        }
    }
}

private fun menuScreenUpdateNextCard(
    ui: MenuScreenViews,
    s: MenuScreenSnapshot,
    fmt: MenuScreenFormats,
    schedule: MenuScreenScheduleSummary,
    actions: MenuScreenActions,
) {
    val next = s.next
    val card = ui.nextCard
    if (next != null) {
        card.title.menuScreenUpdateText(buildList {
            next.title.takeIf(String::isNotBlank)?.let(::add)
            add(fmt.time(next.startMs))
        }.joinToString(" · "))
        val status = buildList {
            if (schedule.nextHasRecording) add(ui.root.context.getString(R.string.tv_menu_scheduled_recording))
            add(ui.root.context.getString(if (schedule.nextHasReminder) R.string.tv_menu_reminder_active else R.string.tv_menu_reminder_available))
        }.joinToString(" · ")
        card.subtitle.menuScreenUpdateText(listOfNotNull(next.category.takeIf(String::isNotBlank), status.takeIf(String::isNotBlank)).joinToString(" · "))
        ui.btnRemind.menuScreenUpdateText(ui.root.context.getString(actions.reminder.labelRes))
        ui.btnRemind.isEnabled = actions.reminder.available
        return
    }

    when {
        !s.online -> {
            card.title.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_offline_title))
            card.subtitle.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_offline_subtitle))
        }
        s.guideLoading -> {
            card.title.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_loading_title))
            card.subtitle.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_loading_subtitle))
        }
        s.hasLoadError -> {
            card.title.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_error_title))
            card.subtitle.menuScreenUpdateText(s.loadError)
        }
        s.programCount == 0 -> {
            card.title.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_empty_guide_title))
            card.subtitle.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_empty_guide_subtitle))
        }
        else -> {
            card.title.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_empty_title))
            card.subtitle.menuScreenUpdateText(ui.root.context.getString(R.string.tv_menu_next_empty_subtitle))
        }
    }
    ui.btnRemind.menuScreenUpdateText(ui.root.context.getString(actions.reminder.labelRes))
    ui.btnRemind.isEnabled = actions.reminder.available
}

private fun menuScreenScheduleSummaryFromPrograms(
    now: Long,
    next: TvProgram?,
    programs: List<ScheduledProgram>,
): MenuScreenScheduleSummary {
    return try {
        var activeRecordings = 0
        var upcomingRecordings = 0
        var activeReminders = 0
        var upcomingReminders = 0
        var nextRecordingStartMs: Long? = null
        var nextReminderStartMs: Long? = null
        var nextHasRecording = false
        var nextHasReminder = false
        for (item in programs) {
            val program = item.program
            if (program.endMs <= now) continue
            val active = program.startMs <= now
            if (item.recording) {
                if (active) activeRecordings++ else {
                    upcomingRecordings++
                    val candidate = nextRecordingStartMs
                    if (candidate == null || program.startMs < candidate) nextRecordingStartMs = program.startMs
                }
            }
            if (item.reminder) {
                if (active) activeReminders++ else {
                    upcomingReminders++
                    val candidate = nextReminderStartMs
                    if (candidate == null || program.startMs < candidate) nextReminderStartMs = program.startMs
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
            nextHasReminder = nextHasReminder,
        )
    } catch (e: Exception) {
        menuScreenReport("agenda", e)
        MenuScreenScheduleSummary()
    }
}

internal fun MainActivity.menuScreenReadSnapshot(
    now: Long,
    next: TvProgram?,
): MenuSnapshot {
    val scheduledPrograms = ProgramSchedule.all(applicationContext).toList()
    val schedule = menuScreenScheduleSummaryFromPrograms(now, next, scheduledPrograms)
    val scheduleBoundary = scheduledPrograms.asSequence().mapNotNull { item ->
        when {
            item.program.startMs > now -> item.program.startMs
            item.program.endMs > now -> item.program.endMs
            else -> null
        }
    }.minOrNull()
    val recording = menuScreenGuardOr("estaGrabando", false) { isRecording() }
    val startedAt = if (recording) menuScreenGuardOr("inicioGrabacion", 0L) {
        menuScreenPrefs().getLong("recording_started_at", 0L)
    } else 0L
    val summary = try {
        recordingLibrarySummary()
    } catch (e: Exception) {
        menuScreenReport("bibliotecaGrabaciones", e)
        null
    }
    return MenuSnapshot(schedule, recording, startedAt, summary, scheduleBoundary).also {
        it.libraryReadAt = recordingSummaryUpdatedAt
    }
}

private fun menuScreenRecordingRowText(snapshot: MenuSnapshot, elapsed: String?, context: android.content.Context): String {
    val schedule = snapshot.schedule
    val recording = snapshot.recording
    val summary = snapshot.librarySummary
    if (summary == null) {
        val libraryLine = context.getString(R.string.tv_menu_library_unavailable)
        return if (recording) listOfNotNull(context.getString(R.string.tv_menu_recording_short_label), elapsed, context.getString(R.string.tv_menu_recording_library_unavailable)).joinToString(" · ") else libraryLine
    }
    if (snapshot.libraryReadAt == 0L) {
        return if (recording) context.getString(R.string.tv_menu_recording_loading_library, elapsed.orEmpty(), context.getString(R.string.tv_menu_recording_library_loading)) else context.getString(R.string.tv_menu_library_loading)
    }
    val (savedCount) = summary
    return when {
        recording -> listOf(context.getString(R.string.tv_menu_recording_short_label), elapsed, if (savedCount > 0) context.getString(R.string.tv_menu_recording_library_count, savedCount) else context.getString(R.string.tv_menu_recording_library_no_saved))
            .filterNotNull().joinToString(" · ")
        !schedule.available -> context.getString(R.string.tv_menu_schedule_unavailable)
        savedCount == 0 && schedule.totalRecordings == 0 && schedule.totalReminders == 0 -> context.getString(R.string.tv_menu_recording_status_empty)
        savedCount == 0 -> context.getString(R.string.tv_menu_recording_summary_empty, schedule.totalRecordings, schedule.totalReminders)
        else -> context.getString(R.string.tv_menu_recording_summary_saved, savedCount, schedule.totalRecordings, schedule.totalReminders)
    }
}

private fun MainActivity.menuScreenApplyRecordingSnapshot(
    ui: MenuScreenViews,
    fmt: MenuScreenFormats,
    state: MenuScreenState,
    snapshot: MenuSnapshot
) {
    val schedule = snapshot.schedule
    val recording = snapshot.recording
    val summary = snapshot.librarySummary
    val libraryLine: String
    if (summary == null) {
        libraryLine = ui.root.context.getString(R.string.tv_menu_library_unavailable)
    } else if (snapshot.libraryReadAt == 0L) {
        libraryLine = ui.root.context.getString(R.string.tv_menu_library_loading)
    } else {
        val (savedCount, savedBytes) = summary
        libraryLine = if (savedCount > 0) ui.root.context.getString(R.string.tv_menu_library_count_bytes, savedCount, formatBytes(savedBytes)) else ui.root.context.getString(R.string.tv_menu_library_empty)
    }

    val scheduleLines = buildList {
        if (schedule.totalRecordings > 0) {
            val nextTime = schedule.nextRecordingStartMs?.let { ui.root.context.getString(R.string.tv_menu_schedule_time_suffix, fmt.time(it)) }.orEmpty()
            add(ui.root.context.getString(R.string.tv_menu_schedule_recordings, schedule.activeRecordings, schedule.upcomingRecordings, nextTime))
        }
        if (schedule.totalReminders > 0) {
            val nextTime = schedule.nextReminderStartMs?.let { ui.root.context.getString(R.string.tv_menu_schedule_time_suffix, fmt.time(it)) }.orEmpty()
            add(ui.root.context.getString(R.string.tv_menu_schedule_reminders, schedule.activeReminders, schedule.upcomingReminders, nextTime))
        }
    }

    val storageText = menuScreenStorageText(state)
    ui.recCard.title.menuScreenUpdateText(storageText.orEmpty())
    ui.recCard.title.setVisibilityIfChanged(if (storageText.isNullOrBlank()) View.GONE else View.VISIBLE)
    val agendaLine = when {
        !schedule.available -> ui.root.context.getString(R.string.tv_menu_schedule_unavailable)
        scheduleLines.isEmpty() -> ui.root.context.getString(R.string.tv_menu_schedule_empty)
        else -> null
    }
    ui.recCard.subtitle.menuScreenUpdateText((listOf(libraryLine) + (scheduleLines.ifEmpty { listOfNotNull(agendaLine) }))
        .joinToString("\n"))
    ui.btnRecordNow.menuScreenUpdateText(ui.root.context.getString(state.actions.recording.labelRes))
    ui.btnRecordNow.isEnabled = state.actions.recording.available
}

internal fun MainActivity.menuScreenApplyClock(
    ui: MenuScreenViews,
    formats: MenuScreenFormats,
    state: MenuScreenState,
    now: Long,
) {
    val minuteKey = now / 60_000L
    if (state.clockMinuteKey != minuteKey) {
        state.clockTextCache = formats.dateTime(now)
        state.clockMinuteKey = minuteKey
    }
    val clockText = state.clockTextCache.orEmpty()
    ui.topBar.clockText.setTextIfChanged(clockText)
    ui.topBar.clockPill.setVisibilityIfChanged(
        if (ui.topBar.clockEnabled && clockText.isNotBlank()) View.VISIBLE else View.GONE
    )

    val general = state.lastGeneralSnapshot
    if (general == null) {
        val initialProgressWidthPx = dp(MENU_HERO_PROGRESS_TRACK_WIDTH_DP)
        if (state.progressWidthPx != initialProgressWidthPx) {
            menuScreenSetProgressWidth(ui.hero, initialProgressWidthPx)
            state.progressWidthPx = initialProgressWidthPx
        }
        return
    }
    val recording = state.lastMenuSnapshot
    val airing = general.airing
    val next = general.next
    val currentProgram = airing != null
    val isLive = airing?.isAiringAt(now) == true
    val heroSource = airing ?: next
    if (state.heroSubtitleTextCache == null ||
        state.heroSubtitleSource != heroSource ||
        state.heroSubtitleCurrentProgram != currentProgram ||
        state.heroSubtitleLive != isLive
    ) {
        state.heroSubtitleTextCache = if (airing != null) {
            val range = ui.root.context.getString(R.string.tv_menu_live_time_range, formats.time(airing.startMs), formats.time(airing.endMs))
            val durationMinutes = ((airing.endMs - airing.startMs).coerceAtLeast(0L) / 60_000L)
            buildList {
                add(ui.root.context.getString(R.string.tv_menu_program_duration, range, durationMinutes))
                airing.category.takeIf(String::isNotBlank)?.let(::add)
                if (isLive) add(ui.root.context.getString(R.string.tv_menu_status_live))
            }.joinToString(" · ")
        } else if (next != null && next.title.isNotBlank()) {
            ui.root.context.getString(R.string.tv_menu_next_program_format, next.title, formats.time(next.startMs))
        } else {
            ui.root.context.getString(R.string.tv_menu_no_more_schedule)
        }
        state.heroSubtitleSource = heroSource
        state.heroSubtitleCurrentProgram = currentProgram
        state.heroSubtitleLive = isLive
    }
    ui.hero.subtitle.setTextIfChanged(state.heroSubtitleTextCache.orEmpty())

    val trackWidth = ui.hero.progressTrack.width
    val nextProgressWidthPx: Int
    if (airing != null && trackWidth > 0) {
        val duration = (airing.endMs - airing.startMs).coerceAtLeast(1L)
        val fraction = ((now - airing.startMs).toFloat() / duration.toFloat())
            .coerceIn(MENU_HERO_PROGRESS_MIN_FRACTION, MENU_HERO_PROGRESS_MAX_FRACTION)
        val minProgressPx = dp(MENU_HERO_PROGRESS_MIN_DP)
        nextProgressWidthPx = (trackWidth * fraction).toInt().coerceAtLeast(minProgressPx)
    } else {
        nextProgressWidthPx = dp(MENU_HERO_PROGRESS_TRACK_WIDTH_DP)
    }
    if (state.progressWidthPx != nextProgressWidthPx) {
        menuScreenSetProgressWidth(ui.hero, nextProgressWidthPx)
        state.progressWidthPx = nextProgressWidthPx
    }

    if (recording != null) {
        val elapsedSecond = if (recording.recording) {
            ((now - recording.recordingStartedAt).coerceAtLeast(0L) / 1_000L)
        } else null
        if (state.recordingRowSnapshot !== recording ||
            (recording.recording && state.recordingRowElapsedSecond != elapsedSecond)
        ) {
            val elapsed = if (recording.recording) {
                formats.recordingElapsed(recording.recordingStartedAt, now)
            } else null
            state.recordingRowTextCache = menuScreenRecordingRowText(recording, elapsed, ui.root.context)
            state.recordingRowSnapshot = recording
            state.recordingRowElapsedSecond = elapsedSecond
        }
        state.recordingRowTextCache?.let { ui.recordingsRow.subtitle?.setTextIfChanged(it) }
    }
}

internal fun MainActivity.menuScreenRefresh(
    ui: MenuScreenViews,
    state: MenuScreenState,
    fmt: MenuScreenFormats,
    s: MenuScreenSnapshot,
    dataSnapshot: MenuSnapshot,
) {
    state.nextProgram = s.next
    state.actions = menuScreenActionsFor(
        hasNextProgram = s.next != null,
        reminderExists = dataSnapshot.schedule.nextHasReminder,
        exactAlarmAllowed = PermissionFlows.canScheduleExactAlarms(this),
        recording = dataSnapshot.recording,
        agendaAvailable = dataSnapshot.schedule.available,
    )

    menuScreenGuard("ilustracion") { menuScreenUpdateArtwork(ui, s, state) }
    menuScreenGuard("subtitulosFilas") { menuScreenUpdateRowSubtitles(ui, s, fmt) }
    menuScreenGuard("infoSenal") { menuScreenUpdateStreamInfo(ui, s) }
    menuScreenGuard("estadoGuia") { menuScreenUpdateGuideStatus(ui, s, fmt) }
    menuScreenGuard("hero") { menuScreenUpdateHero(ui, s) }
    menuScreenGuard("aContinuacion") {
        menuScreenUpdateNextCard(ui, s, fmt, dataSnapshot.schedule, state.actions)
    }
    menuScreenGuard("grabacion") { menuScreenApplyRecordingSnapshot(ui, fmt, state, dataSnapshot) }
    s.nextBoundaryMs = listOfNotNull(s.nextBoundaryMs, dataSnapshot.nextBoundaryMs)
        .filter { it > s.now }
        .minOrNull()
    state.lastGeneralSnapshot = s
    state.lastMenuSnapshot = dataSnapshot
    menuScreenApplyClock(ui, fmt, state, System.currentTimeMillis())
}


