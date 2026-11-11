@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isNotEmpty
import androidx.media3.common.util.UnstableApi
import java.util.WeakHashMap

// ---------- Diálogos de lista con estilo TV ----------

private fun styleDialogRow(activity: MainActivity, row: View, rowHeight: Int) {
    if (row.id == View.NO_ID) row.id = View.generateViewId()
    if (row.minimumHeight != rowHeight) row.minimumHeight = rowHeight
    (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
        val gap = activity.dp(8)
        if (params.height != rowHeight || params.bottomMargin != gap) {
            params.height = rowHeight
            params.bottomMargin = gap
            row.layoutParams = params
        }
    }
    row.background = menuRowBackground(activity)
    row.setPadding(activity.dp(28), activity.dp(10), activity.dp(28), activity.dp(10))
    row.isFocusable = true
    row.isFocusableInTouchMode = true
    row.nextFocusLeftId = row.id
    row.nextFocusRightId = row.id
    if (row is TextView) {
        row.textSize = 20f
        row.setTextColor(Color.WHITE)
        row.setTypeface(row.typeface, Typeface.BOLD)
        val icon = activity.getDrawable(cardIcon(row.text?.toString().orEmpty()))?.mutate()
        icon?.setTint(activity.getColor(R.color.tv_turquoise))
        row.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        row.compoundDrawablePadding = activity.dp(18)
    }
    row.setOnFocusChangeListener { view, focused ->
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationZ(if (focused) activity.dp(4).toFloat() else 0f)
            .setDuration(155L)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .withLayer()
            .start()
    }
}

private fun styleDialogList(activity: MainActivity, list: ListView, styledRows: MutableMap<View, String>) {
    val rowCount = list.count
    if (rowCount <= 0) return
    val minRow = activity.dp(72)
    val rowHeight = if (rowCount in 1..8) {
        ((activity.resources.displayMetrics.heightPixels - activity.dp(220)).coerceAtLeast(minRow) / rowCount)
            .coerceIn(minRow, activity.dp(144))
    } else minRow
    list.layoutParams?.let { params ->
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
            params.height != ViewGroup.LayoutParams.MATCH_PARENT
        ) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            list.layoutParams = params
        }
    }
    for (index in 0 until list.childCount) {
        val row = list.getChildAt(index) ?: continue
        val signature = "${(row as? TextView)?.text}|$rowHeight"
        if (styledRows[row] == signature) continue
        styledRows[row] = signature
        runCatching { styleDialogRow(activity, row, rowHeight) }
    }
}

internal fun AlertDialog.Builder.showForTv(): AlertDialog {
    val activity = context.findMainActivity()
    if (activity == null) {
        val fallback = create()
        runCatching { fallback.show() }
        return fallback
    }
    val dialog = create()
    activity.setTvDialogBounds(dialog, 1f, 1f)
    dialog.setOnShowListener {
        activity.animateDialogEntrance(dialog)
        val list = dialog.listView ?: return@setOnShowListener
        list.divider = null
        list.dividerHeight = activity.dp(10)
        list.clipChildren = true
        list.clipToPadding = false
        list.setPadding(activity.dp(28), activity.dp(14), activity.dp(28), activity.dp(14))
        val styledRows = WeakHashMap<View, String>()
        val resizeRows = Runnable { runCatching { styleDialogList(activity, list, styledRows) } }
        list.post(resizeRows)
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> list.post(resizeRows) }
        list.post {
            if (list.isNotEmpty() && list.findFocus() == null) {
                list.getChildAt(0).requestFocus()
            }
            val closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val lastVisibleRow = list.getChildAt(list.childCount - 1)
            if (closeButton != null && lastVisibleRow != null && list.count <= list.childCount) {
                lastVisibleRow.nextFocusDownId = closeButton.id
                closeButton.nextFocusUpId = lastVisibleRow.id
            }
        }
    }
    if (!activity.isFinishing && !activity.isDestroyed) runCatching { dialog.show() }
    return dialog
}
