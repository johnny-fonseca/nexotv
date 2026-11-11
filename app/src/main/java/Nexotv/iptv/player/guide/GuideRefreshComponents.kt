// GuideRefreshComponents.kt
package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.R
import Nexotv.iptv.player.menu.applyTvFocus
import Nexotv.iptv.player.menu.menuCardSurface
import Nexotv.iptv.player.menu.menuDarkButton
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri
import kotlin.math.roundToInt

private const val TAG = "GuideRefresh"
private const val REFRESH_DEBOUNCE_MS = 500L
private const val NO_CLICK_YET = Long.MIN_VALUE
private const val DEFAULT_SOURCE_LABEL = "host configurado"
private const val DEFAULT_UPDATE_LABEL = "desconocida"
private const val MAX_URL_CHARS = 2048
private const val MAX_HOST_CHARS = 96
private const val MAX_AGE_CHARS = 48
private const val ELLIPSIS = "…"
private const val ICON_TINT = 0xFFDCE4EE.toInt()
private const val FALLBACK_BUTTON_COLOR = 0xFF1A2432.toInt()
private const val FALLBACK_CARD_COLOR = 0xFF0F1823.toInt()
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun Activity.dp(value: Int): Int {
    val density = try {
        resources.displayMetrics.density
    } catch (e: Exception) {
        1f
    }
    val safeDensity = if (density.isFinite() && density > 0f) density else 1f
    return (value * safeDensity).roundToInt()
}

private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

private fun TextView.setSp(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, size)

/** Ejecuta [block] aislando cualquier excepción; devuelve null si falla. */
private inline fun <T : Any> guarded(what: String, block: () -> T?): T? =
    try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Fallo controlado en $what", e)
        null
    }

private fun Activity.fallbackSurface(radiusDp: Int, color: Int): Drawable =
    GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

/** Elimina caracteres de control/formato, colapsa espacios y limita la longitud sin partir pares sustitutos. */
private fun sanitizeLabel(raw: String?, maxChars: Int): String? {
    if (raw.isNullOrEmpty() || maxChars < 2) return null
    val out = StringBuilder(minOf(raw.length, maxChars + 1))
    var lastWasSpace = true
    for (ch in raw) {
        if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
            if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
            continue
        }
        val type = Character.getType(ch)
        if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) continue
        out.append(ch)
        lastWasSpace = false
    }
    var result = out.toString().trimEnd()
    if (result.isEmpty()) return null
    if (result.length > maxChars) {
        var end = maxChars - 1
        if (end > 0 && Character.isHighSurrogate(result[end - 1])) end--
        result = result.substring(0, end).trimEnd() + ELLIPSIS
    }
    return result
}

/** Envuelve la acción de refresco evitando toques repetidos y propagación de excepciones. */
private fun debouncedAction(
    activity: Activity,
    guard: () -> Boolean = { true },
    action: () -> Unit,
): View.OnClickListener {
    var lastClickAt = NO_CLICK_YET
    return View.OnClickListener {
        val allowed = guarded("guard") { guard() } ?: false
        if (!allowed || !activity.isUsable()) return@OnClickListener
        val now = SystemClock.elapsedRealtime()
        if (lastClickAt != NO_CLICK_YET && (now - lastClickAt) in 0L until REFRESH_DEBOUNCE_MS) {
            return@OnClickListener
        }
        lastClickAt = now
        guarded("onRefresh") { action() }
    }
}

private fun resolveHost(sourceUrl: String?): String {
    val raw = sourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_URL_CHARS)
        ?: return DEFAULT_SOURCE_LABEL
    val host = guarded("resolveHost") { raw.toUri().host }
    return sanitizeLabel(host, MAX_HOST_CHARS) ?: DEFAULT_SOURCE_LABEL
}

private fun resolveUpdateAge(updatedAtMs: Long): String {
    val formatted = guarded("resolveUpdateAge") { GuideUpdateAge.format(updatedAtMs) }
    return sanitizeLabel(formatted, MAX_AGE_CHARS) ?: DEFAULT_UPDATE_LABEL
}

private fun Activity.styledText(
    value: String,
    color: Int,
    sizeSp: Float,
    maxLines: Int,
    bold: Boolean = false,
    centered: Boolean = false,
): TextView =
    TextView(this).apply {
        text = value
        setTextColor(color)
        setSp(sizeSp)
        setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        if (centered) gravity = Gravity.CENTER
    }

