package Nexotv.iptv.player

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWindowTest {
    @Test
    fun marginsExtendRecordingWindowAndDetectOverlap() {
        val first = RecordingWindow.fromProgram(0L, 60L * 60_000L, beforeMinutes = 2, afterMinutes = 5)
        val adjacent = RecordingWindow.fromProgram(60L * 60_000L, 90L * 60_000L, beforeMinutes = 0, afterMinutes = 0)

        assertTrue(first.overlaps(adjacent))
        assertTrue(first.startMs == -2L * 60_000L)
        assertTrue(first.endMs == 65L * 60_000L)
    }

    @Test
    fun exactAdjacentWindowsDoNotConflictWithoutPadding() {
        val first = RecordingWindow.fromProgram(0L, 60L * 60_000L, beforeMinutes = 0, afterMinutes = 0)
        val adjacent = RecordingWindow.fromProgram(60L * 60_000L, 90L * 60_000L, beforeMinutes = 0, afterMinutes = 0)

        assertFalse(first.overlaps(adjacent))
    }

    @Test
    fun scheduledAlarmReportsWhenAnotherCaptureOwnsTheService() {
        assertTrue(scheduledStartConflictsWithActiveCapture(true, "channel:100", null))
        assertTrue(scheduledStartConflictsWithActiveCapture(true, "channel:200", "channel:100"))
        assertFalse(scheduledStartConflictsWithActiveCapture(true, "channel:100", "channel:100"))
        assertFalse(scheduledStartConflictsWithActiveCapture(false, "channel:100", null))
        assertFalse(scheduledStartConflictsWithActiveCapture(true, null, null))
    }
}
