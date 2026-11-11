// app/src/main/java/Nexotv/iptv/player/tv/TvStyle.kt
package Nexotv.iptv.player.tv
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.settings.*
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes

/** Tokens visuales compartidos y comportamiento de foco para las vistas programáticas de Android TV. */
internal object TvStyle {
    enum class ButtonKind { PRIMARY, SECONDARY }

    private const val DEFAULT_FOCUS_SCALE_PERCENT = 100
    private const val MIN_FOCUS_SCALE = 1f
    private const val MAX_FOCUS_SCALE = 1.5f
    private val LIVE_KEYWORDS = listOf("vivo", "live", "rec", "grab")

    fun px(context: Context, @DimenRes dimension: Int): Int = context.resources.getDimensionPixelSize(dimension)

    fun fractionWidth(context: Context, fraction: Int): Int {
        val resources = context.resources
        val width = resources.displayMetrics.widthPixels
        val value = resources.getFraction(fraction, 1, 1)
        return (width * value).toInt().coerceIn(0, width)
    }

    fun surface(context: Context, elevated: Boolean = false): GradientDrawable = GradientDrawable().apply {
        cornerRadius = context.resources.getDimension(R.dimen.tv_radius_panel)
        setColor(context.getColor(if (elevated) R.color.tv_menu_panel else R.color.tv_menu_bg))
        setStroke(strokePx(context, 1f), context.getColor(R.color.tv_menu_panel_stroke))
    }

    fun playerCardSurface(context: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(MenuScreenPalette.glassTop, MenuScreenPalette.glassBottom, MenuScreenPalette.glassBottom),
    ).apply {
        cornerRadius = context.dimen(R.dimen.tv_radius_card)
        setStroke(strokePx(context, 1.25f), MenuScreenPalette.glassStroke)
    }

    fun playerOverlaySurface(
        context: Context,
        radiusPx: Float = context.dimen(R.dimen.tv_radius_card),
        borderColor: Int = MenuScreenPalette.glassStroke,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(MenuScreenPalette.glassTop, MenuScreenPalette.glassBottom),
    ).apply {
        cornerRadius = radiusPx.coerceAtLeast(0f)
        setStroke(strokePx(context, 1.25f), borderColor)
    }

    fun focusDrawable(
        context: Context,
        @DimenRes radius: Int = R.dimen.tv_radius_card,
        @ColorRes normalColor: Int = R.color.tv_menu_row_normal,
    ): StateListDrawable {
        val r = context.dimen(radius)
        val focused = GradientDrawable().apply {
            cornerRadius = r
            setColor(context.getColor(R.color.tv_menu_row_focused))
            setStroke(strokePx(context, 3f), context.getColor(R.color.tv_turquoise))
        }
        val normal = GradientDrawable().apply {
            cornerRadius = r
            setColor(context.getColor(normalColor))
            setStroke(strokePx(context, 1f), context.getColor(R.color.tv_menu_row_normal_stroke))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun applyFocusable(view: View, radius: Int = R.dimen.tv_radius_card) {
        view.background = focusDrawable(view.context, radius)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        applyFocusScale(view)
    }

    fun applyText(view: TextView, @DimenRes size: Int, @ColorRes color: Int, bold: Boolean = false) {
        val textStyle = when (size) {
            R.dimen.tv_text_secondary -> R.style.TvTextSecondary
            R.dimen.tv_text_body -> R.style.TvTextBody
            R.dimen.tv_text_title -> R.style.TvTextTitle
            R.dimen.tv_text_heading -> R.style.TvTextHeading
            else -> R.style.TvTextHero
        }
        view.setTextAppearance(textStyle)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.resources.getDimension(size))
        view.setTextColor(view.context.getColor(color))
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
    }

    fun applyPlayerText(
        view: TextView,
        savedSizeSp: Int,
        title: Boolean = false,
        secondary: Boolean = false,
        fixedSize: Int? = null,
        baseSize: Int? = null,
    ) {
        val resources = view.resources
        val metrics = resources.displayMetrics
        val scaledDensity = (metrics.density * resources.configuration.fontScale).takeIf { it > 0f } ?: 1f
        val minimum = resources.getDimension(R.dimen.tv_panel_text_floor) / scaledDensity
        val maximum = maxOf(resources.getInteger(R.integer.tv_panel_text_max_sp), minimum.toInt())
        val preferred = savedSizeSp.coerceIn(minimum.toInt(), maximum)
        val titleMinimum = resources.getDimension(R.dimen.tv_panel_title_floor) / scaledDensity
        val baseline = baseSize?.let { resources.getDimension(it) / scaledDensity } ?: preferred.toFloat()
        val adjusted = baseline + preferred - maximum
        val size = if (title) maxOf(adjusted, titleMinimum) else adjusted.coerceAtLeast(minimum)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        fixedSize?.let { dimension ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(dimension))
        }
        view.setTextColor(view.context.color(if (secondary) R.color.tv_text_secondary else R.color.tv_text_primary))
        if (title) view.setTypeface(view.typeface, Typeface.BOLD)
    }

