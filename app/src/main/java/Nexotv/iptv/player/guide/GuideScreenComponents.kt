// GuideScreenComponents.kt
package Nexotv.iptv.player.guide

import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.R
import Nexotv.iptv.player.menu.applyTvFocus
import Nexotv.iptv.player.menu.menuCardSurface
import Nexotv.iptv.player.menu.menuWhiteButton
import android.app.Activity
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

private const val COMPONENTS_LOG_TAG = "GuideScreenComponents"
private const val MAX_LABEL_LENGTH = 32
private const val MAX_CHANNEL_NAME_LENGTH = 64
private const val REFRESH_DEBOUNCE_MS = 500L
private const val FOCUS_ANIMATION_MS = 150L
private const val FOCUS_ELEVATION = 6f
private const val FALLBACK_CORNER_RADIUS_PX = 12f

private const val FALLBACK_COLOR_TURQUOISE = 0xFF40E0D0.toInt()
private const val FALLBACK_COLOR_TEXT_SECONDARY = 0xFF9AA5B1.toInt()
private const val FALLBACK_COLOR_ROW_FOCUSED = 0xFF1E2A33.toInt()
private const val FALLBACK_COLOR_ROW_NORMAL = 0xFF141C22.toInt()
private const val FALLBACK_COLOR_ROW_STROKE = 0xFF2A3640.toInt()

private object GuideTexts {
    const val EMPTY_GUIDE_TITLE = "No hay programas registrados para este día."
    const val EMPTY_SAVED_TITLE = "No tienes programas guardados en tu lista."
    const val EMPTY_SAVED_HINT = "Explora la guía diaria y presiona «Guardar en Mis programas» para tenerlos a mano."
    const val REFRESH_BUTTON = "Actualizar guía XMLTV"

    val EMPTY_GUIDE_HINT: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val channel = sanitizeLabel(BuildConfig.CHANNEL_NAME, MAX_CHANNEL_NAME_LENGTH)
        if (channel.isEmpty()) {
            "Pulsa «Actualizar guía» para descargar la programación más reciente."
        } else {
            "Pulsa «Actualizar guía» para descargar la programación más reciente de $channel."
        }
    }
}

private fun sanitizeLabel(value: String, maxLength: Int = MAX_LABEL_LENGTH): String {
    if (value.isEmpty() || maxLength <= 0) return ""
    val cleaned = StringBuilder(value.length)
    for (ch in value) {
        cleaned.append(if (Character.isISOControl(ch)) ' ' else ch)
    }
    var result = cleaned.toString().trim()
    if (result.length > maxLength) {
        result = result.substring(0, maxLength)
        if (result.isNotEmpty() && Character.isHighSurrogate(result.last())) {
            result = result.dropLast(1)
        }
        result = result.trimEnd()
    }
    return result
}

private fun Activity.dp(value: Int): Int {
    val density = resources.displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (value * density).toInt()
}

private fun TextView.applyTextSize(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)

/** Ejecuta un callback de la UI aislando cualquier excepción para no cerrar la app. */
private inline fun safeInvoke(action: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.e(COMPONENTS_LOG_TAG, "Error ejecutando $action", e)
    }
}

/** Evalúa [block] y devuelve [fallback] si lanza una excepción. */
private inline fun <T> guarded(action: String, fallback: () -> T, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        Log.e(COMPONENTS_LOG_TAG, "Error resolviendo $action", e)
        fallback()
    }

private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

private fun Activity.safeColor(resId: Int, fallback: Int): Int =
    try {
        getColor(resId)
    } catch (e: Resources.NotFoundException) {
        Log.w(COMPONENTS_LOG_TAG, "Color no disponible: $resId", e)
        fallback
    }

private fun Activity.safeDimension(resId: Int, fallbackPx: Float): Float =
    try {
        resources.getDimension(resId).takeIf { it.isFinite() && it >= 0f } ?: fallbackPx
    } catch (e: Resources.NotFoundException) {
        Log.w(COMPONENTS_LOG_TAG, "Dimensión no disponible: $resId", e)
        fallbackPx
    }

private fun Activity.fallbackSurface(color: Int, radiusPx: Float = FALLBACK_CORNER_RADIUS_PX): Drawable =
    GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
    }

private fun Activity.dayCardBackground(focused: Boolean, selected: Boolean): Drawable =
    GradientDrawable().apply {
        cornerRadius = safeDimension(R.dimen.tv_menu_row_radius, FALLBACK_CORNER_RADIUS_PX)
        val turquoise = safeColor(R.color.tv_turquoise, FALLBACK_COLOR_TURQUOISE)
        when {
            focused -> {
                setColor(safeColor(R.color.tv_menu_row_focused, FALLBACK_COLOR_ROW_FOCUSED))
                setStroke(dp(3), turquoise)
            }
            selected -> {
                setColor(safeColor(R.color.tv_menu_row_focused, FALLBACK_COLOR_ROW_FOCUSED))
                setStroke(dp(1), turquoise)
            }
            else -> {
                setColor(safeColor(R.color.tv_menu_row_normal, FALLBACK_COLOR_ROW_NORMAL))
                setStroke(dp(1), safeColor(R.color.tv_menu_row_normal_stroke, FALLBACK_COLOR_ROW_STROKE))
            }
        }
    }

