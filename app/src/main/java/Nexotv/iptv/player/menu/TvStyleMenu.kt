// app/src/main/java/Nexotv/iptv/player/menu/TvStyleMenu.kt
package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

private const val COLOR_BACKDROP_BOTTOM = 0xFC05080E.toInt()
private const val COLOR_PILL_BG = 0xB30A121F.toInt()
private const val COLOR_PILL_STROKE = 0xFF24374E.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()

private const val COLOR_WHITE_BUTTON_NORMAL = 0xFFF1F5F9.toInt()

private const val COLOR_DARK_BUTTON_FOCUSED = 0xFF162539.toInt()
private const val COLOR_DARK_BUTTON_NORMAL = 0xFF0F1A28.toInt()
private const val COLOR_DARK_BUTTON_STROKE = 0xFF22354B.toInt()
private const val COLOR_BUTTON_TEXTURE_DARK = 0x2AFFFFFF.toInt()
private const val COLOR_BUTTON_TEXTURE_LIGHT = 0x2411263B.toInt()
private const val COLOR_BUTTON_TEXTURE_RED = 0x35FFFFFF.toInt()

private const val COLOR_FOCUS_CARD_FOCUSED = 0xFF101B2B.toInt()
private const val COLOR_FOCUS_CARD_NORMAL = 0xFF0A121F.toInt()
private const val COLOR_FOCUS_CARD_STROKE = 0xFF192A40.toInt()

private const val COLOR_WARNING_BG = 0xEE1E0D12.toInt()
private const val COLOR_WARNING_STROKE = 0xFF8B1D25.toInt()

private const val FOCUS_ELEVATION = 8f
private const val FOCUS_AMBIENT_SHADOW_COLOR = 0xB8000000.toInt()
private const val FOCUS_SPOT_SHADOW_COLOR = 0x88000000.toInt()
private const val FOCUS_ANIMATION_MS = 180L
private const val FOCUS_INTERPOLATOR_FACTOR = 2.4f
private const val FOCUS_SHINE_CORNER_RADIUS_DP = 18f
internal const val MENU_BRAND_BADGE_SIZE_DP = 42

internal fun tvMenuAnimationsEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

private class FocusShineDrawable(
    private val cornerRadiusPx: Float,
    private val durationMs: Long,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundsRect = RectF()
    private val clipPath = Path()
    private val shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var stripeWidth = 0f
    private var animator: ValueAnimator? = null
    private var fraction = 0f

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val currentShader = shader ?: return
        if (bounds.isEmpty || fraction >= 1f) return

        shaderMatrix.setTranslate(-stripeWidth + (bounds.width() + stripeWidth * 2f) * fraction, 0f)
        currentShader.setLocalMatrix(shaderMatrix)
        paint.shader = currentShader
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(boundsRect, paint)
        canvas.restoreToCount(saveCount)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        boundsRect.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        stripeWidth = bounds.width() * 0.42f
        shader = if (bounds.isEmpty) null else LinearGradient(
            0f,
            bounds.top.toFloat(),
            stripeWidth,
            bounds.bottom.toFloat(),
            intArrayOf(Color.TRANSPARENT, 0x16FFFFFF, 0x35FFFFFF, 0x16FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0f, 0.34f, 0.5f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    fun start(onFinished: () -> Unit) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onFinished()
                }
            })
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }
}

private fun Context.densityOf(): Float = resources.displayMetrics.density

private fun Context.dpToPx(value: Float): Int = (densityOf() * value).toInt()

private fun roundedSurface(
    cornerRadiusPx: Float,
    fillColor: Int,
    strokeWidthPx: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
): GradientDrawable = GradientDrawable().apply {
    cornerRadius = cornerRadiusPx
    setColor(fillColor)
    if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
}

