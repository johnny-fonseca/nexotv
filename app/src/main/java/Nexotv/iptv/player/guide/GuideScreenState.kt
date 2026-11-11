package Nexotv.iptv.player.guide

import Nexotv.iptv.player.tv.TvProgram
import android.app.AlertDialog
import android.view.View
import java.lang.ref.WeakReference
import java.util.Calendar

private const val GUIDE_STATE_FIRST_TAB = 0
private const val GUIDE_STATE_LAST_TAB = 1

internal class GuideScreenState(
    dates: List<Calendar>,
    datesBaseKey: Int,
    selectedTab: Int = 0,
    selectedDayIndex: Int = 0,
    selectedProgram: TvProgram? = null,
    lastFocusedProgramCard: View? = null,
    initialGuideFocusView: View? = null,
    dialog: AlertDialog? = null,
) {
    var dates: List<Calendar> = dates
        set(value) {
            field = value
            clampSelectedDayIndex()
        }

    var datesBaseKey: Int = datesBaseKey

    var selectedTab: Int = selectedTab.coerceIn(GUIDE_STATE_FIRST_TAB, GUIDE_STATE_LAST_TAB)
        set(value) {
            field = value.coerceIn(GUIDE_STATE_FIRST_TAB, GUIDE_STATE_LAST_TAB)
        }

    var selectedDayIndex: Int = selectedDayIndex.coerceIn(0, maxOf(dates.lastIndex, 0))
        set(value) {
            field = value.coerceIn(0, maxOf(this.dates.lastIndex, 0))
        }

    var selectedProgram: TvProgram? = selectedProgram

    private var lastFocusedProgramCardRef: WeakReference<View>? =
        lastFocusedProgramCard?.let { WeakReference(it) }

    var lastFocusedProgramCard: View?
        get() = lastFocusedProgramCardRef?.get()
        set(value) {
            lastFocusedProgramCardRef = value?.let { WeakReference(it) }
        }

    private var initialGuideFocusViewRef: WeakReference<View>? =
        initialGuideFocusView?.let { WeakReference(it) }

    var initialGuideFocusView: View?
        get() = initialGuideFocusViewRef?.get()
        set(value) {
            initialGuideFocusViewRef = value?.let { WeakReference(it) }
        }

    var dialog: AlertDialog? = dialog

    private fun clampSelectedDayIndex() {
        selectedDayIndex = selectedDayIndex
    }

    /** Dismisses any open dialog and drops every view, program, and dialog reference held by this state. */
    fun release() {
        val current = dialog
        dialog = null
        if (current != null) {
            try {
                if (current.isShowing) current.dismiss()
            } catch (e: Exception) {
                // Window may already be detached; nothing further to clean up.
            }
        }
        lastFocusedProgramCardRef = null
        initialGuideFocusViewRef = null
        selectedProgram = null
    }
}