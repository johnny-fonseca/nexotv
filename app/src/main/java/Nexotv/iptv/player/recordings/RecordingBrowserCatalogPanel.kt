// RecordingBrowserCatalogPanel.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

internal class RecordingBrowserCatalogPanel(
    val root: LinearLayout,
    val itemsLayout: LinearLayout,
    val scroll: ScrollView,
    val countTv: TextView,
)

internal fun MainActivity.createRecordingBrowserCatalogPanel(): RecordingBrowserCatalogPanel {
    val activity = this

    val itemsLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = false
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipChildren = true
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(4))
        addView(itemsLayout, ViewGroup.LayoutParams(-1, -2))
    }

    val headerTitle = TextView(this).apply {
        text = "CATÁLOGO DE GRABACIONES"
        setTextColor(getColor(R.color.tv_turquoise))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    val countTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val hintTv = TextView(this).apply {
        text = "Usa ↑ ↓ para navegar · OK para reproducir"
        setTextColor(0xFF8294A8.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuMainPanel(activity)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        clipChildren = true
        clipToPadding = true

        addView(headerTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        addView(countTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        addView(hintTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    return RecordingBrowserCatalogPanel(
        root = root,
        itemsLayout = itemsLayout,
        scroll = scroll,
        countTv = countTv,
    )
}
