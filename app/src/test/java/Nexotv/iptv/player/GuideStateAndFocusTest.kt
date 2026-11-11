package Nexotv.iptv.player






import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.guide.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideStateAndFocusTest {
    @Test
    fun searchEmptyStateReflectsGuideAvailabilityAndFreshness() {
        assertEquals(
            GuideSearchEmptyState.NO_MATCHES,
            GuideSearchEmptyStateSelector.select(GuideStatusKind.CURRENT),
        )
        assertEquals(
            GuideSearchEmptyState.NO_GUIDE,
            GuideSearchEmptyStateSelector.select(GuideStatusKind.NO_GUIDE),
        )
        assertEquals(
            GuideSearchEmptyState.NO_GUIDE_AFTER_FAILURE,
            GuideSearchEmptyStateSelector.select(GuideStatusKind.NO_GUIDE_AFTER_FAILURE),
        )
        assertEquals(
            GuideSearchEmptyState.STALE_OR_FAILED,
            GuideSearchEmptyStateSelector.select(GuideStatusKind.STALE),
        )
        assertEquals(
            GuideSearchEmptyState.STALE_OR_FAILED,
            GuideSearchEmptyStateSelector.select(GuideStatusKind.CACHED_AFTER_FAILURE),
        )
    }

    @Test
    fun guideStatusUsesUpdateTimeCoverageAndRefreshFailure() {
        val now = 100_000_000L
        val airing = program("Canal 7", "Noticias", startMs = now - 1_000L, endMs = now + 60_000L)

        assertEquals(
            GuideStatusKind.CURRENT,
            GuideStatusResolver.resolve(listOf(airing), now - 60_000L, false, 6, now),
        )
        assertEquals(
            GuideStatusKind.STALE,
            GuideStatusResolver.resolve(listOf(airing), now - 7 * 3_600_000L, false, 6, now),
        )
        assertEquals(
            GuideStatusKind.STALE,
            GuideStatusResolver.resolve(listOf(airing), 0L, false, -1, now),
        )
        assertEquals(
            GuideStatusKind.CACHED_AFTER_FAILURE,
            GuideStatusResolver.resolve(listOf(airing), now - 60_000L, true, 6, now),
        )
        assertEquals(
            GuideStatusKind.NO_GUIDE_AFTER_FAILURE,
            GuideStatusResolver.resolve(emptyList(), 0L, true, 6, now),
        )
        assertEquals(
            GuideStatusKind.NO_GUIDE,
            GuideStatusResolver.resolve(emptyList(), 0L, false, 6, now),
        )
    }

    @Test
    fun focusRestorationFindsProgramByIdentityThenFallsBackToAiring() {
        val now = 100_000_000L
        val selected = program("Canal 7", "Noticias", startMs = now - 2_000L, endMs = now + 30_000L)
        val airing = program("FUTV", "Partido", startMs = now - 1_000L, endMs = now + 60_000L)
        val rebuilt = selected.copy(title = "Edición especial")

        assertSame(selected, GuideProgramFocus.restore(listOf(airing, selected), GuideProgramFocus.key(selected), now))
        assertSame(airing, GuideProgramFocus.restore(listOf(rebuilt, airing), GuideProgramFocus.key(selected), now))
        assertNull(GuideProgramFocus.restore(listOf(rebuilt), GuideProgramFocus.key(selected), now))
    }

    @Test
    fun searchProgramStateIndexMatchesTitlesCaseInsensitivelyAndKeepsFirstSchedule() {
        val saved = program("Canal 7", "Noticias", startMs = 10L, endMs = 20L)
        val result = saved.copy(channelId = "FUTV", title = "NOTICIAS")
        val firstSchedule = ScheduledProgram(saved, reminder = true, recording = false)
        val duplicateSchedule = ScheduledProgram(saved, reminder = false, recording = true)
        val index = GuideSearchProgramUiStateIndex(
            savedPrograms = listOf(saved),
            scheduledPrograms = listOf(firstSchedule, duplicateSchedule),
        )

        assertTrue(index.isSaved(result))
        assertSame(firstSchedule, index.schedule(result))
        assertNull(index.schedule(result.copy(title = "Deportes")))
    }

    @Test
    fun guideSearchReturnsEarliestEightyMatchesInStableOrder() {
        val now = 1_000L
        val programs = (100 downTo 1).map { offset ->
            program("Canal 7", "Partido $offset", now + offset, now + offset + 10L)
        } + program("Canal 7", "Partido fuera de fecha", now - 20L, now - 10L)

        val matches = GuideProgramSearch.find(programs, "partido", now)

        assertEquals(80, matches.size)
        assertEquals((1L..80L).toList(), matches.map { it.startMs - now })
    }

    @Test
    fun guideSearchKeepsInputOrderForProgramsStartingAtTheSameTime() {
        val now = 1_000L
        val first = program("Canal 7", "Partido primero", now, now + 10L)
        val second = program("Canal 7", "Partido segundo", now, now + 10L)

        assertEquals(
            listOf(first, second),
            GuideProgramSearch.find(listOf(first, second), "partido", now),
        )
    }

    @Test
    fun guideProgramTimeIndexMatchesFirstOverlappingProgramAndHandlesGaps() {
        val first = program("Canal 7", "Primero", 0L, 100L)
        val second = program("Canal 7", "Segundo", 20L, 40L)
        val later = program("Canal 7", "Posterior", 110L, 150L)
        val index = GuideProgramTimeIndex(listOf(later, first, second))

        assertSame(first, index.at(30L))
        assertNull(index.at(100L))
        assertSame(later, index.at(120L))
        assertSame(later, index.nextAfter(100L))
        assertNull(index.nextAfter(150L))
    }

    private fun program(channel: String, title: String, startMs: Long, endMs: Long) = TvProgram(
        channelId = channel,
        title = title,
        description = "",
        startMs = startMs,
        endMs = endMs,
    )
}