internal fun menuBrandBadge(context: Context): FrameLayout {
    return FrameLayout(context).apply {
        background = roundedSurface(
            cornerRadiusPx = context.dpToPx(8f).toFloat(),
            fillColor = context.getColor(R.color.tv_menu_brand_badge),
            strokeWidthPx = context.dpToPx(1f),
            strokeColor = context.getColor(R.color.tv_menu_panel_stroke),
        )
        addView(ImageView(context).apply {
            setImageResource(R.drawable.channel_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(context.dpToPx(32f), context.dpToPx(32f), Gravity.CENTER))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

private fun focusStateList(focused: Drawable, normal: Drawable): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }

/** Textura sutil de líneas diagonales cruzadas, recortada al radio del botón. */
private class ButtonTextureDrawable(
    private val lineColor: Int,
    private val cornerRadiusPx: Float,
    private val lineWidthPx: Float,
    private val spacingPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = lineWidthPx
        strokeCap = Paint.Cap.BUTT
    }
    private val clipPath = Path()
    private val boundsRect = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        boundsRect.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (boundsRect.isEmpty) return
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        val width = boundsRect.width()
        val height = boundsRect.height()
        var offset = -height
        while (offset <= width + height) {
            canvas.drawLine(boundsRect.left + offset, boundsRect.bottom, boundsRect.left + offset + height, boundsRect.top, paint)
            canvas.drawLine(boundsRect.left + offset, boundsRect.top, boundsRect.left + offset + height, boundsRect.bottom, paint)
            offset += spacingPx
        }
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private fun texturedButton(
    context: Context,
    surface: Drawable,
    lineColor: Int,
    cornerRadiusPx: Float,
): Drawable = LayerDrawable(
    arrayOf(
        surface,
        ButtonTextureDrawable(
            lineColor = lineColor,
            cornerRadiusPx = cornerRadiusPx,
            lineWidthPx = context.dpToPx(0.7f).coerceAtLeast(1).toFloat(),
            spacingPx = context.dpToPx(13f).coerceAtLeast(8).toFloat(),
        ),
    ),
)

private fun buttonFocusStateList(
    context: Context,
    focused: Drawable,
    normal: Drawable,
    lineColor: Int,
    cornerRadiusPx: Float,
): StateListDrawable = focusStateList(
    texturedButton(context, focused, lineColor, cornerRadiusPx),
    texturedButton(context, normal, lineColor, cornerRadiusPx),
)

internal fun menuBackdrop(context: Context): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(context.getColor(R.color.tv_menu_bg), COLOR_BACKDROP_BOTTOM),
)

internal fun menuPanelSurface(context: Context): GradientDrawable = menuMainPanel(context)

internal fun menuMainPanel(context: Context): GradientDrawable = roundedSurface(
    cornerRadiusPx = context.resources.getDimension(R.dimen.tv_radius_panel),
    fillColor = context.getColor(R.color.tv_menu_panel),
    strokeWidthPx = context.dpToPx(1f),
    strokeColor = context.getColor(R.color.tv_menu_panel_stroke),
)

internal fun menuCardSurface(context: Context, radiusDp: Float = 20f): GradientDrawable = roundedSurface(
    cornerRadiusPx = context.densityOf() * radiusDp.coerceAtLeast(0f),
    fillColor = context.getColor(R.color.tv_menu_panel),
    strokeWidthPx = context.dpToPx(1f),
    strokeColor = context.getColor(R.color.tv_menu_panel_stroke),
)

internal fun menuPill(
    context: Context,
    bgHex: Int = COLOR_PILL_BG,
    strokeHex: Int = COLOR_PILL_STROKE,
): GradientDrawable = roundedSurface(
    cornerRadiusPx = context.densityOf() * 18f,
    fillColor = bgHex,
    strokeWidthPx = context.dpToPx(1f),
    strokeColor = strokeHex,
)

internal fun menuRowBackground(context: Context): StateListDrawable {
    val radius = context.resources.getDimension(R.dimen.tv_menu_row_radius)
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = context.getColor(R.color.tv_menu_row_focused),
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = context.getColor(R.color.tv_menu_row_focused_stroke),
    )
    val normal = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = context.getColor(R.color.tv_menu_row_normal),
        strokeWidthPx = context.dpToPx(1f),
        strokeColor = context.getColor(R.color.tv_menu_row_normal_stroke),
    )
    return focusStateList(focused, normal)
}

internal fun menuRedCardBackground(context: Context): StateListDrawable {
    val radius = context.densityOf() * 16f
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = context.getColor(R.color.tv_live),
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = COLOR_WHITE,
    )
    val normal = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = context.getColor(R.color.tv_menu_red_btn),
    )
    return focusStateList(focused, normal)
}

