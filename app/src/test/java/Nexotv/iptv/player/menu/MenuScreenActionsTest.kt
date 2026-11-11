package Nexotv.iptv.player.menu

import Nexotv.iptv.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuScreenActionsTest {
    @Test
    fun reminderPrecedenceMatchesLegacyMenu() {
        val reload = menuScreenActionsFor(false, false, false, false, false)
        assertEquals(R.string.tv_menu_action_refresh, reload.reminder.labelRes)
        assertEquals(MenuScreenAction.REFRESH_GUIDE, reload.reminder.action)
        assertTrue(reload.reminder.available)

        val add = menuScreenActionsFor(true, false, true, false, true)
        assertEquals(R.string.tv_menu_action_remind, add.reminder.labelRes)
        assertEquals(MenuScreenAction.ADD_REMINDER, add.reminder.action)

        val remove = menuScreenActionsFor(true, true, false, false, true)
        assertEquals(R.string.tv_menu_action_remove_reminder, remove.reminder.labelRes)
        assertEquals(MenuScreenAction.REMOVE_REMINDER, remove.reminder.action)

        val permission = menuScreenActionsFor(true, false, false, false, true)
        assertEquals(R.string.tv_menu_action_remind, permission.reminder.labelRes)
        assertEquals(MenuScreenAction.REQUEST_EXACT_ALARM, permission.reminder.action)

        val agendaError = menuScreenActionsFor(true, false, true, false, false)
        assertEquals(R.string.tv_menu_action_remind, agendaError.reminder.labelRes)
        assertEquals(MenuScreenAction.ADD_REMINDER, agendaError.reminder.action)
        assertFalse(agendaError.agendaAvailable)
    }

    @Test
    fun recordingLabelAndActionFollowActiveState() {
        val idle = menuScreenActionsFor(false, false, true, false, true).recording
        assertEquals(R.string.tv_menu_action_record, idle.labelRes)
        assertEquals(MenuScreenAction.START_RECORDING, idle.action)

        val active = menuScreenActionsFor(false, false, true, true, true).recording
        assertEquals(R.string.tv_menu_action_stop_recording, active.labelRes)
        assertEquals(MenuScreenAction.STOP_RECORDING, active.action)
    }
}
