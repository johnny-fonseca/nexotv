// ===== app/src/main/java/Nexotv/iptv/player/menu/OptionListDialog.kt =====
package Nexotv.iptv.player.menu

import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.R
import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Renders the focusable rows and sizing for the app's option list dialogs. */
internal class OptionListDialog(
    private val activity: Activity,
    private val title: String,
    private val items: List<Pair<String, () -> Unit>>,
    private val mainMenu: Boolean,
    private val hintFor: (String) -> String,
    private val createFocusCard: (String, String, () -> Unit) -> View,
    private val onDialogCreated: (AlertDialog, LinearLayout) -> Unit,
) {
    fun show() {
        if (!activity.isUsable()) return

        var dialog: AlertDialog? = null
        val rows = buildRows { dialog }
        val scroll = buildScroll(rows)

        scroll.scrollFocusedDescendantIntoView()
        linkRowFocus(rows)
        applyRowHeight(rows)

        val created = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(activity.getString(if (mainMenu) R.string.tv_menu_close else R.string.tv_menu_main_menu), null)
            .create()
        dialog = created
        created.window?.setBackgroundDrawableResource(android.R.color.transparent)
        created.setOnShowListener {
            (activity as? MainActivity)?.animateDialogEntrance(created)
        }

        runCatching { onDialogCreated(created, rows) }
            .onFailure { Log.e(TAG, "Error en onDialogCreated", it) }

        if (!activity.isUsable()) return
        try {
            created.show()
        } catch (e: RuntimeException) {
            Log.e(TAG, "No se pudo mostrar el diálogo", e)
            return
        }
        linkCloseButton(created, rows)
    }

    private fun buildRows(dialogProvider: () -> AlertDialog?): LinearLayout {
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(32), activity.dp(16), activity.dp(32), activity.dp(16))
        }
        items.forEachIndexed { index, (label, action) ->
            val row = createFocusCard(label, hintFor(label)) {
                dialogProvider()?.dismiss()
                runCatching(action).onFailure { Log.e(TAG, "Error al ejecutar la opción '$label'", it) }
            }
            if (row.id == View.NO_ID) row.id = View.generateViewId()
            rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (index < items.lastIndex) bottomMargin = activity.dp(16)
            })
        }
        return rows
    }

    private fun buildScroll(rows: LinearLayout): ScrollView =
        ScrollView(activity).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            addView(rows, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

    private fun linkRowFocus(rows: LinearLayout) {
        val count = rows.childCount
        for (index in 0 until count) {
            val row = rows.getChildAt(index)
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
            row.nextFocusUpId = if (index > 0) rows.getChildAt(index - 1).id else row.id
            row.nextFocusDownId = if (index < count - 1) rows.getChildAt(index + 1).id else row.id
        }
    }

    private fun applyRowHeight(rows: LinearLayout) {
        val minHeight = activity.dp(MIN_ROW_DP)
        val maxHeight = activity.dp(MAX_ROW_DP)
        val available = (activity.resources.displayMetrics.heightPixels - activity.dp(RESERVED_DP))
            .coerceAtLeast(minHeight)
        val rowHeight = (available / items.size.coerceAtLeast(1)).coerceIn(minHeight, maxHeight)
        for (index in 0 until rows.childCount) {
            val row = rows.getChildAt(index)
            (row.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.height = rowHeight
                row.layoutParams = params
            }
        }
    }

    private fun linkCloseButton(dialog: AlertDialog, rows: LinearLayout) {
        if (rows.childCount == 0) return
        val closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val lastRow = rows.getChildAt(rows.childCount - 1)
        if (closeButton.id == View.NO_ID) closeButton.id = View.generateViewId()
        if (lastRow.id == View.NO_ID) lastRow.id = View.generateViewId()
        lastRow.nextFocusDownId = closeButton.id
        closeButton.nextFocusUpId = lastRow.id
    }

    private companion object {
        const val TAG = "OptionListDialog"
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MIN_ROW_DP = 100
        const val MAX_ROW_DP = 200
        const val RESERVED_DP = 180
    }
}

private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
