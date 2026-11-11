package Nexotv.iptv.player

import Nexotv.iptv.player.ui.TvBackStack
import Nexotv.iptv.player.ui.TvExitGuard
import Nexotv.iptv.player.ui.TvKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the P0 focus/key framework. They cover the parts that do not need
 * an Android view tree: the Back stack, the two-press exit window and the key map.
 */
class TvUiFrameworkTest {

    @Test
    fun exitGuardAsksOnceThenExitsInsideTheWindow() {
        var now = 1_000L
        var hints = 0
        val guard = TvExitGuard(
            windowMs = 2_000L,
            onHint = { hints++ },
            clock = { now },
        )

        assertFalse(guard.requestExit())
        assertEquals(1, hints)
        assertTrue(guard.isArmed())

        now += 1_999L
        assertTrue(guard.requestExit())
        assertEquals(1, hints)
        assertFalse(guard.isArmed())
    }

    @Test
    fun exitGuardRearmsAfterTheWindowExpires() {
        var now = 0L
        var hints = 0
        val guard = TvExitGuard(
            windowMs = 2_000L,
            onHint = { hints++ },
            clock = { now },
        )

        assertFalse(guard.requestExit())
        now += 2_001L
        assertFalse(guard.requestExit())
        assertEquals(2, hints)

        now += 1L
        assertTrue(guard.requestExit())
        assertEquals(2, hints)
    }

    @Test
    fun dpadDismissesHintAndRequiresFreshVisibleBackConfirmation() {
        var now = 0L
        var hints = 0
        val guard = TvExitGuard(2_000L, { hints++ }, { now })

        assertFalse(TvExitGuard.shouldExit(true, guard))
        now = 500L
        guard.disarm()
        assertFalse(guard.isArmed())
        now = 600L
        assertFalse(TvExitGuard.shouldExit(true, guard))
        now = 1_500L
        assertTrue(TvExitGuard.shouldExit(true, guard))
        assertEquals(2, hints)
    }

    @Test
    fun expiredHintShowsAgainAndDisabledConfirmationExitsImmediately() {
        var now = 0L
        var hints = 0
        val guard = TvExitGuard(2_000L, { hints++ }, { now })

        assertFalse(TvExitGuard.shouldExit(true, guard))
        now = 2_001L
        assertFalse(TvExitGuard.shouldExit(true, guard))
        assertEquals(2, hints)
        assertTrue(TvExitGuard.shouldExit(false, guard))
    }

    @Test
    fun backStackConsumesTheTopLayerFirstAndDiscardsStaleLayers() {
        val stack = TvBackStack()
        var outerCalls = 0
        var innerCalls = 0

        stack.push { outerCalls++; false }
        stack.push { innerCalls++; true }

        assertTrue(stack.onBack())
        assertEquals(0, outerCalls)
        assertEquals(1, innerCalls)
        assertEquals(1, stack.depth)

        assertFalse(stack.onBack())
        assertEquals(1, outerCalls)
        assertEquals(0, stack.depth)
    }

    @Test
    fun closingALayerRemovesItsHandler() {
        val stack = TvBackStack()
        var calls = 0
        val close = stack.push { calls++; true }

        close()
        assertEquals(0, stack.depth)
        assertFalse(stack.onBack())
        assertEquals(0, calls)
    }

    @Test
    fun keyMapMatchesTheSupportedRemoteKeysOnly() {
        assertEquals(TvKey.UP, TvKey.from(19))
        assertEquals(TvKey.DOWN, TvKey.from(20))
        assertEquals(TvKey.LEFT, TvKey.from(21))
        assertEquals(TvKey.RIGHT, TvKey.from(22))
        assertEquals(TvKey.OK, TvKey.from(23))
        assertEquals(TvKey.OK, TvKey.from(66))
        assertEquals(TvKey.OK, TvKey.from(160))
        assertEquals(TvKey.BACK, TvKey.from(4))
        assertEquals(TvKey.PLAY_PAUSE, TvKey.from(85))
        assertEquals(TvKey.PLAY, TvKey.from(126))
        assertEquals(TvKey.PAUSE, TvKey.from(127))
        assertEquals(TvKey.CHANNEL_UP, TvKey.from(166))
        assertEquals(TvKey.CHANNEL_DOWN, TvKey.from(167))
        assertEquals(TvKey.MENU, TvKey.from(82))
        assertEquals(TvKey.MENU, TvKey.from(176))
        assertEquals(TvKey.OTHER, TvKey.from(29))
    }
}
