@file:Suppress("PackageName")

package Nexotv.iptv.player.ui

import kotlin.math.ceil
import kotlin.math.roundToInt
import Nexotv.iptv.player.tv.TvProgram
import java.util.PriorityQueue

/** Pure home sizing calculation; callers supply visual policy from resources. */
internal data class TvHomeMetrics(
    val usableWidthDp: Int,
    val usableHeightDp: Int,
    val navWidthDp: Int,
    val contentWidthDp: Int,
    val heroRowHeightDp: Int,
    val railCardWidthDp: Int,
    val railCardHeightDp: Int,
    val railFocusRoomDp: Int,
    val railHeightDp: Int,
    val totalContentHeightDp: Int,
) {
    val skeletonWidthDp: Int get() = railCardWidthDp
    val skeletonHeightDp: Int get() = railCardHeightDp

    companion object {
        fun compute(
            widthDp: Int, heightDp: Int, safeMargin: Float, navWidthFraction: Float,
            heroHeightFraction: Float, railCardWidthFraction: Float, focusScale: Float,
            ringDp: Int, aspectWidth: Int, aspectHeight: Int, heroToRailGapDp: Int,
            railTitleHeightDp: Int, titleToCardGapDp: Int, heroMinimumHeightDp: Int = 0,
        ): TvHomeMetrics {
            val usableWidth = (widthDp * (1f - 2f * safeMargin)).roundToInt()
            val usableHeight = (heightDp * (1f - 2f * safeMargin)).roundToInt()
            val navWidth = (widthDp * navWidthFraction).roundToInt()
            val contentWidth = (usableWidth - navWidth - heroToRailGapDp).coerceAtLeast(1)
            val heroHeight = maxOf((usableHeight * heroHeightFraction).roundToInt(), heroMinimumHeightDp)
            val desiredCardWidth = (contentWidth * railCardWidthFraction).roundToInt()
            val availableRailCardHeight = (usableHeight - heroHeight - heroToRailGapDp -
                railTitleHeightDp - titleToCardGapDp).coerceAtLeast(1)
            val heightLimitedCardWidth = ((availableRailCardHeight.toFloat() -
                ceil(2f * ringDp).toInt()) * aspectWidth.toFloat() /
                (aspectHeight + (focusScale - 1f) * aspectWidth)).toInt().coerceAtLeast(1)
            val cardWidth = minOf(desiredCardWidth, heightLimitedCardWidth)
            val cardHeight = (cardWidth * aspectHeight.toFloat() / aspectWidth).roundToInt()
            val focusRoom = ceil(cardHeight * (focusScale - 1f) + 2f * ringDp).toInt()
            val railHeight = railTitleHeightDp + titleToCardGapDp + cardHeight + focusRoom
            return TvHomeMetrics(
                usableWidth, usableHeight, navWidth, contentWidth, heroHeight,
                cardWidth, cardHeight, focusRoom, railHeight,
                heroHeight + heroToRailGapDp + railHeight,
            )
        }
    }
}

internal data class TvHomeGuideSelection(
    val airing: TvProgram?,
    val next: TvProgram?,
    val upcoming: List<TvProgram>,
)

internal enum class TvHomeDownTarget { CONSUME, PLATFORM_SEARCH, RAIL_STATE_ACTION }

internal fun homeNavRightTarget(
    lastFocusedContentId: Int,
    defaultHeroId: Int,
    contentTargetAvailable: Boolean,
): Int = if (contentTargetAvailable) lastFocusedContentId else defaultHeroId
internal fun homeContentLeftTarget(lastFocusedNavId: Int): Int = lastFocusedNavId
internal fun homeRailStateUpTarget(nextActionId: Int): Int = nextActionId
internal fun homeRailCardUpTarget(lastUpperControlId: Int, defaultHeroId: Int, targetAvailable: Boolean): Int =
    if (targetAvailable) lastUpperControlId else defaultHeroId
internal fun homeRailEntryTarget(lastFocusedRailId: Int?, firstVisibleRailId: Int?): Int? =
    lastFocusedRailId ?: firstVisibleRailId
internal fun homeNextActionLeftTarget(lastFocusedHeroButtonId: Int): Int = lastFocusedHeroButtonId
internal fun homeConsumeEdge(viewId: Int): Int = viewId
internal fun homeHeroPlayRightTarget(recordButtonId: Int): Int = recordButtonId
internal fun homeHeroRecordLeftTarget(playButtonId: Int): Int = playButtonId
internal fun homeHeroDownTarget(
    sourceId: Int,
    target: TvHomeDownTarget,
    railTargetId: Int?,
    railStateActionId: Int,
): Int = when (target) {
    TvHomeDownTarget.CONSUME -> sourceId
    TvHomeDownTarget.PLATFORM_SEARCH -> railTargetId ?: sourceId
    TvHomeDownTarget.RAIL_STATE_ACTION -> railStateActionId
}
internal fun homeNextActionDownTarget(loading: Boolean, railHasItems: Boolean): TvHomeDownTarget = when {
    loading -> TvHomeDownTarget.CONSUME
    railHasItems -> TvHomeDownTarget.PLATFORM_SEARCH
    else -> TvHomeDownTarget.RAIL_STATE_ACTION
}
internal fun homeRecordDownTarget(loading: Boolean, railHasItems: Boolean): TvHomeDownTarget = when {
    loading -> TvHomeDownTarget.CONSUME
    railHasItems -> TvHomeDownTarget.PLATFORM_SEARCH
    else -> TvHomeDownTarget.RAIL_STATE_ACTION
}

/** Selects a bounded set from an input whose ordering is not guaranteed. */
internal fun selectHomeGuidePrograms(
    programs: List<TvProgram>,
    nowMs: Long,
    upcomingLimit: Int,
): TvHomeGuideSelection {
    var airing: TvProgram? = null
    var next: TvProgram? = null
    val limit = upcomingLimit.coerceAtLeast(0)
    val upcoming = PriorityQueue<TvProgram>(maxOf(1, limit), compareByDescending(TvProgram::startMs))
    for (program in programs) {
        if (program.startMs <= nowMs && program.endMs > nowMs) {
            val current = airing
            if (current == null || program.startMs > current.startMs) airing = program
        }
        if (program.startMs > nowMs) {
            val first = next
            if (first == null || program.startMs < first.startMs) next = program
            if (limit > 0) {
                if (upcoming.size < limit) upcoming.add(program)
                else {
                    val latestUpcoming = upcoming.peek() ?: continue
                    if (program.startMs < latestUpcoming.startMs) {
                        upcoming.poll()
                        upcoming.add(program)
                    }
                }
            }
        }
    }
    return TvHomeGuideSelection(airing, next, upcoming.toList().sortedBy(TvProgram::startMs))
}
