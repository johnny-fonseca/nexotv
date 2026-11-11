@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status




import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.animation.ValueAnimator
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
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

internal fun MainActivity.showTvToast(message: String, long: Boolean = false) {
    if (!isTvToastInitialized || isFinishing || isInPipMode) return
    if (guideSearchDialog?.isShowing == true || !window.decorView.hasWindowFocus()) {
        try {
            Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
        return
    }
    handler.removeCallbacks(hideTvToast)
    tvToast.text = message
    (tvToast.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
        // Posición fija para que el panel de estado no mueva el aviso.
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.topMargin = dp(92)
        params.bottomMargin = 0
        tvToast.layoutParams = params
    }
    tvToast.animate().cancel()
    tvToast.alpha = 0f
    tvToast.scaleX = 0.97f
    tvToast.scaleY = 0.97f
    tvToast.translationY = -dp(6).toFloat()
    tvToast.visibility = View.VISIBLE
    tvToast.bringToFront()
    tvToast.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
        .setDuration(TOAST_ANIMATION_MS)
        .setInterpolator(DecelerateInterpolator())
        .start()
    handler.postDelayed(hideTvToast, if (long) TOAST_LONG_MS else TOAST_SHORT_MS)
}

