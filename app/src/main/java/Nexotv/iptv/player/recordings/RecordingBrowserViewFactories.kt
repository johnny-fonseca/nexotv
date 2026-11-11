// RecordingBrowserViewFactories.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt

// ---------------------------
// Configuración reutilizable
// ---------------------------
internal data class RecordingActionButtonConfig(
    val textSizeSp: Float = 12f,
    val gravity: Int = Gravity.CENTER,
    val allCaps: Boolean = false,
    val maxLines: Int = 1,
    val ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.END,
    val enabled: Boolean = true,
    val focusable: Boolean = true,
    val clickable: Boolean = true,
    val alphaWhenDisabled: Float = 0.45f,
    val minHeightDp: Int = 36,
    val minWidthDp: Int = 72,
    val paddingHorizontalDp: Int = 12,
    val paddingVerticalDp: Int = 8,
    val typefaceStyle: Int = Typeface.NORMAL,
)

// -----------------------------------------
// Extensión para convertir dp a px de forma segura
// -----------------------------------------
private fun MainActivity.dpToPx(dp: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()

// --------------------------------------------------
// Factory robusta y extensible para botón de acción
// --------------------------------------------------
internal fun MainActivity.createRecordingBrowserActionButton(
    label: String,
    @ColorInt textColor: Int,
    config: RecordingActionButtonConfig = RecordingActionButtonConfig(),
    onClick: ((View) -> Unit)? = null,
    onFocusChanged: ((v: View, hasFocus: Boolean) -> Unit)? = null,
    build: TextView.() -> Unit = {},
): TextView {
    val safeLabel = label.ifBlank { "Acción" } // fallback simple

    return TextView(this).apply {
        id = View.generateViewId()
        text = safeLabel
        setTextColor(textColor)

        // Seguridad básica para evitar tamaños inválidos
        val safeTextSize = config.textSizeSp.coerceAtLeast(10f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, safeTextSize)

        gravity = config.gravity
        isAllCaps = config.allCaps
        maxLines = config.maxLines.coerceAtLeast(1)
        ellipsize = config.ellipsize
        setTypeface(typeface, config.typefaceStyle)

        minHeight = dpToPx(config.minHeightDp.coerceAtLeast(24))
        minWidth = dpToPx(config.minWidthDp.coerceAtLeast(48))

        val horizontalPadding = dpToPx(config.paddingHorizontalDp.coerceAtLeast(0))
        val verticalPadding = dpToPx(config.paddingVerticalDp.coerceAtLeast(0))
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

        isEnabled = config.enabled
        alpha = if (config.enabled) 1f else config.alphaWhenDisabled.coerceIn(0f, 1f)

        isFocusable = config.focusable
        isFocusableInTouchMode = config.focusable
        isClickable = config.clickable && config.enabled

        if (onClick != null) {
            setOnClickListener { v -> onClick(v) }
        }

        if (onFocusChanged != null) {
            onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                onFocusChanged(v, hasFocus)
            }
        }

        // Hook para personalización extra desde el caller
        build()

        // Tu lógica TV de enfoque (se mantiene)
        applyTvFocus(this)
    }
}