    fun styleButton(button: Button, kind: ButtonKind, action: String = "Activar") {
        val resources = button.resources
        button.background = menuActionButton(
            button.context,
            isRed = false,
            isCyanAccent = kind == ButtonKind.PRIMARY,
        )
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.isAllCaps = false
        button.minimumHeight = resources.getDimensionPixelSize(R.dimen.tv_min_row_height)
        button.setPadding(
            resources.getDimensionPixelSize(R.dimen.tv_space_3),
            resources.getDimensionPixelSize(R.dimen.tv_space_1),
            resources.getDimensionPixelSize(R.dimen.tv_space_3),
            resources.getDimensionPixelSize(R.dimen.tv_space_1),
        )
        applyText(button, R.dimen.tv_text_secondary, R.color.tv_text_primary, bold = true)
        button.setTextColor(button.context.getColor(R.color.tv_text_primary))
        applyFocusScale(button)
        button.contentDescription = "${button.text}, $action"
    }

    fun styleDialog(dialog: AlertDialog, initialButton: Int = AlertDialog.BUTTON_POSITIVE) {
        dialog.window?.let { window ->
            window.setBackgroundDrawable(menuBackdrop(dialog.context))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.decorView.setPadding(0, 0, 0, 0)
        }
        val positive: Button? = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative: Button? = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val neutral: Button? = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        positive?.let { styleButton(it, ButtonKind.PRIMARY, "Cerrar") }
        negative?.let { styleButton(it, ButtonKind.SECONDARY, "Cancelar") }
        neutral?.let { styleButton(it, ButtonKind.SECONDARY, "Elegir") }
        val initial = when (initialButton) {
            AlertDialog.BUTTON_NEGATIVE -> negative
            AlertDialog.BUTTON_NEUTRAL -> neutral
            else -> positive
        } ?: positive ?: negative ?: neutral
        initial?.post { if (initial.isAttachedToWindow) initial.requestFocus() }
    }

