// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenViewUtils.kt
// Utilidades de vistas del menú principal: animación de imágenes, elevación por foco, vidrio, etiquetas y botones.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView

// Tiempos y formatos
private const val MENU_FOCUS_ANIM_MS = 155L
private const val MENU_ANIM_DECELERATE_FACTOR = 2.2f

// Carrusel e ilustración del hero
private const val MENU_CAROUSEL_TRANSITION_MS = 850
private const val MENU_CAROUSEL_ZOOM = 1.035f
private const val MENU_CAROUSEL_PAN_DP = 2f

// Tarjetas de vidrio
private const val MENU_GLASS_RADIUS_DP = 12

internal fun ImageView.menuScreenResetImageAnimation() {
    // Invalida cierres pendientes antes de detener movimiento y reemplazar la imagen.
    tag = null
    menuScreenGuard("reinicioAnimacionImagen") { animate().cancel() }
    scaleX = 1f
    scaleY = 1f
    translationY = 0f
    alpha = 1f
}

internal fun ImageView.menuScreenShowStatic(drawableRes: Int) {
    menuScreenResetImageAnimation()
    setImageResource(drawableRes)
}

internal fun ImageView.showCarouselImage(imageIndex: Int) {
    if (MENU_CAROUSEL_IMAGES.isEmpty()) return
    val safeIndex = imageIndex.coerceIn(0, MENU_CAROUSEL_IMAGES.lastIndex)
    if (tag == safeIndex) return
    if (!isAttachedToWindow) return
    tag = safeIndex
    try {
        animate().cancel()
        if (!tvMenuAnimationsEnabled(context)) {
            scaleX = 1f
            scaleY = 1f
            translationY = 0f
            setImageResource(MENU_CAROUSEL_IMAGES[safeIndex])
            return
        }
        val incoming = resources.getDrawable(MENU_CAROUSEL_IMAGES[safeIndex], context.theme).mutate()
        val outgoing = drawable?.constantState
            ?.newDrawable(resources, context.theme)
            ?.mutate()
            ?: resources.getDrawable(R.drawable.guide_placeholder, context.theme).mutate()
        val transition = TransitionDrawable(arrayOf(outgoing, incoming)).apply {
            isCrossFadeEnabled = true
        }
        setImageDrawable(transition)
        transition.startTransition(MENU_CAROUSEL_TRANSITION_MS)

        scaleX = MENU_CAROUSEL_ZOOM
        scaleY = MENU_CAROUSEL_ZOOM
        translationY = context.resources.displayMetrics.density * MENU_CAROUSEL_PAN_DP
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(MENU_CAROUSEL_TRANSITION_MS.toLong())
            .setInterpolator(DecelerateInterpolator(MENU_ANIM_DECELERATE_FACTOR))
            .withEndAction {
                if ((tag == safeIndex) && isAttachedToWindow && drawable === transition) {
                    setImageDrawable(transition.getDrawable(1))
                }
            }
            .start()
    } catch (e: Exception) {
        menuScreenReport("carruselAnimacion", e)
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        setImageResource(MENU_CAROUSEL_IMAGES[safeIndex])
    }
}

internal fun View.menuScreenAnimateFocusLift(focused: Boolean, liftedZ: Float) {
    if (!isAttachedToWindow) return
    menuScreenGuard("animacionFoco") {
        if (!tvMenuAnimationsEnabled(context)) {
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
            translationZ = if (focused) liftedZ else 0f
            cancelTvFocusShine(this)
            return@menuScreenGuard
        }
        if (focused && liftedZ >= 8f) animateTvFocusShine(this, cornerRadiusDp = 50f)
        else if (!focused) cancelTvFocusShine(this)
        animate().cancel()
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationZ(if (focused) liftedZ else 0f)
            .setDuration(MENU_FOCUS_ANIM_MS)
            .setInterpolator(DecelerateInterpolator(MENU_ANIM_DECELERATE_FACTOR))
            .withLayer()
            .start()
    }
}

/** Superficie mate de alto contraste para separar contenido sin competir con la programación. */
internal fun MainActivity.menuScreenGlassSurface(): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(MenuScreenPalette.glassTop, MenuScreenPalette.glassBottom)
).apply {
    cornerRadius = dp(MENU_GLASS_RADIUS_DP).toFloat()
    setStroke(dp(1), MenuScreenPalette.glassStroke)
}

internal fun MainActivity.menuScreenLabel(
    sizeSp: Float,
    text: CharSequence? = null,
    color: Int? = null,
    bold: Boolean = false
): TextView = TextView(this).apply {
    text?.let { this.text = it }
    color?.let(::setTextColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

internal fun MainActivity.menuScreenActionButton(label: String, textColor: Int, cyanAccent: Boolean): TextView {
    val activity = this
    return TextView(activity).apply {
        id = View.generateViewId()
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuActionButton(activity, isCyanAccent = cyanAccent)
        applyTvFocus(this)
    }
}
