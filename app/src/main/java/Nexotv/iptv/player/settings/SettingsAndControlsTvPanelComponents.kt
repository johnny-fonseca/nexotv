@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi

internal const val PANEL_ROW_HEIGHT_DP = 66
internal const val PANEL_CLOSE_WIDTH_DP = 140
internal const val PANEL_CLOSE_HEIGHT_DP = 42

// ---------- Bloques reutilizables de los paneles de TV ----------

internal fun MainActivity.createPanelScaffold(): Pair<LinearLayout, LinearLayout> {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(18), dp(28), dp(12))
        background = menuBackdrop(this@createPanelScaffold)
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(22), dp(28), dp(18))
        background = menuMainPanel(this@createPanelScaffold)
    }
    root.addView(panel, LinearLayout.LayoutParams(-1, -1))
    return root to panel
}

internal fun MainActivity.createPanelHeader(section: String, title: String, iconRes: Int): LinearLayout {
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(14))
    }
    val icon = FrameLayout(this).apply {
        background = menuPill(
            this@createPanelHeader,
            this@createPanelHeader.getColor(R.color.tv_menu_row_normal),
            this@createPanelHeader.getColor(R.color.tv_menu_panel_stroke),
        )
        addView(
            ImageView(this@createPanelHeader).apply {
                runCatching { setImageResource(iconRes) }
                imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER),
        )
    }
    header.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
    header.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@createPanelHeader).apply {
                text = section
                setTextColor(getColor(R.color.tv_turquoise))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.12f
            })
            addView(
                TextView(this@createPanelHeader).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
            )
        },
        LinearLayout.LayoutParams(0, -2, 1f),
    )
    return header
}

internal fun MainActivity.addSectionLabel(parent: LinearLayout, label: String) {
    parent.addView(
        TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.1f
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) },
    )
}

internal fun MainActivity.applyBadgeState(badge: TextView, label: String, active: Boolean) {
    badge.text = label
    badge.setTextColor(if (active) 0xFF07111D.toInt() else getColor(R.color.tv_text_secondary))
    badge.background = if (active) {
        menuPill(this, getColor(R.color.tv_success), getColor(R.color.tv_success))
    } else {
        menuPill(this, getColor(R.color.tv_menu_row_normal), getColor(R.color.tv_menu_panel_stroke))
    }
}

internal fun MainActivity.createBadge(
    label: String,
    active: Boolean = true,
    horizontalDp: Int = 9,
    verticalDp: Int = 5,
): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    setPadding(dp(horizontalDp), dp(verticalDp), dp(horizontalDp), dp(verticalDp))
    applyBadgeState(this, label, active)
}

internal fun MainActivity.createOptionRow(
    label: String,
    description: String,
    badge: TextView?,
    stateDescription: String?,
): LinearLayout {
    val row = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = menuRowBackground(this@createOptionRow)
        contentDescription = listOfNotNull(
            label,
            description.takeIf { it.isNotBlank() },
            stateDescription,
        ).joinToString(". ")
    }
    val labels = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@createOptionRow).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (description.isNotBlank()) {
            addView(
                TextView(this@createOptionRow).apply {
                    text = description
                    setTextColor(getColor(R.color.tv_text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) },
            )
        }
    }
    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
    if (badge != null) row.addView(badge)
    return row
}

internal fun MainActivity.createCloseButton(label: String): TextView = TextView(this).apply {
    id = View.generateViewId()
    text = label
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    isFocusable = true
    isFocusableInTouchMode = true
    isClickable = true
    setPadding(dp(18), dp(8), dp(18), dp(8))
    background = menuActionButton(this@createCloseButton, isRed = false, isCyanAccent = false)
}

internal fun linkFocusChain(rows: List<View>, close: View) {
    close.nextFocusLeftId = close.id
    close.nextFocusRightId = close.id
    close.nextFocusDownId = close.id
    if (rows.isEmpty()) return
    rows.forEachIndexed { index, row ->
        row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: row.id
        row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: close.id
        row.nextFocusLeftId = row.id
        row.nextFocusRightId = row.id
    }
    close.nextFocusUpId = rows.last().id
}

internal fun MainActivity.createTvPanelDialog(root: View): AlertDialog {
    val dialog = AlertDialog.Builder(this).setView(root).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    setTvDialogBounds(dialog, 1f, 1f)
    return dialog
}

internal fun MainActivity.showPanelDialog(dialog: AlertDialog, initialFocus: View?) {
    if (isFinishing || isDestroyed) return
    dialog.setOnShowListener {
        animateDialogEntrance(dialog)
        initialFocus?.post { initialFocus.requestFocus() }
    }
    runCatching { dialog.show() }
}

// ---------- Paneles genéricos ----------

internal fun MainActivity.showStyledChoiceDialog(
    title: String,
    section: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (options.isEmpty()) return
    val selected = selectedIndex.takeIf { it in options.indices } ?: -1
    val (root, panel) = createPanelScaffold()
    panel.addView(
        createPanelHeader(section, title, cardIcon(title)),
        LinearLayout.LayoutParams(-1, -2),
    )
    addSectionLabel(panel, "ELIGE UNA OPCIÓN")

    val rows = options.mapIndexed { index, (label, description) ->
        createOptionRow(
            label = label,
            description = description,
            badge = if (index == selected) createBadge("ACTUAL") else null,
            stateDescription = if (index == selected) "Actual" else null,
        )
    }
    val close = createCloseButton("Cerrar")
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEach {
            addView(it, LinearLayout.LayoutParams(-1, dp(PANEL_ROW_HEIGHT_DP)).apply { bottomMargin = dp(6) })
        }
    }
    linkFocusChain(rows, close)
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        isSmoothScrollingEnabled = true
        scrollFocusedDescendantIntoView()
        addView(list)
    }
    panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    panel.addView(
        close,
        LinearLayout.LayoutParams(dp(PANEL_CLOSE_WIDTH_DP), dp(PANEL_CLOSE_HEIGHT_DP)).apply {
            topMargin = dp(8)
            gravity = Gravity.END
        },
    )

    val dialog = createTvPanelDialog(root)
    close.setOnClickListener { dialog.dismiss() }
    rows.forEachIndexed { index, row ->
        row.setOnClickListener {
            dialog.dismiss()
            onSelect(index)
        }
    }
    showPanelDialog(dialog, rows.getOrNull(selected.coerceAtLeast(0)))
}

internal fun MainActivity.createStyledFormPanel(title: String, section: String, content: View): View {
    val (root, panel) = createPanelScaffold()
    panel.addView(createPanelHeader(section, title, cardIcon(title)))
    (content.parent as? ViewGroup)?.removeView(content)
    if (content is ScrollView) {
        content.isFillViewport = true
        content.isSmoothScrollingEnabled = true
        content.scrollFocusedDescendantIntoView()
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    } else {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            scrollFocusedDescendantIntoView()
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    return root
}