private fun Activity.singleLineLabel(value: String, color: Int, sizeSp: Float, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        setTextColor(color)
        applyTextSize(sizeSp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Opción de fecha enfocable para el selector de días de la guía de TV. */
internal object GuideDaySelectorCard {
    fun create(
        activity: Activity,
        topLabel: String,
        dayNumber: String,
        weekday: String,
        selected: Boolean,
        onSelected: () -> Unit,
    ): LinearLayout {
        val safeTopLabel = sanitizeLabel(topLabel)
        val safeDayNumber = sanitizeLabel(dayNumber)
        val safeWeekday = sanitizeLabel(weekday)
        val colorSecondary = activity.safeColor(R.color.tv_text_secondary, FALLBACK_COLOR_TEXT_SECONDARY)

        return LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isSelected = selected
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            minimumHeight = activity.dp(76)
            minimumWidth = activity.dp(70)
            contentDescription = listOf(safeWeekday, safeDayNumber, safeTopLabel)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

            fun updateBackground(focused: Boolean) {
                background = guarded(
                    action = "fondo del selector de día",
                    fallback = { activity.fallbackSurface(FALLBACK_COLOR_ROW_NORMAL) },
                ) { activity.dayCardBackground(focused, selected) }
            }

            updateBackground(false)

            setOnFocusChangeListener { _, focused ->
                safeInvoke("cambio de foco del selector de día") {
                    updateBackground(focused)
                    animate().cancel()
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(if (focused) FOCUS_ELEVATION else 0f)
                        .setDuration(FOCUS_ANIMATION_MS)
                        .setInterpolator(DecelerateInterpolator(2.2f))
                        .withLayer()
                        .start()
                }
            }

            addView(
                activity.singleLineLabel(
                    value = safeTopLabel,
                    color = if (selected) Color.WHITE else colorSecondary,
                    sizeSp = 9.5f,
                ),
            )
            addView(
                activity.singleLineLabel(
                    value = safeDayNumber,
                    color = Color.WHITE,
                    sizeSp = 18f,
                    bold = true,
                ),
            )
            addView(
                activity.singleLineLabel(
                    value = safeWeekday,
                    color = colorSecondary,
                    sizeSp = 9.5f,
                ),
            )

            setOnClickListener {
                if (activity.isUsable()) safeInvoke("selección de día") { onSelected() }
            }
        }
    }
}

/** Contenido de estado vacío cuando la pestaña de guía seleccionada no tiene programas. */
internal object GuideEmptyState {
    fun create(activity: Activity, isGuideTab: Boolean, onRefresh: () -> Unit): View {
        val colorSecondary = activity.safeColor(R.color.tv_text_secondary, FALLBACK_COLOR_TEXT_SECONDARY)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = guarded<Drawable>(
                action = "fondo del estado vacío",
                fallback = { activity.fallbackSurface(FALLBACK_COLOR_ROW_NORMAL, activity.dp(18).toFloat()) },
            ) { menuCardSurface(activity, 18f) }
            setPadding(activity.dp(20), activity.dp(40), activity.dp(20), activity.dp(40))

            addView(
                TextView(activity).apply {
                    text = if (isGuideTab) GuideTexts.EMPTY_GUIDE_TITLE else GuideTexts.EMPTY_SAVED_TITLE
                    setTextColor(Color.WHITE)
                    applyTextSize(14f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = activity.dp(6) },
            )

            addView(
                TextView(activity).apply {
                    text = if (isGuideTab) GuideTexts.EMPTY_GUIDE_HINT else GuideTexts.EMPTY_SAVED_HINT
                    setTextColor(colorSecondary)
                    applyTextSize(11f)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = activity.dp(16) },
            )

            if (isGuideTab) {
                var lastRefreshClickAt = -REFRESH_DEBOUNCE_MS
                addView(
                    TextView(activity).apply {
                        text = GuideTexts.REFRESH_BUTTON
                        contentDescription = GuideTexts.REFRESH_BUTTON
                        setTextColor(Color.BLACK)
                        applyTextSize(11.8f)
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        background = guarded<Drawable>(
                            action = "fondo del botón de actualización",
                            fallback = { activity.fallbackSurface(Color.WHITE, activity.dp(21).toFloat()) },
                        ) { menuWhiteButton(activity) }
                        setPadding(activity.dp(22), activity.dp(12), activity.dp(22), activity.dp(12))
                        minimumWidth = activity.dp(150)
                        minimumHeight = activity.dp(42)
                        safeInvoke("configuración de foco del botón de actualización") {
                            applyTvFocus(this, scaleTo = 1.05f)
                        }
                        setOnClickListener {
                            if (!activity.isUsable()) return@setOnClickListener
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastRefreshClickAt < REFRESH_DEBOUNCE_MS) return@setOnClickListener
                            lastRefreshClickAt = now
                            safeInvoke("actualización de guía") { onRefresh() }
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        activity.dp(42),
                    ),
                )
            }
        }
    }
}