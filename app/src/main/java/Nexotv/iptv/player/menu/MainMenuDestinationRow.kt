// MainMenuDestinationRow.kt
package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal data class MainMenuDestinationRow(
    val view: View,
    val subtitle: TextView,
)

/** Tiñe la sombra de elevación del control para que el realce de foco se lea como un resplandor neón. */
internal fun View.applyMenuGlow(color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        outlineAmbientShadowColor = color
        outlineSpotShadowColor = color
    }
}

/** Crea una fila de destino enfocable para el menú principal de TV. */
internal class MainMenuDestinationRowFactory(
    private val activity: Activity,
    private val onDestinationSelected: (() -> Unit) -> Unit,
) {

    fun create(
        title: String,
        subtitle: String,
        iconRes: Int,
        action: () -> Unit,
    ): MainMenuDestinationRow {
        val row = createRow()
        val iconBackground = createIconBackground()
        val iconBox = createIconBox(iconBackground)
        val iconView = createIconView(iconRes)
        val titleView = createTitleView(title)
        val subtitleView = createSubtitleView(subtitle)

        iconBox.addView(
            iconView,
            FrameLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER),
        )
        row.addView(iconBox, LinearLayout.LayoutParams(dp(ICON_BOX_SIZE_DP), dp(ICON_BOX_SIZE_DP)))

        val labels = createLabelsContainer().apply {
            addView(titleView)
            addView(subtitleView)
        }
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnFocusChangeListener { view, focused ->
            animateFocus(view, focused)
            applyFocusColors(focused, titleView, subtitleView, iconBackground, iconView)
        }
        row.setOnClickListener { dispatchSelection(action) }

        return MainMenuDestinationRow(row, subtitleView)
    }

    /** Acceso del rail lateral con icono, etiqueta y foco de alto contraste. */
    fun createTab(title: String, iconRes: Int, action: () -> Unit): MainMenuDestinationRow {
        val label = TextView(activity).apply {
            id = View.generateViewId()
            text = title
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.tv_text_secondary))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = createTabBackground()
            setPadding(dp(TAB_PAD_H_DP), dp(TAB_PAD_V_DP), dp(TAB_PAD_H_DP), dp(TAB_PAD_V_DP))
            compoundDrawablePadding = dp(TAB_ICON_LABEL_GAP_DP)
            activity.getDrawable(iconRes)?.mutate()?.let { icon ->
                icon.setBounds(0, 0, dp(TAB_ICON_SIZE_DP), dp(TAB_ICON_SIZE_DP))
                setCompoundDrawablesRelative(icon, null, null, null)
            }
            compoundDrawableTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(activity.getColor(R.color.tv_text_primary), activity.getColor(R.color.tv_text_secondary))
            )
            setOnFocusChangeListener { view, focused -> applyTabFocus(view as TextView, focused) }
            setOnClickListener { dispatchSelection(action) }
        }
        return MainMenuDestinationRow(label, TextView(activity).apply { visibility = View.GONE })
    }

    private fun createTabBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            cornerRadius = dp(TAB_CORNER_DP).toFloat()
            setColor(activity.getColor(R.color.tv_menu_row_focused))
            setStroke(dp(TAB_FOCUSED_STROKE_DP), activity.getColor(R.color.tv_turquoise))
        }
        val idle = GradientDrawable().apply {
            cornerRadius = dp(TAB_CORNER_DP).toFloat()
            setColor(activity.getColor(R.color.tv_menu_row_normal))
            setStroke(dp(TAB_IDLE_STROKE_DP), activity.getColor(R.color.tv_menu_row_normal_stroke))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), idle)
        }
    }

    private fun applyTabFocus(tab: TextView, focused: Boolean) {
        tab.setTextColor(activity.getColor(if (focused) R.color.tv_text_primary else R.color.tv_text_secondary))
        animateTvFocus(tab, focused, focusedScale = TAB_FOCUSED_SCALE, elevationDp = 0f)
    }

    private fun createRow(): LinearLayout = LinearLayout(activity).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuRowBackground(activity)
        setPadding(dp(ROW_HORIZONTAL_PADDING_DP), 0, dp(ROW_HORIZONTAL_PADDING_DP), 0)
        // Deja respirar el halo del foco del D-pad sin recortarlo en el contenedor de la fila.
        clipChildren = false
        clipToPadding = false
    }

    private fun createIconBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ICON_CORNER_RADIUS_DP).toFloat()
        setColor(activity.getColor(R.color.tv_menu_panel))
    }

    private fun createIconBox(iconBackground: GradientDrawable): FrameLayout =
        FrameLayout(activity).apply { background = iconBackground }

    private fun createIconView(iconRes: Int): ImageView = ImageView(activity).apply {
        if (iconRes != 0) {
            runCatching { setImageResource(iconRes) }
                .onFailure { Log.w(TAG, "No se pudo cargar el ícono $iconRes", it) }
        }
        imageTintList = ColorStateList.valueOf(activity.getColor(R.color.tv_turquoise))
    }

    private fun createLabelsContainer(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(LABELS_START_PADDING_DP), 0, dp(LABELS_END_PADDING_DP), 0)
    }

    private fun createTitleView(title: String): TextView = TextView(activity).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
        setTextColor(activity.getColor(R.color.tv_text_primary))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun createSubtitleView(subtitle: String): TextView = TextView(activity).apply {
        text = subtitle
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_TEXT_SIZE_SP)
        setTextColor(activity.getColor(R.color.tv_text_secondary))
    }

    private fun animateFocus(view: View, focused: Boolean) {
        animateTvFocus(view, focused, focusedScale = 1.02f, elevationDp = FOCUSED_ELEVATION)
    }

    private fun applyFocusColors(
        focused: Boolean,
        titleView: TextView,
        subtitleView: TextView,
        iconBackground: GradientDrawable,
        iconView: ImageView,
    ) {
        val turquoise = activity.getColor(R.color.tv_turquoise)
        titleView.setTextColor(if (focused) Color.WHITE else activity.getColor(R.color.tv_text_primary))
        subtitleView.setTextColor(activity.getColor(R.color.tv_text_primary))
        iconBackground.setColor(if (focused) turquoise else activity.getColor(R.color.tv_menu_panel))
        iconView.imageTintList = ColorStateList.valueOf(if (focused) activity.getColor(R.color.tv_menu_bg) else turquoise)
    }

    private fun dispatchSelection(action: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching { onDestinationSelected(action) }
            .onFailure { Log.e(TAG, "Error al abrir el destino seleccionado", it) }
    }

    private fun dp(value: Int): Int = activity.dp(value)

    private companion object {
        const val TAG = "MainMenuDestinationRow"

        const val ROW_HORIZONTAL_PADDING_DP = 12
        const val ICON_BOX_SIZE_DP = 36
        const val ICON_SIZE_DP = 20
        const val ICON_CORNER_RADIUS_DP = 10
        const val LABELS_START_PADDING_DP = 12
        const val LABELS_END_PADDING_DP = 8

        const val TITLE_TEXT_SIZE_SP = 19f
        const val SUBTITLE_TEXT_SIZE_SP = 13f

        const val FOCUSED_ELEVATION = 6f
        // Pestañas de la barra superior
        const val TAB_TEXT_SIZE_SP = 15f
        const val TAB_PAD_H_DP = 11
        const val TAB_PAD_V_DP = 8
        const val TAB_ICON_LABEL_GAP_DP = 10
        const val TAB_ICON_SIZE_DP = 24
        const val TAB_CORNER_DP = 10
        const val TAB_IDLE_STROKE_DP = 1
        const val TAB_FOCUSED_STROKE_DP = 3
        const val TAB_FOCUSED_SCALE = 1.025f
    }
}

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
