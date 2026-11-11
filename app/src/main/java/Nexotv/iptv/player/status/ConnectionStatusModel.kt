@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status


import Nexotv.iptv.player.recordings.*
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

internal val STATUS_HEADER_PREFIX = BuildConfig.BRAND_NAME.uppercase() + "  ·  "
internal const val STATUS_LABEL_SEPARATOR = "  ·  "
internal const val STATUS_SHADOW_COLOR = 0x99000000.toInt()
internal const val STATUS_LIVE_LABEL_COLOR = 0xFF75E2A1.toInt()
internal const val STATUS_HEADER_COLOR = 0xFF9EABC0.toInt()
internal const val STATUS_WARNING_TINT = 0xFFFFC166.toInt()
internal const val STATUS_SUCCESS_TINT = 0xFF7FE1A3.toInt()
internal const val STATUS_DEFAULT_TINT = 0xFF91BFFF.toInt()
internal const val STATUS_REVEAL_DURATION_MS = 240L
internal const val STATUS_HIDE_DURATION_MS = 160L
internal const val STATUS_DIAGNOSTICS_REFRESH_MS = 1_000L
internal const val TOAST_ANIMATION_MS = 180L
internal const val TOAST_SHORT_MS = 2_200L
internal const val TOAST_LONG_MS = 3_500L
internal const val RECORDING_BLINK_MS = 600L
internal const val RECORDING_COLLAPSE_MS = 250L
internal const val RECORDING_STARTED_AT_KEY = "recording_started_at"

internal val STATUS_HIGHLIGHTED_LABELS = setOf(BuildConfig.BRAND_NAME.uppercase(), "AL AIRE", "SIGUIENTE", "DESPUÉS", "VIDEO", "AUDIO", "BUFFER")
internal val STATUS_LABEL_COLOR = Color.rgb(125, 190, 255)

internal class ConnectionStatusCardSpec(
    val title: String,
    val help: String,
    val icon: Int,
    val accent: Int,
    val eyebrow: String = "",
)
internal fun playerStatusPriority(model: PlayerStatus): Int = when (model) {
    is PlayerStatus.NoSpace -> 6
    is PlayerStatus.Offline -> 5
    is PlayerStatus.NoSignal -> 4
    PlayerStatus.Reconnecting -> 3
    PlayerStatus.Connecting -> 2
}

