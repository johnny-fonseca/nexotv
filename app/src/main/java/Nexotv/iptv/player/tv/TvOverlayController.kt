package Nexotv.iptv.player.tv
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * Owns the player overlays that are attached to the activity content root.
 *
 * The controller deliberately exposes the existing views while the remaining
 * status/recording behavior is migrated in later, independently validated
 * steps. This keeps the first extraction behavior-preserving and reversible.
 */
@androidx.media3.common.util.UnstableApi
internal class TvOverlayController(
    private val activity: MainActivity,
    private val handler: Handler,
) {
    lateinit var status: TextView
        private set
    lateinit var playerInfo: PlayerInfoOverlay
        private set
    lateinit var tvToast: TextView
        private set
    lateinit var statusHost: FrameLayout
        private set

    val isStatusInitialized: Boolean get() = ::status.isInitialized
    val isPlayerInfoInitialized: Boolean get() = ::playerInfo.isInitialized
    val isTvToastInitialized: Boolean get() = ::tvToast.isInitialized

    val hideTvToast = Runnable {
        if (isTvToastInitialized && tvToast.isVisible) {
            tvToast.animate().cancel()
            tvToast.animate().alpha(0f).translationY(-activity.dp(6).toFloat())
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    tvToast.visibility = View.GONE
                    tvToast.alpha = 1f
                    tvToast.translationY = 0f
                }.start()
        }
    }

    fun attach(root: FrameLayout) {
        statusHost = root
        playerInfo = PlayerInfoOverlay(activity)
        status = buildStatusView()
        tvToast = buildToastView()
        root.addView(playerInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = activity.dp(42)
            marginStart = activity.dp(44)
        })
        root.addView(tvToast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = activity.dp(44)
        })
        activity.styleStatus()
    }

    fun applyConfiguration() {
        if (isStatusInitialized) {
            status.maxWidth = statusMaxWidthPx()
            (status.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = activity.dp(42)
                params.marginStart = activity.dp(44)
                status.layoutParams = params
            }
        }
        if (isTvToastInitialized) {
            tvToast.maxWidth = toastMaxWidthPx()
            (tvToast.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.topMargin = activity.dp(92)
                params.bottomMargin = 0
                tvToast.layoutParams = params
            }
        }
    }

    fun clear() {
        handler.removeCallbacks(hideTvToast)
        if (isTvToastInitialized) tvToast.animate().cancel()
    }

    private fun buildStatusView(): TextView = TextView(activity).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        maxWidth = statusMaxWidthPx()
        gravity = Gravity.START
        setLineSpacing(activity.dp(4).toFloat(), 1.04f)
        setPadding(activity.dp(24), activity.dp(20), activity.dp(28), activity.dp(20))
        background = TvStyle.playerOverlaySurface(activity, resources.getDimension(R.dimen.tv_radius_panel))
        setShadowLayer(activity.dp(4).toFloat(), 0f, activity.dp(2).toFloat(), 0x99000000.toInt())
        elevation = activity.dp(14).toFloat()
        visibility = View.GONE
    }

    private fun buildToastView(): TextView = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        maxWidth = toastMaxWidthPx()
        setLineSpacing(activity.dp(2).toFloat(), 1f)
        setPadding(activity.dp(24), activity.dp(12), activity.dp(24), activity.dp(12))
        background = TvStyle.playerOverlaySurface(activity, activity.dp(24).toFloat())
        setShadowLayer(activity.dp(4).toFloat(), 0f, activity.dp(2).toFloat(), 0xAA000000.toInt())
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
        elevation = activity.dp(24).toFloat()
    }

    private fun statusMaxWidthPx(): Int =
        (activity.resources.displayMetrics.widthPixels * 0.52f).toInt()
            .coerceAtMost(activity.dp(620))

    private fun toastMaxWidthPx(): Int =
        (activity.resources.displayMetrics.widthPixels * 0.72f).toInt()
}

