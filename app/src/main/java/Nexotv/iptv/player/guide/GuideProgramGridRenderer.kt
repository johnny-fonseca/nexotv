package Nexotv.iptv.player.guide

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.guide.GuideProgramUiStateIndex
import Nexotv.iptv.player.guide.GuideProgramSelection
import Nexotv.iptv.player.guide.GuideProgramFocusKey
import Nexotv.iptv.player.guide.GuideProgramFocus

import Nexotv.iptv.player.guide.GuideEmptyState
import Nexotv.iptv.player.guide.GuideRefreshBanner
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private const val GRID_ERROR_TAG = "GUIDE_ERROR"
private const val GRID_FALLBACK_TIME = "--:--"

private fun logGridFailure(scope: String, error: Throwable) {
    DiagnosticTracker.log(GRID_ERROR_TAG, "programGrid.$scope: ${error.javaClass.simpleName}")
}

private inline fun gridGuard(scope: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        logGridFailure(scope, e)
    }
}

internal data class GuideProgramRenderResult(
    val firstCard: View?,
    val liveCard: View?,
)

/** Renders program rows and owns their focus graph without owning screen state. */
internal class GuideProgramGridRenderer(
    private val activity: MainActivity,
    private val programsContainer: LinearLayout,
    private val programsScrollView: ScrollView,
    private val scheduleTab: View,
    private val savedTab: View,
    private val daysContainer: ViewGroup,
    private val reminderButton: View,
    private val bookmarkButton: View,
    private val recordingButton: View,
    private val prefs: SharedPreferences,
    private val timeFormat: DateFormat,
    private val onRefresh: () -> Unit,
    private val onProgramDetail: (TvProgram?) -> Unit,
    private val onProgramFocused: (View) -> Unit,
    private val onLiveSelected: () -> Unit,
    private val onRequestReminder: () -> Unit,
) {
    private data class GridContentKey(
        val activeListSize: Int,
        val activeListHash: Int,
        val savedProgramsRevision: Long,
        val scheduledProgramsRevision: Long,
        val selectedTab: Int,
        val selectedDayIndex: Int,
        val timeBucket: Long,
    )

    private var lastContentKey: GridContentKey? = null
    private var lastRenderResult: GuideProgramRenderResult? = null

    private fun isActivityGone(): Boolean = activity.isFinishing || activity.isDestroyed

    fun render(
        activeList: List<TvProgram>,
        selectedTab: Int,
        selectedDayIndex: Int,
        nowMs: Long,
    ): GuideProgramRenderResult {
        if (isActivityGone()) return GuideProgramRenderResult(null, null)
        return try {
            renderInternal(activeList, selectedTab, selectedDayIndex, nowMs)
        } catch (e: Exception) {
            logGridFailure("render", e)
            lastContentKey = null
            lastRenderResult = null
            GuideProgramRenderResult(programsContainer.getChildAt(0), null)
        }
    }

    private fun renderInternal(
        activeList: List<TvProgram>,
        selectedTab: Int,
        selectedDayIndex: Int,
        nowMs: Long,
    ): GuideProgramRenderResult {
        ensureNavigationIds()
        val renderList = if (activeList.size > MAX_RENDERED_PROGRAMS) {
            activeList.subList(0, MAX_RENDERED_PROGRAMS)
        } else {
            activeList
        }

        val nextAiringProgram = runCatching { GuideProgramSelection.nextAiring(renderList, nowMs) }.getOrNull()
        val savedSnapshot = runCatching { activity.guideProgramStore.savedProgramsSnapshot() }.getOrNull()
        val scheduledSnapshot = runCatching { ProgramSchedule.uiStateSnapshot(activity) }.getOrNull()
        val cacheable = savedSnapshot != null && scheduledSnapshot != null
        val contentKey = GridContentKey(
            activeListSize = renderList.size,
            activeListHash = renderList.hashCode(),
            savedProgramsRevision = savedSnapshot?.revision ?: -1L,
            scheduledProgramsRevision = scheduledSnapshot?.revision ?: -1L,
            selectedTab = selectedTab,
            selectedDayIndex = selectedDayIndex,
            timeBucket = nowMs / GRID_TIME_BUCKET_MS,
        )
        if (cacheable && contentKey == lastContentKey && programsContainer.childCount > 0) {
            val cached = lastRenderResult
            if (cached != null && isCacheStillValid(cached)) return cached
        }

        val focusedProgramKey = focusedProgramKey()
        gridGuard("animate") { animateMenuLayoutChange(programsContainer) }
        programsContainer.removeAllViews()

        if (renderList.isEmpty()) {
            renderEmptyState(selectedTab, selectedDayIndex)
            gridGuard("detail.empty") { onProgramDetail(null) }
            return GuideProgramRenderResult(null, null).also {
                if (cacheable) {
                    lastContentKey = contentKey
                    lastRenderResult = it
                } else {
                    lastContentKey = null
                    lastRenderResult = null
                }
            }
        }

        val result = renderProgramCards(
            renderList,
            nextAiringProgram,
            selectedTab,
            selectedDayIndex,
            nowMs,
            savedSnapshot?.programs ?: emptyList(),
            scheduledSnapshot?.programs ?: emptyList(),
        )
        renderRefreshBanner()
        val initialProgram = renderList.firstOrNull { it.startMs <= nowMs && it.endMs > nowMs } ?: renderList.first()
        gridGuard("detail.initial") { onProgramDetail(initialProgram) }
        result.liveCard?.let { liveCard ->
            programsScrollView.post {
                gridGuard("scrollLive") {
                    if (!isActivityGone()) {
                        programsScrollView.scrollGuideChildIntoTop(programsContainer, liveCard)
                    }
                }
            }
        }
        restoreFocusedProgram(focusedProgramKey, renderList, selectedTab, nowMs)
        if (cacheable) {
            lastContentKey = contentKey
            lastRenderResult = result
        } else {
            lastContentKey = null
            lastRenderResult = null
        }
        return result
    }

    private fun isCacheStillValid(cached: GuideProgramRenderResult): Boolean {
        val first = cached.firstCard
        val live = cached.liveCard
        return (first == null || first.parent === programsContainer) &&
            (live == null || live.parent === programsContainer)
    }

    private fun ensureNavigationIds() {
        scheduleTab.ensureGuideId()
        savedTab.ensureGuideId()
        reminderButton.ensureGuideId()
        bookmarkButton.ensureGuideId()
        recordingButton.ensureGuideId()
    }

    private fun focusedProgramKey(): GuideProgramFocusKey? = try {
        (0 until programsContainer.childCount)
            .asSequence()
            .mapNotNull { programsContainer.getChildAt(it) }
            .firstOrNull { it.hasFocus() }
            ?.tag as? GuideProgramFocusKey
    } catch (e: Exception) {
        logGridFailure("focusedKey", e)
        null
    }

    private fun renderEmptyState(selectedTab: Int, selectedDayIndex: Int) {
        val emptyBox = try {
            GuideEmptyState.create(activity, selectedTab == 0) {
                gridGuard("emptyRefresh") { onRefresh() }
            }
        } catch (e: Exception) {
            logGridFailure("emptyState", e)
            null
        }
        if (emptyBox != null) {
            programsContainer.addView(emptyBox, LinearLayout.LayoutParams(-1, -2))
        }
        val emptyAction = (emptyBox as? ViewGroup)?.getChildAt(2)
            ?.takeIf { it.isFocusable }
            ?.ensureGuideId()

        if (selectedTab == 0) {
            scheduleTab.nextFocusDownId = daysContainer.getChildAt(0)?.id ?: scheduleTab.id
            daysContainer.getChildAt(selectedDayIndex)?.nextFocusDownId = emptyAction ?: scheduleTab.id
            daysContainer.getChildAt(daysContainer.childCount - 1)?.nextFocusDownId = emptyAction ?: scheduleTab.id
        } else {
            savedTab.nextFocusDownId = emptyAction ?: savedTab.id
        }
    }

    private fun renderProgramCards(
        activeList: List<TvProgram>,
        nextAiringProgram: TvProgram?,
        selectedTab: Int,
        selectedDayIndex: Int,
        nowMs: Long,
        savedPrograms: List<TvProgram>,
        scheduledPrograms: List<ScheduledProgram>,
    ): GuideProgramRenderResult {
        var firstCard: View? = null
        var liveCard: View? = null
        val programStates = GuideProgramUiStateIndex(
            savedPrograms,
            scheduledPrograms,
        )

        for (program in activeList) {
            val card = try {
                val status = GuideProgramStatusResolver.resolve(program, nextAiringProgram, nowMs)
                val scheduled = programStates.schedule(program)
                GuideProgramCard.create(
                    activity = activity,
                    program = program,
                    status = status,
                    saved = programStates.isSaved(program),
                    reminder = scheduled?.reminder == true,
                    recording = scheduled?.recording == true,
                    nowMs = nowMs,
                    formatTime = {
                        try {
                            timeFormat.format(Date(it))
                        } catch (_: Exception) {
                            GRID_FALLBACK_TIME
                        }
                    },
                    onFocused = { focusedCard ->
                        gridGuard("onFocused") { onProgramFocused(focusedCard) }
                        gridGuard("onFocused.detail") { onProgramDetail(program) }
                    },
                    onSelected = { selectedIsLive, _ ->
                        gridGuard("onSelected.detail") { onProgramDetail(program) }
                        gridGuard("onSelected.action") {
                            if (selectedIsLive) onLiveSelected() else onRequestReminder()
                        }
                    },
                    onNavigateRight = { gridGuard("onNavigateRight") { onRequestReminder() } },
                ).apply {
                    ensureGuideId()
                    tag = GuideProgramFocus.key(program)
                } to (status == GuideProgramStatus.LIVE)
            } catch (e: Exception) {
                logGridFailure("card", e)
                null
            } ?: continue

            programsContainer.addView(card.first)
            if (firstCard == null) firstCard = card.first
            if (card.second) liveCard = card.first
        }

        configureProgramFocus(selectedTab, selectedDayIndex)
        return GuideProgramRenderResult(firstCard, liveCard)
    }

    private fun configureProgramFocus(selectedTab: Int, selectedDayIndex: Int) {
        for (index in 0 until programsContainer.childCount) {
            val card = programsContainer.getChildAt(index) ?: continue
            card.ensureGuideId()
            card.nextFocusUpId = programsContainer.getChildAt(index - 1)?.id
                ?: if (selectedTab == 0) daysContainer.getChildAt(selectedDayIndex)?.id ?: scheduleTab.id else savedTab.id
            card.nextFocusDownId = programsContainer.getChildAt(index + 1)?.id ?: card.id
            card.nextFocusLeftId = card.id
            card.nextFocusRightId = reminderButton.id
        }

        val firstCard = programsContainer.getChildAt(0)
        scheduleTab.nextFocusDownId = if (selectedTab == 0) {
            daysContainer.getChildAt(0)?.id ?: firstCard?.id ?: scheduleTab.id
        } else firstCard?.id ?: scheduleTab.id
        savedTab.nextFocusDownId = firstCard?.id ?: savedTab.id
        for (index in 0 until daysContainer.childCount) {
            daysContainer.getChildAt(index)?.nextFocusDownId = firstCard?.id ?: scheduleTab.id
        }
        daysContainer.getChildAt(daysContainer.childCount - 1)?.nextFocusDownId = firstCard?.id ?: scheduleTab.id
    }

    private fun renderRefreshBanner() {
        val lastCard = programsContainer.getChildAt(programsContainer.childCount - 1)
        val banner = try {
            val sourceUrl: String? = try {
                prefs.getString(MainActivity.KEY_EPG_URL, XmlTvGuide.DEFAULT_URL)
            } catch (_: Exception) {
                XmlTvGuide.DEFAULT_URL
            }
            val updatedAtMs = try {
                prefs.getLong(MainActivity.KEY_EPG_UPDATED, 0L)
            } catch (_: Exception) {
                0L
            }
            GuideRefreshBanner.create(
                activity = activity,
                sourceUrl = sourceUrl,
                updatedAtMs = updatedAtMs,
                onRefresh = { gridGuard("bannerRefresh") { onRefresh() } },
            ).apply { ensureGuideId() }
        } catch (e: Exception) {
            logGridFailure("banner", e)
            return
        }
        programsContainer.addView(banner)
        lastCard?.nextFocusDownId = banner.id
        banner.nextFocusUpId = lastCard?.id ?: banner.id
        banner.nextFocusDownId = recordingButton.id
        banner.nextFocusRightId = bookmarkButton.id
    }

    private fun restoreFocusedProgram(
        focusedKey: GuideProgramFocusKey?,
        activeList: List<TvProgram>,
        selectedTab: Int,
        nowMs: Long,
    ) {
        focusedKey ?: return
        programsContainer.post {
            gridGuard("restoreFocus") {
                if (isActivityGone() || !programsContainer.isAttachedToWindow) return@gridGuard
                val restoredProgram = GuideProgramFocus.restore(activeList, focusedKey, nowMs)
                val restoredKey = restoredProgram?.let { GuideProgramFocus.key(it) }
                val target: View = restoredKey?.let { key ->
                    (0 until programsContainer.childCount)
                        .asSequence()
                        .mapNotNull { programsContainer.getChildAt(it) }
                        .firstOrNull { it.tag == key }
                } ?: if (selectedTab == 0) scheduleTab else savedTab
                target.requestFocus()
            }
        }
    }

    private companion object {
        const val GRID_TIME_BUCKET_MS = 30_000L
        const val MAX_RENDERED_PROGRAMS = 1_000
    }
}

private fun View.ensureGuideId(): Int {
    if (id == View.NO_ID) id = View.generateViewId()
    return id
}

private fun ScrollView.scrollGuideChildIntoTop(container: ViewGroup, target: View) {
    if (!target.isAttachedToWindow || target.parent == null) return
    try {
        val bounds = android.graphics.Rect(0, 0, target.width, target.height)
        container.offsetDescendantRectToMyCoords(target, bounds)
        scrollTo(0, (container.top + bounds.top - paddingTop).coerceAtLeast(0))
    } catch (e: Exception) {
        logGridFailure("scrollIntoTop", e)
    }
}