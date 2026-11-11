@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.*
import android.animation.ValueAnimator
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import java.util.Locale

internal class ConnectionPulseView private constructor(
    context: android.content.Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    private val accentColor: Int,
) : View(context, attrs, defStyleAttr) {
    constructor(context: android.content.Context) : this(context, null, 0, MenuScreenPalette.reminderAccent)
    constructor(context: android.content.Context, attrs: AttributeSet?) :
        this(context, attrs, 0, MenuScreenPalette.reminderAccent)
    constructor(context: android.content.Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, MenuScreenPalette.reminderAccent)
    constructor(context: android.content.Context, accentColor: Int) : this(context, null, 0, accentColor)
    private val density = resources.displayMetrics.density
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MenuScreenPalette.glassBottom
    }
    private val accentDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = Color.argb(48, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2.5f
        strokeCap = Paint.Cap.ROUND
        color = accentColor
    }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = accentColor
    }
    private val bounds = RectF()
    private var rotation = 0f
    private var pulseFraction = 0f
    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1_500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_100L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            pulseFraction = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) startAnimators()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) startAnimators() else stopAnimators()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        if (isShown) startAnimators() else stopAnimators()
    }

    private fun startAnimators() {
        if (!rotationAnimator.isStarted) rotationAnimator.start()
        if (!pulseAnimator.isStarted) pulseAnimator.start()
    }

    private fun stopAnimators() {
        rotationAnimator.cancel()
        pulseAnimator.cancel()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.37f
        val pulseRadius = density * (7f + pulseFraction * 4f)
        pulse.alpha = ((1f - pulseFraction) * 90f).toInt()
        canvas.drawCircle(cx, cy, pulseRadius, pulse)
        canvas.drawCircle(cx, cy, radius, track)
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(bounds, rotation, 105f, false, arc)
        canvas.withRotation(rotation, cx, cy) {
            canvas.drawArc(bounds, 180f, 45f, false, arc.apply { alpha = 115 })
            arc.alpha = 255
        }
        canvas.drawCircle(cx, cy, density * 4f, center)
        canvas.drawCircle(cx, cy, density * (2f + pulseFraction), accentDot)
    }
}