internal fun menuActionButton(
    context: Context,
    isRed: Boolean = false,
    isCyanAccent: Boolean = false,
    showNormalBorder: Boolean = !isCyanAccent,
): StateListDrawable {
    val radius = context.densityOf() * 10f
    val turquoise = context.getColor(R.color.tv_turquoise)

    val focusedFill = if (isRed) context.getColor(R.color.tv_live)
    else context.getColor(R.color.tv_menu_row_focused)
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = focusedFill,
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = turquoise,
    )

    val normal = when {
        isRed -> roundedSurface(
            cornerRadiusPx = radius,
            fillColor = context.getColor(R.color.tv_menu_red_btn),
        )
        isCyanAccent -> roundedSurface(
            cornerRadiusPx = radius,
            fillColor = context.getColor(R.color.tv_menu_row_normal),
            strokeWidthPx = if (showNormalBorder) context.dpToPx(1f) else 0,
            strokeColor = context.getColor(R.color.tv_menu_row_normal_stroke),
        )
        else -> roundedSurface(
            cornerRadiusPx = radius,
            fillColor = context.getColor(R.color.tv_menu_row_normal),
            strokeWidthPx = if (showNormalBorder) context.dpToPx(1f) else 0,
            strokeColor = context.getColor(R.color.tv_menu_row_normal_stroke),
        )
    }
    return buttonFocusStateList(
        context,
        focused,
        normal,
        if (isRed) COLOR_BUTTON_TEXTURE_RED else COLOR_BUTTON_TEXTURE_DARK,
        radius,
    )
}

internal fun applyTvFocus(
    view: View,
    scaleTo: Float = 1.03f,
    focusCornerRadiusDp: Float = 18f,
) {
    // Las superficies estáticas necesitan el mismo indicador de foco que las filas con StateListDrawable
    if (view.background !is StateListDrawable && view.foreground == null) {
        val density = view.resources.displayMetrics.density
        val focusedRing = roundedSurface(
            cornerRadiusPx = density * focusCornerRadiusDp.coerceAtLeast(0f),
            fillColor = Color.TRANSPARENT,
            strokeWidthPx = (density * 3f).toInt().coerceAtLeast(1),
            strokeColor = view.context.getColor(R.color.tv_turquoise),
        )
        view.foreground = focusStateList(focusedRing, ColorDrawable(Color.TRANSPARENT))
    }
    view.setOnFocusChangeListener { focusedView, focused ->
        animateTvFocus(focusedView, focused, scaleTo, FOCUS_ELEVATION)
    }
}

/** Profundidad base para tarjetas no enfocables que contienen controles de acción. */
internal fun applyTvCardDepth(view: View, elevationDp: Float = 3f) {
    val density = view.resources.displayMetrics.density
    view.elevation = elevationDp.coerceAtLeast(0f) * density
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.outlineAmbientShadowColor = FOCUS_AMBIENT_SHADOW_COLOR
        view.outlineSpotShadowColor = FOCUS_SPOT_SHADOW_COLOR
    }
}

internal fun animateTvFocus(
    view: View,
    focused: Boolean,
    focusedScale: Float = 1.03f,
    elevationDp: Float = FOCUS_ELEVATION,
) {
    val targetScale = focusedScale
        .takeIf { it.isFinite() }
        ?.coerceIn(1f, 1.5f)
        ?: 1.03f
    val density = view.resources.displayMetrics.density
    view.animate().cancel()
    // translationZ desplaza una sombra existente, pero no crea profundidad por sí solo.
    // La elevación real hace que el contorno del fondo proyecte una sombra 3D visible.
    view.elevation = if (focused) elevationDp.coerceAtLeast(0f) * density else 0f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.outlineAmbientShadowColor = if (focused) FOCUS_AMBIENT_SHADOW_COLOR else Color.BLACK
        view.outlineSpotShadowColor = if (focused) FOCUS_SPOT_SHADOW_COLOR else Color.BLACK
    }
    if (!tvMenuAnimationsEnabled(view.context)) {
        view.scaleX = if (focused) targetScale else 1f
        view.scaleY = if (focused) targetScale else 1f
        view.translationZ = 0f
        cancelTvFocusShine(view)
        return
    }
    view.animate()
        .scaleX(if (focused) targetScale else 1f)
        .scaleY(if (focused) targetScale else 1f)
        .translationZ(0f)
        .setDuration(FOCUS_ANIMATION_MS)
        .setInterpolator(DecelerateInterpolator(FOCUS_INTERPOLATOR_FACTOR))
        .withLayer()
        .start()
    if (focused && (targetScale >= 1.04f || elevationDp >= FOCUS_ELEVATION)) {
        animateTvFocusShine(view)
    } else if (!focused) {
        cancelTvFocusShine(view)
    }
}

