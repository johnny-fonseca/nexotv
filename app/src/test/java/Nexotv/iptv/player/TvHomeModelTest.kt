package Nexotv.iptv.player

import Nexotv.iptv.player.ui.TvChannelUi
import Nexotv.iptv.player.ui.TvHomeMetrics
import Nexotv.iptv.player.ui.TvHomeDownTarget
import Nexotv.iptv.player.ui.homeContentLeftTarget
import Nexotv.iptv.player.ui.homeConsumeEdge
import Nexotv.iptv.player.ui.homeNavRightTarget
import Nexotv.iptv.player.ui.homeNextActionLeftTarget
import Nexotv.iptv.player.ui.homeNextActionDownTarget
import Nexotv.iptv.player.ui.homeRailCardUpTarget
import Nexotv.iptv.player.ui.homeHeroDownTarget
import Nexotv.iptv.player.ui.homeHeroPlayRightTarget
import Nexotv.iptv.player.ui.homeHeroRecordLeftTarget
import Nexotv.iptv.player.ui.homeRailEntryTarget
import Nexotv.iptv.player.ui.homeRailStateUpTarget
import Nexotv.iptv.player.ui.homeRecordDownTarget
import Nexotv.iptv.player.ui.homeRecordingElapsedLabel
import Nexotv.iptv.player.ui.TvProgramUi
import Nexotv.iptv.player.ui.TvQualityLevel
import Nexotv.iptv.player.ui.TvQualityOptionUi
import Nexotv.iptv.player.ui.TvUiState
import Nexotv.iptv.player.ui.airingProgram
import Nexotv.iptv.player.ui.canZap
import Nexotv.iptv.player.ui.hasQualityChoices
import Nexotv.iptv.player.ui.nextProgram
import Nexotv.iptv.player.ui.progressPermille
import Nexotv.iptv.player.ui.selectHomeGuidePrograms
import Nexotv.iptv.player.tv.TvProgram
import Nexotv.iptv.player.ui.upcomingPrograms
import Nexotv.iptv.player.activity.formatClockTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TvHomeModelTest {

    @Test
    fun clockFormatDefaultsToLegacyTwelveHourAndHonoursTwentyFourHourChoice() {
        val date = Date(13 * 60 * 60_000L + 5 * 60_000L)
        val utc = TimeZone.getTimeZone("UTC")

        assertEquals("1:05 PM", formatClockTime(date, preference = null, locale = Locale.US, timeZone = utc))
        assertEquals("13:05", formatClockTime(date, preference = "24-hour", locale = Locale.US, timeZone = utc))
        assertEquals("1:05 PM", formatClockTime(date, preference = "unknown", locale = Locale.US, timeZone = utc))
    }

    @Test
    fun homeMetricsFitSupportedDpConfigurationsAndKeepRailCardsSixteenNine() {
        listOf(960 to 540, 1280 to 720, 1920 to 1080, 960 to 720, 1260 to 540).forEach { (width, height) ->
            val metrics = TvHomeMetrics.compute(
                widthDp = width, heightDp = height, safeMargin = 0.05f,
                navWidthFraction = 0.20f, heroHeightFraction = 0.45f,
                railCardWidthFraction = 0.35f, focusScale = 1.05f, ringDp = 3,
                aspectWidth = 16, aspectHeight = 9, heroToRailGapDp = 24,
                railTitleHeightDp = 32, titleToCardGapDp = 16, heroMinimumHeightDp = 254,
            )
            assertTrue(metrics.totalContentHeightDp <= metrics.usableHeightDp)
            assertTrue(kotlin.math.abs(metrics.railCardHeightDp * 16 - metrics.railCardWidthDp * 9) <= 16)
            assertEquals(metrics.railCardWidthDp, metrics.skeletonWidthDp)
            assertEquals(metrics.railCardHeightDp, metrics.skeletonHeightDp)
            if (width == 960 && height == 540) {
                assertEquals(254, metrics.heroRowHeightDp)
                assertEquals(227, metrics.railCardWidthDp)
                assertEquals(128, metrics.railCardHeightDp)
                assertEquals(467, metrics.totalContentHeightDp)
            }
        }
    }

    @Test
    fun homeMetricsClampRailCardForFourThreeAndUltrawideHeights() {
        listOf(960 to 720, 1260 to 540).forEach { (width, height) ->
            val metrics = TvHomeMetrics.compute(
                widthDp = width, heightDp = height, safeMargin = 0.05f,
                navWidthFraction = 0.20f, heroHeightFraction = 0.45f,
                railCardWidthFraction = 0.35f, focusScale = 1.05f, ringDp = 3,
                aspectWidth = 16, aspectHeight = 9, heroToRailGapDp = 24,
                railTitleHeightDp = 32, titleToCardGapDp = 16, heroMinimumHeightDp = 254,
            )
            assertTrue("$width x $height must fit", metrics.totalContentHeightDp <= metrics.usableHeightDp)
            assertTrue("$width x $height must retain 16:9", kotlin.math.abs(
                metrics.railCardHeightDp * 16 - metrics.railCardWidthDp * 9,
            ) <= 16)
        }
    }

    @Test
    fun homeGuideSelectionHandlesUnsortedRawProgramsAndCapsRail() {
        val guide = listOf(
            rawProgram("late", 500L, 700L),
            rawProgram("current-old", 50L, 300L),
            rawProgram("next", 200L, 400L),
            rawProgram("current-new", 150L, 350L),
            rawProgram("later", 800L, 900L),
        )
        val selected = selectHomeGuidePrograms(guide, nowMs = 175L, upcomingLimit = 1)

        assertEquals("current-new", selected.airing?.title)
        assertEquals("next", selected.next?.title)
        assertEquals(listOf("next"), selected.upcoming.map(TvProgram::title))
    }

    @Test
    fun homeFocusUsesNavMemoryAndRoutesRecordDownByRailState() {
        val lastNav = 22
        val lastContent = 71
        val defaultHero = 11
        val lastUpper = 31
        val lastRail = 41
        val firstVisibleRail = 42
        assertEquals(12, homeHeroPlayRightTarget(12))
        assertEquals(11, homeHeroRecordLeftTarget(11))
        assertEquals(11, homeHeroDownTarget(11, TvHomeDownTarget.CONSUME, null, 50))
        assertEquals(12, homeHeroDownTarget(12, TvHomeDownTarget.CONSUME, null, 50))
        assertEquals(41, homeHeroDownTarget(11, TvHomeDownTarget.PLATFORM_SEARCH, 41, 50))
        assertEquals(50, homeHeroDownTarget(11, TvHomeDownTarget.RAIL_STATE_ACTION, null, 50))
        assertEquals(lastNav, homeContentLeftTarget(lastNav))
        assertEquals(lastContent, homeNavRightTarget(lastContent, defaultHero, contentTargetAvailable = true))
        assertEquals(defaultHero, homeNavRightTarget(lastContent, defaultHero, contentTargetAvailable = false))
        assertEquals(lastContent, homeRailStateUpTarget(lastContent))
        assertEquals(lastUpper, homeRailCardUpTarget(lastUpper, defaultHero, targetAvailable = true))
        assertEquals(defaultHero, homeRailCardUpTarget(lastUpper, defaultHero, targetAvailable = false))
        assertEquals(lastRail, homeRailEntryTarget(lastRail, firstVisibleRail))
        assertEquals(firstVisibleRail, homeRailEntryTarget(null, firstVisibleRail))
        assertEquals(lastUpper, homeNextActionLeftTarget(lastUpper))
        assertEquals(lastNav, homeConsumeEdge(lastNav))
        assertEquals(TvHomeDownTarget.CONSUME, homeNextActionDownTarget(loading = true, railHasItems = false))
        assertEquals(TvHomeDownTarget.CONSUME, homeRecordDownTarget(loading = true, railHasItems = false))
        assertEquals(TvHomeDownTarget.PLATFORM_SEARCH, homeRecordDownTarget(loading = false, railHasItems = true))
        assertEquals(TvHomeDownTarget.RAIL_STATE_ACTION, homeRecordDownTarget(loading = false, railHasItems = false))
    }

    @Test
    fun airingUsesTheMostRecentlyStartedOverlappingProgram() {
        val early = program("Noticias", 0L, 100L)
        val late = program("Deportes", 50L, 150L)
        val ended = program("Ayer", -100L, -10L)

        assertSame(late, airingProgram(listOf(early, late, ended), 60L))
        assertNull(airingProgram(listOf(early, late), 200L))
    }

    @Test
    fun nextIsTheEarliestProgramStartingAfterNow() {
        val soon = program("Cerca", 200L, 300L)
        val later = program("Lejos", 400L, 500L)

        assertSame(soon, nextProgram(listOf(later, soon), 100L))
        assertNull(nextProgram(listOf(soon, later), 900L))
    }

    @Test
    fun upcomingIsBoundedAndSortedEvenWhenTheInputIsUnsorted() {
        val programs = listOf(
            program("C", 300L, 400L),
            program("A", 100L, 200L),
            program("D", 400L, 500L),
            program("B", 200L, 300L),
        )

        val upcoming = upcomingPrograms(programs, 0L, 2)
        assertEquals(listOf("A", "B"), upcoming.map(TvProgramUi::title))
    }

    @Test
    fun upcomingDropsProgramsStartingExactlyNowAndHonoursZeroLimit() {
        val programs = listOf(program("Ahora", 100L, 200L), program("Después", 101L, 200L))

        assertEquals(emptyList<TvProgramUi>(), upcomingPrograms(programs, 100L, 0))
        assertEquals(listOf("Después"), upcomingPrograms(programs, 100L, 5).map(TvProgramUi::title))
    }

    @Test
    fun progressIsClampedAndSafeForInvalidPrograms() {
        val valid = program("Programa", 1_000L, 2_000L)

        assertEquals(0, progressPermille(valid, 900L, 1_000))
        assertEquals(500, progressPermille(valid, 1_500L, 1_000))
        assertEquals(1_000, progressPermille(valid, 2_500L, 1_000))

        val invalid = program("Inválido", 2_000L, 2_000L)
        assertEquals(0, progressPermille(invalid, 2_000L, 1_000))
        assertEquals(0, progressPermille(valid, 1_500L, 0))
    }

    @Test
    fun homeRecordingElapsedUsesTotalMinutesAndSeconds() {
        assertEquals("00:00", homeRecordingElapsedLabel(-1L))
        assertEquals("00:07", homeRecordingElapsedLabel(7_000L))
        assertEquals("12:34", homeRecordingElapsedLabel(754_000L))
        assertEquals("61:05", homeRecordingElapsedLabel(3_665_000L))
    }

    @Test
    fun singleStreamDisablesZapping() {
        val one = TvUiState(channels = listOf(channel("uno")))
        val two = TvUiState(channels = listOf(channel("uno"), channel("dos")))

        assertFalse(one.canZap)
        assertTrue(two.canZap)
    }

    @Test
    fun qualityChoicesRequireAtLeastTwoRenditions() {
        assertFalse(TvUiState().hasQualityChoices)
        assertFalse(
            TvUiState(
                availableQualities = listOf(TvQualityOptionUi(TvQualityLevel.AUTO)),
            ).hasQualityChoices,
        )
        assertTrue(
            TvUiState(
                availableQualities = listOf(
                    TvQualityOptionUi(TvQualityLevel.AUTO),
                    TvQualityOptionUi(TvQualityLevel.HIGH),
                ),
            ).hasQualityChoices,
        )
    }

    private fun channel(id: String) = TvChannelUi(id = id, name = id)

    private fun program(title: String, startMs: Long, endMs: Long) = TvProgramUi(
        channelId = "canal",
        title = title,
        startMs = startMs,
        endMs = endMs,
    )

    private fun rawProgram(title: String, startMs: Long, endMs: Long) = TvProgram(
        channelId = "canal", title = title, description = "", startMs = startMs,
        endMs = endMs, category = "", rating = "", iconUrl = "",
    )
}