    fun showInfoDialog(
        context: Context,
        title: String,
        heading: String,
        help: String,
        iconResource: Int,
        closeLabel: String,
        onCancel: (() -> Unit)? = null,
    ): AlertDialog {
        val titleView = TextView(context).apply {
            text = title
            contentDescription = title
            applyText(this, R.dimen.tv_text_title, R.color.tv_text_primary, bold = true)
            setPadding(
                context.dimenPx(R.dimen.tv_space_4),
                context.dimenPx(R.dimen.tv_space_3),
                context.dimenPx(R.dimen.tv_space_4),
                context.dimenPx(R.dimen.tv_space_2),
            )
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dimenPx(R.dimen.tv_space_4),
                context.dimenPx(R.dimen.tv_space_3),
                context.dimenPx(R.dimen.tv_space_4),
                context.dimenPx(R.dimen.tv_space_3),
            )
            addView(actionCard(context, iconResource, heading, help))
        }
        val dialog = AlertDialog.Builder(context)
            .setCustomTitle(titleView)
            .setView(content)
            .setPositiveButton(closeLabel, null)
            .create()
        context.findMainActivity()?.setTvDialogBounds(dialog, 1f, 1f)
        if (onCancel != null) dialog.setOnCancelListener { onCancel() }
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (canShowDialog(context)) {
            try {
                dialog.show()
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } catch (_: RuntimeException) {
                // La ventana anfitriona ya no es válida; se devuelve el diálogo sin mostrar
            }
        }
        return dialog
    }

    fun primaryButton(context: Context, label: String, action: String = "Activar", onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            styleButton(this, ButtonKind.PRIMARY, action)
            setOnClickListener { onClick() }
        }

    fun secondaryButton(context: Context, label: String, action: String = "Abrir", onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            styleButton(this, ButtonKind.SECONDARY, action)
            setOnClickListener { onClick() }
        }

    fun actionCard(
        context: Context,
        iconResource: Int,
        title: String,
        help: String,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dp = context.resources.displayMetrics.density
        setPadding((dp * 16).toInt(), (dp * 12).toInt(), (dp * 16).toInt(), (dp * 12).toInt())
        minimumHeight = context.dimenPx(R.dimen.tv_min_destination_height)
        background = if (onClick == null) menuCardSurface(context) else menuRowBackground(context)
        isFocusable = onClick != null
        isClickable = onClick != null
        contentDescription = "$title. $help${if (onClick != null) ". Abrir" else ""}"
        val iconBox = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp * 10f
                setColor(0xFF132034.toInt())
            }
        }
        val iconView = ImageView(context).apply {
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.tv_turquoise))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        iconBox.addView(iconView, FrameLayout.LayoutParams((dp * 20).toInt(), (dp * 20).toInt(), Gravity.CENTER))
        addView(iconBox, LinearLayout.LayoutParams((dp * 38).toInt(), (dp * 38).toInt()).apply {
            marginEnd = (dp * 14).toInt()
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                applyText(this, R.dimen.tv_text_body, R.color.tv_text_primary, bold = true)
            })
            if (help.isNotBlank()) {
                addView(TextView(context).apply {
                    text = help
                    applyText(this, R.dimen.tv_text_secondary, R.color.tv_text_secondary)
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        onClick?.let { action ->
            setOnClickListener { action() }
            applyFocusScale(this)
        }
    }

    fun programRow(context: Context, time: String, title: String, state: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dimenPx(R.dimen.tv_min_row_height)
            setPadding(
                context.dimenPx(R.dimen.tv_space_2),
                context.dimenPx(R.dimen.tv_space_1),
                context.dimenPx(R.dimen.tv_space_2),
                context.dimenPx(R.dimen.tv_space_1),
            )
            background = menuRowBackground(context)
            isFocusable = true
            isClickable = true
            applyFocusScale(this)
            contentDescription = "$time, $title, $state. Ver detalles"
            addView(TextView(context).apply {
                text = time
                applyText(this, R.dimen.tv_text_secondary, R.color.tv_text_secondary)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dimenPx(R.dimen.tv_space_2)
            })
            addView(TextView(context).apply {
                text = title
                applyText(this, R.dimen.tv_text_body, R.color.tv_text_primary, bold = true)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusChip(context, state))
            setOnClickListener { onClick() }
        }

    fun dayChip(context: Context, label: String, date: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = context.dimenPx(R.dimen.tv_min_row_height)
            setPadding(
                context.dimenPx(R.dimen.tv_space_2),
                context.dimenPx(R.dimen.tv_space_1),
                context.dimenPx(R.dimen.tv_space_2),
                context.dimenPx(R.dimen.tv_space_1),
            )
            background = menuRowBackground(context)
            isFocusable = true
            isClickable = true
            applyFocusScale(this)
            contentDescription = "Mostrar programación de $label, $date"
            addView(TextView(context).apply {
                text = label
                applyText(this, R.dimen.tv_text_secondary, R.color.tv_text_primary, bold = true)
            })
            addView(TextView(context).apply {
                text = date
                applyText(this, R.dimen.tv_text_secondary, R.color.tv_text_secondary)
            })
            setOnClickListener { onClick() }
        }

    fun statusChip(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        val isLive = LIVE_KEYWORDS.any { label.contains(it, ignoreCase = true) }
        applyText(this, R.dimen.tv_text_secondary, if (isLive) R.color.tv_text_primary else R.color.tv_turquoise, bold = true)
        val vertical = (context.resources.displayMetrics.density * 3f).toInt()
        setPadding(context.dimenPx(R.dimen.tv_space_2), vertical, context.dimenPx(R.dimen.tv_space_2), vertical)
        background = if (isLive) {
            GradientDrawable().apply {
                cornerRadius = context.resources.displayMetrics.density * 10f
                setColor(context.getColor(R.color.tv_menu_red_btn))
            }
        } else {
            menuPill(context)
        }
        contentDescription = "Estado: $label"
    }

    fun applyFocusScale(view: View) {
        val percent = view.resources.getInteger(R.integer.tv_focus_scale_percent)
            .takeIf { it > 0 } ?: DEFAULT_FOCUS_SCALE_PERCENT
        val focusedScale = (percent / 100f).coerceIn(MIN_FOCUS_SCALE, MAX_FOCUS_SCALE)
        view.setOnFocusChangeListener { focusedView, focused ->
            animateTvFocus(focusedView, focused, focusedScale, elevationDp = 5f)
        }
    }

    private fun canShowDialog(context: Context): Boolean {
        val activity = context.findHostActivity() ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun Context.findHostActivity(): Activity? {
        var current: Context? = this
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun strokePx(context: Context, dp: Float): Int =
        (context.resources.displayMetrics.density * dp).toInt().coerceAtLeast(1)

    private fun Context.dimen(@DimenRes id: Int): Float = resources.getDimension(id)
    private fun Context.dimenPx(@DimenRes id: Int): Int = px(this, id)
    private fun Context.color(@ColorRes id: Int): Int = getColor(id)
}