internal fun animateTvFocusShine(view: View, cornerRadiusDp: Float = FOCUS_SHINE_CORNER_RADIUS_DP) {
    if (!tvMenuAnimationsEnabled(view.context)) {
        cancelTvFocusShine(view)
        return
    }
    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
    cancelTvFocusShine(view)
    val density = view.resources.displayMetrics.density
    val shine = FocusShineDrawable(
        cornerRadiusDp.coerceAtLeast(0f) * density,
        view.resources.getInteger(R.integer.tv_classic_menu_focus_shine_duration_ms).toLong()
    ).apply {
        setBounds(0, 0, view.width, view.height)
    }
    val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            view.removeOnAttachStateChangeListener(this)
            cancelTvFocusShine(view)
        }
    }
    view.setTag(R.id.tv_focus_shine, shine)
    view.addOnAttachStateChangeListener(attachListener)
    view.overlay.add(shine)
    shine.start {
        view.removeOnAttachStateChangeListener(attachListener)
        if (view.getTag(R.id.tv_focus_shine) === shine) {
            view.setTag(R.id.tv_focus_shine, null)
            view.overlay.remove(shine)
        }
    }
}

internal fun cancelTvFocusShine(view: View) {
    val shine = view.getTag(R.id.tv_focus_shine) as? FocusShineDrawable ?: return
    view.setTag(R.id.tv_focus_shine, null)
    view.overlay.remove(shine)
    shine.cancel()
}

internal fun menuWhiteButton(context: Context): StateListDrawable {
    val radius = context.densityOf() * 18f
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = COLOR_WHITE,
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = context.getColor(R.color.tv_turquoise),
    )
    val normal = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = COLOR_WHITE_BUTTON_NORMAL,
    )
    return buttonFocusStateList(context, focused, normal, COLOR_BUTTON_TEXTURE_LIGHT, radius)
}

internal fun menuRedButton(context: Context): StateListDrawable {
    val radius = context.densityOf() * 18f
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = context.getColor(R.color.tv_live),
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = COLOR_WHITE,
    )
    val normal = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = context.getColor(R.color.tv_menu_red_btn),
    )
    return buttonFocusStateList(context, focused, normal, COLOR_BUTTON_TEXTURE_RED, radius)
}

internal fun menuDarkButton(context: Context): StateListDrawable {
    val radius = context.densityOf() * 18f
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = COLOR_DARK_BUTTON_FOCUSED,
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = context.getColor(R.color.tv_turquoise),
    )
    val normal = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = COLOR_DARK_BUTTON_NORMAL,
        strokeWidthPx = context.dpToPx(1f),
        strokeColor = COLOR_DARK_BUTTON_STROKE,
    )
    return buttonFocusStateList(context, focused, normal, COLOR_BUTTON_TEXTURE_DARK, radius)
}

internal fun menuWhiteFocusCard(context: Context, radiusDp: Float = 14f): StateListDrawable {
    val radius = context.densityOf() * radiusDp.coerceAtLeast(0f)
    val focused = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = COLOR_FOCUS_CARD_FOCUSED,
        strokeWidthPx = context.dpToPx(3f),
        strokeColor = COLOR_WHITE,
    )
    val normal = roundedSurface(
        cornerRadiusPx = radius,
        fillColor = COLOR_FOCUS_CARD_NORMAL,
        strokeWidthPx = context.dpToPx(1f),
        strokeColor = COLOR_FOCUS_CARD_STROKE,
    )
    return focusStateList(focused, normal)
}

internal fun menuRedWarningBox(context: Context): GradientDrawable = roundedSurface(
    cornerRadiusPx = context.densityOf() * 12f,
    fillColor = COLOR_WARNING_BG,
    strokeWidthPx = context.dpToPx(1f),
    strokeColor = COLOR_WARNING_STROKE,
)