private fun Activity.refreshIcon(loading: Boolean): View =
    if (loading) {
        ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ICON_TINT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    } else {
        ImageView(this).apply {
            guarded("setImageResource") { setImageResource(R.drawable.tv_icon_refresh) }
            imageTintList = ColorStateList.valueOf(ICON_TINT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

/** Control compacto de actualización usado junto al selector de día de la guía. */
@SuppressLint("SetTextI18n")
internal object GuideRefreshButton {
    private const val DESC_LOADING = "Actualizando guía"
    private const val DESC_IDLE = "Actualizar guía"
    private const val LABEL_LOADING = "Actualizando"
    private const val LABEL_IDLE = "Actualizar"

    fun create(activity: Activity, loading: Boolean = false, onRefresh: () -> Unit): LinearLayout =
        guarded("GuideRefreshButton.create") { build(activity, loading, onRefresh) }
            ?: LinearLayout(activity).apply { visibility = View.GONE }

    private fun build(activity: Activity, loading: Boolean, onRefresh: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = if (loading) DESC_LOADING else DESC_IDLE
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
            minimumWidth = activity.dp(82)
            minimumHeight = activity.dp(62)
            background = guarded("menuDarkButton") { menuDarkButton(activity) }
                ?: activity.fallbackSurface(14, FALLBACK_BUTTON_COLOR)
            guarded("applyTvFocus") { applyTvFocus(this, scaleTo = 1.05f) }

            val iconParams = LinearLayout.LayoutParams(activity.dp(18), activity.dp(18)).apply {
                bottomMargin = activity.dp(4)
            }
            addView(activity.refreshIcon(loading), iconParams)

            addView(
                activity.styledText(
                    value = if (loading) LABEL_LOADING else LABEL_IDLE,
                    color = Color.WHITE,
                    sizeSp = 10.2f,
                    maxLines = 1,
                ),
            )

            setOnClickListener(debouncedAction(activity, guard = { !loading }, action = onRefresh))
        }
}

/** Aviso de actualización XMLTV mostrado después de la lista de programas de la guía. */
@SuppressLint("SetTextI18n")
internal object GuideRefreshBanner {
    private const val TITLE = "¿No encuentras tu programa? Actualiza la guía XMLTV"
    private const val ACTION_LABEL = "Actualizar guía"

    fun create(activity: Activity, sourceUrl: String?, updatedAtMs: Long, onRefresh: () -> Unit): View =
        guarded("GuideRefreshBanner.create") {
            build(activity, resolveHost(sourceUrl), resolveUpdateAge(updatedAtMs), onRefresh)
        } ?: View(activity).apply { visibility = View.GONE }

    private fun build(activity: Activity, host: String, updatedAge: String, onRefresh: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = guarded("menuCardSurface") { menuCardSurface(activity, 18f) }
                ?: activity.fallbackSurface(18, FALLBACK_CARD_COLOR)
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = "Actualizar guía XMLTV. Última actualización $updatedAge. Fuente: $host"
            guarded("applyTvFocus") { applyTvFocus(this, scaleTo = 1.02f) }

            val plusBox = FrameLayout(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply {
                    cornerRadius = activity.dp(8).toFloat()
                    setColor(0xFF131F2E.toInt())
                }
                addView(
                    activity.styledText(
                        value = "+",
                        color = 0xFF8899AA.toInt(),
                        sizeSp = 16f,
                        maxLines = 1,
                        bold = true,
                        centered = true,
                    ),
                    FrameLayout.LayoutParams(MATCH, MATCH),
                )
            }
            addView(
                plusBox,
                LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)).apply {
                    marginEnd = activity.dp(10)
                },
            )

            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    activity.styledText(
                        value = TITLE,
                        color = Color.WHITE,
                        sizeSp = 11f,
                        maxLines = 2,
                        bold = true,
                    ),
                )
                addView(
                    activity.styledText(
                        value = "Última actualización $updatedAge · Fuente: $host",
                        color = 0xFF7E90A3.toInt(),
                        sizeSp = 9.5f,
                        maxLines = 2,
                    ),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = activity.dp(2) },
                )
            }
            addView(
                texts,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = activity.dp(10) },
            )

            val actionLabel = activity.styledText(
                value = ACTION_LABEL,
                color = Color.WHITE,
                sizeSp = 10.8f,
                maxLines = 1,
                bold = true,
                centered = true,
            ).apply {
                background = guarded("menuDarkButton") { menuDarkButton(activity) }
                    ?: activity.fallbackSurface(14, FALLBACK_BUTTON_COLOR)
                setPadding(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(8))
            }
            addView(actionLabel, LinearLayout.LayoutParams(WRAP, activity.dp(36)))

            setOnClickListener(debouncedAction(activity, action = onRefresh))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = activity.dp(6)
                bottomMargin = activity.dp(12)
            }
        }
}