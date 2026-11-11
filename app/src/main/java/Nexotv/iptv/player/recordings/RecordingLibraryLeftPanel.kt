// ============================================================================
// Archivo: RecordingLibraryLeftPanel.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

/**
 * No se conoce el tipo devuelto por MainMenuDestinationRowFactory.create(...),
 * por eso solo se exponen su `view` (como View) y un setter para su `subtitle`.
 */
internal class RecordingLibraryLeftPanel(
    val root: LinearLayout,
    val leftPanelSubtitleTv: TextView,
    val rowLibraryView: View,
    val rowScheduledView: View,
    val rowStorageView: View,
    val setRowLibrarySubtitle: (String) -> Unit,
    val setRowScheduledSubtitle: (String) -> Unit,
    val setRowStorageSubtitle: (String) -> Unit,
    val recordRow: LinearLayout,
    val recordTitle: TextView,
    val recordSubtitle: TextView,
)

/** 2. PANEL IZQUIERDO (35%) */
internal fun MainActivity.createRecordingLibraryLeftPanel(
    state: RecordingLibraryState,
    onOpenLibrary: () -> Unit,
    refresh: () -> Unit,
): RecordingLibraryLeftPanel {
    val menuItemsList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    val menuItemsScroll = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipChildren = true
        clipToPadding = false
        setPadding(dp(3), dp(6), dp(3), dp(6))
        addView(menuItemsList, ViewGroup.LayoutParams(-1, -2))
    }

    val rowFactory = MainMenuDestinationRowFactory(this) { action -> action() }

    val rowLibrary = rowFactory.create(
        "Biblioteca completa",
        "Abrir catálogo de Clips",
        R.drawable.tv_menu_recordings,
    ) { onOpenLibrary() }

    val rowScheduled = rowFactory.create(
        "Programadas",
        "Recordatorios y grabaciones en agenda",
        R.drawable.tv_icon_reminder,
    ) {
        state.showScheduledView = !state.showScheduledView
        refresh()
    }

    val rowStorage = rowFactory.create(
        "Destino de grabación",
        "Configurar memoria o disco USB",
        R.drawable.tv_icon_database,
    ) { safeCall("abrir destino", Unit) { showRecordingDestination() } }

    val rowLibrarySubtitle = rowLibrary.subtitle
    val rowScheduledSubtitle = rowScheduled.subtitle
    val rowStorageSubtitle = rowStorage.subtitle

    listOf(rowLibrary.view, rowScheduled.view, rowStorage.view).forEach { v ->
        menuItemsList.addView(v, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
    }

    val recordTitle = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }
    val recordSubtitle = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(COLOR_RECORD_SUBTITLE)
    }
    val recordRow = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuRedCardBackground(this@createRecordingLibraryLeftPanel)
        setPadding(dp(14), 0, dp(14), 0)
        clipChildren = true
        clipToPadding = false

        addView(TextView(this@createRecordingLibraryLeftPanel).apply {
            text = "●"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })

        val textCol = LinearLayout(this@createRecordingLibraryLeftPanel).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(recordTitle)
            addView(recordSubtitle)
        }
        addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        setOnFocusChangeListener { view, focused -> view.animateFocusElevation(focused) }
        setOnClickListener {
            safeCall("alternar grabación", Unit) { toggleRecording() }
            refresh()
        }
    }
    menuItemsList.addView(recordRow, LinearLayout.LayoutParams(-1, dp(56)))

    val leftPanelSubtitleTv = TextView(this).apply {
        text = "Última emisión guardada y biblioteca"
        setTextColor(COLOR_TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    val leftPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuMainPanel(this@createRecordingLibraryLeftPanel)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        clipChildren = false
        clipToPadding = false

        addView(TextView(this@createRecordingLibraryLeftPanel).apply {
            text = "BIBLIOTECA"
            setTextColor(getColor(R.color.tv_turquoise))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        addView(TextView(this@createRecordingLibraryLeftPanel).apply {
            text = "Grabaciones"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        addView(leftPanelSubtitleTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        addView(menuItemsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    val hintText = TextView(this).apply {
        text = "❖  Usa ↑ ↓ para moverte · OK para abrir · Atrás para menú"
        setTextColor(COLOR_TEXT_HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(8), 0, 0)
    }

    val leftContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(leftPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(hintText, LinearLayout.LayoutParams(-1, -2))
    }

    return RecordingLibraryLeftPanel(
        root = leftContainer,
        leftPanelSubtitleTv = leftPanelSubtitleTv,
        rowLibraryView = rowLibrary.view,
        rowScheduledView = rowScheduled.view,
        rowStorageView = rowStorage.view,
        setRowLibrarySubtitle = { text -> rowLibrarySubtitle.text = text },
        setRowScheduledSubtitle = { text -> rowScheduledSubtitle.text = text },
        setRowStorageSubtitle = { text -> rowStorageSubtitle.text = text },
        recordRow = recordRow,
        recordTitle = recordTitle,
        recordSubtitle = recordSubtitle,
    )
}
