package Nexotv.iptv.player.guide


import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.menu.menuMainPanel
import Nexotv.iptv.player.tv.dp
import Nexotv.iptv.player.tv.scrollFocusedDescendantIntoView

import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val GRID_ERROR_TAG = "GUIDE_ERROR"
private const val SECTION_TITLE_SIZE_SP = 10.5f
private const val SECTION_TITLE_LETTER_SPACING = 0.08f

internal data class GuideProgramGridBinding(
    val view: LinearLayout,
    val sectionTitle: TextView,
    val programsContainer: LinearLayout,
    val scrollView: ScrollView,
)

/** Builds the scrollable channel/program grid used by the guide's left panel. */
internal fun MainActivity.buildGuideProgramGrid(colorMuted: Int): GuideProgramGridBinding {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuMainPanel(this@buildGuideProgramGrid)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        clipChildren = true
        clipToPadding = false
    }

    val sectionTitle = TextView(this).apply {
        setTextColor(colorMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_TITLE_SIZE_SP)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = SECTION_TITLE_LETTER_SPACING
        maxLines = 1
        setPadding(dp(4), 0, dp(4), dp(6))
    }
    panel.addView(sectionTitle, LinearLayout.LayoutParams(-1, -2))

    val programsContainer = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = false
    }

    val scrollView = ScrollView(this).apply {
        id = View.generateViewId()
        isFillViewport = true
        clipChildren = true
        clipToPadding = false
        setPadding(0, dp(8), 0, dp(8))
        addView(programsContainer, ViewGroup.LayoutParams(-1, -2))
        try {
            scrollFocusedDescendantIntoView()
        } catch (e: Exception) {
            DiagnosticTracker.log(GRID_ERROR_TAG, "programGrid.scrollFocus: ${e.javaClass.simpleName}")
        }
    }
    panel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

    return GuideProgramGridBinding(panel, sectionTitle, programsContainer, scrollView)
}